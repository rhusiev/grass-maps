package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmap.plugin.api.model.ProfileId
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.service.ProfileValueStore
import nl.r1a.grassmap.plugin.api.service.ProfileValues

/** One profile of the catalog. [routeProfile] is the route profile it carries (architecture section 20). */
data class ProfileEntry(
    val id: ProfileId,
    val routeProfile: RouteProfile,
    val isBuiltIn: Boolean,
)

/**
 * The list of profiles, kept in the same per-profile contract as every other
 * store: the outer key is the [ProfileId], the inner keys are
 * [ROUTE_PROFILE_KEY] and [BUILT_IN_KEY].
 *
 * Walk, bike and hike exist on first start: [load] writes them when the store
 * has none, and puts back a built-in profile that went missing. Names are not
 * stored, because a built-in name is a translated string. Creating, copying and
 * resetting profiles is T-0602.
 */
class ProfileCatalog(
    private val store: ProfileValueStore? = null,
) {
    /** The profiles, built-ins first. A store that cannot be read yields the built-ins alone. */
    @Synchronized
    fun load(): List<ProfileEntry> {
        val stored = (store?.read() as? Outcome.Success)?.value.orEmpty()
        val entries = (BuiltInProfiles.ALL.map(::builtIn) + userEntries(stored)).distinctBy { it.id }
        if (store != null && BuiltInProfiles.ALL.any { it !in stored }) {
            store.write(entries.associate { it.id to encode(it) })
        }
        return entries
    }

    private fun builtIn(id: ProfileId): ProfileEntry =
        ProfileEntry(id, requireNotNull(BuiltInProfiles.routeProfileOf(id)), isBuiltIn = true)

    private fun userEntries(stored: ProfileValues): List<ProfileEntry> =
        stored.mapNotNull { (id, values) ->
            val routeProfile = values[ROUTE_PROFILE_KEY]?.let { BuiltInProfiles.routeProfileOf(ProfileId(it)) }
            routeProfile?.let { ProfileEntry(id, it, isBuiltIn = values[BUILT_IN_KEY] == TRUE) }
        }

    private fun encode(entry: ProfileEntry): Map<String, String> =
        mapOf(
            ROUTE_PROFILE_KEY to BuiltInProfiles.idOf(entry.routeProfile).value,
            BUILT_IN_KEY to entry.isBuiltIn.toString(),
        )

    companion object {
        const val SCHEMA_VERSION = 1
        const val ROUTE_PROFILE_KEY = "route_profile"
        const val BUILT_IN_KEY = "built_in"
        private const val TRUE = "true"
    }
}
