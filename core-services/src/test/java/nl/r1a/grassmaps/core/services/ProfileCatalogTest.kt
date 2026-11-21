package nl.r1a.grassmaps.core.services

import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugin.api.service.ProfileValueStore
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

    @Test
    fun creatingAProfileKeepsWhatTheCatalogCannotParse() {
        withStore { store ->
            val catalog = ProfileCatalog(store)
            catalog.load()
            store.write((store.read() as Outcome.Success).value + (ProfileId("mystery") to mapOf("route_profile" to "unicycle")))

            val created = (catalog.create("Gravel", BuiltInProfiles.BIKE) as Outcome.Success).value
            val stored = (store.read() as Outcome.Success).value

            assertEquals(ProfileId("gravel"), created.id)
            assertEquals(RouteProfile.Bike, created.routeProfile)
            assertEquals(BuiltInProfiles.BIKE, created.copiedFrom)
            assertEquals(mapOf("route_profile" to "unicycle"), stored[ProfileId("mystery")])
            assertEquals(listOf("walk", "bike", "hike", "gravel"), catalog.load().map { it.id.value })
        }
    }

    @Test
    fun namesBecomeUniqueIdsAndAreKeptInCreationOrder() {
        val catalog = ProfileCatalog()

        val first = (catalog.create("Gravel", BuiltInProfiles.BIKE) as Outcome.Success).value
        val second = (catalog.create("Gravel", BuiltInProfiles.BIKE) as Outcome.Success).value
        val third = (catalog.create("  !! ", BuiltInProfiles.WALK) as Outcome.Success).value

        assertEquals(listOf("gravel", "gravel-2", "profile"), listOf(first, second, third).map { it.id.value })
        assertEquals("Gravel", second.name)
        assertEquals(listOf("walk", "bike", "hike", "gravel", "gravel-2", "profile"), catalog.load().map { it.id.value })
    }

    @Test
    fun userProfilesKeepTheirCreationOrderEvenThoughTheFileIsSortedById() {
        withStore { store ->
            val catalog = ProfileCatalog(store)
            catalog.create("Zed", BuiltInProfiles.WALK)
            catalog.create("Alpha", BuiltInProfiles.WALK)

            assertEquals(listOf("walk", "bike", "hike", "zed", "alpha"), ProfileCatalog(store).load().map { it.id.value })
        }
    }

    @Test
    fun aBlankOrOverlongNameOrAnUnknownSourceIsRefused() {
        val catalog = ProfileCatalog()

        assertEquals(Outcome.Failure(AppError.InvalidInput), catalog.create("   ", BuiltInProfiles.BIKE))
        assertEquals(
            Outcome.Failure(AppError.InvalidInput),
            catalog.create("x".repeat(ProfileCatalog.NAME_MAX_LENGTH + 1), BuiltInProfiles.BIKE),
        )
        assertEquals(Outcome.Failure(AppError.NotFound), catalog.create("Gravel", ProfileId("nope")))
    }

    @Test
    fun removeDeletesAUserProfileAndRefusesABuiltInOne() {
        val catalog = ProfileCatalog()
        val gravel = (catalog.create("Gravel", BuiltInProfiles.BIKE) as Outcome.Success).value

        assertEquals(Outcome.Failure(AppError.InvalidInput), catalog.remove(BuiltInProfiles.BIKE))
        assertEquals(Outcome.Success(Unit), catalog.remove(gravel.id))
        assertEquals(BuiltInProfiles.ALL, catalog.load().map { it.id })
        assertEquals(Outcome.Failure(AppError.NotFound), catalog.remove(gravel.id))
    }

    @Test
    fun upsertRestoresAUserProfileWithItsIdAndOrder() {
        withStore { store ->
            val source = ProfileCatalog(store)
            val gravel = (source.create("Gravel", BuiltInProfiles.BIKE) as Outcome.Success).value
            val packing = (source.create("Packing", BuiltInProfiles.WALK) as Outcome.Success).value
            val copied = source.load().filter { entry -> !entry.isBuiltIn }

            withStore { empty ->
                val restored = ProfileCatalog(empty)
                copied.forEach { entry ->
                    assertEquals(Outcome.Success(Unit), restored.upsert(entry))
                }
                assertEquals(copied, restored.load().filter { entry -> !entry.isBuiltIn })
            }

            assertEquals(listOf(gravel.id, packing.id), copied.map { entry -> entry.id })
            assertTrue(copied.all { entry -> entry.order > 0L })
        }
    }

    @Test
    fun upsertKeepsWhatItDoesNotKnowAndRefusesABuiltInChange() {
        withStore { store ->
            store.write(
                mapOf(
                    ProfileId("bike") to mapOf("route_profile" to "bike", "built_in" to "true", "future_key" to "kept"),
                    ProfileId("mystery") to mapOf("route_profile" to "unicycle", "note" to "from a later build"),
                ),
            )
            val catalog = ProfileCatalog(store)

            assertEquals(
                Outcome.Success(Unit),
                catalog.upsert(ProfileEntry(ProfileId("gravel"), RouteProfile.Bike, false, name = "Gravel")),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                catalog.upsert(ProfileEntry(BuiltInProfiles.WALK, RouteProfile.Bike, isBuiltIn = true)),
            )

            val stored = (store.read() as Outcome.Success).value
            assertEquals("kept", stored.getValue(ProfileId("bike"))["future_key"])
            assertEquals("from a later build", stored.getValue(ProfileId("mystery"))["note"])
            assertTrue(stored.containsKey(ProfileId("gravel")))
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
