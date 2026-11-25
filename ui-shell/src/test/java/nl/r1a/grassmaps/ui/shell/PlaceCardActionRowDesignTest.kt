package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.PlaceCard
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Final design-aware review 4 P1 2: the place card drew Save, Share and Open as
 * stacked full-width text rows, and its pinned Route action had no time even
 * though the subtitle carries a straight-line walking estimate. The canon puts
 * the three actions in one horizontal row of icon tiles and times the Route
 * label from that same estimate. The composable arrangement is pinned from
 * source because a JVM test cannot render it; the resolved text is pinned
 * through the real catalogues.
 */
class PlaceCardActionRowDesignTest {
    private val host = File(HOST_FILE).readText()
    private val content = File(CONTENT_FILE).readText()

    @Test
    fun theActionsAreOneHorizontalRowInsteadOfStackedRows() {
        val tiles = host.substringAfter("private fun Tiles(").substringBefore("private fun Tile(")
        assertTrue("the action row is gone", tiles.isNotBlank())
        assertTrue("the actions must share one Row", tiles.contains("Row("))
        assertTrue("each tile must take an equal share of the row", tiles.contains("Modifier.weight(1f)"))

        val tile = host.substringAfter("private fun Tile(").substringBefore("private fun Line(")
        assertFalse("a tile still fills the sheet width", tile.contains("fillMaxWidth()"))
        assertTrue("a tile is still a clickable target", tile.contains("role = Role.Button"))
        assertTrue("the tile lost its icon", tile.contains("ShellIcon("))
        assertTrue("the tile lost its visible label", tile.contains("BasicText("))
    }

    @Test
    fun everyTileIsA48DpTarget() {
        assertEquals("48", Regex("""const val TILE_TARGET_DP = (\d+)""").find(host)?.groupValues?.get(1))
        val tile = host.substringAfter("private fun Tile(").substringBefore("private fun Line(")
        assertTrue("the tile lost its minimum height", tile.contains(".heightIn(min = TILE_TARGET_DP.dp)"))
    }

    @Test
    fun theThreeTileLabelsExistInBothLanguages() {
        val english = TestStringCatalog.read(ENGLISH).resolver()
        val ukrainian = TestStringCatalog.read(UKRAINIAN).resolver()

        assertEquals("Save", english.resolve("place_card_save", emptyList()))
        assertEquals("Share", english.resolve("place_card_share", emptyList()))
        assertEquals("Open", english.resolve("place_card_open_action", emptyList()))
        assertEquals("Зберегти", ukrainian.resolve("place_card_save", emptyList()))
        assertEquals("Поділитися", ukrainian.resolve("place_card_share", emptyList()))
        assertEquals("Відкрити", ukrainian.resolve("place_card_open_action", emptyList()))
    }

    @Test
    fun theRouteLabelUsesTheStraightLineWalkEstimateInBothLanguages() {
        val card = PlaceCard(name = "cafe", position = GeoPoint(0.01, 0.0))
        val origin = GeoPoint(0.0, 0.0)

        val english = strings(ENGLISH, ENGLISH_APP)
        val englishSheet = placeCardSheet(placeCardContent(card, english), english, origin, UnitSystem.Metric, Locale.US)
        assertEquals("Route · estimated 15 min on foot", englishSheet.routeLabel)

        val ukrainian = strings(UKRAINIAN, UKRAINIAN_APP)
        val ukrainianSheet =
            placeCardSheet(placeCardContent(card, ukrainian), ukrainian, origin, UnitSystem.Metric, Locale.US)
        assertEquals("Маршрут · орієнтовно 15 хв пішки", ukrainianSheet.routeLabel)
    }

    @Test
    fun withoutAPositionTheRouteLabelStaysUntimed() {
        val english = strings(ENGLISH, ENGLISH_APP)
        val sheet = placeCardSheet(placeCardContent(PlaceCard(name = "cafe"), english), english)

        assertEquals("Route", sheet.routeLabel)
    }

    private fun strings(
        path: String,
        appPath: String,
    ): Strings = TestStringCatalog.read(path).merge(TestStringCatalog.read(appPath)).resolver()

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/PlaceCardHost.kt"
        const val CONTENT_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/PlaceCardContent.kt"
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
        const val ENGLISH_APP = "../app/src/main/res/values/strings.xml"
        const val UKRAINIAN_APP = "../app/src/main/res/values-uk/strings.xml"
    }
}
