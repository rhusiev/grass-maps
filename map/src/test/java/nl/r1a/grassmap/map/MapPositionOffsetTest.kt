package nl.r1a.grassmap.map

import androidx.compose.ui.unit.LayoutDirection
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position

class MapPositionOffsetTest {
    @Test
    fun aThirtyPercentOffsetPutsTheMarkerAtEightyPercentOfTheHeight() {
        val padding = positionPadding(ScreenOffset(x = 0f, y = 300f))

        assertEquals(600f, padding.calculateTopPadding().value, 0.001f)
        assertEquals(800f, puckScreenYDp(viewportHeightDp = 1000f, padding = padding), 0.001f)
    }

    @Test
    fun aNegativeOffsetUsesTheOppositeEdge() {
        val padding = positionPadding(ScreenOffset(x = -100f, y = -200f))

        assertEquals(200f, padding.calculateRightPadding(LayoutDirection.Ltr).value, 0.001f)
        assertEquals(400f, padding.calculateBottomPadding().value, 0.001f)
        assertEquals(300f, puckScreenYDp(viewportHeightDp = 1000f, padding = padding), 0.001f)
    }

    @Test
    fun aCentredOffsetAddsNoPadding() {
        val padding = positionPadding(ScreenOffset(x = 0f, y = 0f))

        assertEquals(0f, padding.calculateTopPadding().value, 0.001f)
        assertEquals(500f, puckScreenYDp(viewportHeightDp = 1000f, padding = padding), 0.001f)
    }

    @Test
    fun aCameraMoveKeepsTheThirtyPercentOffset() {
        val padding = positionPadding(ScreenOffset(x = 0f, y = 300f))
        val before =
            CameraPosition(
                center = Position(latitude = 49.84, longitude = 24.03),
                zoom = 12.0,
                bearing = 0.0,
                pitch = 0.0,
                padding = padding,
            )

        val after = before.apply(CameraUpdate(center = GeoPoint(latitude = 49.85, longitude = 24.04)))

        assertEquals(600f, after.padding.calculateTopPadding().value, 0.001f)
        assertEquals(800f, puckScreenYDp(viewportHeightDp = 1000f, padding = after.padding), 0.001f)
    }
}
