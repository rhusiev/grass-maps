package nl.r1a.grassmap.plugins.share

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.PlaceCard
import nl.r1a.grassmap.plugin.api.model.PlaceCardAction
import nl.r1a.grassmap.plugin.api.model.PlaceCardEntry
import nl.r1a.grassmap.plugin.api.model.PlaceCardLink
import nl.r1a.grassmap.plugin.api.model.PlaceCardShortcut
import nl.r1a.grassmap.plugin.api.provider.PlaceDetails

/**
 * The coordinate rows and the share actions a place card carries (T-0210).
 *
 * `plugins/osm-places` maps the tags; this object adds the parts that need the
 * point and the identity the same details already hold, and keeps every format
 * and link rule in one module. `app` composes the two when it resolves a card,
 * so `ui-shell` draws rows it was given and the provider stays a tag mapper.
 */
object ShareCard {
    const val COPY_COORDINATES_LABEL_KEY: String = "share.copy_coordinates"
    const val SHARE_PLACE_LABEL_KEY: String = "share.share_place"
    const val OPEN_IN_LABEL_KEY: String = "share.open_in"

    /** The decimal, DMS and DDM rows, in that order. */
    fun coordinates(point: GeoPoint): List<PlaceCardEntry> = CoordinateFormats.rows(point)

    /**
     * Copy, share and open-in. The copy target is the decimal pair; the share
     * target is the message [placeMessage] builds; the open target is the
     * `geo:` URI. An absent point means there is nothing to act on, so no
     * shortcut is drawn.
     */
    fun shortcuts(details: PlaceDetails): List<PlaceCardShortcut> {
        val point = details.place.position
        if (!point.isFinite()) {
            return emptyList()
        }
        val identity = ShareLinks.identityOf(details.osmType, details.osmId)
        return listOf(
            PlaceCardShortcut(
                labelKey = COPY_COORDINATES_LABEL_KEY,
                link = PlaceCardLink(PlaceCardAction.COPY_COORDINATES, CoordinateFormats.copyText(point)),
            ),
            PlaceCardShortcut(
                labelKey = SHARE_PLACE_LABEL_KEY,
                link =
                    PlaceCardLink(
                        PlaceCardAction.SHARE,
                        placeMessage(details.place.name, identity, point),
                    ),
            ),
            PlaceCardShortcut(
                labelKey = OPEN_IN_LABEL_KEY,
                link = PlaceCardLink(PlaceCardAction.OPEN_IN, ShareLinks.geoUri(point, details.place.name)),
            ),
        )
    }

    /** [card] with the coordinate rows and the shortcuts for [details]. */
    fun decorate(
        card: PlaceCard,
        details: PlaceDetails,
    ): PlaceCard =
        card.copy(
            coordinates = coordinates(details.place.position),
            shortcuts = shortcuts(details),
        )

    /**
     * The message a place share carries: the name when there is one, the
     * decimal pair, and then either the osm.org element or, for a place with
     * no identity, the `geo:` URI for the same point.
     */
    fun placeMessage(
        name: String?,
        identity: PlaceIdentity?,
        point: GeoPoint,
    ): String {
        val link = ShareLinks.placeLink(identity, point, name)
        return messageOf(name, CoordinateFormats.decimal(point), link.uri)
    }

    /**
     * The message a current-location share carries. A fix has no OSM element,
     * so the link is the `geo:` URI for the same point.
     */
    fun locationMessage(point: GeoPoint): String = messageOf(null, CoordinateFormats.decimal(point), ShareLinks.geoUri(point))

    private fun messageOf(vararg lines: String?): String =
        lines.mapNotNull { line -> line?.trim()?.takeIf { it.isNotEmpty() } }.joinToString("\n")
}
