package nl.r1a.grassmap.places

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.command.CancellationKey
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search screen's commands (T-0203): typed parameters, the shared
 * cancellation key, and the replay harness reporting percentiles.
 */
class SearchScreenCommandsTest {
    private val session = SearchScreenSession { request -> Outcome.Success(listOf(hit(request.query))) }
    private val command = SearchScreenCommand(session)

    @Test
    fun aKeystrokeSharesOneCancellationKey() {
        assertEquals(
            CancellationKey(SEARCH_SCREEN_CANCELLATION_KEY),
            command.policy.cancellableBy,
        )
        assertTrue("the shell drives the screen, so the channel may too", command.policy.allowlistedForControl)
    }

    @Test
    fun theReplayHarnessIsNotCancelledByAKeystroke() {
        val replay = ReplaySearchCommand(session)
        assertNull("a replay must not share the keystroke cancellation key", replay.policy.cancellableBy)
        assertTrue(replay.policy.allowlistedForControl)
    }

    @Test
    fun aMissingQueryIsInvalidInput() =
        runBlocking {
            assertEquals(Outcome.Failure(AppError.InvalidInput), command.execute(JsonNull))
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                command.execute(JsonObject(mapOf("query" to JsonPrimitive("   ")))),
            )
        }

    @Test
    fun aLimitOutsideTheContractIsInvalidInput() =
        runBlocking {
            val params =
                buildJsonObject {
                    put("query", "львів")
                    put("limit", MAX_SEARCH_RESULTS + 1)
                }
            assertEquals(Outcome.Failure(AppError.InvalidInput), command.execute(params))
        }

    @Test
    fun aKeystrokePublishesItsHits() =
        runBlocking {
            val params =
                buildJsonObject {
                    put("query", "львів")
                    put("sequence", 1)
                }
            val outcome = command.execute(params)

            assertTrue(outcome is Outcome.Success)
            assertEquals(SearchScreenStatus.RESULTS, session.state.value.status)
            assertEquals(
                listOf("львів"),
                session.state.value.hits
                    .map { it.id },
            )
        }

    @Test
    fun clearEmptiesTheScreen() =
        runBlocking {
            command.execute(
                buildJsonObject {
                    put("query", "львів")
                    put("sequence", 1)
                },
            )
            val cleared = command.execute(buildJsonObject { put("clear", true) })

            assertTrue(cleared is Outcome.Success)
            assertEquals(SearchScreenStatus.IDLE, session.state.value.status)
        }

    @Test
    fun theReplayReportsTheThreePercentiles() =
        runBlocking {
            val replay = ReplaySearchCommand(session)
            val params =
                buildJsonObject {
                    put(
                        "queries",
                        kotlinx.serialization.json.JsonArray(
                            listOf(JsonPrimitive("львів"), JsonPrimitive("славсько")),
                        ),
                    )
                }
            val outcome = replay.execute(params)

            val summary = (outcome as? Outcome.Success)?.value?.value
            assertTrue("unexpected replay outcome: $outcome", summary != null)
            assertTrue("the summary must name p50: $summary", summary!!.contains("p50_ms="))
            assertTrue("the summary must name p95: $summary", summary.contains("p95_ms="))
            assertTrue("the summary must name p99: $summary", summary.contains("p99_ms="))
            assertTrue("the summary must count the keystrokes: $summary", summary.contains("keystrokes="))
        }

    @Test
    fun aReplayWithoutQueriesIsInvalidInput() =
        runBlocking {
            val replay = ReplaySearchCommand(session)
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                replay.execute(buildJsonObject { put("queries", kotlinx.serialization.json.JsonArray(emptyList())) }),
            )
        }

    private fun hit(id: String): PlaceHit =
        PlaceHit(
            id = id,
            name = id,
            kind = "place=street",
            position = GeoPoint(49.8419, 24.0316),
            address = null,
            regionBuild = "test",
            score = 1f,
        )
}
