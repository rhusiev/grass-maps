package nl.r1a.grassmap.plugins.osm.places

/**
 * The i18n keys the card rows and typed values name.
 *
 * They live in this module's own string resources, so the fields an OSM tag
 * feeds are described next to the table that maps them. The shell resolves the
 * keys through the `Strings` service; no English text is compiled into the code.
 */
object OsmPlaceLabels {
    const val PHONE = "osm_places.phone"
    const val WEBSITE = "osm_places.website"
    const val EMAIL = "osm_places.email"

    const val WHEELCHAIR_ACCESS = "osm_places.wheelchair_access"
    const val WHEELCHAIR_DESCRIPTION = "osm_places.wheelchair_description"
    const val WHEELCHAIR_TOILETS = "osm_places.wheelchair_toilets"

    const val WHEELCHAIR_YES = "osm_places.wheelchair_yes"
    const val WHEELCHAIR_NO = "osm_places.wheelchair_no"
    const val WHEELCHAIR_LIMITED = "osm_places.wheelchair_limited"
    const val WHEELCHAIR_DESIGNATED = "osm_places.wheelchair_designated"

    const val YES = "osm_places.yes"
    const val NO = "osm_places.no"

    const val ATTRIBUTE_BRAND = "osm_places.attribute_brand"
    const val ATTRIBUTE_CUISINE = "osm_places.attribute_cuisine"
    const val ATTRIBUTE_OUTDOOR_SEATING = "osm_places.attribute_outdoor_seating"
    const val ATTRIBUTE_TAKEAWAY = "osm_places.attribute_takeaway"
    const val ATTRIBUTE_DELIVERY = "osm_places.attribute_delivery"
    const val ATTRIBUTE_INTERNET_ACCESS = "osm_places.attribute_internet_access"
    const val ATTRIBUTE_SMOKING = "osm_places.attribute_smoking"
    const val ATTRIBUTE_OPERATOR = "osm_places.attribute_operator"
}
