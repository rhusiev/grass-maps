package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
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
}
