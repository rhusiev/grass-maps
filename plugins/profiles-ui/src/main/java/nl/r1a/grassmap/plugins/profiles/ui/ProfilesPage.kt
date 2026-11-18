package nl.r1a.grassmap.plugins.profiles.ui

/**
 * The page the profiles screen shows (T-0602). The shell holds it, so system
 * back and the header chevron walk up the same chain with [back].
 */
sealed interface ProfilesPage {
    /** The list of all profiles. */
    data object List : ProfilesPage

    /** The editor of one profile. */
    data class Editor(
        val profile: String,
    ) : ProfilesPage

    /** The new-profile sheet over the list. */
    data object NewProfile : ProfilesPage

    /** The copy-settings sheet over the editor of [profile]. */
    data class CopySettings(
        val profile: String,
    ) : ProfilesPage

    /** The page one step up, or null when the screen closes. */
    fun back(): ProfilesPage? =
        when (this) {
            List -> null
            is Editor, NewProfile -> List
            is CopySettings -> Editor(profile)
        }
}
