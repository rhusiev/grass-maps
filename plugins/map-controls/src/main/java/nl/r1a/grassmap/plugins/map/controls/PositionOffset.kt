package nl.r1a.grassmap.plugins.map.controls

import nl.r1a.grassmap.plugin.api.model.ScreenOffset

/**
 * A position-marker offset as a fraction of the viewport (T-0106).
 *
 * The offset is screen space, not geographic: `y = 0.3` places the marker a
 * third of the viewport below the centre, which is the useful place for a
 * navigation view. The fraction is the typed seam; the concrete screen offset
 * needs the viewport size the map currently has.
 */
data class PositionOffsetFraction(
    val x: Float = 0f,
    val y: Float = 0f,
) {
    /** Resolves the fraction against a viewport size in dp. */
    fun resolve(
        viewportWidthDp: Float,
        viewportHeightDp: Float,
    ): ScreenOffset =
        ScreenOffset(
            x = normalized(x) * viewportWidthDp,
            y = normalized(y) * viewportHeightDp,
        )

    private fun normalized(value: Float): Float = if (value.isFinite()) value.coerceIn(MIN_FRACTION, MAX_FRACTION) else 0f

    companion object {
        /** A marker with no offset from the centre. */
        val CENTER: PositionOffsetFraction = PositionOffsetFraction()

        private const val MIN_FRACTION = -1f
        private const val MAX_FRACTION = 1f
    }
}

/**
 * Supplies the marker offset for one profile. Profiles arrive with T-0601;
 * until then the app uses [CENTERED] and the plugin setting carries the default.
 */
fun interface PositionOffsetProvider {
    fun offsetForProfile(profileId: String): PositionOffsetFraction

    companion object {
        val CENTERED: PositionOffsetProvider = PositionOffsetProvider { PositionOffsetFraction.CENTER }
    }
}
