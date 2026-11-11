package nl.r1a.grassmap.core.services

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.NetworkSwitches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacySwitchSettingsTest {
    private val settings = PrivacySwitchSettings()

    @Test
    fun everySwitchIsOffByDefault() =
        runTest {
            assertTrue(NetworkSwitches.all.isNotEmpty())

            NetworkSwitches.all.forEach { switch ->
                val state = settings.state(switch)
                assertFalse("$switch starts enabled", state.enabled)
                assertEquals(0L, state.requests)
            }
        }

    @Test
    fun statesListsEveryRegisteredSwitchInSettingsOrder() =
        runTest {
            assertEquals(NetworkSwitches.all, settings.states().map { it.switch })
        }

    @Test
    fun enablingOneSwitchLeavesTheOthersOff() =
        runTest {
            val outcome = settings.setEnabled(NetworkSwitches.onlineSearch, true)

            assertEquals(Outcome.Success(Unit), outcome)
            assertTrue(settings.state(NetworkSwitches.onlineSearch).enabled)
            assertFalse(settings.state(NetworkSwitches.onlineTiles).enabled)
            assertFalse(settings.state(NetworkSwitches.regionUpdates).enabled)
        }

    @Test
    fun unregisteredSwitchReadsOffAndCannotBeEnabled() =
        runTest {
            val unregistered = SwitchId("network.unregistered")

            assertFalse(settings.state(unregistered).enabled)
            assertEquals(
                Outcome.Failure(AppError.NotFound),
                settings.setEnabled(unregistered, true),
            )
            assertFalse(settings.state(unregistered).enabled)
        }

    @Test
    fun unregisteredSwitchStaysOffEvenWhenTheStoreHoldsStateForIt() =
        runTest {
            val unregistered = SwitchId("network.unregistered")
            val store = InMemorySwitchStateStore()
            store.setEnabled(unregistered, true)
            val settings = PrivacySwitchSettings(store = store)

            assertFalse(settings.state(unregistered).enabled)
        }

    @Test
    fun recordedRequestsAreReadableThroughTheSettingsView() =
        runTest {
            settings.recordRequest(NetworkSwitches.onlineSearch)
            settings.recordRequest(NetworkSwitches.onlineSearch)

            assertEquals(2L, settings.state(NetworkSwitches.onlineSearch).requests)
        }

    @Test
    fun turningASwitchOffKeepsItsRequestCounter() =
        runTest {
            settings.setEnabled(NetworkSwitches.regionUpdates, true)
            settings.recordRequest(NetworkSwitches.regionUpdates)

            settings.setEnabled(NetworkSwitches.regionUpdates, false)

            val state = settings.state(NetworkSwitches.regionUpdates)
            assertFalse(state.enabled)
            assertEquals(1L, state.requests)
        }
}
