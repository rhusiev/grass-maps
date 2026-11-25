package nl.r1a.grassmaps.app

import androidx.compose.ui.unit.dp
import nl.r1a.grassmaps.core.services.MapButtonLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Final blind review P2-3: frequent rail controls sit at the top by default.
 * A bottom default was evaluated instead of forced. With the five default
 * controls the bottom-end rail can reach the centred Layers button, and an
 * on-device clearance proof is not available here, so the default stays at the
 * top end. This is a recorded deviation, not a broken control.
 */
class MapRailDefaultTest {
    private val screenWidth = 411.dp

    @Test
    fun theDefaultRailStaysTopEndBecauseABottomRailIsNotProvenClear() {
        assertFalse("the rail default changed without a clearance proof", MapButtonLayout.DEFAULT.arc)

        val railWidth = 184.dp
        val railLeft = screenWidth - BAR_PADDING_DP.dp - railWidth
        val layersRight = screenWidth / 2 + LAYERS_HALF_WIDTH_DP.dp
        val layersTop = mapBottomInsets(arc = false).layersDp + LAYERS_HEIGHT_DP

        assertTrue(
            "a ${railWidth.value} dp rail starts at ${railLeft.value} dp and reaches Layers at ${layersRight.value} dp",
            railLeft < layersRight,
        )
        assertTrue(
            "a rail at $ARC_BOTTOM_CLEARANCE_DP dp crosses Layers ending at $layersTop dp",
            ARC_BOTTOM_CLEARANCE_DP < layersTop,
        )
    }

    private companion object {
        // The Moto g32 screen width and the measured Layers button footprint.
        const val LAYERS_HALF_WIDTH_DP = 32
        const val LAYERS_HEIGHT_DP = 48
    }
}
