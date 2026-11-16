package nl.r1a.grassmap.plugins.downloads.ui

import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.StreamId

/**
 * The ids and value types the downloads plugin declares (T-0504).
 *
 * Every id is namespaced under the plugin id and `plugin.json` must name the
 * same values; `DownloadsUiManifestTest` keeps the two in step.
 */
object DownloadsUiContract {
    const val PLUGIN_ID: String = "downloads.ui"
    const val STATE_STREAM_ID: String = "downloads.ui.state"
    const val STATE_VALUE_TYPE: String = "nl.r1a.grassmap.plugin.api.model.DownloadsState"
    const val STATE_SERIALIZER: String = "nl.r1a.grassmap.plugin.api.model.DownloadsState.serializer"

    const val REFRESH: String = "downloads.ui.refresh"
    const val UPDATE_ALL: String = "downloads.ui.update_all"
    const val DOWNLOAD_REGION: String = "downloads.ui.download_region"
    const val CANCEL_REGION: String = "downloads.ui.cancel_region"
    const val REMOVE_REGION: String = "downloads.ui.remove_region"
    const val SELECT_REGION: String = "downloads.ui.select_region"
    const val SET_SERVER: String = "downloads.ui.set_server"
    const val SET_MOBILE_DATA: String = "downloads.ui.set_mobile_data"
    const val SET_AUTO_UPDATE: String = "downloads.ui.set_auto_update"
    const val USE_MOBILE_DATA_NOW: String = "downloads.ui.use_mobile_data_now"

    val stateStream: StreamId = StreamId(STATE_STREAM_ID)

    val refresh: CommandId = CommandId(REFRESH)
    val updateAll: CommandId = CommandId(UPDATE_ALL)
    val downloadRegion: CommandId = CommandId(DOWNLOAD_REGION)
    val cancelRegion: CommandId = CommandId(CANCEL_REGION)
    val removeRegion: CommandId = CommandId(REMOVE_REGION)
    val selectRegion: CommandId = CommandId(SELECT_REGION)
    val setServer: CommandId = CommandId(SET_SERVER)
    val setMobileData: CommandId = CommandId(SET_MOBILE_DATA)
    val setAutoUpdate: CommandId = CommandId(SET_AUTO_UPDATE)
    val useMobileDataNow: CommandId = CommandId(USE_MOBILE_DATA_NOW)

    val commands: List<CommandId> =
        listOf(
            refresh,
            updateAll,
            downloadRegion,
            cancelRegion,
            removeRegion,
            selectRegion,
            setServer,
            setMobileData,
            setAutoUpdate,
            useMobileDataNow,
        )
}
