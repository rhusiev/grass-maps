package nl.r1a.grassmap.plugins.share

import nl.r1a.grassmap.plugin.api.contract.CommandId

/**
 * The ids and commands `plugins/share` declares (T-0210).
 *
 * Every id is namespaced under the plugin id and `plugin.json` names the same
 * values; `ShareManifestTest` keeps the two in step.
 */
object ShareContract {
    const val PLUGIN_ID: String = "share"

    const val COPY_COORDINATES: String = "share.copy_coordinates"
    const val SHARE_PLACE: String = "share.share_place"
    const val SHARE_LOCATION: String = "share.share_location"
    const val OPEN_POINT: String = "share.open_point"

    val copyCoordinates: CommandId = CommandId(COPY_COORDINATES)
    val sharePlace: CommandId = CommandId(SHARE_PLACE)
    val shareLocation: CommandId = CommandId(SHARE_LOCATION)
    val openPoint: CommandId = CommandId(OPEN_POINT)

    val commands: List<CommandId> =
        listOf(copyCoordinates, sharePlace, shareLocation, openPoint)
}
