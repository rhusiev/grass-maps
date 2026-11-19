package nl.r1a.grassmap.app.devcheck

import android.graphics.BitmapFactory

private const val SAMPLE_STRIDE_PX = 8
private const val AREA_LEFT_PCT = 5
private const val AREA_RIGHT_PCT = 75
private const val AREA_TOP_PCT = 15
private const val AREA_BOTTOM_PCT = 60
private const val PERCENT = 100
private const val BLANK_DOMINANT_SHARE = 0.995

/** What a check of the map area found: [dominantShare] is the share of the most common sampled color. */
internal data class MapAreaCheck(
    val dominantShare: Double,
    val isBlank: Boolean,
)

/**
 * Looks at the part of the window that the map fills when no panel is open and
 * the controls sit outside it. The area is blank when one color covers
 * [BLANK_DOMINANT_SHARE] or more of the sampled pixels, which is what a missing
 * map surface (transparent, black or white) gives.
 */
internal object MapPixels {
    fun check(
        width: Int,
        height: Int,
        pixelAt: (Int, Int) -> Int,
    ): MapAreaCheck {
        val counts = mutableMapOf<Int, Int>()
        var total = 0
        for (y in (height * AREA_TOP_PCT / PERCENT) until (height * AREA_BOTTOM_PCT / PERCENT) step SAMPLE_STRIDE_PX) {
            for (x in (width * AREA_LEFT_PCT / PERCENT) until (width * AREA_RIGHT_PCT / PERCENT) step SAMPLE_STRIDE_PX) {
                counts.merge(pixelAt(x, y), 1, Int::plus)
                total++
            }
        }
        val share = if (total == 0) 1.0 else (counts.values.maxOrNull() ?: total).toDouble() / total
        return MapAreaCheck(share, share >= BLANK_DOMINANT_SHARE)
    }

    /** Decodes [png] and checks its map area, or returns null when it does not decode. */
    fun checkPng(png: ByteArray): MapAreaCheck? {
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        return try {
            check(bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) }
        } finally {
            bitmap.recycle()
        }
    }
}
