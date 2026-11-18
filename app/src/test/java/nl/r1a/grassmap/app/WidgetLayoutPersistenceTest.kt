package nl.r1a.grassmap.app

import nl.r1a.grassmap.core.services.ProfileValueFile
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ProfileId
import nl.r1a.grassmap.ui.shell.GridWidget
import nl.r1a.grassmap.ui.shell.WidgetLayout
import nl.r1a.grassmap.ui.shell.WidgetLayoutStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A restart is a new store on the same file: the layouts must come back per profile. */
class WidgetLayoutPersistenceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val walk = ProfileId("walk")
    private val bike = ProfileId("bike")

    @Test
    fun editedLayoutsOfTwoProfilesSurviveARestartApart() {
        val file = File(folder.root, UserDataLayout.WIDGET_LAYOUT_PATH)
        val first = openStore(file)
        first.load()
        val walkLayout = WidgetLayout.DEFAULT.add(GridWidget.Coordinates).remove(GridWidget.Altitude)
        val bikeLayout = WidgetLayout(emptyList()).add(GridWidget.SpeedMax).add(GridWidget.SpeedCurrent).move(GridWidget.SpeedCurrent, -1)
        assertTrue(first.save(walk, walkLayout) is Outcome.Success)
        assertTrue(first.save(bike, bikeLayout) is Outcome.Success)

        val restarted = openStore(file)
        assertTrue(restarted.load() is Outcome.Success)

        assertEquals(walkLayout, restarted.layoutOf(walk))
        assertEquals(bikeLayout, restarted.layoutOf(bike))
        assertEquals(WidgetLayout.DEFAULT, restarted.layoutOf(ProfileId("drive")))
    }

    @Test
    fun aRemovedWidgetStaysRemovedAfterARestart() {
        val file = File(folder.root, UserDataLayout.WIDGET_LAYOUT_PATH)
        val first = openStore(file)
        first.save(walk, WidgetLayout.DEFAULT.remove(GridWidget.SelectedPoint))

        val restarted = openStore(file)
        restarted.load()

        assertEquals(WidgetLayout.DEFAULT.remove(GridWidget.SelectedPoint), restarted.layoutOf(walk))
    }

    @Test
    fun aStoreWithoutAFileShowsTheDefault() {
        val store = openStore(File(folder.root, UserDataLayout.WIDGET_LAYOUT_PATH))
        assertTrue(store.load() is Outcome.Success)
        assertEquals(WidgetLayout.DEFAULT, store.layoutOf(walk))
    }

    private fun openStore(file: File): WidgetLayoutStore =
        WidgetLayoutStore(ProfileValueFile(file, WidgetLayoutStore.SCHEMA_VERSION, WidgetLayoutStore.ERROR_ID))
}
