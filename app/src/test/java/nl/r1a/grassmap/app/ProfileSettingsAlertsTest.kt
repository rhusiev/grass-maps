package nl.r1a.grassmap.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import nl.r1a.grassmap.alerts.AlertSettings
import nl.r1a.grassmap.alerts.AlertSettingsStore
import nl.r1a.grassmap.alerts.ProfileAlertSettings
import nl.r1a.grassmap.core.services.ProfileCatalog
import nl.r1a.grassmap.core.services.ProfileSettingsService
import nl.r1a.grassmap.core.services.RoutingOptionsSettings
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.service.ProfileValueStore
import nl.r1a.grassmap.plugin.api.service.ProfileValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The profile actions on the real alert store with its background writer, as the app wires them (T-0602). */
class ProfileSettingsAlertsTest {
    private val signal = ProfileAlertSettings(settings = AlertSettings(signal = true))

    @Test
    fun aCopyIsOnDiskBeforeTheCallReturns() {
        val backing = SwitchableStore()
        val job = Job()
        val alerts = AlertSettingsStore(backing, writeScope = CoroutineScope(job)).also { it.load() }
        val service = serviceOn(alerts)
        alerts.set(RouteProfile.Walk, signal)

        val copied = service.copySettings("walk", "bike")

        assertEquals(signal, alerts.forProfile(RouteProfile.Bike))
        assertEquals(alerts.read(BuiltInProfiles.WALK), backing.stored[BuiltInProfiles.BIKE])
        assertEquals(true, copied is Outcome.Success)
        job.cancel()
    }

    @Test
    fun aRefusedAlertWriteFailsTheActionAndLeavesNoUndoEntry() {
        val backing = SwitchableStore()
        val alerts = AlertSettingsStore(backing, writeScope = CoroutineScope(Job())).also { it.load() }
        val service = serviceOn(alerts)
        alerts.set(RouteProfile.Walk, signal)
        backing.failWrites = true

        val result = service.copySettings("walk", "bike")

        assertEquals(Outcome.Failure(AppError.CorruptData), result)
        assertEquals(ProfileAlertSettings.DEFAULT, alerts.forProfile(RouteProfile.Bike))
        assertNull((service.state() as Outcome.Success).value.undo)
    }

    @Test
    fun aNewerAlertFileRefusesTheActionBeforeAnyGroupIsTouched() {
        val newer = AppError.NewerSchema(found = 2, supported = AlertSettingsStore.SCHEMA_VERSION)
        val alerts = AlertSettingsStore(SwitchableStore(readFailure = newer))
        val service = serviceOn(alerts)

        assertEquals(Outcome.Failure(newer), service.create("Gravel", "bike"))
    }

    private fun serviceOn(alerts: AlertSettingsStore): ProfileSettingsService {
        val routing = RoutingOptionsSettings()
        routing.set(BuiltInProfiles.WALK, RouteOptions(hills = RoutePreference.Avoid))
        return ProfileSettingsService(ProfileCatalog(), listOf(routing, alerts), ensureReady = alerts::ensureLoaded)
    }

    private class SwitchableStore(
        var stored: ProfileValues = emptyMap(),
        private val readFailure: AppError? = null,
        var failWrites: Boolean = false,
    ) : ProfileValueStore {
        override fun read(): Outcome<ProfileValues> = readFailure?.let { Outcome.Failure(it) } ?: Outcome.Success(stored)

        override fun write(values: ProfileValues): Outcome<Unit> {
            if (failWrites) return Outcome.Failure(AppError.CorruptData)
            stored = values
            return Outcome.Success(Unit)
        }
    }
}
