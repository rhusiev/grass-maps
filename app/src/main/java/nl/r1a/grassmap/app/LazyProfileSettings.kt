package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ProfileSettingsAccess
import nl.r1a.grassmap.plugin.api.model.ProfilesState

/**
 * The profile actions of `plugins/profiles-ui` (T-0602), resolved on first
 * use. The service reads the profile stores, so building it must wait for the
 * first action, which runs off the Main thread.
 */
class LazyProfileSettings(
    private val provider: () -> ProfileSettingsAccess,
) : ProfileSettingsAccess {
    override fun state(): Outcome<ProfilesState> = provider().state()

    override fun create(
        name: String,
        from: String,
    ): Outcome<ProfilesState> = provider().create(name, from)

    override fun copySettings(
        from: String,
        to: String,
        groups: Set<String>,
    ): Outcome<ProfilesState> = provider().copySettings(from, to, groups)

    override fun reset(
        profile: String,
        groups: Set<String>,
    ): Outcome<ProfilesState> = provider().reset(profile, groups)

    override fun undo(): Outcome<ProfilesState> = provider().undo()
}
