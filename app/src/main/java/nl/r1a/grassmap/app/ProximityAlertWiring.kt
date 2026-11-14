package nl.r1a.grassmap.app

import nl.r1a.grassmap.alerts.AlertSettings
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.places.FavouritesSnapshot
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ProximityPlaceKind
import nl.r1a.grassmap.plugin.api.provider.Place
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityPlace
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityPlaceSource
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityPlaces
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityState
import nl.r1a.grassmap.tracks.StoredTrack

/**
 * Maps the published navigation state onto the proximity schedule's own input
 * (T-0413). The plugin module does not import `navigation`: the app owns the
 * phase mapping and the fix, and the schedule owns the distance and latch.
 *
 * A session is active from its first state past Idle until it is stopped, so a
 * pause or an arrival keeps the latches and a finished trip clears them. The
 * position is the raw fix, not the snapped point: the alert answers "how far
 * is this place from where I am", and the snapped point is a claim about the
 * line.
 */
internal fun NavigationState.toProximityState(): ProximityState =
    ProximityState(
        active = phase != NavigationPhase.Idle && phase != NavigationPhase.Stopped,
        fixTimeMillis = fix?.timeMillis,
        position = fix?.position,
    )

/**
 * Whether any proximity alert can be raised right now (T-0413). The schedule
 * asks this before every lookup, so a session whose three place switches are
 * off does no favourites, waypoint or index work at all.
 *
 * An on `poi` switch with no chosen kind cannot alert either, because the
 * schedule has nothing to match. Round 8's alerts page will write both the
 * switches and the chips; until it exists this is false for every session.
 */
internal fun proximityAlertsPossible(
    settings: AlertSettings,
    poiKinds: Set<String>,
): Boolean =
    settings.favourite ||
        settings.trackWaypoint ||
        (settings.poi && poiKinds.isNotEmpty())

/**
 * The places a proximity alert can announce (T-0413): the favourites library,
 * the waypoints of every stored track, and the place kinds the user picked
 * from the offline index.
 *
 * Each part is read independently, so a missing favourites file or an
 * unmounted search index still leaves the other parts working. A failed read
 * contributes nothing and is logged; it is never a reason to announce a place
 * with a guessed distance.
 */
internal class LibraryProximityPlaces(
    private val favourites: suspend () -> Outcome<FavouritesSnapshot>,
    private val tracks: suspend () -> List<StoredTrack>,
    private val pois: suspend (BoundingBox, Set<String>) -> Outcome<List<Place>>,
    private val log: (String) -> Unit = {},
) : ProximityPlaceSource {
    override suspend fun places(
        box: BoundingBox,
        poiKinds: Set<String>,
    ): ProximityPlaces {
        val places = mutableListOf<ProximityPlace>()
        places += favouritePlaces(box)
        places += waypointPlaces(box)
        if (poiKinds.isNotEmpty()) {
            places += poiPlaces(box, poiKinds)
        }
        return ProximityPlaces(places)
    }

    private suspend fun favouritePlaces(box: BoundingBox): List<ProximityPlace> =
        when (val loaded = favourites()) {
            is Outcome.Success -> {
                loaded.value.favourites.mapNotNull { favourite ->
                    placeOrNull(
                        kind = ProximityPlaceKind.Favourite,
                        id = favourite.id.value,
                        name = favourite.name,
                        latitude = favourite.latitude,
                        longitude = favourite.longitude,
                        box = box,
                    )
                }
            }

            is Outcome.Failure -> {
                log("favourites unreadable: ${loaded.error}")
                emptyList()
            }
        }

    private suspend fun waypointPlaces(box: BoundingBox): List<ProximityPlace> =
        tracks().flatMap { track ->
            track.waypoints.mapIndexedNotNull { index, waypoint ->
                placeOrNull(
                    kind = ProximityPlaceKind.TrackWaypoint,
                    id = "${track.id.value}#$index",
                    name = waypoint.name,
                    latitude = waypoint.position.latitude,
                    longitude = waypoint.position.longitude,
                    box = box,
                )
            }
        }

    private suspend fun poiPlaces(
        box: BoundingBox,
        poiKinds: Set<String>,
    ): List<ProximityPlace> =
        when (val found = pois(box, poiKinds)) {
            is Outcome.Success -> {
                found.value.map { place ->
                    ProximityPlace(
                        kind = ProximityPlaceKind.Poi,
                        id = place.id,
                        position = place.position,
                        name = place.name,
                        poiKind = place.kind,
                    )
                }
            }

            is Outcome.Failure -> {
                log("place lookup failed: ${found.error}")
                emptyList()
            }
        }

    /**
     * A place inside [box], or null for one outside it or with coordinates the
     * models cannot hold. This check runs before the model is built, because a
     * stored favourite can carry a corrupt position and one such record must
     * not stop every other place from being announced.
     */
    private fun placeOrNull(
        kind: ProximityPlaceKind,
        id: String,
        name: String?,
        latitude: Double,
        longitude: Double,
        box: BoundingBox,
    ): ProximityPlace? {
        val position = GeoPoint(latitude, longitude)
        if (!position.latitude.isFinite() || !position.longitude.isFinite() || !box.contains(position)) {
            return null
        }
        return ProximityPlace(kind = kind, id = id, position = position, name = name)
    }
}
