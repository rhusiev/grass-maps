package nl.r1a.grassmap.routing

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.map
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RouteHistoryEntry
import nl.r1a.grassmap.plugin.api.model.RouteHistoryRules
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.service.Clock
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The planning history store (T-0304). Every assertion fails if the store is
 * removed: the order tests read the file back, the off test compares the file's
 * bytes, and the version tests assert the typed refusal.
 */
class RouteHistoryStoreTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "route-history-${UUID.randomUUID()}")
    private val file = File(File(root, "history"), "route-history.json")
    private val clock = MutableClock(1_000L)
    private val setting = MutableRouteSetting()
    private val store = RouteHistoryStore(file, setting, clock)

    @After
    fun removeRoot() {
        root.deleteRecursively()
    }

    @Test
    fun `three routes list newest first with their chains`() =
        runTest {
            rememberAt(1_000L, "one")
            rememberAt(2_000L, "two")
            rememberAt(3_000L, "three")

            val entries = entries()

            assertEquals(listOf("three", "two", "one"), entries.map { entry -> entry.start.label })
            assertEquals(listOf(3_000L, 2_000L, 1_000L), entries.map { entry -> entry.atMillis })
            assertEquals(3, entries.size)
            assertEquals(ENTRY.vias, entries.first().vias)
            assertEquals(ENTRY.distanceMeters, entries.first().distanceMeters, 0.0)
        }

    @Test
    fun `a stored route is read back from a new store instance`() =
        runTest {
            rememberAt(1_000L, "one")
            rememberAt(2_000L, "two")

            val reopened = RouteHistoryStore(file, setting, clock)

            assertEquals(listOf("two", "one"), listed(reopened.recent()).map { entry -> entry.start.label })
        }

    @Test
    fun `repeating a chain moves it to the front with its new time`() =
        runTest {
            rememberAt(1_000L, "one")
            rememberAt(2_000L, "two")
            rememberAt(3_000L, "one")

            val entries = entries()

            assertEquals(listOf("one", "two"), entries.map { entry -> entry.start.label })
            assertEquals(3_000L, entries.first().atMillis)
        }

    @Test
    fun `the same points with another label are another route`() =
        runTest {
            rememberAt(1_000L, "one")
            rememberAt(2_000L, "one-renamed")

            assertEquals(2, entries().size)
        }

    @Test
    fun `the store keeps the newest twenty routes`() =
        runTest {
            repeat(RouteHistoryRules.DEFAULT_MAX_ENTRIES + 1) { index ->
                rememberAt(1_000L + index, "route-$index")
            }

            val entries = entries()

            assertEquals(RouteHistoryRules.DEFAULT_MAX_ENTRIES, entries.size)
            assertEquals("route-20", entries.first().start.label)
            assertEquals("route-1", entries.last().start.label)
        }

    @Test
    fun `clear empties the store completely`() =
        runTest {
            rememberAt(1_000L, "one")
            rememberAt(2_000L, "two")
            assertTrue("the fixture must have written a file", file.isFile)

            assertEquals(Outcome.Success(Unit), store.clear())

            assertFalse("clear must remove the file itself", file.exists())
            assertEquals(emptyList<RouteHistoryEntry>(), entries())
        }

    @Test
    fun `three routes with history off leave the file byte identical`() =
        runTest {
            rememberAt(1_000L, "one")
            rememberAt(2_000L, "two")
            rememberAt(3_000L, "three")
            val sentinel = file.readBytes()

            setting.enabled = false
            rememberAt(4_000L, "four")
            rememberAt(5_000L, "five")
            rememberAt(6_000L, "six")

            assertArrayEquals("history off must not rewrite the store", sentinel, file.readBytes())
            assertEquals("history off must not display the old file", emptyList<RouteHistoryEntry>(), entries())

            setting.enabled = true
            assertEquals(
                "the stored routes must survive being off",
                listOf("three", "two", "one"),
                entries().map { entry -> entry.start.label },
            )
        }

    @Test
    fun `three routes with history on do change the file`() =
        runTest {
            rememberAt(1_000L, "one")
            val before = file.readBytes()

            rememberAt(2_000L, "two")
            rememberAt(3_000L, "three")
            rememberAt(4_000L, "four")
            val after = file.readBytes()

            assertFalse("the positive control must see a write", before.contentEquals(after))
            assertEquals(4, entries().size)
        }

    @Test
    fun `history off never reads a newer file`() =
        runTest {
            writeRaw("""{"format":2,"entries":[]}""")
            val sentinel = file.readBytes()
            setting.enabled = false

            assertEquals(
                Outcome.Success(emptyList<RouteHistoryEntry>()),
                store.remember(ENTRY.copy(start = ENTRY.start.copy(label = "one"))),
            )
            assertEquals(emptyList<RouteHistoryEntry>(), entries())

            assertArrayEquals(sentinel, file.readBytes())
        }

    @Test
    fun `a corrupt file reads as empty and the next route replaces it`() =
        runTest {
            writeRaw("not json at all")

            assertEquals(emptyList<RouteHistoryEntry>(), entries())

            rememberAt(1_000L, "one")

            assertEquals(listOf("one"), entries().map { entry -> entry.start.label })
        }

    @Test
    fun `a newer file format is refused and never overwritten`() =
        runTest {
            writeRaw("""{"format":2,"entries":[]}""")
            val sentinel = file.readBytes()

            assertEquals(Outcome.Failure(AppError.Unsupported), store.remember(ENTRY))
            assertEquals(Outcome.Failure(AppError.Unsupported), store.recent())
            assertArrayEquals("a newer file must not be overwritten", sentinel, file.readBytes())

            assertEquals(Outcome.Success(Unit), store.clear())
            assertFalse(file.exists())
        }

    @Test
    fun `an out of range point is invalid input and writes nothing`() =
        runTest {
            val bad = ENTRY.copy(destination = RoutePlanPoint(GeoPoint(91.0, 24.0)))

            assertEquals(Outcome.Failure(AppError.InvalidInput), store.remember(bad))

            assertFalse(file.exists())
        }

    private suspend fun rememberAt(
        atMillis: Long,
        label: String,
    ) {
        clock.now = atMillis
        val entry =
            ENTRY.copy(
                start = ENTRY.start.copy(label = label),
                destination = ENTRY.destination.copy(label = label),
            )
        assertEquals(Outcome.Success(Unit), store.remember(entry).map { Unit })
    }

    private suspend fun entries(): List<RouteHistoryEntry> = listed(store.recent())

    private fun listed(outcome: Outcome<List<RouteHistoryEntry>>): List<RouteHistoryEntry> =
        when (outcome) {
            is Outcome.Failure -> error("list failed: ${outcome.error}")
            is Outcome.Success -> outcome.value
        }

    private fun writeRaw(text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private class MutableClock(
        var now: Long,
    ) : Clock {
        override fun nowMillis(): Long = now
    }

    private class MutableRouteSetting(
        var enabled: Boolean = true,
    ) : RouteHistorySetting {
        override fun isEnabled(): Boolean = enabled
    }

    private companion object {
        val ENTRY =
            RouteHistoryEntry(
                start = RoutePlanPoint(GeoPoint(49.8290, 23.9520), "start"),
                destination = RoutePlanPoint(GeoPoint(49.8420, 24.0320), "destination"),
                vias = listOf(RoutePlanPoint(GeoPoint(49.8330, 23.9700), "Cafe")),
                distanceMeters = 7_100.0,
                durationSeconds = 1_500.0,
            )
    }
}
