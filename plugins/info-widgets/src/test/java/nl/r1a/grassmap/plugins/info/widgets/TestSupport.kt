package nl.r1a.grassmap.plugins.info.widgets

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.host.PluginServices
import nl.r1a.grassmap.plugin.api.host.StreamAccess
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Provider
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.LocationService
import nl.r1a.grassmap.plugin.api.widget.WidgetDescriptor

internal class FakeClock(
    var now: Long = 0L,
) : Clock {
    override fun nowMillis(): Long = now
}

internal class RecordingContext(
    override val services: PluginServices,
) : PluginContext {
    override val pluginId: PluginId = PluginId(InfoWidgetsContract.PLUGIN_ID)

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
    fixes: Flow<LocationFix?>,
    override val elevation: ElevationAccess = ElevationAccess { Outcome.Success(null) },
    override val clock: Clock = FakeClock(),
) : PluginServices {
    override val camera: Flow<Camera> = emptyFlow()

    override val streams: StreamAccess =
        object : StreamAccess {
            override fun <T : Any> subscribe(streamId: StreamId): Flow<T> = emptyFlow()
        }

    override val location: LocationService =
        object : LocationService {
            override val fixes: Flow<LocationFix?> = fixes

            override fun hasPermission(): Boolean = true

            override fun requiresPermission(): Boolean = false
        }
}

internal fun fixAt(
    position: GeoPoint,
    altitudeMeters: Double? = null,
    accuracyMeters: Double = 4.0,
    speedMetersPerSecond: Double? = null,
    bearingDegrees: Double? = null,
    timeMillis: Long = 1_792_000_000_000L,
): LocationFix =
    LocationFix(
        position = position,
        accuracyMeters = accuracyMeters,
        altitudeMeters = altitudeMeters,
        bearingDegrees = bearingDegrees,
        speedMetersPerSecond = speedMetersPerSecond,
        timeMillis = timeMillis,
    )

/** The T-0023 stand-in values at its authored ways, used as the elevation oracle. */
internal object ElevationOracle {
    val peak: GeoPoint = GeoPoint(latitude = 49.9490, longitude = 23.9570)
    const val PEAK_METERS: Double = 352.75
    const val TOLERANCE_METERS: Double = 0.5

    val access: ElevationAccess =
        ElevationAccess { position ->
            when (position) {
                peak -> Outcome.Success(PEAK_METERS)
                else -> Outcome.Success(null)
            }
        }
}

internal object FailingElevation : ElevationAccess {
    override suspend fun elevationMeters(position: GeoPoint): Outcome<Double?> = Outcome.Failure(AppError.CorruptData)
}
