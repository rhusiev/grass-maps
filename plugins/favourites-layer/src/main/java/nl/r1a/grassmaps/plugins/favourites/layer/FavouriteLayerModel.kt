package nl.r1a.grassmaps.plugins.favourites.layer

import nl.r1a.grassmaps.plugin.api.model.GeoPoint

/** The marker background a saved place draws, after any layer-wide override. */
enum class MarkerShape(
    val wireName: String,
    val sprite: String,
) {
    Circle("circle", "osmc_bg_circle"),
    Square("square", "osmc_bg_square"),
    Octagon("octagon", "favourite_octagon"),
    ;

    companion object {
        fun fromWire(value: String?): MarkerShape? = entries.firstOrNull { it.wireName == value?.trim()?.lowercase() }
    }
}

/** One drawable saved place. Its icon and shape are effective values from the store. */
data class FavouriteLayerMarker(
    val id: String,
    val name: String,
    val group: String? = null,
    val position: GeoPoint,
    val color: String,
    val icon: String? = null,
    val shape: MarkerShape = MarkerShape.Circle,
)

/** The library snapshot the layer draws. The host fills it from the favourites store. */
data class FavouriteLayerLibrary(
    val markers: List<FavouriteLayerMarker> = emptyList(),
)

/**
 * The four persisted layer controls (T-0215).
 *
 * A null [markerShape] keeps the shape each saved place carries in the store;
 * a non-null value draws every marker with that shape. A group in
 * [hiddenGroups] contributes no feature at all, not a transparent one.
 */
data class FavouriteLayerSettings(
    val iconsPerPoint: Boolean = true,
    val markerShape: MarkerShape? = null,
    val showNames: Boolean = false,
    val hiddenGroups: Set<String> = emptySet(),
)
