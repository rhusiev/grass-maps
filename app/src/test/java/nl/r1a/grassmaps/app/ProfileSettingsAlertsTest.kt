package nl.r1a.grassmaps.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmaps.alerts.AlertSettings
import nl.r1a.grassmaps.alerts.AlertSettingsStore
import nl.r1a.grassmaps.alerts.ProfileAlertSettings
import nl.r1a.grassmaps.core.services.ProfileCatalog
import nl.r1a.grassmaps.core.services.ProfileSettingsService
import nl.r1a.grassmaps.core.services.ProfileValueFile
import nl.r1a.grassmaps.core.services.RoutingOptionsSettings
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.provider.RouteOptions
import nl.r1a.grassmaps.plugin.api.provider.RoutePreference
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugin.api.service.ProfileSettingsGroup
import nl.r1a.grassmaps.plugin.api.service.ProfileValueStore
import nl.r1a.grassmaps.plugin.api.service.ProfileValues
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

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

    @Test
    fun unknownKeysInTheAlertsFileSurviveEveryAction() {
        val file = File(directory, "alerts.json")
        file.writeText(
            """{"schema_version":1,"profiles":{"bike":{"signal":"true","surface":"smooth"},"hike":{"surface":"rough"},"walk":{"poi":"true"}}}""",
        )
        val alerts =
            AlertSettingsStore(
                ProfileValueFile(file, AlertSettingsStore.SCHEMA_VERSION, AlertSettingsStore.ERROR_ID),
            ).also { it.load() }
        val service = serviceOn(alerts)

        service.copySettings("walk", "bike", setOf(ProfileSettingsGroup.ALERTS))
        service.reset("hike", setOf(ProfileSettingsGroup.ALERTS))
        service.create("Gravel", "bike")
        service.reset("bike", setOf(ProfileSettingsGroup.ALERTS))
        alerts.set(RouteProfile.Walk, ProfileAlertSettings.DEFAULT)

        val profiles =
            Json
                .parseToJsonElement(file.readText())
                .jsonObject
                .getValue("profiles")
                .jsonObject
        assertEquals(
            "smooth",
            profiles
                .getValue("bike")
                .jsonObject
                .getValue("surface")
                .jsonPrimitive.content,
        )
        assertEquals("""{"surface":"rough"}""", profiles.getValue("hike").toString())
        assertEquals(null, profiles["walk"])
        assertEquals(
            "false",
            profiles
                .getValue("gravel")
                .jsonObject
                .getValue("signal")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun aCorruptAlertsFileStillLetsARoutingOnlyActionRun() {
        val alerts = AlertSettingsStore(SwitchableStore(readFailure = AppError.CorruptData))
        val service = serviceOn(alerts)

        assertEquals(true, service.copySettings("walk", "bike", setOf(ProfileSettingsGroup.ROUTING)) is Outcome.Success)
        assertEquals(Outcome.Failure(AppError.CorruptData), service.copySettings("walk", "hike"))
        assertEquals(Outcome.Failure(AppError.CorruptData), service.reset("bike", setOf(ProfileSettingsGroup.ALERTS)))
    }

    @Test
    fun anAlertsFileThatCouldNotBeReadIsReadAgainByTheNextAction() {
        val backing = SwitchableStore(readFailure = AppError.Internal(AlertSettingsStore.ERROR_ID, "IOException"))
        val service = serviceOn(AlertSettingsStore(backing))

        assertEquals(true, service.create("Gravel", "bike") is Outcome.Failure)
        backing.readFailure = null

        assertEquals(true, service.create("Gravel", "bike") is Outcome.Success)
    }

    @Test
    fun aProfileResetToTheAlertDefaultsLeavesNoEntryInTheFile() {
        val file = File(directory, "alerts.json")
        file.writeText("""{"schema_version":1,"profiles":{"bike":{"signal":"true"},"hike":{"poi":"true"}}}""")
        val alerts =
            AlertSettingsStore(
                ProfileValueFile(file, AlertSettingsStore.SCHEMA_VERSION, AlertSettingsStore.ERROR_ID),
            ).also { it.load() }
        val service = serviceOn(alerts)

        val reset = service.reset("bike", setOf(ProfileSettingsGroup.ALERTS))

        assertEquals(true, reset is Outcome.Success)
        val profiles =
            Json
                .parseToJsonElement(file.readText())
                .jsonObject
                .getValue("profiles")
                .jsonObject
        assertNull("the reset profile writes no entry", profiles["bike"])
        assertEquals("""{"poi":"true"}""", profiles.getValue("hike").toString())
        assertEquals(ProfileAlertSettings.DEFAULT, alerts.forProfile(RouteProfile.Bike))
    }

    private lateinit var directory: File

    @Before
    fun makeDirectory() {
        directory = Files.createTempDirectory("alerts-actions-").toFile()
    }

    @After
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    private fun serviceOn(alerts: AlertSettingsStore): ProfileSettingsService {
        val routing = RoutingOptionsSettings()
        routing.set(BuiltInProfiles.WALK, RouteOptions(hills = RoutePreference.Avoid))
        return ProfileSettingsService(ProfileCatalog(), listOf(routing, alerts), ensureReady = { group ->
            if (group ===
                alerts
            ) {
                alerts.ensureLoaded()
            } else {
                Outcome.Success(Unit)
            }
        })
    }

    private class SwitchableStore(
        var stored: ProfileValues = emptyMap(),
        var readFailure: AppError? = null,
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
