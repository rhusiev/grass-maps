package nl.r1a.grassmap.plugins.valhalla

import nl.r1a.grassmap.plugin.api.model.GeoPoint

/**
 * Google encoded polyline with the six decimal places Valhalla uses for its
 * shapes. Valhalla both returns and accepts this form in
 * `linear_cost_factors`.
 */
object Polyline6 {
    private const val SCALE = 1_000_000.0
    private const val CHUNK_BITS = 5
    private const val CHUNK_MASK = 0x1f
    private const val CONTINUATION_BIT = 0x20
    private const val ENCODING_OFFSET = 63

    fun encode(points: List<GeoPoint>): String =
        buildString {
            var previousLatitude = 0L
            var previousLongitude = 0L
            for (point in points) {
                val latitude = Math.round(point.latitude * SCALE)
                val longitude = Math.round(point.longitude * SCALE)
                append(encodeValue(latitude - previousLatitude))
                append(encodeValue(longitude - previousLongitude))
                previousLatitude = latitude
                previousLongitude = longitude
            }
        }

    fun decode(encoded: String): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        var index = 0
        var latitude = 0L
        var longitude = 0L
        while (index < encoded.length) {
            val latitudeDelta = decodeValue(encoded, index) ?: return points
            index = latitudeDelta.second
            val longitudeDelta = decodeValue(encoded, index) ?: return points
            index = longitudeDelta.second
            latitude += latitudeDelta.first
            longitude += longitudeDelta.first
            points +=
                GeoPoint(
                    latitude = latitude / SCALE,
                    longitude = longitude / SCALE,
                )
        }
        return points
    }

    private fun encodeValue(value: Long): String {
        var remaining = if (value < 0) (value shl 1).inv() else (value shl 1)
        return buildString {
            while (remaining >= CONTINUATION_BIT) {
                val chunk = ((remaining and CHUNK_MASK.toLong()) or CONTINUATION_BIT.toLong()).toInt()
                append((chunk + ENCODING_OFFSET).toChar())
                remaining = remaining shr CHUNK_BITS
            }
            append((remaining.toInt() + ENCODING_OFFSET).toChar())
        }
    }

    /** The decoded value and the index after it, or null at a bad chunk. */
    private fun decodeValue(
        encoded: String,
        start: Int,
    ): Pair<Long, Int>? {
        var index = start
        var shift = 0
        var result = 0L
        while (true) {
            if (index >= encoded.length) return null
            val chunk = encoded[index].code - 63
            if (chunk < 0) return null
            index += 1
            result = result or ((chunk and CHUNK_MASK).toLong() shl shift)
            shift += CHUNK_BITS
            if (chunk and CONTINUATION_BIT == 0) break
        }
        val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return delta to index
    }
}
