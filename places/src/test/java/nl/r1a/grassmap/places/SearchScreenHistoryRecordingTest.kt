package nl.r1a.grassmap.places

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
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
 * The search screen writes history on exactly two events (T-0207): a submitted
 * field and a picked result. A keystroke writes nothing, a failed search writes
 * nothing, and the field clear stays a field clear.
 *
 * The keystroke and submit tests share one command with the store attached, so
 * a keystroke that starts writing again fails them.
 */
class SearchScreenHistoryRecordingTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "search-history-recording-${UUID.randomUUID()}")
    private val file = File(root, "history/search-history.json")
    private val clock = MutableClock(1_000L)
    private val setting = MutableHistorySetting()
    private val history = SearchHistoryStore(file, setting, clock)
    private val session =
        SearchScreenSession { request ->
            if (request.query == "fail") {
                Outcome.Failure(AppError.Network)
            } else {
                Outcome.Success(listOf(hit(request.query)))
            }
        }
    private val command = SearchScreenCommand(session, history)

    @After
    fun removeRoot() {
        root.deleteRecursively()
    }

    @Test
    fun `typing without submitting stores nothing`() =
        runTest {
            listOf("l", "lv", "lvi", "lviv").forEachIndexed { index, typed ->
                assertTrue(
                    "keystroke \"$typed\" must search",
                    command.execute(keystroke(typed, sequence = index + 1L)) is Outcome.Success,
                )
            }

            assertFalse("a keystroke must not create a history file", file.exists())
            assertEquals(emptyList<SearchHistoryEntry>(), entries())
        }

    @Test
    fun `submitting stores the typed text once`() =
        runTest {
            command.execute(keystroke("lviv", sequence = 1))
            command.execute(submit("lviv", sequence = 2))

            assertEquals(listOf("lviv"), entries().map { entry -> entry.query })
        }

    @Test
    fun `picking a result stores the result text`() =
        runTest {
            command.execute(keystroke("кава", sequence = 1))

            assertTrue(command.execute(pick("Кава на площі Ринок")) is Outcome.Success)

            val entry = entries().single()
            assertEquals("Кава на площі Ринок", entry.query)
            assertEquals("lviv", entry.index)
            assertEquals(5, entry.limit)
            assertEquals(GeoPoint(49.83, 24.03), entry.position)
        }

    @Test
    fun `backspacing after a submit does not store the fragment`() =
        runTest {
            command.execute(submit("lviv", sequence = 1))
            val sentinel = file.readBytes()
            clock.nowMillis = 2_000L

            command.execute(keystroke("l", sequence = 2))

            assertArrayEquals("a keystroke must not rewrite the store", sentinel, file.readBytes())
            assertEquals(listOf("lviv"), entries().map { entry -> entry.query })
        }

    @Test
    fun `submitting the same query twice keeps one entry`() =
        runTest {
            command.execute(submit("lviv", sequence = 1))
            clock.nowMillis = 2_000L
            command.execute(submit("lviv", sequence = 2))

            val entries = entries()
            assertEquals(1, entries.size)
            assertEquals("lviv", entries.single().query)
            assertEquals(2_000L, entries.single().atMillis)
        }

    @Test
    fun `a submitted search is remembered with its replay context`() =
        runTest {
            val params =
                buildJsonObject {
                    put("query", "кава")
                    put("sequence", 1)
                    put("submit", true)
                    put("index", "lviv")
                    put("limit", 5)
                    put(
                        "viewport",
                        buildJsonObject {
                            put("south", 49.80)
                            put("west", 24.00)
                            put("north", 49.86)
                            put("east", 24.07)
                        },
                    )
                    put(
                        "position",
                        buildJsonObject {
                            put("lat", 49.83)
                            put("lon", 24.03)
                        },
                    )
                }

            assertTrue(command.execute(params) is Outcome.Success)

            val entry = entries().single()
            assertEquals("кава", entry.query)
            assertEquals("lviv", entry.index)
            assertEquals(5, entry.limit)
            assertEquals(1_000L, entry.atMillis)
            assertEquals(BoundingBox(GeoPoint(49.80, 24.00), GeoPoint(49.86, 24.07)), entry.viewport)
            assertEquals(GeoPoint(49.83, 24.03), entry.position)
        }

    @Test
    fun `a failed search is not remembered`() =
        runTest {
            val outcome = command.execute(submit("fail", sequence = 1))

            assertEquals(Outcome.Failure(AppError.Network), outcome)
            assertEquals(emptyList<SearchHistoryEntry>(), entries())
        }

    @Test
    fun `a field clear is not remembered`() =
        runTest {
            command.execute(submit("кава", sequence = 1))
            command.execute(buildJsonObject { put("clear", true) })

            assertEquals(listOf("кава"), entries().map { entry -> entry.query })
        }

    @Test
    fun `a search with history off leaves the file byte identical`() =
        runTest {
            command.execute(submit("кава", sequence = 1))
            val sentinel = file.readBytes()
            setting.enabled = false

            command.execute(submit("аптека", sequence = 2))
            command.execute(pick("Парк"))

            assertArrayEquals(sentinel, file.readBytes())
        }

    @Test
    fun `a search with no history store still completes`() =
        runTest {
            val plain = SearchScreenCommand(session)

            assertTrue(plain.execute(keystroke("кава", sequence = 1)) is Outcome.Success)
            assertTrue(plain.execute(submit("кава", sequence = 2)) is Outcome.Success)
            assertTrue(plain.execute(pick("Кава")) is Outcome.Success)
        }

    @Test
    fun `a submit and a pick in one request are invalid input`() =
        runTest {
            val params =
                buildJsonObject {
                    put("query", "кава")
                    put("submit", true)
                    put("pick", true)
                }

            assertEquals(Outcome.Failure(AppError.InvalidInput), command.execute(params))
            assertFalse(file.exists())
        }

    @Test
    fun `a pick with a blank query is invalid input`() =
        runTest {
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                command.execute(
                    buildJsonObject {
                        put("query", "  ")
                        put("pick", true)
                    },
                ),
            )
            assertFalse(file.exists())
        }

    private fun keystroke(
        query: String,
        sequence: Long,
    ): JsonObject =
        buildJsonObject {
            put("query", query)
            put("sequence", sequence)
        }

    private fun submit(
        query: String,
        sequence: Long,
    ): JsonObject =
        buildJsonObject {
            put("query", query)
            put("sequence", sequence)
            put("submit", true)
        }

    private fun pick(text: String): JsonObject =
        buildJsonObject {
            put("query", text)
            put("pick", true)
            put("index", "lviv")
            put("limit", 5)
            put(
                "position",
                buildJsonObject {
                    put("lat", 49.83)
                    put("lon", 24.03)
                },
            )
        }

    private suspend fun entries(): List<SearchHistoryEntry> {
        val outcome = history.list()
        assertTrue("list failed: $outcome", outcome is Outcome.Success)
        return (outcome as Outcome.Success).value
    }

    private fun hit(query: String): PlaceHit =
        PlaceHit(
            id = query,
            name = query,
            kind = "cafe",
            position = GeoPoint(49.84, 24.03),
            address = null,
            regionBuild = "test",
            score = 1.0f,
        )

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
