package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmap.plugin.api.model.ProfileActionKind
import nl.r1a.grassmap.plugin.api.model.ProfileId
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.service.ProfileSettingsGroup
import nl.r1a.grassmap.plugin.api.service.ProfileValueStore
import nl.r1a.grassmap.plugin.api.service.ProfileValues
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Create, copy, reset and undo, checked against the profile files on disk (T-0602). */
class ProfileSettingsServiceTest {
    private lateinit var directory: File
    private lateinit var catalogFile: ProfileValueFile
    private lateinit var routingFile: ProfileValueFile
    private lateinit var extraFile: ProfileValueFile
    private lateinit var routing: RoutingOptionsSettings
    private lateinit var extra: FileGroup
    private lateinit var service: ProfileSettingsService

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("profile-settings-").toFile()
        catalogFile = file("catalog.json")
        routingFile = file("routing.json")
        extraFile = file("extra.json")
        routing = RoutingOptionsSettings(routingFile)
        extra = FileGroup(extraFile)
        service = ProfileSettingsService(ProfileCatalog(catalogFile), listOf(routing, extra))
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun bikeCopiesToANewProfileCalledGravelAndUndoRemovesIt() {
        routing.set(BuiltInProfiles.BIKE, RouteOptions(hills = RoutePreference.Avoid, steps = RoutePreference.Avoid))
        extra.replace(BuiltInProfiles.BIKE, mapOf("mode" to "loud"))

        val state = success(service.create("Gravel", "bike"))

        val gravel = ProfileId("gravel")
        assertEquals(listOf("walk", "bike", "hike", "gravel"), state.profiles.map { it.id })
        assertEquals(ProfileActionKind.CREATED, state.undo?.kind)
        assertEquals(
            mapOf("route_profile" to "bike", "built_in" to "false", "name" to "Gravel", "copied_from" to "bike", "order" to "1"),
            stored(catalogFile).getValue(gravel),
        )
        assertEquals(stored(routingFile).getValue(BuiltInProfiles.BIKE), stored(routingFile).getValue(gravel))
        assertEquals(mapOf("mode" to "loud"), stored(extraFile)[gravel])

        val undone = success(service.undo())

        assertEquals(BuiltInProfiles.ALL.map { it.value }, undone.profiles.map { it.id })
        assertNull(undone.undo)
        assertNull(stored(catalogFile)[gravel])
        assertNull(stored(routingFile)[gravel])
        assertNull(stored(extraFile)[gravel])
        assertEquals("avoid", stored(routingFile).getValue(BuiltInProfiles.BIKE)["hills"])
    }

    @Test
    fun aSettingResetsToItsDefaultAndUndoPutsItBack() {
        routing.set(BuiltInProfiles.BIKE, RouteOptions(hills = RoutePreference.Avoid))

        val state = success(service.reset("bike", setOf(ProfileSettingsGroup.ROUTING)))

        assertEquals(ProfileActionKind.RESET, state.undo?.kind)
        assertNull(stored(routingFile)[BuiltInProfiles.BIKE])
        assertEquals(RouteOptions(), routing.forProfile(BuiltInProfiles.BIKE))

        success(service.undo())

        assertEquals("avoid", stored(routingFile).getValue(BuiltInProfiles.BIKE)["hills"])
    }

    @Test
    fun aCopiedProfileResetsToItsSourceAsItIsNow() {
        success(service.create("Gravel", "bike"))
        val gravel = ProfileId("gravel")
        routing.set(gravel, RouteOptions(steps = RoutePreference.Avoid))
        routing.set(BuiltInProfiles.BIKE, RouteOptions(busyRoads = RoutePreference.Avoid))

        val state = success(service.reset("gravel"))

        assertEquals("bike", state.undo?.source)
        assertEquals(RouteOptions(busyRoads = RoutePreference.Avoid), routing.forProfile(gravel))
        assertEquals(stored(routingFile).getValue(BuiltInProfiles.BIKE), stored(routingFile).getValue(gravel))
    }

    @Test
    fun settingsCopyFromWalkIntoAnotherProfileAndUndoRestoresTheOldOnes() {
        routing.set(BuiltInProfiles.WALK, RouteOptions(roughSurfaces = RoutePreference.Avoid))
        routing.set(BuiltInProfiles.HIKE, RouteOptions(hills = RoutePreference.Avoid))
        extra.replace(BuiltInProfiles.WALK, mapOf("mode" to "quiet"))

        val state = success(service.copySettings("walk", "hike", setOf(ProfileSettingsGroup.ROUTING)))

        assertEquals(ProfileActionKind.COPIED, state.undo?.kind)
        assertEquals(stored(routingFile).getValue(BuiltInProfiles.WALK), stored(routingFile).getValue(BuiltInProfiles.HIKE))
        assertNull(stored(extraFile)[BuiltInProfiles.HIKE])

        success(service.undo())

        assertEquals(RouteOptions(hills = RoutePreference.Avoid), routing.forProfile(BuiltInProfiles.HIKE))
        assertEquals("avoid", stored(routingFile).getValue(BuiltInProfiles.HIKE)["hills"])
    }

    @Test
    fun copyingFromADefaultGroupClearsTheTarget() {
        routing.set(BuiltInProfiles.HIKE, RouteOptions(hills = RoutePreference.Avoid))

        success(service.copySettings("walk", "hike"))

        assertNull(stored(routingFile)[BuiltInProfiles.HIKE])
    }

    @Test
    fun unknownProfilesAndGroupsAndACopyOntoItselfAreRefused() {
        assertEquals(Outcome.Failure(AppError.InvalidInput), service.copySettings("walk", "walk"))
        assertEquals(Outcome.Failure(AppError.InvalidInput), service.copySettings("walk", "bike", setOf("nope")))
        assertEquals(Outcome.Failure(AppError.NotFound), service.copySettings("walk", "nope"))
        assertEquals(Outcome.Failure(AppError.NotFound), service.reset("nope"))
        assertEquals(Outcome.Failure(AppError.NotFound), service.create("Gravel", "nope"))
        assertEquals(Outcome.Failure(AppError.NotFound), service.undo())
    }

    @Test
    fun aGroupThatFailsLeavesNoHalfCreatedProfile() {
        routing.set(BuiltInProfiles.BIKE, RouteOptions(hills = RoutePreference.Avoid))
        val broken = ProfileSettingsService(ProfileCatalog(catalogFile), listOf(routing, FailingGroup))

        assertEquals(Outcome.Failure(AppError.CorruptData), broken.create("Gravel", "bike"))

        assertEquals(BuiltInProfiles.ALL.toSet(), stored(catalogFile).keys)
        assertNull(stored(routingFile)[ProfileId("gravel")])
        assertNull(success(broken.state()).undo)
    }

    @Test
    fun onlyTheLastActionsCanBeUndone() {
        repeat(ProfileSettingsService.UNDO_LIMIT + 1) { success(service.copySettings("walk", "bike")) }

        repeat(ProfileSettingsService.UNDO_LIMIT) { success(service.undo()) }

        assertEquals(Outcome.Failure(AppError.NotFound), service.undo())
    }

    @Test
    fun theStateListsTheGroupsAndTheNamesOfUserProfiles() {
        val state = success(service.create("  Gravel  ", "walk"))

        assertEquals(listOf("routing", "extra"), state.groups)
        assertEquals("Gravel", state.profiles.last().name)
        assertEquals("walk", state.profiles.last().copiedFrom)
        assertTrue(state.profiles.take(BuiltInProfiles.ALL.size).all { it.builtIn && it.name == null })
    }

    private fun file(name: String) = ProfileValueFile(File(directory, name), ProfileCatalog.SCHEMA_VERSION, "test")

    private fun stored(store: ProfileValueStore): ProfileValues = success(store.read())

    private fun <T> success(outcome: Outcome<T>): T = (outcome as Outcome.Success).value

    private class FileGroup(
        private val store: ProfileValueStore,
    ) : ProfileSettingsGroup {
        override val id: String = "extra"

        override fun read(profile: ProfileId): Map<String, String> = (store.read() as Outcome.Success).value[profile].orEmpty()

        override fun replace(
            profile: ProfileId,
            values: Map<String, String>,
        ): Outcome<Unit> {
            val all = (store.read() as Outcome.Success).value
            return store.write(if (values.isEmpty()) all - profile else all + (profile to values))
        }
    }

    private object FailingGroup : ProfileSettingsGroup {
        override val id: String = "failing"

        override fun read(profile: ProfileId): Map<String, String> = emptyMap()

        override fun replace(
            profile: ProfileId,
            values: Map<String, String>,
        ): Outcome<Unit> = Outcome.Failure(AppError.CorruptData)
    }
}
