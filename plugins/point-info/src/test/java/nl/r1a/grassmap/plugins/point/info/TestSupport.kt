package nl.r1a.grassmap.plugins.point.info

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
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
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.api.widget.WidgetDescriptor

/**
 * A clock that advances on every read, so every value passes the cadence
 * throttle and a test never waits on wall time.
 */
internal class TickingClock(
    private var now: Long = 1_000L,
) : Clock {
    override fun nowMillis(): Long {
        now += TICK_MILLIS
        return now
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
    }
}

internal class RecordingContext(
    override val services: PluginServices,
) : PluginContext {
    override val pluginId: PluginId = PluginId(PointInfoContract.PLUGIN_ID)

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
    camera: Flow<Camera>,
    override val clock: Clock = TickingClock(),
) : PluginServices {
    override val camera: Flow<Camera> = camera

    override val elevation: ElevationAccess = ElevationAccess { Outcome.Success(null) }

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

/** Resolves the English point-info strings a test asserts on, or the key itself. */
internal class TestStrings(
    private val values: Map<String, String> = ENGLISH,
) : Strings {
    override fun resolve(
        key: String,
        arguments: List<String>,
    ): String {
        val template = values[key] ?: return key
        return arguments.foldIndexed(template) { index, text, argument ->
            text.replace("%${index + 1}\$s", argument)
        }
    }

    override fun contains(key: String): Boolean = key in values

    internal companion object {
        val ENGLISH =
            mapOf(
                PointInfoLabel.DISTANCE_ROW_KEY to "Distance %1\$s",
                PointInfoLabel.AZIMUTH_ROW_KEY to "Azimuth %1\$s",
                "point_info_distance_metres" to "%1\$s m",
                "point_info_distance_kilometres" to "%1\$s km",
                "point_info_azimuth_degrees" to "%1\$s°",
            )
    }
}

internal fun fixAt(
    position: GeoPoint,
    timeMillis: Long = 1_792_000_000_000L,
): LocationFix =
    LocationFix(
        position = position,
        accuracyMeters = 4.0,
        altitudeMeters = null,
        bearingDegrees = null,
        speedMetersPerSecond = null,
        timeMillis = timeMillis,
    )

internal fun cameraAt(
    center: GeoPoint,
    zoom: Double = 13.0,
): Camera = Camera(center = center, zoom = zoom)
