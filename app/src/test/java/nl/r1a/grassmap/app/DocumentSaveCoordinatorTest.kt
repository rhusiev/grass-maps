package nl.r1a.grassmap.app

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.DocumentSaveResult
import nl.r1a.grassmap.plugin.api.host.PlatformFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-0410: the picker request is a suspend value, so the activity result, a
 * dismissal, a second request and a timeout are all testable without Android.
 */
class DocumentSaveCoordinatorTest {
    @Test
    fun aChosenUriIsWrittenAndReportsSaved() =
        runTest {
            val writer = RecordingWriter()
            val coordinator = DocumentSaveCoordinator(writer)
            var launched: PlatformFile? = null
            coordinator.bind(this) { file -> launched = file }
            val file = file()

            val result = async { coordinator.save(file) }
            runCurrent()
            assertEquals(file, launched)
            coordinator.complete("content://provider/track.gpx")

            assertEquals(Outcome.Success(DocumentSaveResult.SAVED), result.await())
            assertEquals(listOf("content://provider/track.gpx:${file.bytes.size}"), writer.calls)
        }

    @Test
    fun aDismissedPickerIsCancelledNotAnError() =
        runTest {
            val coordinator = DocumentSaveCoordinator(RecordingWriter())
            coordinator.bind(this) { }
            val result = async { coordinator.save(file()) }
            runCurrent()

            coordinator.complete(null)

            assertEquals(Outcome.Success(DocumentSaveResult.CANCELLED), result.await())
        }

    @Test
    fun aWriteFailureReachesTheCaller() =
        runTest {
            val coordinator =
                DocumentSaveCoordinator(
                    DocumentWriter { _, _ -> Outcome.Failure(AppError.PermissionDenied) },
                )
            coordinator.bind(this) { }
            val result = async { coordinator.save(file()) }
            runCurrent()

            coordinator.complete("content://provider/track.gpx")

            assertEquals(Outcome.Failure(AppError.PermissionDenied), result.await())
        }

    @Test
    fun aSecondRequestWhileThePickerIsOpenIsNotReady() =
        runTest {
            val coordinator = DocumentSaveCoordinator(RecordingWriter())
            coordinator.bind(this) { }
            val first = async { coordinator.save(file()) }
            runCurrent()

            assertEquals(Outcome.Failure(AppError.NotReady), coordinator.save(file()))

            coordinator.complete("content://provider/track.gpx")
            assertEquals(Outcome.Success(DocumentSaveResult.SAVED), first.await())
        }

    @Test
    fun noBoundActivityIsNotReady() =
        runTest {
            val coordinator = DocumentSaveCoordinator(RecordingWriter())

            assertEquals(Outcome.Failure(AppError.NotReady), coordinator.save(file()))
        }

    @Test
    fun anUnansweredPickerTimesOut() =
        runTest {
            val coordinator = DocumentSaveCoordinator(RecordingWriter(), timeoutMillis = 1_000)
            coordinator.bind(this) { }
            val result = async { coordinator.save(file()) }
            runCurrent()

            advanceTimeBy(1_001)
            runCurrent()

            assertEquals(Outcome.Failure(AppError.Cancelled), result.await())
        }

    @Test
    fun aRecreatedActivityCanStillCompleteTheRequest() =
        runTest {
            val first = Any()
            val second = Any()
            var launches = 0
            val coordinator = DocumentSaveCoordinator(RecordingWriter())
            coordinator.bind(first) { launches += 1 }
            val result = async { coordinator.save(file()) }
            runCurrent()
            coordinator.unbind(first)
            coordinator.bind(second) { launches += 1 }

            coordinator.complete("content://provider/track.gpx")

            assertEquals(1, launches)
            assertEquals(Outcome.Success(DocumentSaveResult.SAVED), result.await())
        }

    @Test
    fun aLatePickerAnswerIsIgnoredAfterTheTimeout() =
        runTest {
            val writer = RecordingWriter()
            val coordinator = DocumentSaveCoordinator(writer, timeoutMillis = 1_000)
            coordinator.bind(this) { }
            val result = async { coordinator.save(file()) }
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            coordinator.complete("content://provider/track.gpx")

            assertEquals(Outcome.Failure(AppError.Cancelled), result.await())
            assertTrue(writer.calls.isEmpty())
        }

    private fun file(): PlatformFile =
        PlatformFile(
            fileName = "track.gpx",
            mimeType = "application/gpx+xml",
            bytes = "<gpx/>".toByteArray(),
        )

    private class RecordingWriter : DocumentWriter {
        val calls = mutableListOf<String>()

        override suspend fun write(
            uri: String,
            file: PlatformFile,
        ): Outcome<Unit> {
            calls += "$uri:${file.bytes.size}"
            return Outcome.Success(Unit)
        }
    }
}
