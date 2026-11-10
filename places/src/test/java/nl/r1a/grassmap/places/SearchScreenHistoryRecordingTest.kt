package nl.r1a.grassmap.places

import kotlinx.coroutines.test.runTest
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The search command records what it completed (T-0207): a terminal search is
 * stored with its replay context, a failed one is not, and the field clear
 * stays a field clear.
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
    fun `a completed search is remembered with its replay context`() =
        runTest {
            val params =
                buildJsonObject {
                    put("query", "кава")
                    put("sequence", 1)
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
            val outcome = command.execute(buildJsonObject { put("query", "fail") })

            assertEquals(Outcome.Failure(AppError.Network), outcome)
            assertEquals(emptyList<SearchHistoryEntry>(), entries())
        }

    @Test
    fun `a field clear is not remembered`() =
        runTest {
            command.execute(buildJsonObject { put("query", "кава") })
            command.execute(buildJsonObject { put("clear", true) })

            assertEquals(listOf("кава"), entries().map { entry -> entry.query })
        }

    @Test
    fun `a search with history off leaves the file byte identical`() =
        runTest {
            command.execute(buildJsonObject { put("query", "кава") })
            val sentinel = file.readBytes()
            setting.enabled = false

            command.execute(buildJsonObject { put("query", "аптека") })
            command.execute(buildJsonObject { put("query", "парк") })

            assertArrayEquals(sentinel, file.readBytes())
        }

    @Test
    fun `a search with no history store still completes`() =
        runTest {
            val plain = SearchScreenCommand(session)

            assertTrue(plain.execute(buildJsonObject { put("query", "кава") }) is Outcome.Success)
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
        var now: Long,
    ) : Clock {
        override fun nowMillis(): Long = now
    }

    private class MutableHistorySetting(
        var enabled: Boolean = true,
    ) : SearchHistorySetting {
        override fun isEnabled(): Boolean = enabled
    }
}
