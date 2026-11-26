package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.places.FavouriteShape
import nl.r1a.grassmaps.places.FavouritesSnapshot
import nl.r1a.grassmaps.places.favourite
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugins.favourites.layer.FavouriteLayerSettings
import nl.r1a.grassmaps.plugins.favourites.layer.MarkerShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FavouritesLayerSettingsStoreTest {
    @Test
    fun theDefaultsDrawIconsKeepTheStoreShapeAndHideNothing() {
        val settings = FavouritesLayerSettingsStore(file = null).current()

        assertEquals(FavouriteLayerSettings(), settings)
        assertTrue(settings.iconsPerPoint)
        assertEquals(null, settings.markerShape)
        assertFalse(settings.showNames)
        assertTrue(settings.hiddenGroups.isEmpty())
    }

    @Test
    fun allFourControlsPersistAcrossANewInstance() {
        val file = File.createTempFile("favourites-layer", ".json").also { it.deleteOnExit() }
        val first = FavouritesLayerSettingsStore(file)

        assertEquals(Outcome.Success(Unit), first.setIconsPerPoint(false))
        assertEquals(Outcome.Success(Unit), first.setMarkerShape(MarkerShape.Octagon))
        assertEquals(Outcome.Success(Unit), first.setShowNames(true))
        assertEquals(Outcome.Success(Unit), first.setGroupShown("Home", false))
        assertEquals(Outcome.Success(Unit), first.setGroupShown("Work", true))

        val second = FavouritesLayerSettingsStore(file).also { it.ensureLoaded() }.current()
        assertFalse(second.iconsPerPoint)
        assertEquals(MarkerShape.Octagon, second.markerShape)
        assertTrue(second.showNames)
        assertEquals(setOf("Home"), second.hiddenGroups)
    }

    @Test
    fun aCorruptFileReadsAsTheDefaults() {
        val file = File.createTempFile("favourites-layer", ".json").also { it.deleteOnExit() }
        file.writeText("{not json")

        val store = FavouritesLayerSettingsStore(file)
        store.ensureLoaded()

        assertEquals(FavouriteLayerSettings(), store.current())
    }

    @Test
    fun theConstructorReadsNothingAndTheLoadPublishesTheStoredControls() {
        val file = File.createTempFile("favourites-layer", ".json").also { it.deleteOnExit() }
        FavouritesLayerSettingsStore(file).setShowNames(true)
        var resolved = 0
        val store = FavouritesLayerSettingsStore { file.also { resolved += 1 } }

        assertEquals(0, resolved)
        assertEquals(FavouriteLayerSettings(), store.active.value)

        store.ensureLoaded()
        store.ensureLoaded()

        assertEquals(1, resolved)
        assertTrue(store.active.value.showNames)
    }

    @Test
    fun aChangeBeforeTheFirstReadKeepsTheOtherStoredControls() {
        val file = File.createTempFile("favourites-layer", ".json").also { it.deleteOnExit() }
        FavouritesLayerSettingsStore(file).setGroupShown("Home", false)

        val store = FavouritesLayerSettingsStore(file)
        assertEquals(Outcome.Success(Unit), store.setShowNames(true))

        val reopened = FavouritesLayerSettingsStore(file).also { it.ensureLoaded() }.current()
        assertTrue(reopened.showNames)
        assertEquals(setOf("Home"), reopened.hiddenGroups)
    }

    @Test
    fun theMappingCarriesTheChosenIconShapeAndName() {
        val snapshot =
            FavouritesSnapshot(
                favourites =
                    listOf(
                        favourite(49.84, 24.03, "Rynok", group = "Lviv", icon = "cafe", shape = FavouriteShape.Square),
                        favourite(49.85, 24.04, "Park", icon = "park"),
                    ),
            )
        val library = snapshot.toFavouriteLayerLibrary()

        assertEquals(2, library.markers.size)
        assertEquals("Rynok", library.markers[0].name)
        assertEquals("cafe", library.markers[0].icon)
        assertEquals(MarkerShape.Square, library.markers[0].shape)
        assertEquals("Lviv", library.markers[0].group)
        assertEquals(49.84, library.markers[0].position.latitude, 0.0001)
    }
}
