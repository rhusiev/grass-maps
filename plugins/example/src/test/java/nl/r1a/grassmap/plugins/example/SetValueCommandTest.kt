package nl.r1a.grassmap.plugins.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetValueCommandTest {
    @Test
    fun commandSetsValueAndBuildsItsInverse() = runTest {
        val state = MutableStateFlow(3)
        val command = SetValueCommand(state)

        val result = command.execute(7)

        assertEquals(7, state.value)
        assertTrue(result is Outcome.Success)
        val inverse = (result as Outcome.Success).value.inverse
        assertNotNull(inverse)
        assertEquals(CommandId("example.set_value"), inverse?.commandId)

        val undo = command.invokeRaw(inverse?.params ?: throw AssertionError("missing inverse"))

        assertTrue(undo is Outcome.Success)
        assertEquals(3, state.value)
    }
}
