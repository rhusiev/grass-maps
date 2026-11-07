package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.LogRecord
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
     * when the directory cannot be written. It never throws, so a broken crash
     * report cannot break the crash handler.
     */
    fun report(
        thread: Thread,
        throwable: Throwable,
    ): Outcome<File> {
        val now = clock()
        val target = File(directory, "$CRASH_PREFIX$now$CRASH_SUFFIX")
        val content = scrubber.scrub(format(now, thread, throwable))
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
        thread: Thread,
        throwable: Throwable,
    ): String {
        val stored = records()
        return buildString {
            appendLine("# Grass Maps crash report")
            appendLine("time=${Instant.ofEpochMilli(now)}")
            appendLine("thread=${thread.name}")
            append(deviceInfo().asText())
            appendLine()
            appendLine("exception=${throwable::class.java.name}: ${throwable.message.orEmpty()}")
            appendLine("stack:")
            appendLine(throwable.stackTraceToString())
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
 * Installs a process crash handler that resolves its guard lazily.
 *
 * Resolving [guard] only when a crash happens keeps the diagnostics objects off
 * the startup path: the application can install the handler without reading the
 * diagnostics directory, and a normal launch never pays for it.
 */
fun installCrashGuard(guard: () -> CrashGuard?) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching { guard()?.report(thread, throwable) }
        if (previous != null) {
            previous.uncaughtException(thread, throwable)
        } else {
            throwable.printStackTrace()
        }
    }
}
