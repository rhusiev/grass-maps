package nl.r1a.grassmap.plugins.favourites.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.host.FavouriteListChange
import nl.r1a.grassmap.plugin.api.host.FavouriteListUndo
import nl.r1a.grassmap.plugin.api.host.FavouritesListsAccess
import nl.r1a.grassmap.plugin.api.host.PluginServices
import nl.r1a.grassmap.plugin.api.host.StreamAccess
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.FavouriteListSummary
import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouritePointSummary
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Provider
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.LocationService
import nl.r1a.grassmap.plugin.api.widget.WidgetDescriptor

internal class RecordingContext(
    override val services: PluginServices,
) : PluginContext {
    override val pluginId: PluginId = PluginId(FavouritesUiContract.PLUGIN_ID)

    val commands = mutableListOf<Command<*, *>>()
    val streams = mutableListOf<StreamDescriptor<*>>()
    val providers = mutableListOf<Provider>()
    val widgets = mutableListOf<WidgetDescriptor>()

    override fun addCommand(command: Command<*, *>) {
        commands += command
    }

    override fun addStream(stream: StreamDescriptor<*>) {
        streams += stream
    }

    override fun addProvider(provider: Provider) {
        providers += provider
    }

    override fun addWidget(widget: WidgetDescriptor) {
        widgets += widget
    }
}

internal class FakeServices(
    override val favouritesLists: FavouritesListsAccess,
) : PluginServices {
    override val streams: StreamAccess =
        object : StreamAccess {
            override fun <T : Any> subscribe(streamId: StreamId): Flow<T> = emptyFlow()
        }

    override val location: LocationService =
        object : LocationService {
            override val fixes: Flow<LocationFix?> = emptyFlow()

            override fun hasPermission(): Boolean = false
        }

    override val elevation: ElevationAccess = ElevationAccess { Outcome.Success(null) }

    override val camera: Flow<Camera> = emptyFlow()

    override val clock: Clock = Clock { 0L }
}

/**
 * A typed fake of the host access. It records every call and mutates its own
 * state the way the store would, so the plugin's published stream is testable
 * without a file.
 */
internal class FakeListsAccess : FavouritesListsAccess {
    var current =
        FavouriteListsState(
            lists = listOf(FavouriteListSummary("Lviv", "#FF0000", pointCount = 1)),
            points = listOf(FavouritePointSummary("p1", "Rynok Square", "Lviv")),
        )

    val calls = mutableListOf<String>()
    var undoResult: FavouriteListUndo = FavouriteListUndo.APPLIED
    var lastUndoReceipt: String? = null

    override suspend fun state(): Outcome<FavouriteListsState> = Outcome.Success(current)

    override suspend fun createList(
        name: String,
        color: String,
        icon: String?,
    ): Outcome<FavouriteListChange> {
        calls += "create:$name:$color:$icon"
        current = current.copy(lists = current.lists + FavouriteListSummary(name, color, icon))
        return change("r-create")
    }

    override suspend fun movePoint(
        pointId: String,
        list: String,
    ): Outcome<FavouriteListChange> {
        calls += "move:$pointId:$list"
        current =
            current.copy(
                points =
                    current.points.map { point ->
                        if (point.id == pointId) point.copy(list = list) else point
                    },
            )
        return change("r-move")
    }

    override suspend fun recolourList(
        name: String,
        color: String,
    ): Outcome<FavouriteListChange> {
        calls += "recolour:$name:$color"
        current =
            current.copy(
                lists =
                    current.lists.map { list ->
                        if (list.name == name) list.copy(color = color) else list
                    },
            )
        return change("r-recolour")
    }

    override suspend fun setSpecial(
        pointId: String,
        special: FavouriteSpecial,
    ): Outcome<FavouriteListChange> {
        calls += "special:$pointId:$special"
        current =
            current.copy(
                points =
                    current.points.map { point ->
                        when {
                            point.id == pointId -> point.copy(special = special)
                            point.special == special -> point.copy(special = null)
                            else -> point
                        }
                    },
            )
        return change("r-special")
    }

    override suspend fun undo(receipt: String): Outcome<FavouriteListUndo> {
        calls += "undo:$receipt"
        lastUndoReceipt = receipt
        return Outcome.Success(undoResult)
    }

    private fun change(receipt: String): Outcome<FavouriteListChange> = Outcome.Success(FavouriteListChange(current, receipt))
}
