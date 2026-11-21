package nl.r1a.grassmaps.plugin.api.pmtiles

/** The tile id of the PMTiles v3 Hilbert curve, shared by the writer and the reader. */
object PmtilesTileId {
    /**
     * The number of tiles below [zoom], plus the position of (x, y) on that
     * zoom's Hilbert curve.
     */
    fun of(
        zoom: Int,
        x: Long,
        y: Long,
    ): Long {
        var below = 0L
        for (level in 0 until zoom) {
            below += (1L shl level) * (1L shl level)
        }
        if (zoom == 0) {
            return below
        }
        var position = 0L
        var remainingX = x
        var remainingY = y
        var step = 1L shl (zoom - 1)
        while (step > 0L) {
            val rx = if (remainingX and step > 0L) 1L else 0L
            val ry = if (remainingY and step > 0L) 1L else 0L
            position += step * step * ((3L * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) {
                    remainingX = step - 1L - remainingX
                    remainingY = step - 1L - remainingY
                }
                val swap = remainingX
                remainingX = remainingY
                remainingY = swap
            }
            step = step shr 1
        }
        return below + position
    }
}
