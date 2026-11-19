package nl.r1a.grassmap.app.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.app.FavouritesAccess
import nl.r1a.grassmap.core.services.backup.BackupKinds
import nl.r1a.grassmap.core.services.backup.BundleEntry
import nl.r1a.grassmap.core.services.backup.BundleFile
import nl.r1a.grassmap.core.services.backup.BundleKindSource
import nl.r1a.grassmap.places.Favourite
import nl.r1a.grassmap.places.FavouriteGroup
import nl.r1a.grassmap.places.FavouriteId
import nl.r1a.grassmap.places.FavouriteShape
import nl.r1a.grassmap.places.FavouriteSpecial
import nl.r1a.grassmap.places.FavouritesSnapshot
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The favourites library as one bundle kind (T-0608). A point and a list are
 * both entries, so a list's name, colour, icon and shape conflict like any
 * other id. The library's GPX document rides along as one kind-wide file, so
 * the bundle can be read by OsmAnd; the entries are the import source.
 */
internal class FavouritesBundleKind(
    private val favourites: FavouritesAccess,
) : BundleKindSource {
    override val kind: String = BackupKinds.FAVOURITES

    override suspend fun count(): Outcome<Int> =
        when (val loaded = favourites.load()) {
            is Outcome.Success -> Outcome.Success(loaded.value.favourites.size + loaded.value.groups.size)
            is Outcome.Failure -> loaded
        }

    override suspend fun read(): Outcome<List<BundleEntry>> {
        val snapshot =
            when (val loaded = favourites.load()) {
                is Outcome.Success -> loaded.value
                is Outcome.Failure -> return loaded
            }
        return Outcome.Success(entriesOf(snapshot))
    }

    /** The library's own GPX document, for a reader that does not know this bundle. */
    override suspend fun files(): Outcome<List<BundleFile>> =
        when (val exported = favourites.export()) {
            is Outcome.Success -> Outcome.Success(listOf(BundleFile(kind + "/favourites.gpx", exported.value)))
            is Outcome.Failure -> exported
        }

    override suspend fun apply(entries: List<BundleEntry>): Outcome<Unit> {
        val current =
            when (val loaded = favourites.load()) {
                is Outcome.Success -> loaded.value
                is Outcome.Failure -> return loaded
            }
        val mergedPoints = current.favourites.toMutableList()
        val mergedGroups = current.groups.toMutableList()
        for (entry in entries) {
            if (entry.id.startsWith(LIST_PREFIX)) {
                val group = groupOf(entry) ?: return Outcome.Failure(AppError.InvalidInput)
                val index = mergedGroups.indexOfFirst { existing -> existing.name == group.name }
                if (index >= 0) mergedGroups[index] = group else mergedGroups += group
            } else {
                val favourite = favouriteOf(entry) ?: return Outcome.Failure(AppError.InvalidInput)
                val index = mergedPoints.indexOfFirst { existing -> existing.id == favourite.id }
                if (index >= 0) mergedPoints[index] = favourite else mergedPoints += favourite
            }
        }
        return favourites.save(
            FavouritesSnapshot(groups = mergedGroups, favourites = mergedPoints),
        )
    }

    private fun entriesOf(snapshot: FavouritesSnapshot): List<BundleEntry> {
        val entries = mutableListOf<BundleEntry>()
        snapshot.favourites.forEach { favourite ->
            entries += BundleEntry(favourite.id.value, favouriteValue(favourite))
        }
        snapshot.groups.forEach { group ->
            entries += BundleEntry(LIST_PREFIX + group.name, groupValue(group))
        }
        return entries
    }

    private fun favouriteValue(favourite: Favourite): JsonObject =
        buildJsonObject {
            put(LATITUDE_KEY, favourite.latitude)
            put(LONGITUDE_KEY, favourite.longitude)
            put(NAME_KEY, favourite.name)
            favourite.description?.let { put(DESCRIPTION_KEY, it) }
            favourite.group?.let { put(GROUP_KEY, it) }
            put(COLOUR_KEY, favourite.color)
            favourite.icon?.let { put(ICON_KEY, it) }
            put(SHAPE_KEY, favourite.shape.wireName)
            favourite.special?.let { put(SPECIAL_KEY, it.wireName) }
        }

    private fun groupValue(group: FavouriteGroup): JsonObject =
        buildJsonObject {
            put(NAME_KEY, group.name)
            put(COLOUR_KEY, group.color)
            group.icon?.let { put(ICON_KEY, it) }
            put(SHAPE_KEY, group.shape.wireName)
        }

    private fun favouriteOf(entry: BundleEntry): Favourite? {
        val value = entry.value
        val latitude = value.doubleValue(LATITUDE_KEY) ?: return null
        val longitude = value.doubleValue(LONGITUDE_KEY) ?: return null
        val name = value.stringValue(NAME_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val shape = shapeOf(value.stringValue(SHAPE_KEY)) ?: return null
        val special =
            value.stringValue(SPECIAL_KEY)?.let { wire ->
                FavouriteSpecial.entries.firstOrNull { special -> special.wireName == wire }
            }
        return Favourite(
            id = FavouriteId(entry.id),
            latitude = latitude,
            longitude = longitude,
            name = name,
            description = value.stringValue(DESCRIPTION_KEY),
            group = value.stringValue(GROUP_KEY),
            color = value.stringValue(COLOUR_KEY) ?: Favourite.DEFAULT_COLOR,
            icon = value.stringValue(ICON_KEY),
            shape = shape,
            special = special,
        )
    }

    private fun groupOf(entry: BundleEntry): FavouriteGroup? {
        val value = entry.value
        val name = value.stringValue(NAME_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val shape = shapeOf(value.stringValue(SHAPE_KEY)) ?: return null
        return FavouriteGroup(
            name = name,
            color = value.stringValue(COLOUR_KEY) ?: Favourite.DEFAULT_COLOR,
            icon = value.stringValue(ICON_KEY),
            shape = shape,
        )
    }

    private fun shapeOf(wireName: String?): FavouriteShape? =
        if (wireName == null) {
            FavouriteShape.Circle
        } else {
            FavouriteShape.entries.firstOrNull { shape -> shape.wireName == wireName }
        }

    private companion object {
        const val LIST_PREFIX = "list:"
        const val LATITUDE_KEY = "latitude"
        const val LONGITUDE_KEY = "longitude"
        const val NAME_KEY = "name"
        const val DESCRIPTION_KEY = "description"
        const val GROUP_KEY = "group"
        const val COLOUR_KEY = "color"
        const val ICON_KEY = "icon"
        const val SHAPE_KEY = "shape"
        const val SPECIAL_KEY = "special"
    }
}
