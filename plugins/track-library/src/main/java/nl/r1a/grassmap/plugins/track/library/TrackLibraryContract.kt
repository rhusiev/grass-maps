package nl.r1a.grassmap.plugins.track.library

import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.StreamId

/**
 * The ids, value type and commands the track library plugin declares (T-0414).
 *
 * Every id is namespaced under the plugin id and `plugin.json` must name the
 * same values; `TrackLibraryManifestTest` keeps the two in step.
 */
object TrackLibraryContract {
    const val PLUGIN_ID: String = "track.library"
    const val STATE_STREAM_ID: String = "track.library.state"
    const val STATE_VALUE_TYPE: String = "nl.r1a.grassmap.plugin.api.model.TrackLibraryState"
    const val STATE_SERIALIZER: String = "nl.r1a.grassmap.plugin.api.model.TrackLibraryState.serializer"

    const val CREATE_FOLDER: String = "track.library.create_folder"
    const val RENAME_FOLDER: String = "track.library.rename_folder"
    const val EDIT_TRACK: String = "track.library.edit_track"
    const val MOVE_TRACK: String = "track.library.move_track"
    const val DELETE_TRACK: String = "track.library.delete_track"
    const val UNDO: String = "track.library.undo"
    const val SET_QUERY: String = "track.library.set_query"
    const val SET_SORT: String = "track.library.set_sort"
    const val SELECT_FOLDER: String = "track.library.select_folder"

    val stateStream: StreamId = StreamId(STATE_STREAM_ID)

    val createFolder: CommandId = CommandId(CREATE_FOLDER)
    val renameFolder: CommandId = CommandId(RENAME_FOLDER)
    val editTrack: CommandId = CommandId(EDIT_TRACK)
    val moveTrack: CommandId = CommandId(MOVE_TRACK)
    val deleteTrack: CommandId = CommandId(DELETE_TRACK)
    val undo: CommandId = CommandId(UNDO)
    val setQuery: CommandId = CommandId(SET_QUERY)
    val setSort: CommandId = CommandId(SET_SORT)
    val selectFolder: CommandId = CommandId(SELECT_FOLDER)

    val commands: List<CommandId> =
        listOf(
            createFolder,
            renameFolder,
            editTrack,
            moveTrack,
            deleteTrack,
            undo,
            setQuery,
            setSort,
            selectFolder,
        )
}
