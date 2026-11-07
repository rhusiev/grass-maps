package nl.r1a.grassmap.app

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import nl.r1a.grassmap.core.services.MapControlCommands
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.manifest.GeneratedCommand
import nl.r1a.grassmap.plugin.api.model.CameraUpdate

/** The owner of host commands that have no plugin behind them. */
internal val CORE_PLUGIN: PluginId = PluginId("core")

internal const val ZOOM_STEP: Double = 1.0
internal const val MIN_ZOOM: Double = 1.0
internal const val MAX_ZOOM: Double = 20.0

/**
 * The process-level map commands. They hold a [MapSessionHolder], not a surface,
 * so the same instances survive a recomposition or an Activity recreation.
 */
internal fun mapCommands(
    session: MapSessionHolder,
    location: LocationController,
    permission: LocationPermissionCoordinator,
): List<Command<*, *>> =
    listOf(
        ZoomCommand(session, ZOOM_STEP),
        ZoomCommand(session, -ZOOM_STEP),
        NorthUpCommand(session),
        FollowCommand(location, permission),
    )

/** The bus-side catalog entries for [commands], owned by the core host plugin. */
internal fun generatedMapCommands(commands: List<Command<*, *>>): List<GeneratedCommand> =
    commands.map { command ->
        GeneratedCommand(
            id = command.id,
            pluginId = CORE_PLUGIN,
            undoable = command.policy.undoable,
            requiresSwitch = command.policy.requiresSwitch,
            allowlistedForControl = command.policy.allowlistedForControl,
            dispatcher = command.policy.dispatcher,
        )
    }

private val HOST_POLICY: CommandPolicy =
    CommandPolicy(
        undoable = false,
        allowlistedForControl = false,
        dispatcher = CommandDispatcher.MAIN,
    )

/**
 * Accepts any JSON parameter element for a command that takes none. The bus
 * invokes such a command with `JsonNull`, which the builtin `Unit` serializer
 * rejects, so the element is read and discarded.
 */
private object UnitParamsSerializer : KSerializer<Unit> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("nl.r1a.grassmap.app.NoParams", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Unit,
    ) {
        encoder.encodeString("")
    }

    override fun deserialize(decoder: Decoder) {
        if (decoder is JsonDecoder) {
            decoder.decodeJsonElement()
        }
    }
}

/** Zooms by [delta], clamped to the map's supported range, and returns the result. */
internal class ZoomCommand(
    private val session: MapSessionHolder,
    private val delta: Double,
) : Command<Unit, Double>(
        id = if (delta > 0) MapControlCommands.ZOOM_IN else MapControlCommands.ZOOM_OUT,
        paramsSerializer = UnitParamsSerializer,
        resultSerializer = Double.serializer(),
        doc = "Changes the map zoom by one step and returns the new zoom.",
        policy = HOST_POLICY,
    ) {
    override suspend fun execute(params: Unit): Outcome<CommandResult<Double>> {
        val host = session.hostOrNull() ?: return Outcome.Failure(AppError.NotReady)
        val zoom = (host.camera.value.zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        return move(host, CameraUpdate(zoom = zoom), zoom)
    }
}

/** Points the camera north and returns the new bearing. */
internal class NorthUpCommand(
    private val session: MapSessionHolder,
) : Command<Unit, Double>(
        id = MapControlCommands.NORTH_UP,
        paramsSerializer = UnitParamsSerializer,
        resultSerializer = Double.serializer(),
        doc = "Sets the map bearing to north and returns it.",
        policy = HOST_POLICY,
    ) {
    override suspend fun execute(params: Unit): Outcome<CommandResult<Double>> {
        val host = session.hostOrNull() ?: return Outcome.Failure(AppError.NotReady)
        return when (val outcome = host.moveCamera(CameraUpdate(bearing = NORTH_BEARING))) {
            is Outcome.Success -> Outcome.Success(CommandResult(NORTH_BEARING))
            is Outcome.Failure -> outcome
        }
    }
}

private suspend fun move(
    host: MapHost,
    update: CameraUpdate,
    value: Double,
): Outcome<CommandResult<Double>> =
    when (val outcome = host.animateCamera(update)) {
        is Outcome.Success -> Outcome.Success(CommandResult(value))
        is Outcome.Failure -> outcome
    }

/**
 * Asks for the location permission when the user first taps the position
 * control, then toggles camera follow. A denied answer is
 * [AppError.PermissionDenied]: the map keeps working and the screen explains
 * why location helps.
 */
internal class FollowCommand(
    private val location: LocationController,
    private val permission: LocationPermissionCoordinator,
) : Command<Unit, Boolean>(
        id = MapControlCommands.FOLLOW,
        paramsSerializer = UnitParamsSerializer,
        resultSerializer = Boolean.serializer(),
        doc = "Toggles following the current position and returns the new state.",
        policy = HOST_POLICY,
    ) {
    override suspend fun execute(params: Unit): Outcome<CommandResult<Boolean>> =
        when (val allowed = permission.ensureAllowed()) {
            is Outcome.Failure -> {
                allowed
            }

            is Outcome.Success -> {
                if (!allowed.value) {
                    Outcome.Failure(AppError.PermissionDenied)
                } else {
                    location.onPermissionGranted()
                    when (val outcome = location.toggleFollowing()) {
                        is Outcome.Success -> Outcome.Success(CommandResult(outcome.value))
                        is Outcome.Failure -> outcome
                    }
                }
            }
        }
}

private const val NORTH_BEARING: Double = 0.0
