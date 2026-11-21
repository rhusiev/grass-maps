package nl.r1a.grassmaps.plugins.favourites.layer

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The feature list a layer draws, before any style JSON is built (T-0215).
 *
 * One feature is one saved place. A hidden group contributes no feature, so a
 * hidden group can never leave a faint marker behind. The icon is the sprite
 * name when the point's OsmAnd icon maps to one in the shipped sheet; an
 * unknown icon becomes the neutral dot rather than a missing image.
 */
object FavouriteLayerFeatures {
    fun visible(
        library: FavouriteLayerLibrary,
        settings: FavouriteLayerSettings,
    ): List<FavouriteLayerMarker> =
        library.markers
            .filterNot { marker -> marker.group != null && marker.group in settings.hiddenGroups }
            .map { marker ->
                val chosen = settings.markerShape
                if (chosen == null || chosen == marker.shape) marker else marker.copy(shape = chosen)
            }

    fun featureCollection(
        library: FavouriteLayerLibrary,
        settings: FavouriteLayerSettings,
    ): String = featureCollection(visible(library, settings), settings)

    fun featureCollection(
        markers: List<FavouriteLayerMarker>,
        settings: FavouriteLayerSettings,
    ): String {
        return buildJsonObject {
            put("type", "FeatureCollection")
            putJsonArray("features") {
                for (marker in markers) {
                    add(feature(marker, settings))
                }
            }
        }.toString()
    }

    private fun feature(
        marker: FavouriteLayerMarker,
        settings: FavouriteLayerSettings,
    ) = buildJsonObject {
        put("type", "Feature")
        putJsonObject("geometry") {
            put("type", "Point")
            putJsonArray("coordinates") {
                add(marker.position.longitude)
                add(marker.position.latitude)
            }
        }
        putJsonObject("properties") {
            put("id", marker.id)
            put("name", marker.name)
            put("group", marker.group.orEmpty())
            put("icon", marker.icon.orEmpty())
            put("shape", shapeOf(marker, settings).wireName)
            put("color", marker.color)
            put("shapeIcon", shapeOf(marker, settings).sprite)
            put("iconSprite", iconSprite(marker.icon))
        }
    }

    private fun shapeOf(
        marker: FavouriteLayerMarker,
        settings: FavouriteLayerSettings,
    ): MarkerShape = settings.markerShape ?: marker.shape

    private fun iconSprite(icon: String?): String = if (icon in SPRITE_ICONS) icon!! else FALLBACK_ICON

    private val SPRITE_ICONS =
        setOf(
            "aerodrome", "animal", "arrow", "artwork", "attraction", "bar", "beach", "beauty", "bench", "books",
            "building", "bus_stop", "cafe", "capital", "clothes", "convenience", "drinking_water", "electronics",
            "fast_food", "ferry_terminal", "forest", "garden", "library", "marina", "museum", "park", "peak",
            "post_office", "restaurant", "school", "stadium", "supermarket", "theatre", "toilets", "townhall",
            "townspot", "train_station", "university", "zoo",
        )

    private const val FALLBACK_ICON = "osmc_fg_dot"
}
