package nl.r1a.grassmap.plugins.dev.tools

import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.contract.SwitchId

/**
 * The ids the manifest declares. Activation, the app panel and the tests name
 * the same constants, so a renamed id cannot drift from `plugin.json`.
 */
object DevToolsContract {
    const val PLUGIN_ID: String = "dev.tools"
    const val STATE_STREAM_ID: String = "dev.tools.state"
    const val START_REPLAY_COMMAND_ID: String = "dev.tools.start_replay"
    const val STOP_REPLAY_COMMAND_ID: String = "dev.tools.stop_replay"

    const val STATE_VALUE_TYPE: String = "nl.r1a.grassmap.plugins.dev.tools.DevToolsPanelState"
    const val STATE_SERIALIZER: String = "$STATE_VALUE_TYPE.serializer"

    val stateStream: StreamId = StreamId(STATE_STREAM_ID)
    val startReplayCommand: CommandId = CommandId(START_REPLAY_COMMAND_ID)
    val stopReplayCommand: CommandId = CommandId(STOP_REPLAY_COMMAND_ID)

    /** The AI-control switch the architecture puts the release plugin behind. */
    val aiControlSwitch: SwitchId = SwitchId("ai.control")
}
