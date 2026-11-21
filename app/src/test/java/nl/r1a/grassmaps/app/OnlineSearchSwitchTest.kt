package nl.r1a.grassmaps.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmaps.core.CommandDispatchers
import nl.r1a.grassmaps.core.services.PrivacyNetworkGate
import nl.r1a.grassmaps.core.services.PrivacySwitchSettings
import nl.r1a.grassmaps.plugin.api.command.CommandResult
import nl.r1a.grassmaps.plugin.api.contract.CancellationHandle
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.provider.SearchContext
import nl.r1a.grassmaps.plugin.api.service.NetworkSwitches
import nl.r1a.grassmaps.plugin.api.service.PrivacySwitches
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugins.online.search.PhotonPlaceProvider
import nl.r1a.grassmaps.plugins.online.search.PhotonTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The online-search switch's debug command and the cancellation it causes mid-flight (T-0616). */
class OnlineSearchSwitchTest {
    @Test
    fun `the debug command toggles the switch and is allowlisted`() =
        runTest {
            val container = container()
            val command = OnlineSearchSwitchCommand { container }

            assertTrue(command.policy.allowlistedForControl)
            assertEquals(Outcome.Success(CommandResult("enabled=true")), command.execute(params(true)))
            assertTrue(container.onlineSearchEnabled)
            assertEquals(Outcome.Success(CommandResult("enabled=false")), command.execute(params(false)))
            assertEquals(false, container.onlineSearchEnabled)
        }

    @Test
    fun `the debug command is allowlisted in the control catalog`() =
        runTest {
            lateinit var container: AppContainer
            container =
                AppContainer(
                    strings = KeyOnlyStrings,
                    extraCommands = { listOf(OnlineSearchSwitchCommand { container }) },
                )

            val entry = container.controlCommands.singleOrNull { it.id == ONLINE_SEARCH_SWITCH_COMMAND }

            assertNotNull("core.search.switch is not in the control catalog", entry)
            assertEquals(true, entry?.allowlistedForControl)
        }

    @Test
    fun `switching off through the command cancels a search already in flight`() =
        runTest {
            val switches = PrivacySwitchSettings()
            val container = container(switches)
            val command = OnlineSearchSwitchCommand { container }
            command.invokeRaw(params(true))
            var chunksRead = 0
            val provider =
                PhotonPlaceProvider(
                    gate = PrivacyNetworkGate(switches),
                    transport =
                        PhotonTransport {
                            repeat(CHUNKS) {
                                currentCoroutineContext().ensureActive()
                                chunksRead++
                                command.invokeRaw(params(false))
                            }
                            Outcome.Success(SUCCESS_BODY)
                        },
                    minIntervalMillis = 0,
                )

            val outcome =
                provider.search(
                    query = "Львів",
                    context = SearchContext(),
                    limit = 5,
                    cancellation = CancellationHandle.None,
                )

            assertEquals(Outcome.Failure(AppError.SwitchOff(NetworkSwitches.onlineSearch)), outcome)
            assertEquals(1, chunksRead)
            assertEquals(false, container.onlineSearchEnabled)
            assertEquals(1L, switches.state(NetworkSwitches.onlineSearch).requests)
        }

    private fun TestScope.container(switches: PrivacySwitches = PrivacySwitchSettings()): AppContainer =
        AppContainer(
            strings = KeyOnlyStrings,
            dispatchers =
                CommandDispatchers(
                    main = StandardTestDispatcher(testScheduler),
                    default = StandardTestDispatcher(testScheduler),
                    io = StandardTestDispatcher(testScheduler),
                ),
            privacySwitches = switches,
        )

    private fun params(enabled: Boolean): JsonObject = JsonObject(mapOf(PARAM_ENABLED to JsonPrimitive(enabled)))

    private object KeyOnlyStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = key

        override fun contains(key: String): Boolean = false
    }

    private companion object {
        const val PARAM_ENABLED = "enabled"
        const val CHUNKS = 3
        const val SUCCESS_BODY = """{"type":"FeatureCollection","features":[]}"""
    }
}
