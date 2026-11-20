package nl.r1a.grassmap.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.core.services.ButtonLayoutStore
import nl.r1a.grassmap.core.services.MapButtonLayout
import nl.r1a.grassmap.core.services.MapControlCommands
import nl.r1a.grassmap.core.services.QuickAccessButton
import nl.r1a.grassmap.plugin.api.model.ProfileId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ButtonLayoutControllerTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "button-controller-${UUID.randomUUID()}")
    private val store = ButtonLayoutStore(File(directory, "button-layout.json"))
    private val walk = ProfileId("walk")
    private val bike = ProfileId("bike")
    private val failures = mutableListOf<String>()

    @After
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    private fun TestScope.controller(profile: ProfileId): ButtonLayoutController =
        ButtonLayoutController(
            store = store,
            profile = profile,
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            onFailure = { failures += it },
        )

    @Test
    fun anEditBeforeTheLoadIsIgnoredAndTheStoredLayoutSurvives() =
        runTest {
            val controller = controller(walk)
            store.save(walk, MapButtonLayout.DEFAULT.remove(MapControlCommands.NORTH_UP))

            controller.edit { it.place(MapControlCommands.SAVED) }
            advanceUntilIdle()

            assertFalse(controller.state.value.isLoaded)
            assertFalse(controller.state.value.isEditable)
            assertEquals(MapButtonLayout.DEFAULT, controller.state.value.layout)

            controller.load()

            assertEquals(
                MapButtonLayout.DEFAULT.remove(MapControlCommands.NORTH_UP),
                controller.state.value.layout,
            )
            assertTrue(failures.isEmpty())
        }

    @Test
    fun anEditSavesTheLayoutAndARestartReadsItBack() =
        runTest {
            val controller = controller(walk)
            controller.load()

            controller.edit { current -> current.remove(MapControlCommands.NORTH_UP).place(MapControlCommands.TRACKS) }
            advanceUntilIdle()

            val expected = MapButtonLayout.DEFAULT.remove(MapControlCommands.NORTH_UP).place(MapControlCommands.TRACKS)
            assertEquals(expected, controller.state.value.layout)

            val restarted = controller(walk)
            restarted.load()
            assertEquals(expected, restarted.state.value.layout)
        }

    @Test
    fun threeProfilesKeepTheirOwnLayoutsAcrossARestart() =
        runTest {
            val walking = controller(walk)
            val cycling = controller(bike)
            walking.load()
            cycling.load()

            walking.edit { it.place(MapControlCommands.SAVED) }
            cycling.edit { it.remove(MapControlCommands.ZOOM_OUT).place(MapControlCommands.TRACKS) }
            advanceUntilIdle()

            val restartedWalking = controller(walk)
            val restartedCycling = controller(bike)
            restartedWalking.load()
            restartedCycling.load()

            assertEquals(
                MapButtonLayout.DEFAULT.place(MapControlCommands.SAVED),
                restartedWalking.state.value.layout,
            )
            assertEquals(
                MapButtonLayout.DEFAULT.remove(MapControlCommands.ZOOM_OUT).place(MapControlCommands.TRACKS),
                restartedCycling.state.value.layout,
            )
        }

    @Test
    fun theQuickAccessParametersAndTheArcSurviveARestart() =
        runTest {
            val controller = controller(walk)
            controller.load()
            val parameters = JsonObject(mapOf("point" to JsonPrimitive("home")))

            controller.edit { current ->
                current.copy(
                    quickAccess = QuickAccessButton(MapControlCommands.ROUTE_PLANNER, parameters),
                    arc = true,
                )
            }
            advanceUntilIdle()

            val restarted = controller(walk)
            restarted.load()
            assertEquals(
                MapControlCommands.ROUTE_PLANNER,
                restarted.state.value.layout.quickAccess
                    ?.command,
            )
            assertEquals(
                parameters,
                restarted.state.value.layout.quickAccess
                    ?.parameters,
            )
            assertTrue(restarted.state.value.layout.arc)
        }
}
