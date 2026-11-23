package nl.r1a.grassmaps.plugins.downloads.ui

import nl.r1a.grassmaps.plugin.api.model.CatalogPartKind
import nl.r1a.grassmaps.plugin.api.model.DownloadPartRow
import nl.r1a.grassmaps.plugin.api.model.DownloadRowStatus
import nl.r1a.grassmaps.plugin.api.model.FirstRunOffer
import nl.r1a.grassmaps.plugin.api.model.FirstRunOfferKind
import nl.r1a.grassmaps.plugin.ui.api.LightShellColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Downloads legend and the first-run part pills (T-0612).
 *
 * A JVM test cannot render the private mini-map composable, so this pins the
 * derivation the composable reads. That is weaker than a rendered assertion:
 * it proves the labels and colours the screen asks for, not that they land on
 * the right pixels. The colour check compares each legend paint with the exact
 * value the map's own [fillFor] paints, so the legend cannot drift from the map.
 */
class DownloadsLegendAndPillsTest {
    @Test
    fun legendNamesEveryMapStateWithItsPaint() {
        assertEquals(
            listOf(
                LEGEND_ON_THIS_PHONE to MiniMapPaint.INSTALLED,
                LEGEND_UPDATING to MiniMapPaint.PENDING,
                LEGEND_UPDATE_READY to MiniMapPaint.PENDING,
                LEGEND_NEAR_YOU to MiniMapPaint.SELECTED,
                LEGEND_AVAILABLE to MiniMapPaint.AVAILABLE,
                LEGEND_YOU to MiniMapPaint.SELECTED,
            ),
            miniMapLegend.map { it.labelKey to it.paint },
        )
    }

    /**
     * The mini-map has no separate fix marker: its region fills and the one
     * selected outline are the only colours it paints. Both position labels
     * take that outline, and updating and update ready take the one pending
     * tint. The legend uses those exact values rather than the prototype's.
     */
    @Test
    fun legendColoursAreTheOnesTheMapPaints() {
        val colors = LightShellColors

        assertEquals(colors.accent.copy(alpha = 0.55f), fillFor(DownloadRowStatus.INSTALLED, colors))
        assertEquals(colors.accent.copy(alpha = 0.3f), fillFor(DownloadRowStatus.DOWNLOADING, colors))
        assertEquals(colors.accent.copy(alpha = 0.3f), fillFor(DownloadRowStatus.UPDATE_AVAILABLE, colors))
        assertEquals(colors.line, fillFor(DownloadRowStatus.AVAILABLE, colors))
        assertEquals(colors.danger.copy(alpha = 0.3f), fillFor(DownloadRowStatus.FAILED, colors))

        assertEquals(MiniMapPaint.INSTALLED.color(colors), fillFor(DownloadRowStatus.INSTALLED, colors))
        assertEquals(MiniMapPaint.PENDING.color(colors), fillFor(DownloadRowStatus.DOWNLOADING, colors))
        assertEquals(MiniMapPaint.PENDING.color(colors), fillFor(DownloadRowStatus.UPDATE_AVAILABLE, colors))
        assertEquals(MiniMapPaint.AVAILABLE.color(colors), fillFor(DownloadRowStatus.AVAILABLE, colors))
        assertEquals(MiniMapPaint.SELECTED.color(colors), colors.ink)
    }

    @Test
    fun theSixLegendLabelsAreTranslatedInBothBundles() {
        val keys = miniMapLegend.map { it.labelKey }.toSet()
        assertEquals(6, keys.size)

        for (path in listOf(ENGLISH, UKRAINIAN)) {
            val bundle = resources(path)
            for (key in keys) {
                assertTrue("$path: $key is missing", key in bundle)
                assertTrue("$path: $key is blank", bundle.getValue(key).isNotBlank())
            }
        }

        val english = resources(ENGLISH)
        assertEquals("On this phone", english.getValue(LEGEND_ON_THIS_PHONE))
        assertEquals("Updating", english.getValue(LEGEND_UPDATING))
        assertEquals("Update ready", english.getValue(LEGEND_UPDATE_READY))
        assertEquals("Near you", english.getValue(LEGEND_NEAR_YOU))
        assertEquals("Available", english.getValue(LEGEND_AVAILABLE))
        assertEquals("You", english.getValue(LEGEND_YOU))

        val ukrainian = resources(UKRAINIAN)
        assertEquals("На цьому телефоні", ukrainian.getValue(LEGEND_ON_THIS_PHONE))
        assertEquals("Оновлюється", ukrainian.getValue(LEGEND_UPDATING))
        assertEquals("Готове до оновлення", ukrainian.getValue(LEGEND_UPDATE_READY))
        assertEquals("Поруч із вами", ukrainian.getValue(LEGEND_NEAR_YOU))
        assertEquals("Доступні", ukrainian.getValue(LEGEND_AVAILABLE))
        assertEquals("Ви", ukrainian.getValue(LEGEND_YOU))
    }

    @Test
    fun regionOfferPillsComeFromItsPartsInDesignOrder() {
        val offer = regionOffer(CatalogPartKind.CONTOURS, CatalogPartKind.MAP, CatalogPartKind.ELEVATION, CatalogPartKind.SEARCH)

        assertEquals(
            listOf(CatalogPartKind.MAP, CatalogPartKind.SEARCH, CatalogPartKind.ELEVATION, CatalogPartKind.CONTOURS),
            DownloadsScreen.partPills(offer),
        )
    }

    @Test
    fun pillDerivationUsesOfferPartsAndSkipsKindsItDoesNotName() {
        val addon = part(CatalogPartKind.ELEVATION_30M)
        val other = part(CatalogPartKind.OTHER)
        val offer = regionOffer(CatalogPartKind.ROUTING, CatalogPartKind.MAP, addon.kind, other.kind)

        assertEquals(listOf(CatalogPartKind.MAP, CatalogPartKind.ROUTING), DownloadsScreen.partPills(offer))
    }

    @Test
    fun firstRunPartsHaveNoOwnAction() {
        val offer = regionOffer(CatalogPartKind.MAP, CatalogPartKind.SEARCH)

        assertTrue(offer.parts.all { it.downloadId == null })
        assertTrue(offer.parts.all { it.transfer == null })
        assertEquals(listOf(CatalogPartKind.MAP, CatalogPartKind.SEARCH), DownloadsScreen.partPills(offer))
    }

    @Test
    fun everyFirstRunOfferKindDerivesFromItsOwnParts() {
        val world =
            FirstRunOffer(kind = FirstRunOfferKind.WORLD, region = "world", bytes = 1, parts = listOf(part(CatalogPartKind.MAP)))
        val routing =
            FirstRunOffer(kind = FirstRunOfferKind.ROUTING, region = "ua-46", bytes = 1, parts = listOf(part(CatalogPartKind.ROUTING)))

        assertEquals(listOf(CatalogPartKind.MAP), DownloadsScreen.partPills(world))
        assertEquals(listOf(CatalogPartKind.ROUTING), DownloadsScreen.partPills(routing))
        assertTrue(DownloadsScreen.partPills(FirstRunOffer(kind = FirstRunOfferKind.REGION, region = "ua-46", bytes = 1)).isEmpty())
    }

    private fun regionOffer(vararg kinds: CatalogPartKind): FirstRunOffer =
        FirstRunOffer(
            kind = FirstRunOfferKind.REGION,
            region = "ua-46",
            bytes = 1,
            // Deliberately unsorted: the pills follow the design order, not the offer's.
            parts = kinds.map(::part),
        )

    private fun part(kind: CatalogPartKind) = DownloadPartRow(kind = kind, version = "v", bytes = 1L, installed = false)

    private fun resources(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
    }
}
