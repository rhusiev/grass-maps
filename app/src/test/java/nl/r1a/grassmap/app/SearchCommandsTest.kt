package nl.r1a.grassmap.app

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.core.CommandDispatchers
import nl.r1a.grassmap.places.PlacesAccess
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The debug search trigger: typed failures, and it stays control-allowlisted. */
class SearchCommandsTest {
    private val command = SearchQueryCommand({ PlacesAccess(null) }) { }

    @Test
    fun `no parameters are invalid input`() =
        runTest {
            assertEquals(Outcome.Failure(AppError.InvalidInput), command.execute(JsonNull))
        }

    @Test
    fun `a blank query is invalid input`() =
        runTest {
            val params = JsonObject(mapOf("query" to JsonPrimitive("  ")))
            assertEquals(Outcome.Failure(AppError.InvalidInput), command.execute(params))
        }

    @Test
    fun `a limit outside the contract is invalid input`() =
        runTest {
            val params = JsonObject(mapOf("query" to JsonPrimitive("львів"), "limit" to JsonPrimitive(0)))
            assertEquals(Outcome.Failure(AppError.InvalidInput), command.execute(params))
        }

    @Test
    fun `a missing search root is not ready`() =
        runTest {
            val params = JsonObject(mapOf("query" to JsonPrimitive("львів")))
            assertEquals(Outcome.Failure(AppError.NotReady), command.execute(params))
        }

    @Test
    fun `the search trigger is allowlisted for the debug channel`() =
        runTest {
            val container =
                AppContainer(
                    strings = KeyOnlyStrings,
                    dispatchers =
                        CommandDispatchers(
                            main = StandardTestDispatcher(testScheduler),
                            default = StandardTestDispatcher(testScheduler),
                            io = StandardTestDispatcher(testScheduler),
                        ),
                )
            val entry = container.controlCommands.singleOrNull { it.id == SEARCH_QUERY_COMMAND }
            assertTrue("core.search.query is not in the control catalog", entry != null)
            assertEquals(true, entry?.allowlistedForControl)
        }

    private object KeyOnlyStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = key

        override fun contains(key: String): Boolean = false
    }
}
