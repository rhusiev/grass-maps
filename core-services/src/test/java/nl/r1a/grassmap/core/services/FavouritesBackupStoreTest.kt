package nl.r1a.grassmap.core.services

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.Clock
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FavouritesBackupStoreTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "favourites-backup-${UUID.randomUUID()}")
    private val directory = File(root, "backups/favourites")
    private val clock = MutableClock(Instant.parse("2026-11-01T10:00:00Z").toEpochMilli())
    private val store = FavouritesBackupStore(directory, clock)

    @After
    fun removeRoot() {
        directory.setWritable(true)
        root.deleteRecursively()
    }

    @Test
    fun fifteenEditsOverEightDaysLeaveTenCopiesAndAtMostTwoPerDay() =
        runTest {
            val editsPerDay = listOf(2, 2, 2, 2, 2, 2, 1, 2)
            var edit = 0
            editsPerDay.forEachIndexed { day, edits ->
                repeat(edits) { index ->
                    edit += 1
                    clock.set(Instant.parse(dayInstant(day)).plusSeconds(index * 60L))
                    assertTrue(store.capture(document(edit)) is Outcome.Success)
                }
            }

            assertEquals(15, edit)
            val copies = listOrFail()
            assertEquals(FavouritesBackupStore.DEFAULT_MAX_COPIES, copies.size)
            val perDay = copies.groupingBy { copy -> dayOf(copy.takenAtMillis) }.eachCount()
            assertTrue(
                "a day may keep at most two copies, found $perDay",
                perDay.values.all { count -> count <= FavouritesBackupStore.DEFAULT_MAX_COPIES_PER_DAY },
            )
            assertEquals(FavouritesBackupStore.DEFAULT_MAX_COPIES, copyFiles().size)
            assertArrayEquals(document(edit), readOrFail(copies.first().id))
        }

    @Test
    fun aSameDayBurstKeepsTwoCopiesAndTheLatestState() =
        runTest {
            repeat(5) { index ->
                clock.set(Instant.parse("2026-11-02T10:00:00Z").plusSeconds(index.toLong()))
                assertTrue(store.capture(document(index)) is Outcome.Success)
            }

            val copies = listOrFail()
            assertEquals(2, copies.size)
            assertArrayEquals(document(4), readOrFail(copies.first().id))
            assertTrue(copies.all { copy -> dayOf(copy.takenAtMillis).toString() == "2026-11-02" })
        }

    @Test
    fun aCopyRestoresToTheTargetFile() =
        runTest {
            assertTrue(store.capture(document(1)) is Outcome.Success)
            clock.advanceSeconds(60)
            val second = captureOrFail(document(2))
            val target = File(root, "live/favourites.gpx")

            assertEquals(Outcome.Success(Unit), store.restore(second.id, target))

            assertArrayEquals(document(2), target.readBytes())
        }

    @Test
    fun restoreOfAMissingCopyIsNotFoundAndLeavesTheTarget() =
        runTest {
            val live = "the live library".toByteArray()
            val target = liveTarget(live)

            assertEquals(Outcome.Failure(AppError.NotFound), store.restore("favourites-1-0001", target))

            assertArrayEquals(live, target.readBytes())
        }

    @Test
    fun restoreOfACorruptCopyIsCorruptDataAndLeavesTheTarget() =
        runTest {
            val copy = captureOrFail(document(1))
            val stored = File(directory, "${copy.id}.gpx")
            stored.writeBytes("<gpx>damaged after the copy</gpx>".toByteArray())
            val live = "the live library".toByteArray()
            val target = liveTarget(live)

            assertEquals(Outcome.Failure(AppError.CorruptData), store.restore(copy.id, target))

            assertArrayEquals(live, target.readBytes())
        }

    @Test
    fun aFailedWriteLeavesThePreviousCopiesIntact() =
        runTest {
            val first = captureOrFail(document(1))
            clock.advanceSeconds(60)
            val second = captureOrFail(document(2))
            assumeTrue("the directory must be read-only for this test", directory.setWritable(false))
            val failed =
                try {
                    store.capture(document(3))
                } finally {
                    directory.setWritable(true)
                }

            assertTrue(failed is Outcome.Failure)
            assertEquals(listOf(second.id, first.id), listOrFail().map { copy -> copy.id })
            assertArrayEquals(document(2), readOrFail(second.id))
            assertArrayEquals(document(1), readOrFail(first.id))
        }

    @Test
    fun aCorruptIndexIsRebuiltFromTheCopyFiles() =
        runTest {
            captureOrFail(document(1))
            clock.advanceSeconds(60)
            captureOrFail(document(2))
            File(directory, "index.tsv").writeText("not an index")

            val copies = FavouritesBackupStore(directory, clock).list().valueOrFail()

            assertEquals(2, copies.size)
            assertArrayEquals(document(2), readOrFail(copies.first().id))
        }

    @Test
    fun anEmptyDocumentIsInvalidInput() =
        runTest {
            assertEquals(Outcome.Failure(AppError.InvalidInput), store.capture(ByteArray(0)))
            assertEquals(emptyList<FavouritesBackupCopy>(), listOrFail())
        }

    private suspend fun captureOrFail(document: ByteArray): FavouritesBackupCopy =
        when (val outcome = store.capture(document)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("capture failed: ${outcome.error}")
        }

    private suspend fun listOrFail(): List<FavouritesBackupCopy> = store.list().valueOrFail()

    private suspend fun readOrFail(id: String): ByteArray =
        when (val outcome = store.read(id)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("read failed: ${outcome.error}")
        }

    private fun liveTarget(bytes: ByteArray): File {
        val target = File(root, "live/favourites.gpx")
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return target
    }

    private fun copyFiles(): List<File> = (directory.listFiles() ?: emptyArray()).filter { file -> file.extension == "gpx" }

    private fun document(index: Int): ByteArray = "<gpx version=\"1.1\"><metadata>$index</metadata></gpx>".toByteArray()

    private fun dayInstant(day: Int): String = "2026-11-${(day + 1).toString().padStart(2, '0')}T10:00:00Z"

    private fun dayOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    private fun <T> Outcome<T>.valueOrFail(): T =
        when (this) {
            is Outcome.Success -> value
            is Outcome.Failure -> throw AssertionError("unexpected failure: $error")
        }

    private class MutableClock(
        private var millis: Long,
    ) : Clock {
        override fun nowMillis(): Long = millis

        fun set(instant: Instant) {
            millis = instant.toEpochMilli()
        }

        fun advanceSeconds(seconds: Long) {
            millis += seconds * 1_000L
        }
    }
}
