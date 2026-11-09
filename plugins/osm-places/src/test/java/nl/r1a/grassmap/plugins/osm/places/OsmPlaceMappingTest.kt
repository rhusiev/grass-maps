package nl.r1a.grassmap.plugins.osm.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 40-key table (T-0208). Every key names the field it feeds, and every
 * ignored key names why it is not drawn.
 */
class OsmPlaceMappingTest {
    @Test
    fun theTableMapsExactlyFortyKeys() {
        assertEquals(FORTY_KEYS, OsmPlaceMapping.entries.size)
        assertEquals(
            FORTY_KEYS,
            OsmPlaceMapping.entries
                .map { it.key }
                .distinct()
                .size,
        )
    }

    @Test
    fun everyKeyKeepsItsExpectedField() {
        assertEquals(EXPECTED_FIELDS, OsmPlaceMapping.entries.associate { it.key to it.field })
    }

    @Test
    fun theIgnoredKeysAreNamedWithAReason() {
        assertEquals(
            setOf(
                "name:en",
                "name:uk",
                "wheelchair:description:en",
                "wheelchair:description:uk",
                "layer",
                "source",
            ),
            OsmPlaceMapping.ignoredKeys,
        )
        val ignored =
            OsmPlaceMapping.entries
                .filter { it.field is PlaceCardField.Ignored }
                .associate { it.key to (it.field as PlaceCardField.Ignored).reason }
        assertEquals(IgnoreReason.LOCALIZED_NAME_DUPLICATE, ignored.getValue("name:en"))
        assertEquals(IgnoreReason.LOCALIZED_NAME_DUPLICATE, ignored.getValue("name:uk"))
        assertEquals(IgnoreReason.LOCALIZED_DESCRIPTION_DUPLICATE, ignored.getValue("wheelchair:description:en"))
        assertEquals(IgnoreReason.LOCALIZED_DESCRIPTION_DUPLICATE, ignored.getValue("wheelchair:description:uk"))
        assertEquals(IgnoreReason.EDITING_METADATA, ignored.getValue("layer"))
        assertEquals(IgnoreReason.EDITING_METADATA, ignored.getValue("source"))
    }

    @Test
    fun everyRowCoversOneRequiredCardRow() {
        val fields = OsmPlaceMapping.entries.map { it.field }
        assertEquals(1, fields.count { it == PlaceCardField.Name })
        assertEquals(6, fields.count { it == PlaceCardField.Kind })
        assertEquals(1, fields.count { it == PlaceCardField.OpeningHours })
        assertEquals(3, fields.count { it is ContactField.Phone })
        assertEquals(3, fields.count { it is ContactField.Website })
        assertEquals(2, fields.count { it is ContactField.Email })
        assertEquals(6, fields.count { it is ContactField.Social })
        assertEquals(1, fields.count { it == PlaceCardField.WheelchairAccess })
        assertEquals(1, fields.count { it == PlaceCardField.WheelchairDescription })
        assertEquals(2, fields.count { it == PlaceCardField.WheelchairToilets })
        assertEquals(8, fields.count { it is PlaceCardField.Attribute })
        assertEquals(6, fields.count { it is PlaceCardField.Ignored })
        assertTrue(OsmPlaceMapping.ignoredKeys.all { key -> OsmPlaceMapping.fieldOf(key) is PlaceCardField.Ignored })
    }

    @Test
    fun anUnlistedKeyHasNoField() {
        assertEquals(null, OsmPlaceMapping.fieldOf("note"))
    }

    private companion object {
        const val FORTY_KEYS = 40

        val EXPECTED_FIELDS: Map<String, PlaceCardField> =
            mapOf(
                "name" to PlaceCardField.Name,
                "name:en" to PlaceCardField.Ignored(IgnoreReason.LOCALIZED_NAME_DUPLICATE),
                "name:uk" to PlaceCardField.Ignored(IgnoreReason.LOCALIZED_NAME_DUPLICATE),
                "amenity" to PlaceCardField.Kind,
                "shop" to PlaceCardField.Kind,
                "tourism" to PlaceCardField.Kind,
                "leisure" to PlaceCardField.Kind,
                "office" to PlaceCardField.Kind,
                "healthcare" to PlaceCardField.Kind,
                "brand" to PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_BRAND),
                "phone" to ContactField.Phone,
                "contact:phone" to ContactField.Phone,
                "mobile" to ContactField.Phone,
                "website" to ContactField.Website,
                "contact:website" to ContactField.Website,
                "url" to ContactField.Website,
                "email" to ContactField.Email,
                "contact:email" to ContactField.Email,
                "contact:facebook" to ContactField.Social(SocialNetwork.FACEBOOK),
                "contact:instagram" to ContactField.Social(SocialNetwork.INSTAGRAM),
                "contact:telegram" to ContactField.Social(SocialNetwork.TELEGRAM),
                "contact:twitter" to ContactField.Social(SocialNetwork.TWITTER),
                "contact:viber" to ContactField.Social(SocialNetwork.VIBER),
                "contact:whatsapp" to ContactField.Social(SocialNetwork.WHATSAPP),
                "wheelchair" to PlaceCardField.WheelchairAccess,
                "wheelchair:description" to PlaceCardField.WheelchairDescription,
                "toilets:wheelchair" to PlaceCardField.WheelchairToilets,
                "wheelchair:toilets" to PlaceCardField.WheelchairToilets,
                "wheelchair:description:en" to
                    PlaceCardField.Ignored(IgnoreReason.LOCALIZED_DESCRIPTION_DUPLICATE),
                "wheelchair:description:uk" to
                    PlaceCardField.Ignored(IgnoreReason.LOCALIZED_DESCRIPTION_DUPLICATE),
                "opening_hours" to PlaceCardField.OpeningHours,
                "cuisine" to PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_CUISINE),
                "outdoor_seating" to PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_OUTDOOR_SEATING),
                "takeaway" to PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_TAKEAWAY),
                "delivery" to PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_DELIVERY),
                "internet_access" to PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_INTERNET_ACCESS),
                "smoking" to PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_SMOKING),
                "operator" to PlaceCardField.Attribute(OsmPlaceLabels.ATTRIBUTE_OPERATOR),
                "layer" to PlaceCardField.Ignored(IgnoreReason.EDITING_METADATA),
                "source" to PlaceCardField.Ignored(IgnoreReason.EDITING_METADATA),
            )
    }
}
