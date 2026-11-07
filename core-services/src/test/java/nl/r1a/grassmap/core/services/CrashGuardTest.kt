package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertFalse
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
        assertTrue(text.contains("Pixel 7"))
        assertTrue(text.contains("Android 14"))
        assertTrue(text.contains("last thing before the crash"))
        assertFalse(text.contains("supersecret"))
        assertFalse(text.contains("token=supersecret"))
    }

    @Test
    fun anInstalledHandlerWritesTheReportAndChainsToThePreviousHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var chained: Throwable? = null
        val guard =
            CrashGuard(
                directory = directory,
                records = { emptyList() },
                deviceInfo = { DeviceInfo.UNKNOWN },
                clock = { 7L },
            )
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, throwable -> chained = throwable }
            installCrashGuard { guard }
            val failure = IllegalStateException("boom")

            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(Thread.currentThread(), failure)

            assertTrue(File(directory, "crash-7.txt").isFile)
            assertTrue(chained === failure)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun anUnwritableDirectoryReturnsAFailureInsteadOfThrowing() {
        val blocked = File(directory, "blocked")
        blocked.mkdirs()
        blocked.setWritable(false)
        val guard =
            CrashGuard(
                directory = blocked,
                records = { emptyList() },
                deviceInfo = { DeviceInfo.UNKNOWN },
                clock = { 42L },
            )

        val outcome =
            try {
                guard.report(Thread("main"), IllegalStateException("boom"))
            } finally {
                blocked.setWritable(true)
            }

        assertTrue(outcome is Outcome.Failure)
    }
}
