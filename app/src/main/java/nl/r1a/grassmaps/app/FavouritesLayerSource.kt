package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.places.FavouritesSnapshot
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugins.favourites.layer.FavouriteLayerLibrary
import nl.r1a.grassmaps.plugins.favourites.layer.FavouriteLayerMarker
import nl.r1a.grassmaps.plugins.favourites.layer.MarkerShape

/** The host's mapping from the persisted favourites library to the layer's drawable points. */
internal fun FavouritesSnapshot.toFavouriteLayerLibrary(): FavouriteLayerLibrary =
    FavouriteLayerLibrary(
        markers =
            favourites.map { favourite ->
                FavouriteLayerMarker(
                    id = favourite.id.value,
                    name = favourite.name,
                    group = favourite.group,
                    position = GeoPoint(favourite.latitude, favourite.longitude),
                    color = favourite.color,
                    icon = favourite.icon,
                    shape = MarkerShape.fromWire(favourite.shape.wireName) ?: MarkerShape.Circle,
                )
            },
    )
