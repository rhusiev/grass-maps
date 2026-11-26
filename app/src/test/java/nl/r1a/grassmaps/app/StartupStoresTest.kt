package nl.r1a.grassmaps.app

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.core.services.DEFAULT_PROFILE
import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The map screen builds these stores during its first composition, on the
 * Main thread. On Android every file provider calls `Context.getFilesDir`,
 * which touches the disk, so building a store must not call its provider.
 * The plugin host is built before, on IO, by the map bundle load.
 */
class StartupStoresTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val calls = mutableListOf<String>()

    @Test
    fun theMapScreenStoresCallNoFileProviderWhenTheyAreBuilt() {
        val container = recordingContainer(folder.newFolder())
        container.providerRegistry
        calls.clear()

        container.tracks
        container.trackLayer
        container.favouritesLayer
        container.favouritesLayerSettings
        container.buttonLayoutController(DEFAULT_PROFILE)

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun theLoadsOffMainReadTheStoresThroughTheirProviders() =
        runTest {
            val container = recordingContainer(folder.newFolder())
            container.providerRegistry
            calls.clear()

            container.tracks.reload()
            container.favouritesLayerSettings.ensureLoaded()
            container.buttonLayoutController(DEFAULT_PROFILE).load()

            assertEquals(setOf(TRACKS, FAVOURITES_LAYER, USER_DATA), calls.toSet())
        }

    private fun recordingContainer(root: File): AppContainer =
        AppContainer(
            strings = KeyOnlyStrings,
            tracksRootProvider = { record(TRACKS, File(root, TRACKS)) },
            favouritesLayerSettingsFileProvider = { record(FAVOURITES_LAYER, File(root, FAVOURITES_LAYER)) },
            userDataRootProvider = { record(USER_DATA, root) },
        )

    private fun record(
        name: String,
        file: File,
    ): File {
        calls += name
        return file
    }

    private object KeyOnlyStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = key

        override fun contains(key: String): Boolean = false
    }

    private companion object {
        const val TRACKS = "tracks"
        const val FAVOURITES_LAYER = "favourites-layer.json"
        const val USER_DATA = "user-data"
    }
}
