package nl.r1a.grassmap.alerts

import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The per-profile alert settings (T-0413). */
class AlertSettingsStoreTest {
    @Test
    fun aSwitchOnBikeLeavesWalkOffAndSurvivesAReload() {
        val directory = temporaryDirectory()
        try {
            val file = File(directory, "settings/alerts.json")
            val store = AlertSettingsStore(file).also { it.load() }

            store.set(RouteProfile.Bike, ProfileAlertSettings(settings = AlertSettings(signal = true)))

            assertEquals(AlertSettings.ALL_OFF, store.forProfile(RouteProfile.Walk).settings)
            assertEquals(AlertSettings(signal = true), store.forProfile(RouteProfile.Bike).settings)

            val reloaded = AlertSettingsStore(file).also { it.load() }

            assertEquals(AlertSettings(signal = true), reloaded.forProfile(RouteProfile.Bike).settings)
            assertEquals(AlertSettings.ALL_OFF, reloaded.forProfile(RouteProfile.Walk).settings)
            assertEquals(AlertSettings.ALL_OFF, reloaded.forProfile(RouteProfile.Hike).settings)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun everySwitchRoundTripsWithoutLeakingToAnotherProfile() {
        val directory = temporaryDirectory()
        try {
            val file = File(directory, "settings/alerts.json")
            val allOn =
                AlertSettings(
                    signal = true,
                    reroute = true,
                    offRoute = true,
                    arrival = true,
                    tunnel = true,
                    railwayCrossing = true,
                    hazard = true,
                    favourite = true,
                    trackWaypoint = true,
                    poi = true,
                )
            AlertSettingsStore(file).also { it.load() }.set(
                RouteProfile.Hike,
                ProfileAlertSettings(settings = allOn),
            )

            val reloaded = AlertSettingsStore(file).also { it.load() }

            assertEquals(allOn, reloaded.forProfile(RouteProfile.Hike).settings)
            AlertKind.entries.forEach { kind ->
                assertFalse(
                    "$kind leaked to walk",
                    reloaded.forProfile(RouteProfile.Walk).settings.isEnabled(kind),
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun theChannelsAndChosenKindsArePerProfile() {
        val store = AlertSettingsStore().also { it.load() }
        val walk =
            ProfileAlertSettings(
                channels = AlertChannels(banner = true),
                poiKinds = setOf("amenity=drinking_water"),
            )
        val hike =
            ProfileAlertSettings(
                settings = AlertSettings(poi = true),
                channels = AlertChannels(sound = true, vibration = true),
                poiKinds = setOf("natural=spring"),
            )

        store.set(RouteProfile.Walk, walk)
        store.set(RouteProfile.Hike, hike)

        assertEquals(walk, store.forProfile(RouteProfile.Walk))
        assertEquals(hike, store.forProfile(RouteProfile.Hike))
        assertEquals(ProfileAlertSettings.DEFAULT, store.forProfile(RouteProfile.Car))
    }

    @Test
    fun theActiveProfileReadsThatProfilesValues() {
        val store = AlertSettingsStore().also { it.load() }
        store.set(RouteProfile.Bike, ProfileAlertSettings(settings = AlertSettings(arrival = true)))
        store.set(RouteProfile.Walk, ProfileAlertSettings(settings = AlertSettings(hazard = true)))

        store.activate(RouteProfile.Bike)

        assertEquals(RouteProfile.Bike, store.activeProfile())
        assertTrue(store.current().settings.arrival)
        assertFalse("bike must not read walk's switch", store.current().settings.hazard)

        store.activate(RouteProfile.Walk)

        assertEquals(ProfileAlertSettings(settings = AlertSettings(hazard = true)), store.current())
    }

    @Test
    fun theChosenKindsSurviveAReloadWithTheirIndexSpelling() {
        val directory = temporaryDirectory()
        try {
            val file = File(directory, "settings/alerts.json")
            val store = AlertSettingsStore(file).also { it.load() }
            store.set(
                RouteProfile.Hike,
                ProfileAlertSettings(
                    settings = AlertSettings(poi = true, railwayCrossing = true),
                    channels = AlertChannels(banner = true, vibration = true),
                    poiKinds = setOf("natural=spring", "amenity=shelter"),
                ),
            )

            val text = file.readText()

            assertTrue(text, text.contains("\"railway_crossing\":true"))
            assertTrue(text, text.contains("\"banner\":true"))
            assertTrue(text, text.contains("\"amenity=shelter\""))
            val reloaded = AlertSettingsStore(file).also { it.load() }
            assertEquals(
                setOf("natural=spring", "amenity=shelter"),
                reloaded.forProfile(RouteProfile.Hike).poiKinds,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aCorruptFileReadsAsAllOff() {
        val directory = temporaryDirectory()
        try {
            val file = File(directory, "alerts.json")
            file.writeText("{not json")

            val store = AlertSettingsStore(file).also { it.load() }

            assertEquals(ProfileAlertSettings.DEFAULT, store.forProfile(RouteProfile.Bike))
            assertEquals(ProfileAlertSettings.DEFAULT, store.current())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun temporaryDirectory(): File = Files.createTempDirectory("alerts-settings").toFile()
}
