package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.host.PluginServices
import nl.r1a.grassmap.plugin.api.host.StreamAccess
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.host.TrackLibraryAccess
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.TrackLibraryState
import nl.r1a.grassmap.plugin.api.provider.Provider
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.LocationService
import nl.r1a.grassmap.plugin.api.widget.WidgetDescriptor
import nl.r1a.grassmap.plugins.track.library.TrackFolderStore
import nl.r1a.grassmap.plugins.track.library.TrackLibraryContract
import nl.r1a.grassmap.plugins.track.library.TrackLibraryPlugin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A share-sheet import reaches the library screen that is already running (T-0416).
 *
 * The import writes the track through [TrackAccess] with no library action, so
 * the plugin's cached state must follow the store rather than keep the snapshot
 * it read at activation. The test activates the real plugin over the real file
 * store, imports through the real share path, and reads the one state stream the
 * screen binds - without recreating the store, the library access or the plugin.
 *
 * The activity's part stays the device check: this drives the same import entry
 * point with the reader seam, exactly as [TrackShareImportTest] does.
 */
class TrackShareLibraryRefreshTest {
    private val roots = mutableListOf<File>()

    @After
    fun deleteRoots() {
        roots.forEach { root -> root.deleteRecursively() }
    }

    @Test
    fun aShareImportReachesTheOpenLibraryWithoutReopeningIt() =
        runBlocking {
            val root = temporaryRoot()
            val tracks = trackAccess(root)
            val library = StoreTrackLibrary({ tracks }, TrackFolderStore(File(root, LIBRARY_INDEX)))
            val context = LibraryContext(LibraryServices(library))
            val plugin = TrackLibraryPlugin()
            plugin.activate(context)
            val state = context.stateStream()
            try {
                assertTrue("the screen starts on an empty library", state.value.tracks.isEmpty())

                val shared = TrackShareImport(tracks = { tracks }, log = {})
                requireNotNull(shared.import(SHARE_URI, reader()))

                val visible =
                    withTimeout(REFRESH_TIMEOUT_MILLIS) {
                        state.first { libraryState -> libraryState.tracks.isNotEmpty() }
                    }
                assertEquals(TRACK_NAME, visible.tracks.single().name)
                assertEquals(1, tracks.summaries().size)
            } finally {
                plugin.deactivate()
            }
        }

    private fun reader(): SharedDocumentReader =
        SharedDocumentReader { _ ->
            Outcome.Success(
                SharedDocument(
                    displayName = DISPLAY_NAME,
                    mimeType = null,
                    bytes = gpxBytes(),
                ),
            )
        }

    private fun gpxBytes(): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE_RESOURCE)) {
            "missing test resource $FIXTURE_RESOURCE"
        }.use { it.readBytes() }

    private fun trackAccess(root: File): TrackAccess =
        TrackAccess(
            registry =
                ProviderRegistry(
                    PluginHost(
                        plugins = GeneratedPlugins.plugins,
                        streams = emptyList(),
                        commands = emptyList(),
                        dispatcher = Dispatchers.Unconfined,
                    ),
                ),
            providers = GeneratedPlugins.providers,
            tracksRoot = root,
        )

    private fun temporaryRoot(): File = Files.createTempDirectory("track-share-refresh").toFile().also { root -> roots += root }

    private companion object {
        const val SHARE_URI = "content://com.example.share/1"
        const val DISPLAY_NAME = "mock-walk.gpx"
        const val TRACK_NAME = "Mock walk around Lviv"
        const val FIXTURE_RESOURCE = "location/mock-walk.gpx"
        const val LIBRARY_INDEX = ".library.json"
        const val REFRESH_TIMEOUT_MILLIS = 5_000L
    }
}

/** The activation sink, keeping the one stream the screen binds. */
private class LibraryContext(
    override val services: PluginServices,
) : PluginContext {
    override val pluginId: PluginId = PluginId(TrackLibraryContract.PLUGIN_ID)

    private val streams = mutableListOf<StreamDescriptor<*>>()

    override fun addCommand(command: Command<*, *>) = Unit

    override fun addStream(stream: StreamDescriptor<*>) {
        streams += stream
    }

    override fun addProvider(provider: Provider) = Unit

    override fun addWidget(widget: WidgetDescriptor) = Unit

    @Suppress("UNCHECKED_CAST")
    fun stateStream(): StateFlow<TrackLibraryState> =
        streams.single { stream -> stream.id == TrackLibraryContract.stateStream }.flow as StateFlow<TrackLibraryState>
}

/** The host inputs activation reads; only the library access matters here. */
private class LibraryServices(
    override val trackLibrary: TrackLibraryAccess,
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
