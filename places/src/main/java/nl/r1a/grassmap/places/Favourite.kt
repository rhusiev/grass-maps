package nl.r1a.grassmap.places

/**
 * A stored favourite's identity. The GPX dialect has no id field, so the id is
 * derived from the exported values plus the record's ordinal among identical
 * records. The same rule makes a reload and a re-import agree on the id.
 */
@JvmInline
value class FavouriteId(
    val value: String,
) {
    override fun toString(): String = value
}

/** The marker background OsmAnd draws behind a favourite icon. */
enum class FavouriteShape(
    val wireName: String,
) {
    Circle("circle"),
    Square("square"),
    Octagon("octagon"),
}

/**
 * The Home or Work special place (T-0213). At most one favourite holds each,
 * and a favourite holds at most one of them.
 */
enum class FavouriteSpecial(
    val wireName: String,
) {
    Home("home"),
    Work("work"),
}

/**
 * One favourite as OsmAnd's favourites GPX 1.1 dialect stores it.
 *
 * [group] is the `<type>` element, [color] and [icon] are the `osmand:` colour
 * and icon names, and [shape] is the `osmand:background` marker shape. Values
 * are effective values: a waypoint without its own appearance inherits the
 * group's defaults, and a waypoint in a group without a default inherits the
 * documented defaults ([DEFAULT_COLOR] and [FavouriteShape.Circle]).
 *
 * [special] is not part of OsmAnd's dialect. The store writes it as
 * `<grassmap:special>` inside `<extensions>`, which OsmAnd and plain GPX
 * readers ignore.
 */
data class Favourite(
    val id: FavouriteId,
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val description: String? = null,
    val group: String? = null,
    val color: String = DEFAULT_COLOR,
    val icon: String? = null,
    val shape: FavouriteShape = FavouriteShape.Circle,
    val special: FavouriteSpecial? = null,
) {
    companion object {
        /** OsmAnd's documented default icon colour. */
        const val DEFAULT_COLOR: String = "red"
    }
}

/** A group definition from `<osmand:points_groups>` and the defaults it carries. */
data class FavouriteGroup(
    val name: String,
    val color: String = Favourite.DEFAULT_COLOR,
    val icon: String? = null,
    val shape: FavouriteShape = FavouriteShape.Circle,
)

/** Every stored favourite in file order, plus the group definitions. */
data class FavouritesSnapshot(
    val groups: List<FavouriteGroup> = emptyList(),
    val favourites: List<Favourite> = emptyList(),
)

/** A deleted favourite and the index it occupied, enough to restore it exactly. */
data class DeletedFavourite(
    val favourite: Favourite,
    val index: Int,
)

/** Why one waypoint of an imported document was not stored. */
enum class FavouriteSkipReason {
    MissingCoordinates,
    OutOfRangeCoordinates,
    MissingName,
}

/** One skipped waypoint, located by its index among the document's `<wpt>` elements. */
data class SkippedFavourite(
    val index: Int,
    val reason: FavouriteSkipReason,
)

/** The waypoints read from one document, with the parts that were skipped. */
data class FavouriteDocument(
    val snapshot: FavouritesSnapshot,
    val skipped: List<SkippedFavourite> = emptyList(),
)

/** The result of merging one document into the store. */
data class FavouriteImport(
    val imported: Int,
    val skipped: List<SkippedFavourite> = emptyList(),
)

/**
 * Builds a favourite with an id derived from its own values. A store that
 * already holds an identical record gives the new one a suffixed id when it is
 * added; the store's copy is the authoritative one.
 */
fun favourite(
    latitude: Double,
    longitude: Double,
    name: String,
    description: String? = null,
    group: String? = null,
    color: String = Favourite.DEFAULT_COLOR,
    icon: String? = null,
    shape: FavouriteShape = FavouriteShape.Circle,
): Favourite =
    deriveIds(
        listOf(
            Favourite(
                id = FavouriteId(EMPTY_ID),
                latitude = latitude,
                longitude = longitude,
                name = name,
                description = description,
                group = group,
                color = color,
                icon = icon,
                shape = shape,
            ),
        ),
    ).single()

internal const val EMPTY_ID: String = ""

/** Replaces every id with one derived from the record's value and its ordinal. */
internal fun deriveIds(favourites: List<Favourite>): List<Favourite> {
    val seen = mutableMapOf<String, Int>()
    return favourites.map { favourite ->
        val base = FavouriteIds.base(favourite)
        val ordinal = seen[base] ?: 0
        seen[base] = ordinal + 1
        favourite.copy(id = FavouriteId(if (ordinal == 0) base else "$base-$ordinal"))
    }
}
