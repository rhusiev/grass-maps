package nl.r1a.grassmap.control

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.contract.WidgetId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.manifest.GeneratedStream
import nl.r1a.grassmap.plugin.api.manifest.LocationNeed
import nl.r1a.grassmap.plugin.api.manifest.PluginContributions
import nl.r1a.grassmap.plugin.api.manifest.PluginFactory
import nl.r1a.grassmap.plugin.api.manifest.PluginManifest
import nl.r1a.grassmap.plugin.api.manifest.PluginNeeds
import nl.r1a.grassmap.plugin.api.manifest.StreamDeclaration
import nl.r1a.grassmap.plugin.api.manifest.WidgetDeclaration
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.service.LocationService
import nl.r1a.grassmap.plugins.info.widgets.InfoWidgetsContract
import nl.r1a.grassmap.plugins.info.widgets.InfoWidgetsPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Evidence that the three widget streams answer `control.stream.subscribe` and
 * update while the committed 1 Hz mock walk replays. It drives the real control
 * session and the real plugin over the real thresholds; the position source is
 * the committed GPX, and the camera and the DEM are the harness's stand-ins
 * because the app wiring for those is out of this task's scope.
 */
class InfoWidgetsControlEvidenceTest {
    @Test
    fun widgetStreamsUpdateOverTheControlChannelDuringAOneHertzWalk() =
        runBlocking {
            val harness =
                Harness(
                    commands = emptyList(),
                    streams = PluginHostControlStreams(pluginHost(WalkSource())),
                )
            val session = launch { harness.session.run() }
            harness.authenticate()

            val pending = ArrayDeque<JsonObject>()
            val subscriptions =
                listOf(
                    InfoWidgetsContract.altitudeStream,
                    InfoWidgetsContract.coordinatesStream,
                    InfoWidgetsContract.gpsStream,
                ).associateWith { streamId -> subscribe(harness, streamId, pending) }

            val samples = mutableMapOf<String, JsonObject>()
            val fixes = mutableSetOf<String>()
            withTimeout(WALK_TIMEOUT_MILLIS) {
                while (!complete(samples, fixes)) {
                    val notification = if (pending.isEmpty()) harness.nextObject() else pending.removeFirst()
                    val params = notification["params"]?.jsonObject ?: continue
                    val stream = params["stream"]?.jsonPrimitive?.content ?: continue
                    val value = params["value"]?.jsonObject ?: continue
                    println("control.stream.data stream=$stream value=$value")
                    samples[stream] = value
                    if (stream == InfoWidgetsContract.COORDINATES_STREAM_ID) {
                        (value["fix"] as? JsonObject)?.let { fix -> fixes += fix.toString() }
                    }
                }
            }

            assertTrue(subscriptions.values.all { it > 0 })
            val altitude = samples.getValue(InfoWidgetsContract.ALTITUDE_STREAM_ID)
            assertEquals("Dem", altitude["source"]?.jsonPrimitive?.content)
            assertEquals(
                TERRAIN_METERS,
                altitude["altitudeMeters"]?.jsonPrimitive?.content?.toDouble() ?: Double.NaN,
                ORACLE_TOLERANCE_METERS,
            )
            val coordinates = samples.getValue(InfoWidgetsContract.COORDINATES_STREAM_ID)
            assertTrue(coordinates["fix"] is JsonObject)
            assertTrue(coordinates["mapCenter"] is JsonObject)
            val gps = samples.getValue(InfoWidgetsContract.GPS_STREAM_ID)
            assertEquals(5.0, gps["accuracyMeters"]?.jsonPrimitive?.content?.toDouble() ?: 0.0, 0.0)
            session.cancel()
        }

    /** True once every stream has shown a value and the fix has moved. */
    private fun complete(
        samples: Map<String, JsonObject>,
        fixes: Set<String>,
    ): Boolean {
        val altitude = samples[InfoWidgetsContract.ALTITUDE_STREAM_ID] ?: return false
        val coordinates = samples[InfoWidgetsContract.COORDINATES_STREAM_ID] ?: return false
        val gps = samples[InfoWidgetsContract.GPS_STREAM_ID] ?: return false
        return altitude["source"]?.jsonPrimitive?.content == "Dem" &&
            coordinates["fix"] is JsonObject &&
            coordinates["mapCenter"] is JsonObject &&
            gps["accuracyMeters"] != null &&
            fixes.size >= MINIMUM_DISTINCT_FIXES
    }

    private suspend fun subscribe(
        harness: Harness,
        streamId: StreamId,
        pending: ArrayDeque<JsonObject>,
    ): Long {
        harness.send(
            id = NEXT_ID.getAndIncrement(),
            method = ControlSession.METHOD_SUBSCRIBE,
            params = buildJsonObject { put(ControlSession.STREAM_PARAM, streamId.value) },
        )
        while (true) {
            val frame = harness.nextObject()
            val subscription = frame["result"]?.jsonObject?.get("subscription")
            if (subscription != null) {
                return subscription.jsonPrimitive.long
            }
            pending.addLast(frame)
        }
    }

    private fun pluginHost(fixes: LocationService): PluginHost {
        val declarations = manifest()
        return PluginHost(
            plugins = listOf(PluginFactory(manifest = declarations) { InfoWidgetsPlugin() }),
            streams =
                declarations.contributes.streams.map { stream ->
                    GeneratedStream(
                        id = stream.id,
                        pluginId = PluginId(InfoWidgetsContract.PLUGIN_ID),
                        valueType = stream.valueType,
                        serializer = stream.serializer,
                    )
                },
            commands = emptyList(),
            locationService = fixes,
            elevationAccess = elevation(),
            cameraFlow =
                flow {
                    emit(Camera(center = CAMERA_CENTRE, zoom = 14.0))
                },
            clockMillis = { System.currentTimeMillis() },
        )
    }

    private fun manifest(): PluginManifest =
        PluginManifest(
            id = PluginId(InfoWidgetsContract.PLUGIN_ID),
            name = "plugin.info_widgets.name",
            description = "plugin.info_widgets.description",
            version = "0.1.0",
            api = "0.1.0",
            implementation = "nl.r1a.grassmap.plugins.info.widgets.InfoWidgetsPlugin",
            contributes =
                PluginContributions(
                    streams =
                        listOf(
                            stream(InfoWidgetsContract.altitudeStream, InfoWidgetsContract.ALTITUDE_VALUE_TYPE),
                            stream(InfoWidgetsContract.coordinatesStream, InfoWidgetsContract.COORDINATES_VALUE_TYPE),
                            stream(InfoWidgetsContract.gpsStream, InfoWidgetsContract.GPS_VALUE_TYPE),
                        ),
                    widgets =
                        listOf(
                            widget(
                                InfoWidgetsContract.altitudeStream,
                                InfoWidgetsContract.ALTITUDE_VALUE_TYPE,
                                InfoWidgetsContract.ALTITUDE_TITLE,
                            ),
                            widget(
                                InfoWidgetsContract.coordinatesStream,
                                InfoWidgetsContract.COORDINATES_VALUE_TYPE,
                                InfoWidgetsContract.COORDINATES_TITLE,
                            ),
                            widget(
                                InfoWidgetsContract.gpsStream,
                                InfoWidgetsContract.GPS_VALUE_TYPE,
                                InfoWidgetsContract.GPS_TITLE,
                            ),
                        ),
                ),
            needs =
                PluginNeeds(
                    location = LocationNeed.WHILE_IN_USE,
                    regions = listOf("elevation"),
                ),
            activation = listOf("stream"),
        )

    private fun stream(
        id: StreamId,
        valueType: String,
    ): StreamDeclaration =
        StreamDeclaration(
            id = id,
            valueType = valueType,
            serializer = "$valueType.serializer",
        )

    private fun widget(
        id: StreamId,
        valueType: String,
        title: String,
    ): WidgetDeclaration =
        WidgetDeclaration(
            id = WidgetId(id.value),
            title = title,
            valueType = valueType,
            streamId = id,
            updateIntervalMillis = InfoWidgetsContract.UPDATE_INTERVAL_MILLIS,
        )

    /** The harness DEM: a flat 301.5 m over the walk, distinct from every fix altitude. */
    private fun elevation(): ElevationAccess =
        ElevationAccess { position ->
            if (WALK_BOUNDS.contains(position)) {
                Outcome.Success(TERRAIN_METERS)
            } else {
                Outcome.Success(null)
            }
        }

    /** Replays the committed mock walk, one fix per second. */
    private class WalkSource : LocationService {
        override fun hasPermission(): Boolean = true

        override fun requiresPermission(): Boolean = false

        override val fixes: Flow<LocationFix?> =
            flow {
                val points = parseWalk(FILE)
                if (points.isEmpty()) {
                    emit(null)
                    return@flow
                }
                var index = 0
                while (true) {
                    emit(points[index])
                    index = (index + 1) % points.size
                    delay(TICK_MILLIS)
                }
            }
    }

    private companion object {
        const val WALK_TIMEOUT_MILLIS = 15_000L
        const val TICK_MILLIS = 1_000L
        const val MINIMUM_DISTINCT_FIXES = 2
        const val TERRAIN_METERS = 301.5
        const val ORACLE_TOLERANCE_METERS = 0.5
        val NEXT_ID = AtomicLong(10L)
        val CAMERA_CENTRE = GeoPoint(latitude = 49.8400, longitude = 24.0300)
        val WALK_BOUNDS = WalkBounds(south = 49.83, west = 24.02, north = 49.86, east = 24.05)
        val FILE = File("../test-data/location/mock-walk.gpx")

        private val TRACK_POINT =
            Regex("""<trkpt lat="([-0-9.]+)" lon="([-0-9.]+)">.*?<ele>([-0-9.]+)</ele>""")

        fun parseWalk(file: File): List<LocationFix> =
            TRACK_POINT
                .findAll(file.readText())
                .mapIndexedNotNull { index, match ->
                    val latitude = match.groupValues[1].toDoubleOrNull() ?: return@mapIndexedNotNull null
                    val longitude = match.groupValues[2].toDoubleOrNull() ?: return@mapIndexedNotNull null
                    val altitude = match.groupValues[3].toDoubleOrNull() ?: return@mapIndexedNotNull null
                    LocationFix(
                        position = GeoPoint(latitude, longitude),
                        accuracyMeters = 5.0,
                        altitudeMeters = altitude,
                        speedMetersPerSecond = 1.2,
                        bearingDegrees = 90.0,
                        timeMillis = 1_792_000_000_000L + index * TICK_MILLIS,
                    )
                }.toList()
    }

    private class WalkBounds(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double,
    ) {
        fun contains(point: GeoPoint): Boolean = point.latitude in south..north && point.longitude in west..east
    }
}
