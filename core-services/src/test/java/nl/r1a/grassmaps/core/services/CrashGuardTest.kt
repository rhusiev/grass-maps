package nl.r1a.grassmaps.core.services

import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.service.LogRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class CrashGuardTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "crash-${UUID.randomUUID()}")

    @After
    fun removeDirectory() {
        directory.setWritable(true)
        directory.deleteRecursively()
    }

    @Test
    fun aReportHoldsTheFailureDeviceInfoAndLogTailButNoToken() {
        val logger = RingLogger(capacity = 4, clock = { 1_000L })
        logger.info("app", "last thing before the crash")
        val guard =
            CrashGuard(
                directory = directory,
                records = logger::snapshot,
                deviceInfo = { DeviceInfo(model = "Pixel 7", osVersion = "Android 14", appVersion = "1.2.3") },
                clock = { 42L },
            )

        val outcome = guard.report(Thread("main"), IllegalStateException("boom token=supersecret"))

        assertTrue(outcome is Outcome.Success)
        val file = (outcome as Outcome.Success).value
        assertTrue(file.isFile)
        assertTrue(file.name.startsWith("crash-"))
        assertTrue(file.name.endsWith(".txt"))
        assertFalse(File(directory, "${file.name}.tmp").exists())

        val text = file.readText()
        assertTrue(text.contains("# Grass Maps crash report"))
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("boom"))
        assertTrue(text.contains("at nl.r1a.grassmap.core.services.CrashGuardTest."))
        assertTrue(text.contains("Pixel 7"))
        assertTrue(text.contains("Android 14"))
        assertTrue(text.contains("last thing before the crash"))
        assertFalse(text.contains("supersecret"))
        assertFalse(text.contains("token=supersecret"))
    }

    @Test
    fun aStackOverflowReportSkipsTheStackAndStillWrites() {
        val overflow = stackOverflow()

        val outcome = guardAt().report(Thread("main"), overflow)

        assertTrue(outcome is Outcome.Success)
        val text = (outcome as Outcome.Success).value.readText()
        assertTrue(text.contains("exception=java.lang.StackOverflowError"))
        assertTrue(text.contains("# stack skipped: stack overflow"))
        assertFalse(text.contains("at nl.r1a.grassmap.core.services.CrashGuardTest."))
        assertFalse(text.contains("\tat "))
    }

    @Test
    fun aStackThatCannotBeFormattedStillWritesTheRestOfTheReport() {
        val failure =
            object : IllegalStateException("boom") {
                override fun toString(): String = throw IllegalStateException("cannot print this stack")
            }

        val outcome = guardAt().report(Thread("main"), failure)

        assertTrue(outcome is Outcome.Success)
        val text = (outcome as Outcome.Success).value.readText()
        assertTrue(text.contains("exception="))
        assertTrue(text.contains("boom"))
        assertTrue(text.contains("# stack skipped: the stack could not be formatted"))
        assertFalse(text.contains("at nl.r1a.grassmap.core.services.CrashGuardTest."))
    }

    @Test
    fun anInstalledHandlerWritesTheReportOnItsOwnThreadAndChainsToThePrevious() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var chained: Throwable? = null
        var reportedOn: String? = null
        val guard =
            guardAt(
                records = {
                    reportedOn = Thread.currentThread().name
                    emptyList()
                },
                clock = { 7L },
            )
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, throwable -> chained = throwable }
            installCrashGuard { guard }
            val failure = IllegalStateException("boom")
            val caller = Thread.currentThread().name

            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(Thread("crasher"), failure)

            assertTrue(File(directory, "crash-7.txt").isFile)
            assertSame(failure, chained)
            assertTrue(reportedOn != null && reportedOn != caller)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun aReportThatCannotBeWrittenStillChainsTheOriginalThrowable() {
        directory.mkdirs()
        val blocked = File(directory, "blocked")
        assertTrue(blocked.createNewFile())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var chained: Throwable? = null
        val guard = guardAt(writeDirectory = blocked, clock = { 9L })
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, throwable -> chained = throwable }
            installCrashGuard { guard }
            val failure = IllegalStateException("boom")

            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(Thread("crasher"), failure)

            assertSame(failure, chained)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun installingTheGuardTwiceLeavesOneGuardInTheChain() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var reports = 0
        var chained: Throwable? = null
        var chains = 0
        val guard = guardAt(clock = { 11L })
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
                chains += 1
                chained = throwable
            }
            installCrashGuard {
                reports += 1
                guard
            }
            installCrashGuard {
                reports += 1
                guard
            }
            val failure = IllegalStateException("boom")

            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(Thread("crasher"), failure)

            assertEquals(1, reports)
            assertEquals(1, chains)
            assertSame(failure, chained)
            assertTrue(File(directory, "crash-11.txt").isFile)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    private fun guardAt(
        writeDirectory: File = directory,
        records: () -> List<LogRecord> = { emptyList() },
        clock: () -> Long = { DEFAULT_CLOCK },
    ): CrashGuard =
        CrashGuard(
            directory = writeDirectory,
            records = records,
            deviceInfo = { DeviceInfo.UNKNOWN },
            clock = clock,
        )

    private fun stackOverflow(): StackOverflowError =
        try {
            recurse()
            error("unreachable after the stack overflow")
        } catch (overflow: StackOverflowError) {
            overflow
        }

    private fun recurse(): Unit = recurse()

    private companion object {
        const val DEFAULT_CLOCK = 42L
    }
}
