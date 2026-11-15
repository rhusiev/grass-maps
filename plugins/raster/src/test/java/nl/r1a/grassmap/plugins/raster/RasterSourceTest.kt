package nl.r1a.grassmap.plugins.raster

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The OSM preset's policy, the URL template rules and the offline refusal. */
class RasterSourceTest {
    @Test
    fun theOsmPresetIsViewOnlyAndCarriesItsAttribution() {
        val source = RasterPresets.openStreetMap

        assertEquals("https://tile.openstreetmap.org/{z}/{x}/{y}.png", source.tileUrlTemplate)
        assertEquals(19, source.maxZoom)
        assertEquals(256, source.tileSize)
        assertEquals(OfflineTileDownload.REFUSED_BY_POLICY, source.offlineDownload)
        assertTrue(source.attributionHtml, source.attributionHtml.contains("OpenStreetMap"))
        assertTrue(source.attributionHtml, source.attributionHtml.contains("openstreetmap.org/copyright"))
    }

    @Test
    fun theOsmPresetRefusesOfflineDownload() {
        val outcome = RasterOfflineDownload.prepare(RasterPresets.openStreetMap)

        assertEquals(Outcome.Failure(AppError.Unsupported), outcome)
    }

    @Test
    fun aCustomSourceMayBeDownloaded() {
        assertEquals(Outcome.Success(Unit), RasterOfflineDownload.prepare(custom("https://t.example/{z}/{x}/{y}.png")))
    }

    @Test
    fun theOsmHostStaysViewOnlyEvenAsACustomSource() {
        val source = custom("https://tile.openstreetmap.org/{z}/{x}/{y}.png")

        assertEquals(OfflineTileDownload.REFUSED_BY_POLICY, source.offlineDownload)
        assertEquals(Outcome.Failure(AppError.Unsupported), RasterOfflineDownload.prepare(source))
    }

    @Test
    fun aTileOutsideTheSourceIsNotBuilt() {
        val source = RasterPresets.openStreetMap

        assertNull(source.tileUrl(z = 20, x = 0, y = 0))
        assertNull(source.tileUrl(z = 4, x = 16, y = 0))
        assertNull(source.tileUrl(z = 4, x = 0, y = 16))
        assertEquals(
            "https://tile.openstreetmap.org/4/10/6.png",
            source.tileUrl(z = 4, x = 10, y = 6),
        )
    }

    @Test
    fun theTemplateMatcherAcceptsOnlyItsOwnUrls() {
        val source = RasterPresets.openStreetMap

        assertTrue(source.allows("https://tile.openstreetmap.org/8/10/6.png"))
        assertTrue(source.allows("https://tile.openstreetmap.org/18/262144/6.png"))
        assertFalse(source.allows("https://tile.openstreetmap.org/8/10/6.jpg"))
        assertFalse(source.allows("https://example.org/8/10/6.png"))
        assertFalse(source.allows("https://tile.openstreetmap.org/8/10/6.png?extra=1"))
        assertFalse(source.allows("https://tile.openstreetmap.org/8/10.png"))
    }

    @Test
    fun aCustomTemplateMustCarryTheThreePlaceholders() {
        assertEquals(AppError.InvalidInput, failure("https://t.example/{z}/{x}.png"))
        assertEquals(AppError.InvalidInput, failure("https://t.example/plain.png"))
        assertEquals(AppError.InvalidInput, failure("https://t.example/{z}/{x}/{y}/{q}.png"))
        assertEquals(AppError.InvalidInput, failure("ftp://t.example/{z}/{x}/{y}.png"))
    }

    @Test
    fun cleartextIsAllowedOnlyForLoopback() {
        assertTrue(custom("http://127.0.0.1:8080/{z}/{x}/{y}.png").tileUrlTemplate.isNotBlank())
        assertTrue(custom("http://localhost:8080/{z}/{x}/{y}.png").tileUrlTemplate.isNotBlank())
        assertEquals(AppError.InvalidInput, failure("http://t.example/{z}/{x}/{y}.png"))
        assertTrue(custom("https://t.example/{z}/{x}/{y}.png").tileUrlTemplate.isNotBlank())
    }

    private fun custom(template: String): RasterSource =
        when (val outcome = RasterSources.custom(template)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("template refused: ${outcome.error}")
        }

    private fun failure(template: String): AppError? = (RasterSources.custom(template) as? Outcome.Failure)?.error
}
