package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ProfileId
import nl.r1a.grassmap.plugin.api.service.ProfileValueStore
import nl.r1a.grassmap.plugin.api.service.ProfileValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutStoreTest {
    private val walk = ProfileId("walk")
    private val bike = ProfileId("bike")

    @Test
    fun aProfileWithNoValueShowsTheDefault() {
        val store = WidgetLayoutStore(FakeStore())
        store.load()
        assertEquals(WidgetLayout.DEFAULT, store.layoutOf(walk))
    }

    @Test
    fun aSavedLayoutIsStoredAsCommaSeparatedKeysUnderTheProfile() {
        val backing = FakeStore()
        val store = WidgetLayoutStore(backing)
        store.save(walk, WidgetLayout(listOf(GridWidget.Coordinates, GridWidget.SpeedMax)))
        assertEquals(mapOf(walk to mapOf("widgets" to "coordinates,speed_max")), backing.values)
    }

    @Test
    fun savingOneProfileKeepsTheOthers() {
        val backing = FakeStore(mapOf(bike to mapOf("widgets" to "altitude", "other" to "kept")))
        val store = WidgetLayoutStore(backing)
        store.save(walk, WidgetLayout(listOf(GridWidget.SpeedCurrent)))
        assertEquals(mapOf("widgets" to "altitude", "other" to "kept"), backing.values[bike])
        assertEquals("speed_current", backing.values[walk]?.get("widgets"))
    }

    @Test
    fun anEmptyLayoutIsARealValue() {
        val store = WidgetLayoutStore(FakeStore())
        store.save(walk, WidgetLayout(emptyList()))
        val reopened = WidgetLayoutStore(FakeStore(mapOf(walk to mapOf("widgets" to ""))))
        reopened.load()
        assertEquals(WidgetLayout(emptyList()), reopened.layoutOf(walk))
        assertEquals(WidgetLayout(emptyList()), store.layoutOf(walk))
    }

    @Test
    fun unknownKeysAreNotShownAndDuplicatesKeptOnce() {
        val store = WidgetLayoutStore(FakeStore(mapOf(walk to mapOf("widgets" to "altitude,battery,altitude,speed_max"))))
        store.load()
        assertEquals(listOf(GridWidget.Altitude, GridWidget.SpeedMax), store.layoutOf(walk).widgets)
    }

    @Test
    fun aNewerSchemaFailsTheLoadAndEveryWriteAndKeepsTheDefault() {
        val backing = FakeStore(failure = AppError.NewerSchema(found = 2, supported = 1))
        val store = WidgetLayoutStore(backing)
        assertTrue(store.load() is Outcome.Failure)
        assertEquals(WidgetLayout.DEFAULT, store.layoutOf(walk))
        assertTrue(store.save(walk, WidgetLayout(emptyList())) is Outcome.Failure)
        assertEquals(WidgetLayout.DEFAULT, store.layoutOf(walk))
        assertEquals(emptyMap<ProfileId, Map<String, String>>(), backing.values)
    }

    @Test
    fun aSaveAfterAFailedLoadRetriesTheLoadAndKeepsTheOtherProfiles() {
        val backing = FakeStore(mapOf(bike to mapOf("widgets" to "altitude")), failure = AppError.PermissionDenied)
        val store = WidgetLayoutStore(backing)
        assertTrue(store.load() is Outcome.Failure)
        backing.failure = null
        assertTrue(store.save(walk, WidgetLayout(listOf(GridWidget.SpeedMax))) is Outcome.Success)
        assertEquals("altitude", backing.values[bike]?.get("widgets"))
        assertEquals("speed_max", backing.values[walk]?.get("widgets"))
    }

    @Test
    fun aSaveWhileTheLoadKeepsFailingWritesNothing() {
        val stored = mapOf(bike to mapOf("widgets" to "altitude"))
        val backing = FakeStore(stored, failure = AppError.PermissionDenied, failsOnlyReads = true)
        val store = WidgetLayoutStore(backing)
        assertTrue(store.load() is Outcome.Failure)
        assertTrue(store.save(walk, WidgetLayout(listOf(GridWidget.SpeedMax))) is Outcome.Failure)
        assertEquals(stored, backing.values)
        assertEquals(0, backing.writes)
    }

    @Test
    fun savingKeepsAKeyThisBuildDoesNotKnowAtItsPosition() {
        val backing = FakeStore(mapOf(walk to mapOf("widgets" to "altitude,battery,speed_max,battery")))
        val store = WidgetLayoutStore(backing)
        store.load()
        store.save(walk, store.layoutOf(walk).move(GridWidget.SpeedMax, -1))
        assertEquals("speed_max,battery,altitude", backing.values[walk]?.get("widgets"))
        assertEquals(listOf(GridWidget.SpeedMax, GridWidget.Altitude), store.layoutOf(walk).widgets)
    }

    private class FakeStore(
        var values: ProfileValues = emptyMap(),
        var failure: AppError? = null,
        private val failsOnlyReads: Boolean = false,
    ) : ProfileValueStore {
        var writes = 0

        override fun read(): Outcome<ProfileValues> = failure?.let { Outcome.Failure(it) } ?: Outcome.Success(values)

        override fun write(values: ProfileValues): Outcome<Unit> {
            writes += 1
            failure?.takeUnless { failsOnlyReads }?.let { return Outcome.Failure(it) }
            this.values = values
            return Outcome.Success(Unit)
        }
    }
}
