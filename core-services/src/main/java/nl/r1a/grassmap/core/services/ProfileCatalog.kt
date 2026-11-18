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
 * stored, because a built-in name is a translated string. A rewrite keeps every stored
 * entry and key, including ones this build cannot parse.
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
            store.write(withBuiltIns(stored))
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

    /** Every stored entry as it is, extra keys and entries this build cannot parse included, plus the missing built-ins. */
    private fun withBuiltIns(stored: ProfileValues): ProfileValues =
        stored + BuiltInProfiles.ALL.filter { it !in stored }.associateWith(::encodeBuiltIn)

    private fun encodeBuiltIn(id: ProfileId): Map<String, String> =
        mapOf(ROUTE_PROFILE_KEY to id.value, BUILT_IN_KEY to TRUE)

    companion object {
        const val SCHEMA_VERSION = 1
        const val ERROR_ID = "core.services.profile_catalog"
        const val ROUTE_PROFILE_KEY = "route_profile"
        const val BUILT_IN_KEY = "built_in"
        private const val TRUE = "true"
    }
}
