package nl.r1a.grassmap.app.devcheck

import android.graphics.BitmapFactory

private const val SAMPLE_STRIDE_PX = 8
private const val AREA_LEFT_PCT = 5
private const val AREA_RIGHT_PCT = 75
private const val AREA_TOP_PCT = 15
private const val AREA_BOTTOM_PCT = 60
private const val PERCENT = 100
private const val BLANK_DOMINANT_SHARE = 0.995
private const val BLANK_TRANSPARENT_SHARE = 0.5
private const val ALPHA_MASK = 0xFF000000.toInt()

/**
 * What a check of the map area found: [dominantShare] is the share of the most
 * common sampled color, [transparentShare] the share of fully transparent
 * pixels, and [isBlank] the verdict.
 */
internal data class MapAreaCheck(
    val dominantShare: Double,
    val transparentShare: Double,
    val isBlank: Boolean,
)

/**
 * Looks at the part of the window that the map fills when no panel is open and
 * the controls sit outside it.
 *
 * The area is blank when most of it is transparent: a map surface that is not
 * composited into the capture leaves its area empty, while a drawn map is
 * opaque everywhere. The credit line can still be drawn over that empty area,
 * so a majority is the test, not the whole box. One opaque color over nearly
 * the whole box is blank too, which is what a flat fill gives.
 */
internal object MapPixels {
    fun check(
        width: Int,
        height: Int,
        pixelAt: (Int, Int) -> Int,
    ): MapAreaCheck {
        val counts = mutableMapOf<Int, Int>()
        var transparent = 0
        var total = 0
        for (y in (height * AREA_TOP_PCT / PERCENT) until (height * AREA_BOTTOM_PCT / PERCENT) step SAMPLE_STRIDE_PX) {
            for (x in (width * AREA_LEFT_PCT / PERCENT) until (width * AREA_RIGHT_PCT / PERCENT) step SAMPLE_STRIDE_PX) {
                val color = pixelAt(x, y)
                if (color and ALPHA_MASK == 0) {
                    transparent++
                }
                counts.merge(color, 1, Int::plus)
                total++
            }
        }
        val dominantShare = if (total == 0) 1.0 else (counts.values.maxOrNull() ?: total).toDouble() / total
        val transparentShare = if (total == 0) 1.0 else transparent.toDouble() / total
        return MapAreaCheck(
            dominantShare = dominantShare,
            transparentShare = transparentShare,
            isBlank = transparentShare >= BLANK_TRANSPARENT_SHARE || dominantShare >= BLANK_DOMINANT_SHARE,
        )
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
