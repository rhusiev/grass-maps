package nl.r1a.grassmap.places

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** The clear and list actions of the search history (T-0207). */
class SearchHistoryCommandsTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "search-history-commands-${UUID.randomUUID()}")
    private val file = File(root, "history/search-history.json")
    private val setting = MutableHistorySetting()
    private val clock = MutableClock(1_000L)
    private val store = SearchHistoryStore(file, setting, clock)
    private val clear = SearchHistoryClearCommand(store)
    private val list = SearchHistoryListCommand(store)

    @After
    fun removeRoot() {
        root.deleteRecursively()
    }

    @Test
    fun `clear empties the store and reports zero entries`() =
        runTest {
            seed("кава", "аптека")

            val outcome = clear.execute(JsonNull)

            assertEquals(Outcome.Success(CommandResult("cleared=true entries=0")), outcome)
            assertFalse(file.exists())
        }

    @Test
    fun `clear works with history off`() =
        runTest {
            seed("кава", "аптека")
            setting.enabled = false

            assertEquals(Outcome.Success(CommandResult("cleared=true entries=0")), clear.execute(JsonNull))
            assertFalse(file.exists())
        }

    @Test
    fun `the list action reports the count and no query text`() =
        runTest {
            seed("кава", "аптека")

            val outcome = list.execute(JsonNull)

            assertEquals(Outcome.Success(CommandResult("entries=2")), outcome)
            assertFalse("query text must not reach the logged channel", outcome.toString().contains("кава"))
        }

    @Test
    fun `both actions are control-allowlisted and run on IO`() {
        val commands = searchHistoryCommands(store)

        assertEquals(2, commands.size)
        assertTrue(commands.all { command -> command.policy.allowlistedForControl })
        assertTrue(commands.all { command -> command.policy.dispatcher == CommandDispatcher.IO })
    }

    private suspend fun seed(vararg queries: String) {
        queries.forEach { query ->
            clock.now += 1_000L
            assertEquals(Outcome.Success(Unit), store.remember(query))
        }
    }

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
