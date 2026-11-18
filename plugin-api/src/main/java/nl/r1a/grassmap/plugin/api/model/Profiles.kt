package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.Serializable

/**
 * One profile as the profiles screen draws it (T-0602). [name] is null for a
 * built-in profile, whose name is a translated string. [copiedFrom] is the
 * profile id a user profile was created from.
 */
@Serializable
data class ProfileSummary(
    val id: String,
    val name: String? = null,
    val builtIn: Boolean = false,
    val copiedFrom: String? = null,
)

/** What a profile action did, so the screen can say it and offer undo. */
@Serializable
enum class ProfileActionKind {
    CREATED,
    COPIED,
    RESET,
}

/** The last action that undo reverts. [source] is the profile copied from, when there is one. */
@Serializable
data class ProfileAction(
    val kind: ProfileActionKind,
    val profile: String,
    val source: String? = null,
)

/**
 * The profiles screen state. [groups] are the settings groups the host can
 * copy and reset, and [undo] is the action undo reverts, or null.
 */
@Serializable
data class ProfilesState(
    val profiles: List<ProfileSummary> = emptyList(),
    val groups: List<String> = emptyList(),
    val undo: ProfileAction? = null,
)
