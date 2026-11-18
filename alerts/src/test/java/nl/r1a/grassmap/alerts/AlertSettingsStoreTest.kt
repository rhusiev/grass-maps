package nl.r1a.grassmap.alerts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmap.plugin.api.model.ProfileId
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.service.ProfileValueStore
import nl.r1a.grassmap.plugin.api.service.ProfileValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The per-profile alert settings (T-0413) on the profile store (T-0601). */
class AlertSettingsStoreTest {
    @Test
    fun aSwitchOnBikeLeavesWalkOffAndSurvivesAReload() {
        val backing = MemoryStore()
        val store = AlertSettingsStore(backing).also { it.load() }

        store.set(RouteProfile.Bike, ProfileAlertSettings(settings = AlertSettings(signal = true)))

        assertEquals(AlertSettings.ALL_OFF, store.forProfile(RouteProfile.Walk).settings)
        assertEquals(AlertSettings(signal = true), store.forProfile(RouteProfile.Bike).settings)

        val reloaded = AlertSettingsStore(backing).also { it.load() }

        assertEquals(AlertSettings(signal = true), reloaded.forProfile(RouteProfile.Bike).settings)
        assertEquals(AlertSettings.ALL_OFF, reloaded.forProfile(RouteProfile.Walk).settings)
        assertEquals(AlertSettings.ALL_OFF, reloaded.forProfile(RouteProfile.Hike).settings)
    }

    @Test
    fun everySwitchRoundTripsWithoutLeakingToAnotherProfile() {
        val backing = MemoryStore()
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
        AlertSettingsStore(backing).also { it.load() }.set(RouteProfile.Hike, ProfileAlertSettings(settings = allOn))

        val reloaded = AlertSettingsStore(backing).also { it.load() }

        assertEquals(allOn, reloaded.forProfile(RouteProfile.Hike).settings)
        AlertKind.entries.forEach { kind ->
            assertFalse("$kind leaked to walk", reloaded.forProfile(RouteProfile.Walk).settings.isEnabled(kind))
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
        assertEquals(BuiltInProfiles.BIKE, store.activeProfileId())
        assertTrue(store.current().settings.arrival)
        assertFalse("bike must not read walk's switch", store.current().settings.hazard)

        store.activate(RouteProfile.Walk)

        assertEquals(ProfileAlertSettings(settings = AlertSettings(hazard = true)), store.current())
    }

    @Test
    fun theStoredValuesAreStableStringsKeyedByProfileId() {
        val backing = MemoryStore()
        val store = AlertSettingsStore(backing).also { it.load() }

        store.set(
            RouteProfile.Hike,
            ProfileAlertSettings(
                settings = AlertSettings(poi = true, railwayCrossing = true),
                channels = AlertChannels(banner = true, vibration = true),
                poiKinds = setOf("natural=spring", "amenity=shelter"),
            ),
        )

        val hike = backing.stored.getValue(BuiltInProfiles.HIKE)
        assertEquals("true", hike["railway_crossing"])
        assertEquals("true", hike["banner"])
        assertEquals("false", hike["signal"])
        assertEquals("""["amenity=shelter","natural=spring"]""", hike["poi_kinds"])
        assertEquals(setOf(BuiltInProfiles.HIKE), backing.stored.keys)
        val reloaded = AlertSettingsStore(backing).also { it.load() }
        assertEquals(setOf("natural=spring", "amenity=shelter"), reloaded.forProfile(RouteProfile.Hike).poiKinds)
    }

    @Test
    fun aProfileBackAtItsDefaultsIsNoLongerStored() {
        val backing = MemoryStore()
        val store = AlertSettingsStore(backing).also { it.load() }
        store.set(RouteProfile.Bike, ProfileAlertSettings(settings = AlertSettings(signal = true)))

        store.set(RouteProfile.Bike, ProfileAlertSettings.DEFAULT)

        assertEquals(emptyMap<ProfileId, Map<String, String>>(), backing.stored)
    }

    @Test
    fun aNewerSchemaIsRefusedOnLoadAndNeverOverwritten() {
        val newer = AppError.NewerSchema(found = 2, supported = AlertSettingsStore.SCHEMA_VERSION)
        val backing = MemoryStore(failure = newer)
        val store = AlertSettingsStore(backing)

        assertEquals(Outcome.Failure(newer), store.load())
        assertEquals(ProfileAlertSettings.DEFAULT, store.forProfile(RouteProfile.Bike))
        assertEquals(
            Outcome.Failure(newer),
            store.set(RouteProfile.Bike, ProfileAlertSettings(settings = AlertSettings(signal = true))),
        )
        assertEquals(emptyMap<ProfileId, Map<String, String>>(), backing.stored)
    }

    @Test
    fun twoWritersRacingLeaveTheNewestSnapshotInTheFile() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondWritten = CountDownLatch(1)
        val calls = AtomicInteger()
        val backing =
            object : ProfileValueStore {
                @Volatile var stored: ProfileValues = emptyMap()

                override fun read(): Outcome<ProfileValues> = Outcome.Success(stored)

                override fun write(values: ProfileValues): Outcome<Unit> {
                    if (calls.incrementAndGet() == 1) {
                        entered.countDown()
                        release.await()
                    } else {
                        secondWritten.countDown()
                    }
                    stored = values
                    return Outcome.Success(Unit)
                }
            }
        val job = Job()
        val store = AlertSettingsStore(backing, writeScope = CoroutineScope(job))
        val older = ProfileAlertSettings(settings = AlertSettings(signal = true))
        val newer = ProfileAlertSettings(settings = AlertSettings(signal = true, reroute = true))

        store.set(RouteProfile.Walk, older)
        assertTrue(entered.await(WAIT_S, TimeUnit.SECONDS))
        store.set(RouteProfile.Walk, newer)
        secondWritten.await(SECOND_WRITER_GRACE_MS, TimeUnit.MILLISECONDS)
        release.countDown()
        runBlocking { job.children.toList().forEach { it.join() } }

        val reloaded = AlertSettingsStore(backing).also { it.load() }
        assertEquals(newer, reloaded.forProfile(RouteProfile.Walk))
    }

    private companion object {
        const val WAIT_S = 10L
        const val SECOND_WRITER_GRACE_MS = 300L
    }

    private class MemoryStore(
        var stored: ProfileValues = emptyMap(),
        private val failure: AppError? = null,
    ) : ProfileValueStore {
        override fun read(): Outcome<ProfileValues> = failure?.let { Outcome.Failure(it) } ?: Outcome.Success(stored)

        override fun write(values: ProfileValues): Outcome<Unit> {
            failure?.let { return Outcome.Failure(it) }
            stored = values
            return Outcome.Success(Unit)
        }
    }
}
