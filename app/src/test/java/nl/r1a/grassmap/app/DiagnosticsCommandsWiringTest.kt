package nl.r1a.grassmap.app

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.core.CommandDispatchers
import nl.r1a.grassmap.core.services.DiagnosticsCommands
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The diagnostics commands reach the debug control channel through the host catalog. */
class DiagnosticsCommandsWiringTest {
    @Test
    fun `the bundle and toggle commands are allowlisted for the debug channel`() =
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

            val bundle = container.controlCommands.singleOrNull { it.id == DiagnosticsCommands.WRITE_BUNDLE }
            val toggle = container.controlCommands.singleOrNull { it.id == DiagnosticsCommands.SET_LOGGING }

            assertNotNull("core.diagnostics.write_bundle is not in the control catalog", bundle)
            assertNotNull("core.diagnostics.set_logging is not in the control catalog", toggle)
            assertEquals(true, bundle?.allowlistedForControl)
            assertEquals(true, toggle?.allowlistedForControl)
        }

    private object KeyOnlyStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = key

        override fun contains(key: String): Boolean = false
    }
}
