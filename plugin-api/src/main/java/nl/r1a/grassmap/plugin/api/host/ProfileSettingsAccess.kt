package nl.r1a.grassmap.plugin.api.host

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ProfilesState

/**
 * The profile actions behind `plugins/profiles-ui` (T-0602).
 *
 * The host owns the profile list and every per-profile settings store, so each
 * action writes the profile files before the plugin publishes the new state.
 * The host also holds the undo history: [undo] reverts the action that
 * [ProfilesState.undo] names. A settings group is named by its id, for example
 * `routing`. An action with no group ids covers every group in
 * [ProfilesState.groups].
 */
interface ProfileSettingsAccess {
    fun state(): Outcome<ProfilesState>

    /** Adds a profile named [name] that starts as a copy of [from]. */
    fun create(
        name: String,
        from: String,
    ): Outcome<ProfilesState>

    /** Replaces the [groups] of [to] with those of [from]. */
    fun copySettings(
        from: String,
        to: String,
        groups: Set<String> = emptySet(),
    ): Outcome<ProfilesState>

    /**
     * Puts the [groups] of [profile] back to their baseline: the profile it was
     * copied from, or the defaults for a built-in profile.
     */
    fun reset(
        profile: String,
        groups: Set<String> = emptySet(),
    ): Outcome<ProfilesState>

    /** Reverts the last action. It is [AppError.NotFound] when there is nothing to revert. */
    fun undo(): Outcome<ProfilesState>
}

/** The access a build without profile stores wires. */
object NoProfileSettingsAccess : ProfileSettingsAccess {
    override fun state(): Outcome<ProfilesState> = notReady()

    override fun create(
        name: String,
        from: String,
    ): Outcome<ProfilesState> = notReady()

    override fun copySettings(
        from: String,
        to: String,
        groups: Set<String>,
    ): Outcome<ProfilesState> = notReady()

    override fun reset(
        profile: String,
        groups: Set<String>,
    ): Outcome<ProfilesState> = notReady()

    override fun undo(): Outcome<ProfilesState> = notReady()

    private fun notReady(): Outcome.Failure = Outcome.Failure(AppError.NotReady)
}
