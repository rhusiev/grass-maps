package nl.r1a.grassmap.places

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.SearchContext
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
 * The search history store (T-0207). Every assertion here fails if the store is
 * removed: the order tests read the file back, the off test compares the file's
 * bytes, and the version tests assert the typed refusal.
 */
class SearchHistoryStoreTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "search-history-${UUID.randomUUID()}")
    private val file = File(File(root, "history"), "search-history.json")
    private val clock = MutableClock(1_000L)
    private val setting = MutableHistorySetting()
    private val store = SearchHistoryStore(file, setting, clock)

    @After
    fun removeRoot() {
        root.deleteRecursively()
    }

    @Test
    fun `three searches list newest first`() =
        runTest {
            rememberAt(1_000L, "кава")
            rememberAt(2_000L, "аптека")
            rememberAt(3_000L, "парк")

            val entries = entries()

            assertEquals(listOf("парк", "аптека", "кава"), entries.map { entry -> entry.query })
            assertEquals(listOf(3_000L, 2_000L, 1_000L), entries.map { entry -> entry.atMillis })
            assertEquals("each search is its own row", 3, entries.size)
        }

    @Test
    fun `a later search is read back from a new store instance`() =
        runTest {
            rememberAt(1_000L, "кава")
            rememberAt(2_000L, "аптека")

            val reopened = SearchHistoryStore(file, setting, clock)
            val entries =
                when (val listed = reopened.list()) {
                    is Outcome.Failure -> error("list failed: ${listed.error}")
                    is Outcome.Success -> listed.value
                }

            assertEquals(listOf("аптека", "кава"), entries.map { entry -> entry.query })
        }

    @Test
    fun `a repeated query moves to the front with its new time`() =
        runTest {
            rememberAt(1_000L, "кава")
            rememberAt(2_000L, "аптека")
            rememberAt(3_000L, "кава")

            val entries = entries()

            assertEquals(listOf("кава", "аптека"), entries.map { entry -> entry.query })
            assertEquals(3_000L, entries.first().atMillis)
        }

    @Test
    fun `one typing session keeps one row`() =
        runTest {
            listOf("l", "lv", "lvi", "lviv").forEachIndexed { index, typed ->
                rememberAt(1_000L + index, typed)
            }

            rememberAt(2_000L, "l")

            val entries = entries()

            assertEquals(1, entries.size)
            assertEquals("l", entries.first().query)
        }

    @Test
    fun `the store keeps the newest twenty entries`() =
        runTest {
            repeat(SearchHistoryStore.DEFAULT_MAX_ENTRIES + 1) { index ->
                rememberAt(1_000L + index, "query-$index")
            }

            val entries = entries()

            assertEquals(SearchHistoryStore.DEFAULT_MAX_ENTRIES, entries.size)
            assertEquals("query-20", entries.first().query)
            assertEquals("query-1", entries.last().query)
        }

    @Test
    fun `clear empties the store completely`() =
        runTest {
            rememberAt(1_000L, "кава")
            rememberAt(2_000L, "аптека")
            assertTrue("the fixture must have written a file", file.isFile)

            val cleared = store.clear()

            assertEquals(Outcome.Success(Unit), cleared)
            assertFalse("clear must remove the file itself", file.exists())
            assertEquals(emptyList<SearchHistoryEntry>(), entries())
        }

    @Test
    fun `three searches with history off leave the file byte identical`() =
        runTest {
            rememberAt(1_000L, "кава")
            rememberAt(2_000L, "аптека")
            rememberAt(3_000L, "парк")
            val sentinel = file.readBytes()

            setting.enabled = false
            rememberAt(4_000L, "лікарня")
            rememberAt(5_000L, "школа")
            rememberAt(6_000L, "банк")

            assertArrayEquals("history off must not rewrite the store", sentinel, file.readBytes())
            assertEquals("history off must not display the old file", emptyList<SearchHistoryEntry>(), entries())

            setting.enabled = true
            assertEquals(
                "the stored searches must survive being off",
                listOf("парк", "аптека", "кава"),
                entries().map { entry -> entry.query },
            )
        }

    @Test
    fun `three searches with history on do change the file`() =
        runTest {
            rememberAt(1_000L, "кава")
            val before = file.readBytes()

            rememberAt(2_000L, "аптека")
            rememberAt(3_000L, "парк")
            rememberAt(4_000L, "банк")
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

            assertEquals(Outcome.Success(Unit), store.remember("кава"))
            assertEquals(emptyList<SearchHistoryEntry>(), entries())

            assertArrayEquals(sentinel, file.readBytes())
        }

    @Test
    fun `a corrupt file reads as empty and the next search replaces it`() =
        runTest {
            writeRaw("not json at all")

            assertEquals(emptyList<SearchHistoryEntry>(), entries())

            rememberAt(1_000L, "кава")

            assertEquals(listOf("кава"), entries().map { entry -> entry.query })
        }

    @Test
    fun `a newer file format is refused and never overwritten`() =
        runTest {
            writeRaw("""{"format":2,"entries":[]}""")
            val sentinel = file.readBytes()

            assertEquals(Outcome.Failure(AppError.Unsupported), store.remember("кава"))
            assertEquals(Outcome.Failure(AppError.Unsupported), store.list())
            assertArrayEquals("a newer file must not be overwritten", sentinel, file.readBytes())

            assertEquals(Outcome.Success(Unit), store.clear())
            assertFalse(file.exists())
        }

    @Test
    fun `the replay context is stored and returned`() =
        runTest {
            val context =
                SearchContext(
                    viewport = BoundingBox(GeoPoint(49.80, 24.00), GeoPoint(49.86, 24.07)),
                    language = "uk",
                    position = GeoPoint(49.83, 24.03),
                )

            store.remember("кава", index = "lviv", limit = 7, context = context)

            val entry = entries().single()
            assertEquals("кава", entry.query)
            assertEquals("lviv", entry.index)
            assertEquals(7, entry.limit)
            assertEquals(context, entry.context())
        }

    @Test
    fun `a blank query is refused and writes nothing`() =
        runTest {
            assertEquals(Outcome.Failure(AppError.InvalidInput), store.remember("   "))

            assertFalse(file.exists())
        }

    @Test
    fun `a limit outside the contract is refused`() =
        runTest {
            assertEquals(Outcome.Failure(AppError.InvalidInput), store.remember("кава", limit = 0))
            assertEquals(Outcome.Failure(AppError.InvalidInput), store.remember("кава", limit = MAX_SEARCH_RESULTS + 1))

            assertFalse(file.exists())
        }

    private suspend fun rememberAt(
        millis: Long,
        query: String,
    ): Outcome<Unit> {
        clock.nowMillis = millis
        val outcome = store.remember(query)
        assertEquals("remember failed for $query", Outcome.Success(Unit), outcome)
        return outcome
    }

    private suspend fun entries(): List<SearchHistoryEntry> {
        val outcome = store.list()
        assertTrue("list failed: $outcome", outcome is Outcome.Success)
        return (outcome as Outcome.Success).value
    }

    private fun writeRaw(text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private class MutableClock(
        var nowMillis: Long,
    ) : Clock {
        override fun nowMillis(): Long = nowMillis
    }

    private class MutableHistorySetting(
        var enabled: Boolean = true,
    ) : SearchHistorySetting {
        override fun isEnabled(): Boolean = enabled
    }
}
