package nl.r1a.grassmap.core.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.Clock
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class FavouritesBackupRecorderTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "favourites-recorder-${UUID.randomUUID()}")
    private val directory = File(root, "backups/favourites")
    private val store = FavouritesBackupStore(directory, Clock { 1_760_000_000_000L })
    private var exports = 0

    @After
    fun removeRoot() {
        root.deleteRecursively()
    }

    @Test
    fun aBurstWritesOneCopyAfterTheQuietWindow() =
        runTest {
            val recorder = recorder(this)

            repeat(5) {
                recorder.onMutation()
                advanceTimeBy(DEBOUNCE_MILLIS / 2)
            }
            assertEquals(0, exports)

            advanceTimeBy(DEBOUNCE_MILLIS + 1)
            runCurrent()

            assertEquals(1, exports)
            val copies = store.list().valueOrFail()
            assertEquals(1, copies.size)
            assertArrayEquals(document(1), store.read(copies.single().id).valueOrFail())
        }

    @Test
    fun anEditInsideTheWindowRestartsTheTimer() =
        runTest {
            val recorder = recorder(this)

            recorder.onMutation()
            advanceTimeBy(DEBOUNCE_MILLIS - 1)
            assertEquals(0, exports)

            recorder.onMutation()
            advanceTimeBy(DEBOUNCE_MILLIS - 1)
            assertEquals(0, exports)

            advanceTimeBy(2)
            runCurrent()

            assertEquals(1, exports)
            assertEquals(1, store.list().valueOrFail().size)
        }

    @Test
    fun eachMutationThatSettlesWritesItsOwnCopy() =
        runTest {
            val recorder = recorder(this)

            repeat(2) {
                recorder.onMutation()
                advanceTimeBy(DEBOUNCE_MILLIS + 1)
                runCurrent()
            }

            assertEquals(2, exports)
            assertEquals(2, store.list().valueOrFail().size)
        }

    private fun recorder(scope: kotlinx.coroutines.CoroutineScope): FavouritesBackupRecorder =
        FavouritesBackupRecorder(
            store = store,
            scope = scope,
            export = {
                exports += 1
                Outcome.Success(document(exports))
            },
            debounceMillis = DEBOUNCE_MILLIS,
        )

    private fun document(index: Int): ByteArray = "<gpx version=\"1.1\"><metadata>$index</metadata></gpx>".toByteArray()

    private fun <T> Outcome<T>.valueOrFail(): T =
        when (this) {
            is Outcome.Success -> value
            is Outcome.Failure -> throw AssertionError("unexpected failure: $error")
        }

    private companion object {
        const val DEBOUNCE_MILLIS = 1_000L
    }
}
