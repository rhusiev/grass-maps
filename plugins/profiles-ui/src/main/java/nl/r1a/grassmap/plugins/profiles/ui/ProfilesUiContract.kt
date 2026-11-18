package nl.r1a.grassmap.plugins.profiles.ui

import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.StreamId

/**
 * The ids and value types the profiles plugin declares (T-0602).
 *
 * Every id is namespaced under the plugin id and `plugin.json` must name the
 * same values; `ProfilesUiManifestTest` keeps the two in step.
 */
object ProfilesUiContract {
    const val PLUGIN_ID: String = "profiles.ui"
    const val STATE_STREAM_ID: String = "profiles.ui.state"
    const val STATE_VALUE_TYPE: String = "nl.r1a.grassmap.plugin.api.model.ProfilesState"
    const val STATE_SERIALIZER: String = "nl.r1a.grassmap.plugin.api.model.ProfilesState.serializer"

    const val CREATE: String = "profiles.ui.create"
    const val COPY_SETTINGS: String = "profiles.ui.copy_settings"
    const val RESET: String = "profiles.ui.reset"
    const val UNDO: String = "profiles.ui.undo"

    val stateStream: StreamId = StreamId(STATE_STREAM_ID)

    val create: CommandId = CommandId(CREATE)
    val copySettings: CommandId = CommandId(COPY_SETTINGS)
    val reset: CommandId = CommandId(RESET)
    val undo: CommandId = CommandId(UNDO)

    val commands: List<CommandId> = listOf(create, copySettings, reset, undo)
}
