package nl.r1a.grassmap.plugins.profiles.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ProfileSettingsAccess
import nl.r1a.grassmap.plugin.api.model.ProfilesState

/**
 * The profiles screen's state and actions inside `plugins/profiles-ui`
 * (T-0602).
 *
 * The host owns the profile list, the stores and the undo history. This
 * session calls the host, publishes the state the host answered and keeps
 * nothing else, so the screen and the stream show what the files hold. The
 * calls read and write files, so the commands run them on the IO dispatcher.
 */
class ProfilesUiSession(
    private val access: ProfileSettingsAccess,
) {
    private val current = MutableStateFlow(ProfilesState())
    val state: StateFlow<ProfilesState> = current.asStateFlow()

    /** Reads the host's state once, when the screen opens. */
    fun load(): Outcome<ProfilesState> = publish(access.state())

    fun create(
        name: String,
        from: String,
    ): Outcome<ProfilesState> = publish(access.create(name, from))

    fun copySettings(
        from: String,
        to: String,
        groups: Set<String>,
    ): Outcome<ProfilesState> = publish(access.copySettings(from, to, groups))

    fun reset(
        profile: String,
        groups: Set<String>,
    ): Outcome<ProfilesState> = publish(access.reset(profile, groups))

    fun undo(): Outcome<ProfilesState> = publish(access.undo())

    private fun publish(outcome: Outcome<ProfilesState>): Outcome<ProfilesState> {
        if (outcome is Outcome.Success) {
            current.value = outcome.value
        }
        return outcome
    }
}
