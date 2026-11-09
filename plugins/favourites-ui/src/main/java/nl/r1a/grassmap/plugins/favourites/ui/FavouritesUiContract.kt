package nl.r1a.grassmap.plugins.favourites.ui

import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.StreamId

/**
 * The ids, value type and commands the lists plugin declares (T-0213).
 *
 * Every id is namespaced under the plugin id and `plugin.json` must name the
 * same values; `FavouritesUiManifestTest` keeps the two in step.
 */
object FavouritesUiContract {
    const val PLUGIN_ID: String = "favourites.ui"
    const val LISTS_STREAM_ID: String = "favourites.ui.lists"
    const val LISTS_VALUE_TYPE: String = "nl.r1a.grassmap.plugin.api.model.FavouriteListsState"
    const val LISTS_SERIALIZER: String = "nl.r1a.grassmap.plugin.api.model.FavouriteListsState.serializer"

    const val CREATE_LIST: String = "favourites.ui.create_list"
    const val MOVE_POINT: String = "favourites.ui.move_point"
    const val RECOLOUR_LIST: String = "favourites.ui.recolour_list"
    const val SET_HOME: String = "favourites.ui.set_home"
    const val SET_WORK: String = "favourites.ui.set_work"
    const val UNDO: String = "favourites.ui.undo"

    val listsStream: StreamId = StreamId(LISTS_STREAM_ID)

    val createList: CommandId = CommandId(CREATE_LIST)
    val movePoint: CommandId = CommandId(MOVE_POINT)
    val recolourList: CommandId = CommandId(RECOLOUR_LIST)
    val setHome: CommandId = CommandId(SET_HOME)
    val setWork: CommandId = CommandId(SET_WORK)
    val undo: CommandId = CommandId(UNDO)

    val commands: List<CommandId> =
        listOf(createList, movePoint, recolourList, setHome, setWork, undo)
}
