package nl.r1a.grassmap.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The debug fixture's contour archive is a copy of the committed test-region
 * component, the same way `fixture-mini-map.pmtiles` copies the map. A changed
 * fixture must not leave the APK drawing stale contours.
 */
class FixtureContoursAssetTest {
    private val asset = File("src/main/assets/fixture-mini-contours.pmtiles")
    private val component = File("../test-data/regions/fixture-mini/2026-11-03-1/contours.pmtiles")

    @Test
    fun thePackagedArchiveIsTheCommittedComponentByteForByte() {
        assertTrue("the packaged contour fixture is missing", asset.isFile)
        assertTrue("the committed contour component is missing", component.isFile)
        assertArrayEquals(component.readBytes(), asset.readBytes())
    }
}
