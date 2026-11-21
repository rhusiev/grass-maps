package nl.r1a.grassmaps.core.services

import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.service.LogRecord
import java.io.File
import java.time.Instant

/**
 * Writes a crash report on an uncaught failure.
 *
 * The report is one text file in the app-private `diagnostics/` directory. It
 * carries the exception type and message, the stack trace, the device facts and
 * the tail of the log ring. The whole report is redacted before it is written,
 * so a token or a coordinate in an exception message is not recorded. The
 * handler chains to the platform handler, so installing it never hides a
 * crash; nothing is uploaded.
 */
class CrashGuard(
    private val directory: File,
    private val records: () -> List<LogRecord>,
    private val deviceInfo: () -> DeviceInfo,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scrubber: LogScrubber = LogScrubber(),
) {
    /**
     * Writes the report for [throwable]. Returns the file, or a typed failure
     * when the report cannot be built or the directory cannot be written. It
     * never throws, so a broken crash report cannot break the crash handler.
     */
    fun report(
        thread: Thread,
        throwable: Throwable,
    ): Outcome<File> = report(CrashSnapshot.of(thread, throwable))

    /**
     * Writes the report for a failure already captured on the crashing thread.
     * The installed handler calls this on its own thread, so building the text,
     * redacting it and writing the file never run on the failing thread.
     */
    internal fun report(snapshot: CrashSnapshot): Outcome<File> {
        val now = clock()
        val target = File(directory, "$CRASH_PREFIX$now$CRASH_SUFFIX")
        val content =
            try {
                scrubber.scrub(format(now, snapshot))
            } catch (failure: Throwable) {
                return Outcome.Failure(AppError.Internal(ERROR_ID, failure.message))
            }
        return when (
            val outcome =
                AtomicFiles.write(target, ERROR_ID) { stream ->
                    stream.write(content.toByteArray())
                }
        ) {
            is Outcome.Success -> Outcome.Success(target)
            is Outcome.Failure -> outcome
        }
    }

    /**
     * Installs this guard as the process handler, chaining to the handler that
     * was installed before it. The composition root calls it once in
     * `Application.onCreate`.
     */
    fun install() {
        installCrashGuard { this }
    }

    private fun format(
        now: Long,
        snapshot: CrashSnapshot,
    ): String {
        val stored = records()
        return buildString {
            appendLine("# Grass Maps crash report")
            appendLine("time=${Instant.ofEpochMilli(now)}")
            appendLine("thread=${snapshot.threadName}")
            append(deviceInfo().asText())
            appendLine()
            appendLine("exception=${snapshot.type}: ${snapshot.message.orEmpty()}")
            appendLine("stack:")
            appendLine(snapshot.stackText())
            appendLine("recent log:")
            appendLine("# records=${stored.size}")
            stored.forEach { record ->
                append(record.level.name)
                append(' ')
                append(record.tag)
                append(": ")
                appendLine(record.message)
            }
        }
    }

    private companion object {
        const val ERROR_ID = "core.services.crash_report"
        const val CRASH_PREFIX = "crash-"
        const val CRASH_SUFFIX = ".txt"
    }
}

/**
 * The facts about a failure that are read on the crashing thread: the original
 * throwable, its thread, its class name and its message.
 *
 * The handler hands one of these to a fresh thread. Reading the message here
 * keeps the work on the failing thread small and means the report does not call
 * back into app code for it.
 */
internal class CrashSnapshot private constructor(
    val throwable: Throwable,
    val threadName: String,
    val type: String,
    val message: String?,
) {
    /**
     * The stack as text. A [StackOverflowError] is skipped because its stack
     * names the recursion rather than the cause and is the largest allocation
     * of the report. Any other failure to render the stack leaves the rest of
     * the report intact.
     */
    fun stackText(): String {
        if (throwable is StackOverflowError) {
            return STACK_OVERFLOW_MARKER
        }
        return try {
            throwable.stackTraceToString()
        } catch (_: Throwable) {
            STACK_FORMAT_FAILED_MARKER
        }
    }

    companion object {
        fun of(
            thread: Thread,
            throwable: Throwable,
        ): CrashSnapshot =
            CrashSnapshot(
                throwable = throwable,
                threadName = thread.name,
                type = throwable.javaClass.name,
                message = runCatching { throwable.message }.getOrNull(),
            )
    }
}

/**
 * Installs a process crash handler that resolves its guard lazily.
 *
 * Resolving [guard] only when a crash happens keeps the diagnostics objects off
 * the startup path: the application can install the handler without reading the
 * diagnostics directory, and a normal launch never pays for it.
 *
 * The report is built on a fresh thread with an explicit stack size, never on
 * the thread that is failing. The original failure reaches the previous handler
 * whether or not the report was written, so a report problem cannot hide the
 * crash from the platform. Installing again replaces this handler instead of
 * chaining a second copy of it, so one crash never writes two reports.
 */
fun installCrashGuard(guard: () -> CrashGuard?) {
    val current = Thread.getDefaultUncaughtExceptionHandler()
    val previous = if (current is CrashHandler) current.previous else current
    Thread.setDefaultUncaughtExceptionHandler(CrashHandler(guard, previous))
}

/**
 * The process handler: it snapshots the failure, writes the report on a thread
 * that has room to build it, and then always chains the original throwable.
 */
private class CrashHandler(
    private val guard: () -> CrashGuard?,
    val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        try {
            reportOnItsOwnThread(thread, throwable)
        } catch (_: Throwable) {
            // Capturing the failure or starting the reporter is best effort;
            // the original throwable still goes to the previous handler below.
        } finally {
            chain(thread, throwable)
        }
    }

    private fun reportOnItsOwnThread(
        thread: Thread,
        throwable: Throwable,
    ) {
        val snapshot = CrashSnapshot.of(thread, throwable)
        val reporter =
            Thread(null, { writeReport(snapshot) }, REPORT_THREAD_NAME, REPORT_THREAD_STACK_BYTES)
        reporter.isDaemon = true
        // The process default handler is this one. Swallowing an escape keeps a
        // failed report from entering the crash handler again.
        reporter.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
        try {
            reporter.start()
            reporter.join(REPORT_WAIT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun writeReport(snapshot: CrashSnapshot) {
        try {
            guard()?.report(snapshot)
        } catch (_: Throwable) {
            // The report is best effort; the original failure must still chain.
        }
    }

    private fun chain(
        thread: Thread,
        throwable: Throwable,
    ) {
        if (previous != null) {
            previous.uncaughtException(thread, throwable)
        } else {
            throwable.printStackTrace()
        }
    }

    private companion object {
        const val REPORT_THREAD_NAME = "crash-report"

        /**
         * One MiB is the platform's usual thread stack, written out so the
         * report never runs on the dying thread's remaining stack.
         */
        const val REPORT_THREAD_STACK_BYTES = 1024L * 1024L

        /**
         * A small report is written in milliseconds. The bound keeps a blocked
         * reporter from holding a dying process; after it, the original failure
         * still reaches the previous handler.
         */
        const val REPORT_WAIT_MS = 2_000L
    }
}

private const val STACK_OVERFLOW_MARKER = "# stack skipped: stack overflow"
private const val STACK_FORMAT_FAILED_MARKER = "# stack skipped: the stack could not be formatted"
