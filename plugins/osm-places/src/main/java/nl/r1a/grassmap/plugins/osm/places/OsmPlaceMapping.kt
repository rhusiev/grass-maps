package nl.r1a.grassmap.plugins.osm.places

/**
 * The typed card field an OSM key feeds (T-0208).
 *
 * A tag is either a field the card draws, or [Ignored] with the reason it is
 * not drawn. An ignored key is listed on purpose: the mapping table is the
 * whole statement of what the card reads, so a key that is dropped silently
 * would be indistinguishable from a key nobody handled.
 */
sealed interface PlaceCardField {
    data object Name : PlaceCardField

    data object Kind : PlaceCardField

    data object OpeningHours : PlaceCardField

    data object WheelchairAccess : PlaceCardField

    data object WheelchairDescription : PlaceCardField

    data object WheelchairToilets : PlaceCardField

    /** An extra OSM attribute, labelled by [labelKey]. */
    data class Attribute(
        val labelKey: String,
    ) : PlaceCardField

    data class Ignored(
        val reason: IgnoreReason,
    ) : PlaceCardField
}

/** A field whose row is a contact channel: a phone, a site, an address or a profile. */
sealed interface ContactField : PlaceCardField {
    data object Phone : ContactField

    data object Website : ContactField

    data object Email : ContactField

    data class Social(
        val network: SocialNetwork,
    ) : ContactField
}

/** Why the card deliberately shows nothing for a key. */
enum class IgnoreReason {
    /** A localised copy of `name`; the index already supplies the display name. */
    LOCALIZED_NAME_DUPLICATE,

    /** A localised copy of `wheelchair:description`; the base key is shown. */
    LOCALIZED_DESCRIPTION_DUPLICATE,

    /** Editing or provenance metadata with no place meaning. */
    EDITING_METADATA,
}

/** The social networks a place can name through a `contact:*` key. */
enum class SocialNetwork(
    val labelKey: String,
) {
    FACEBOOK("osm_places.social_facebook"),
    INSTAGRAM("osm_places.social_instagram"),
    TELEGRAM("osm_places.social_telegram"),
    TWITTER("osm_places.social_twitter"),
    VIBER("osm_places.social_viber"),
    WHATSAPP("osm_places.social_whatsapp"),
}

/** One row of the mapping table: an OSM key and the field it feeds. */
data class OsmTagMapping(
    val key: String,
    val field: PlaceCardField,
)

/**
 * The 40 OSM keys the place card reads, in the order the card draws them.
 *
 * The table is the contract `OsmPlaceMappingTest` checks: 40 entries, one per
 * key, with the six ignored keys named rather than dropped.
 */
object OsmPlaceMapping {
    val entries: List<OsmTagMapping> =
        listOf(
            OsmTagMapping("name", PlaceCardField.Name),
            OsmTagMapping("name:en", PlaceCardField.Ignored(IgnoreReason.LOCALIZED_NAME_DUPLICATE)),
            OsmTagMapping("name:uk", PlaceCardField.Ignored(IgnoreReason.LOCALIZED_NAME_DUPLICATE)),
            OsmTagMapping("amenity", PlaceCardField.Kind),
            OsmTagMapping("shop", PlaceCardField.Kind),
            OsmTagMapping("tourism", PlaceCardField.Kind),
            OsmTagMapping("leisure", PlaceCardField.Kind),
            OsmTagMapping("office", PlaceCardField.Kind),
            OsmTagMapping("healthcare", PlaceCardField.Kind),
            OsmTagMapping("brand", PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_BRAND)),
            OsmTagMapping("phone", ContactField.Phone),
            OsmTagMapping("contact:phone", ContactField.Phone),
            OsmTagMapping("mobile", ContactField.Phone),
            OsmTagMapping("website", ContactField.Website),
            OsmTagMapping("contact:website", ContactField.Website),
            OsmTagMapping("url", ContactField.Website),
            OsmTagMapping("email", ContactField.Email),
            OsmTagMapping("contact:email", ContactField.Email),
            OsmTagMapping("contact:facebook", ContactField.Social(SocialNetwork.FACEBOOK)),
            OsmTagMapping("contact:instagram", ContactField.Social(SocialNetwork.INSTAGRAM)),
            OsmTagMapping("contact:telegram", ContactField.Social(SocialNetwork.TELEGRAM)),
            OsmTagMapping("contact:twitter", ContactField.Social(SocialNetwork.TWITTER)),
            OsmTagMapping("contact:viber", ContactField.Social(SocialNetwork.VIBER)),
            OsmTagMapping("contact:whatsapp", ContactField.Social(SocialNetwork.WHATSAPP)),
            OsmTagMapping("wheelchair", PlaceCardField.WheelchairAccess),
            OsmTagMapping("wheelchair:description", PlaceCardField.WheelchairDescription),
            OsmTagMapping("toilets:wheelchair", PlaceCardField.WheelchairToilets),
            OsmTagMapping("wheelchair:toilets", PlaceCardField.WheelchairToilets),
            OsmTagMapping(
                "wheelchair:description:en",
                PlaceCardField.Ignored(IgnoreReason.LOCALIZED_DESCRIPTION_DUPLICATE),
            ),
            OsmTagMapping(
                "wheelchair:description:uk",
                PlaceCardField.Ignored(IgnoreReason.LOCALIZED_DESCRIPTION_DUPLICATE),
            ),
            OsmTagMapping("opening_hours", PlaceCardField.OpeningHours),
            OsmTagMapping("cuisine", PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_CUISINE)),
            OsmTagMapping("outdoor_seating", PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_OUTDOOR_SEATING)),
            OsmTagMapping("takeaway", PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_TAKEAWAY)),
            OsmTagMapping("delivery", PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_DELIVERY)),
            OsmTagMapping("internet_access", PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_INTERNET_ACCESS)),
            OsmTagMapping("smoking", PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_SMOKING)),
            OsmTagMapping("operator", PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_OPERATOR)),
            OsmTagMapping("layer", PlaceCardField.Ignored(IgnoreReason.EDITING_METADATA)),
            OsmTagMapping("source", PlaceCardField.Ignored(IgnoreReason.EDITING_METADATA)),
        )

    private val byKey: Map<String, PlaceCardField> = entries.associate { it.key to it.field }

    /** The field [key] feeds, or null when the table does not name the key. */
    fun fieldOf(key: String): PlaceCardField? = byKey[key]

    /** The keys the table maps to [PlaceCardField.Ignored]. */
    val ignoredKeys: Set<String> =
        entries.filter { it.field is PlaceCardField.Ignored }.map { it.key }.toSet()
}
