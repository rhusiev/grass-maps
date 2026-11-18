package nl.r1a.grassmap.plugins.profiles.ui

import nl.r1a.grassmap.plugin.api.model.ProfileSummary
import nl.r1a.grassmap.plugin.api.model.ProfilesState

/** What the profiles screen derives from [ProfilesState] (T-0602). */
object ProfilesScreen {
    /** The profile with [id], or null when the list has no such profile. */
    fun find(
        state: ProfilesState,
        id: String,
    ): ProfileSummary? = state.profiles.firstOrNull { it.id == id }

    /** The profiles a copy into [target] can read from: every profile but [target]. */
    fun sources(
        state: ProfilesState,
        target: String,
    ): List<ProfileSummary> = state.profiles.filterNot { it.id == target }

    /**
     * The first profile to preselect: [preferred] when it is in [options],
     * otherwise the first option.
     */
    fun initialSource(
        options: List<ProfileSummary>,
        preferred: String?,
    ): String? = options.firstOrNull { it.id == preferred }?.id ?: options.firstOrNull()?.id
}
