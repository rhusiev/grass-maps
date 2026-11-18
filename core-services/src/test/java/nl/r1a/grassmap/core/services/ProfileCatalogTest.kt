package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmap.plugin.api.model.ProfileId
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.service.ProfileValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The profile list: walk, bike and hike exist on first start (T-0601). */
class ProfileCatalogTest {
    @Test
    fun firstStartHasWalkBikeAndHikeWithTheirRouteProfiles() {
        val entries = ProfileCatalog().load()

        assertEquals(BuiltInProfiles.ALL, entries.map { it.id })
        assertEquals(listOf(RouteProfile.Walk, RouteProfile.Bike, RouteProfile.Hike), entries.map { it.routeProfile })
        assertTrue(entries.all { it.isBuiltIn })
    }

    @Test
    fun firstStartWritesTheBuiltInsAndASecondStartReadsThemBack() {
        val directory = Files.createTempDirectory("profile-catalog-").toFile()
        try {
            val file = File(directory, "profiles.json")
            val first = ProfileCatalog(ProfileValueFile(file, ProfileCatalog.SCHEMA_VERSION, "test")).load()

            assertTrue(file.isFile)
            assertEquals(first, ProfileCatalog(ProfileValueFile(file, ProfileCatalog.SCHEMA_VERSION, "test")).load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aMissingBuiltInProfileIsPutBack() {
        val directory = Files.createTempDirectory("profile-catalog-").toFile()
        try {
            val store = ProfileValueFile(File(directory, "profiles.json"), ProfileCatalog.SCHEMA_VERSION, "test")
            store.write(emptyMap())

            assertEquals(BuiltInProfiles.ALL, ProfileCatalog(store).load().map { it.id })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aRewriteKeepsUnparsableEntriesAndExtraKeys() {
        withStore { store ->
            store.write(
                mapOf(
                    ProfileId("bike") to mapOf("route_profile" to "bike", "built_in" to "true", "future_key" to "kept"),
                    ProfileId("mystery") to mapOf("route_profile" to "unicycle", "note" to "from a later build"),
                ),
            )

            val entries = ProfileCatalog(store).load()
            val stored = (store.read() as Outcome.Success).value

            assertEquals(BuiltInProfiles.ALL, entries.map { it.id })
            assertEquals("kept", stored.getValue(BuiltInProfiles.BIKE)["future_key"])
            assertEquals(
                mapOf("route_profile" to "unicycle", "note" to "from a later build"),
                stored[ProfileId("mystery")],
            )
        }
    }

    private fun withStore(block: (ProfileValueStore) -> Unit) {
        val directory = Files.createTempDirectory("profile-catalog-").toFile()
        try {
            block(ProfileValueFile(File(directory, "profiles.json"), ProfileCatalog.SCHEMA_VERSION, "test"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
