package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.map
import nl.r1a.grassmap.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmap.plugin.api.model.ProfileId
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.service.ProfileValueStore
import nl.r1a.grassmap.plugin.api.service.ProfileValues

/**
 * One profile of the catalog. [routeProfile] is the route profile it carries
 * (architecture section 20). A built-in profile has no [name], because its
 * name is a translated string. [copiedFrom] is the profile a user profile was
 * created from.
 */
data class ProfileEntry(
    val id: ProfileId,
    val routeProfile: RouteProfile,
    val isBuiltIn: Boolean,
    val name: String? = null,
    val copiedFrom: ProfileId? = null,
    /** The creation order a user profile keeps; built-ins and older entries are 0. */
    val order: Long = 0L,
)

/**
 * The list of profiles, kept in the same per-profile contract as every other
 * store: the outer key is the [ProfileId], the inner keys are
 * [ROUTE_PROFILE_KEY] and [BUILT_IN_KEY].
 *
 * Walk, bike and hike exist on first start: [load] writes them when the store
 * has none, and puts back a built-in profile that went missing. Names are not
 * stored, because a built-in name is a translated string. A rewrite keeps every stored
 * entry and key, including ones this build cannot parse.
 */
class ProfileCatalog(
    private val store: ProfileValueStore? = null,
) {
    private var memory: ProfileValues = emptyMap()

    /** The profiles, built-ins first, then user profiles in creation order. A store that cannot be read yields the built-ins alone. */
    @Synchronized
    fun load(): List<ProfileEntry> {
        val read = readStored()
        val stored = (read as? Outcome.Success)?.value.orEmpty()
        if (read is Outcome.Success && BuiltInProfiles.ALL.any { it !in stored }) {
            writeStored(withBuiltIns(stored))
        }
        return entries(stored)
    }

    /** Adds a user profile named [name] that carries the route profile of [from], and returns it. */
    @Synchronized
    fun create(
        name: String,
        from: ProfileId,
    ): Outcome<ProfileEntry> {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > NAME_MAX_LENGTH) return Outcome.Failure(AppError.InvalidInput)
        val stored =
            when (val read = readStored()) {
                is Outcome.Failure -> return read
                is Outcome.Success -> read.value
            }
        val source = entries(stored).firstOrNull { it.id == from } ?: return Outcome.Failure(AppError.NotFound)
        val all = withBuiltIns(stored)
        val entry = ProfileEntry(uniqueId(trimmed, all.keys), source.routeProfile, isBuiltIn = false, name = trimmed, copiedFrom = from)
        return writeStored(all + (entry.id to encode(entry) + (ORDER_KEY to nextOrder(all).toString()))).map { entry }
    }

    /** Removes the user profile [id]. A built-in profile or an entry this build cannot parse stays. */
    @Synchronized
    fun remove(id: ProfileId): Outcome<Unit> {
        val stored =
            when (val read = readStored()) {
                is Outcome.Failure -> return read
                is Outcome.Success -> read.value
            }
        val entry = entries(stored).firstOrNull { it.id == id } ?: return Outcome.Failure(AppError.NotFound)
        if (entry.isBuiltIn) return Outcome.Failure(AppError.InvalidInput)
        return writeStored(stored - id)
    }

    /**
     * Puts one profile from a bundle into the catalog, keeping its id and its
     * order. A built-in entry can only confirm the fixed profile it names. Every
     * entry this build does not know stays written back unchanged.
     */
    @Synchronized
    fun upsert(entry: ProfileEntry): Outcome<Unit> {
        val name = entry.name?.trim()
        if (entry.id.value.isBlank() || entry.id.value.length > ID_MAX_LENGTH) return Outcome.Failure(AppError.InvalidInput)
        if (entry.isBuiltIn) {
            if (name != null) return Outcome.Failure(AppError.InvalidInput)
        } else if (name.isNullOrEmpty() || name.length > NAME_MAX_LENGTH) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val stored =
            when (val read = readStored()) {
                is Outcome.Failure -> return read
                is Outcome.Success -> read.value
            }
        val all = withBuiltIns(stored)
        val existing = entries(all).firstOrNull { it.id == entry.id }
        if (existing?.isBuiltIn == true) {
            return if (existing.routeProfile == entry.routeProfile) Outcome.Success(Unit) else Outcome.Failure(AppError.InvalidInput)
        }
        if (entry.isBuiltIn || BuiltInProfiles.routeProfileOf(entry.id) != null) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val order = entry.order.takeIf { it > 0L } ?: stored[entry.id]?.let(::orderOf) ?: nextOrder(all)
        return writeStored(
            all + (entry.id to encode(entry.copy(name = name)) + (ORDER_KEY to order.toString())),
        )
    }

    private fun readStored(): Outcome<ProfileValues> = store?.read() ?: Outcome.Success(memory)

    private fun writeStored(values: ProfileValues): Outcome<Unit> = store?.write(values) ?: Outcome.Success(Unit).also { memory = values }

    private fun entries(stored: ProfileValues): List<ProfileEntry> =
        (BuiltInProfiles.ALL.map(::builtIn) + userEntries(stored)).distinctBy { it.id }

    private fun builtIn(id: ProfileId): ProfileEntry =
        ProfileEntry(id, requireNotNull(BuiltInProfiles.routeProfileOf(id)), isBuiltIn = true)

    private fun userEntries(stored: ProfileValues): List<ProfileEntry> =
        stored.entries.sortedBy { orderOf(it.value) }.mapNotNull { (id, values) ->
            val routeProfile = values[ROUTE_PROFILE_KEY]?.let { BuiltInProfiles.routeProfileOf(ProfileId(it)) }
            routeProfile?.let {
                ProfileEntry(
                    id = id,
                    routeProfile = it,
                    isBuiltIn = values[BUILT_IN_KEY] == TRUE,
                    name = values[NAME_KEY],
                    copiedFrom = values[COPIED_FROM_KEY]?.let(::ProfileId),
                    order = orderOf(values),
                )
            }
        }

    /** Every stored entry as it is, extra keys and entries this build cannot parse included, plus the missing built-ins. */
    private fun withBuiltIns(stored: ProfileValues): ProfileValues =
        stored + BuiltInProfiles.ALL.filter { it !in stored }.associateWith(::encodeBuiltIn)

    private fun encodeBuiltIn(id: ProfileId): Map<String, String> = mapOf(ROUTE_PROFILE_KEY to id.value, BUILT_IN_KEY to TRUE)

    private fun encode(entry: ProfileEntry): Map<String, String> =
        listOfNotNull(
            ROUTE_PROFILE_KEY to BuiltInProfiles.idOf(entry.routeProfile).value,
            BUILT_IN_KEY to entry.isBuiltIn.toString(),
            entry.name?.let { NAME_KEY to it },
            entry.copiedFrom?.let { COPIED_FROM_KEY to it.value },
            entry.order.takeIf { it > 0L }?.let { ORDER_KEY to it.toString() },
        ).toMap()

    private fun orderOf(values: Map<String, String>): Long = values[ORDER_KEY]?.toLongOrNull() ?: 0L

    /** The file is sorted by id, so the creation order of user profiles is stored in each entry. */
    private fun nextOrder(stored: ProfileValues): Long = (stored.values.maxOfOrNull(::orderOf) ?: 0L) + 1L

    private fun uniqueId(
        name: String,
        taken: Set<ProfileId>,
    ): ProfileId {
        val base =
            name
                .lowercase()
                .replace(NON_ID_CHARACTERS, "-")
                .trim('-')
                .ifEmpty { FALLBACK_ID }
        return generateSequence(1) { it + 1 }
            .map { if (it == 1) base else "$base-$it" }
            .map(::ProfileId)
            .first { it !in taken }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val ERROR_ID = "core.services.profile_catalog"
        const val ROUTE_PROFILE_KEY = "route_profile"
        const val BUILT_IN_KEY = "built_in"
        const val NAME_KEY = "name"
        const val COPIED_FROM_KEY = "copied_from"
        const val ORDER_KEY = "order"

        /** The longest name a user profile may have. */
        const val NAME_MAX_LENGTH = 40

        /** The longest profile id a bundle may carry. */
        const val ID_MAX_LENGTH = 64

        private const val FALLBACK_ID = "profile"
        private const val TRUE = "true"
        private val NON_ID_CHARACTERS = Regex("[^\\p{L}\\p{N}]+")
    }
}
