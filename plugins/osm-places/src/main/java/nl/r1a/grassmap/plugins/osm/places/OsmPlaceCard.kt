package nl.r1a.grassmap.plugins.osm.places

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.PlaceCard
import nl.r1a.grassmap.plugin.api.model.PlaceCardAction
import nl.r1a.grassmap.plugin.api.model.PlaceCardEntry
import nl.r1a.grassmap.plugin.api.model.PlaceCardLink
import nl.r1a.grassmap.plugin.api.model.PlaceCardValue
import nl.r1a.grassmap.plugin.api.provider.PlaceCardContract
import nl.r1a.grassmap.plugin.api.provider.PlaceCardProvider
import nl.r1a.grassmap.plugin.api.provider.PlaceDetails
import java.util.Locale

/**
 * The place card over the 40-key table (T-0208).
 *
 * It maps [PlaceDetails] onto card rows and nothing else: the tags are the ones
 * the `places.search` provider returned, so the card cannot read an index or a
 * tag map of its own. Absent, blank and repeated values are dropped rather than
 * drawn as empty or doubled rows.
 */
class OsmPlaceCard : PlaceCardProvider {
    override val contract = PlaceCardContract.REFERENCE

    override fun card(details: PlaceDetails): Outcome<PlaceCard> {
        val tags = details.tags
        var name: String? = null
        var kind: String? = null
        var openingHours: String? = null
        val contacts = LinkedHashMap<String, PlaceCardEntry>()
        val accessibility = LinkedHashMap<PlaceCardField, PlaceCardEntry>()
        val attributes = LinkedHashMap<String, PlaceCardEntry>()

        for (mapping in OsmPlaceMapping.entries) {
            val field = mapping.field
            if (field is PlaceCardField.Ignored) {
                continue
            }
            val value = tags[mapping.key]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            when (field) {
                PlaceCardField.Name -> {
                    if (name == null) name = value
                }

                PlaceCardField.Kind -> {
                    if (kind == null) kind = value
                }

                PlaceCardField.OpeningHours -> {
                    if (openingHours == null) openingHours = value
                }

                PlaceCardField.WheelchairAccess -> {
                    accessibility.putIfAbsent(
                        field,
                        PlaceCardEntry(OsmPlaceLabels.WHEELCHAIR_ACCESS, wheelchairValue(value)),
                    )
                }

                PlaceCardField.WheelchairToilets -> {
                    accessibility.putIfAbsent(
                        field,
                        PlaceCardEntry(OsmPlaceLabels.WHEELCHAIR_TOILETS, wheelchairValue(value)),
                    )
                }

                PlaceCardField.WheelchairDescription -> {
                    accessibility.putIfAbsent(
                        field,
                        PlaceCardEntry(OsmPlaceLabels.WHEELCHAIR_DESCRIPTION, PlaceCardValue.Text(value)),
                    )
                }

                is ContactField -> {
                    contacts.putIfAbsent("$field|$value", contactEntry(field, value))
                }

                is PlaceCardField.Attribute -> {
                    attributes.putIfAbsent(field.labelKey, PlaceCardEntry(field.labelKey, booleanValue(value)))
                }

                is PlaceCardField.Ignored -> {
                    Unit
                }
            }
        }

        return Outcome.Success(
            PlaceCard(
                name =
                    name ?: details.place.name
                        .trim()
                        .takeIf { it.isNotEmpty() },
                kind =
                    kind ?: details.place.kind
                        .substringAfterLast('=')
                        .trim()
                        .takeIf { it.isNotEmpty() },
                address =
                    details.place.address
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                openingHours = openingHours,
                contacts = contacts.values.toList(),
                accessibility = accessibility.values.toList(),
                attributes = attributes.values.toList(),
            ),
        )
    }

    private fun contactEntry(
        field: ContactField,
        value: String,
    ): PlaceCardEntry =
        when (field) {
            ContactField.Phone -> {
                PlaceCardEntry(
                    labelKey = OsmPlaceLabels.PHONE,
                    value = PlaceCardValue.Text(value),
                    link = PlaceCardLink(PlaceCardAction.CALL, "tel:$value"),
                )
            }

            ContactField.Website -> {
                PlaceCardEntry(
                    labelKey = OsmPlaceLabels.WEBSITE,
                    value = PlaceCardValue.Text(value),
                    link = PlaceCardLink(PlaceCardAction.OPEN_WEBSITE, value),
                )
            }

            ContactField.Email -> {
                PlaceCardEntry(
                    labelKey = OsmPlaceLabels.EMAIL,
                    value = PlaceCardValue.Text(value),
                    link = PlaceCardLink(PlaceCardAction.SEND_EMAIL, "mailto:$value"),
                )
            }

            is ContactField.Social -> {
                PlaceCardEntry(
                    labelKey = field.network.labelKey,
                    value = PlaceCardValue.Text(value),
                    link = PlaceCardLink(PlaceCardAction.OPEN_SOCIAL, value),
                )
            }
        }
}

/** A wheelchair value the card knows, or the raw value when it does not. */
internal fun wheelchairValue(value: String): PlaceCardValue {
    val key =
        when (value.lowercase(Locale.ROOT)) {
            "yes" -> OsmPlaceLabels.WHEELCHAIR_YES
            "no" -> OsmPlaceLabels.WHEELCHAIR_NO
            "limited" -> OsmPlaceLabels.WHEELCHAIR_LIMITED
            "designated" -> OsmPlaceLabels.WHEELCHAIR_DESIGNATED
            else -> return PlaceCardValue.Text(value)
        }
    return PlaceCardValue.Label(key)
}

/** A boolean OSM value as a localised label, any other value as raw text. */
internal fun booleanValue(value: String): PlaceCardValue =
    when (value.lowercase(Locale.ROOT)) {
        "yes" -> PlaceCardValue.Label(OsmPlaceLabels.YES)
        "no" -> PlaceCardValue.Label(OsmPlaceLabels.NO)
        else -> PlaceCardValue.Text(value)
    }
