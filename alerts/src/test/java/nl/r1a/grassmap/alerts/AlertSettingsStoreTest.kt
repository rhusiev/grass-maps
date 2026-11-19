package nl.r1a.grassmap.alerts

import kotlinx.coroutines.CoroutineDispatcher
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** The per-profile alert settings (T-0413) on the profile store (T-0601). */
class AlertSettingsStoreTest {
    @Test
    fun theStoredValuesCopyToAnotherProfileAsAGroup() {
        val backing = MemoryStore()
        val store = AlertSettingsStore(backing).also { it.load() }
        store.set(RouteProfile.Bike, ProfileAlertSettings(settings = AlertSettings(signal = true), poiKinds = setOf("cafe")))

        val copied = store.replace(ProfileId("gravel"), store.read(BuiltInProfiles.BIKE))

        assertEquals(Outcome.Success(Unit), copied)
        assertEquals(store.forProfile(RouteProfile.Bike), store.forProfile(ProfileId("gravel")))
        assertTrue(store.read(BuiltInProfiles.WALK).isEmpty())

        store.replace(ProfileId("gravel"), emptyMap())

        assertEquals(ProfileAlertSettings(), store.forProfile(ProfileId("gravel")))
        assertFalse((backing.read() as Outcome.Success).value.containsKey(ProfileId("gravel")))
    }

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
        val secondWriterParked = CountDownLatch(1)
        val calls = AtomicInteger()
        val backing =
            object : ProfileValueStore {
                @Volatile var stored: ProfileValues = emptyMap()

                override fun read(): Outcome<ProfileValues> = Outcome.Success(stored)

                override fun write(values: ProfileValues): Outcome<Unit> {
                    if (calls.incrementAndGet() == 1) {
                        entered.countDown()
                        release.await()
                    }
                    stored = values
                    return Outcome.Success(Unit)
                }
            }
        val job = Job()
        val dispatcher = CountingDispatcher(secondWriterParked)
        val store = AlertSettingsStore(backing, writeScope = CoroutineScope(job), writeDispatcher = dispatcher)
        val older = ProfileAlertSettings(settings = AlertSettings(signal = true))
        val newer = ProfileAlertSettings(settings = AlertSettings(signal = true, reroute = true))

        store.set(RouteProfile.Walk, older)
        assertTrue(entered.await(WAIT_S, TimeUnit.SECONDS))
        store.set(RouteProfile.Walk, newer)
        assertTrue(secondWriterParked.await(WAIT_S, TimeUnit.SECONDS))
        release.countDown()
        runBlocking { job.children.toList().forEach { it.join() } }

        val reloaded = AlertSettingsStore(backing).also { it.load() }
        assertEquals(newer, reloaded.forProfile(RouteProfile.Walk))
    }

    @Test
    fun aFailedWriteLeavesTheSettingsInMemoryAsTheyWere() {
        val backing = MemoryStore()
        val store = AlertSettingsStore(backing).also { it.load() }
        val signal = ProfileAlertSettings(settings = AlertSettings(signal = true))
        backing.writeFailure = AppError.CorruptData

        assertEquals(Outcome.Failure(AppError.CorruptData), store.set(RouteProfile.Bike, signal))
        assertEquals(
            Outcome.Failure(AppError.CorruptData),
            store.replace(
                BuiltInProfiles.BIKE,
                store.read(BuiltInProfiles.BIKE) + ("x" to "y"),
            ),
        )

        assertEquals(ProfileAlertSettings.DEFAULT, store.forProfile(RouteProfile.Bike))
        assertTrue(store.read(BuiltInProfiles.BIKE).isEmpty())
    }

    @Test
    fun replaceWritesBeforeItReturnsEvenWithAWriteScope() {
        val backing = MemoryStore()
        val job = Job()
        val store = AlertSettingsStore(backing, writeScope = CoroutineScope(job)).also { it.load() }
        val source = AlertSettingsStore().also { it.set(RouteProfile.Walk, ProfileAlertSettings(settings = AlertSettings(signal = true))) }

        assertEquals(Outcome.Success(Unit), store.replace(BuiltInProfiles.BIKE, source.read(BuiltInProfiles.WALK)))

        assertEquals(source.read(BuiltInProfiles.WALK), backing.stored.getValue(BuiltInProfiles.BIKE))
        backing.writeFailure = AppError.CorruptData
        assertEquals(Outcome.Failure(AppError.CorruptData), store.replace(BuiltInProfiles.BIKE, emptyMap()))
        assertEquals(source.forProfile(RouteProfile.Walk), store.forProfile(RouteProfile.Bike))
        job.cancel()
    }

    @Test
    fun theLoadResultIsKeptForEnsureLoaded() {
        val newer = AppError.NewerSchema(found = 2, supported = AlertSettingsStore.SCHEMA_VERSION)
        val store = AlertSettingsStore(MemoryStore(failure = newer))

        assertEquals(Outcome.Failure(newer), store.ensureLoaded())
        assertEquals(Outcome.Failure(newer), store.ensureLoaded())
    }

    private companion object {
        const val WAIT_S = 10L
    }

    /**
     * Runs on two threads. [secondRunDone] opens when the second dispatched
     * block returns: the second writer has then either written or suspended on
     * the write lock, so no timing guess is needed.
     */
    private class CountingDispatcher(
        private val secondRunDone: CountDownLatch,
    ) : CoroutineDispatcher() {
        private val pool = Executors.newFixedThreadPool(WRITER_THREADS)
        private val dispatches = AtomicInteger()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            val index = dispatches.getAndIncrement()
            pool.execute {
                block.run()
                if (index == SECOND_DISPATCH) {
                    secondRunDone.countDown()
                }
            }
        }

        private companion object {
            const val WRITER_THREADS = 2
            const val SECOND_DISPATCH = 1
        }
    }

    private class MemoryStore(
        var stored: ProfileValues = emptyMap(),
        private val failure: AppError? = null,
        var writeFailure: AppError? = null,
    ) : ProfileValueStore {
        override fun read(): Outcome<ProfileValues> = failure?.let { Outcome.Failure(it) } ?: Outcome.Success(stored)

        override fun write(values: ProfileValues): Outcome<Unit> {
            (failure ?: writeFailure)?.let { return Outcome.Failure(it) }
            stored = values
            return Outcome.Success(Unit)
        }
    }
}
