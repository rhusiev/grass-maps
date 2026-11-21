package nl.r1a.grassmaps.plugin.api.service

import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.ProfileId

/**
 * One per-profile settings store seen as a set of encoded values, so profiles
 * can be copied and reset without knowing what the settings mean (T-0602).
 *
 * [read] gives the stored values of [profile], empty when the profile uses
 * the defaults. [replace] makes [values] the whole stored state of [profile],
 * and an empty map puts the defaults back.
 */
interface ProfileSettingsGroup {
    /** The stable id of the group, for example `routing`. */
    val id: String

    fun read(profile: ProfileId): Map<String, String>

    fun replace(
        profile: ProfileId,
        values: Map<String, String>,
    ): Outcome<Unit>

    companion object {
        const val ROUTING = "routing"
        const val ALERTS = "alerts"
        const val STYLE = "style"
        const val UNITS = "units"
    }
}
