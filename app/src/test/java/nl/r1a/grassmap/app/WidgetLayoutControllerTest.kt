package nl.r1a.grassmaps.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugin.api.service.ProfileValueStore
import nl.r1a.grassmaps.plugin.api.service.ProfileValues
import nl.r1a.grassmaps.ui.shell.GridWidget
import nl.r1a.grassmaps.ui.shell.WidgetLayout
import nl.r1a.grassmaps.ui.shell.WidgetLayoutStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetLayoutControllerTest {
    private val walk = ProfileId("walk")
    private val bike = ProfileId("bike")
    private val failures = mutableListOf<String>()

    private fun TestScope.controller(
        backing: FakeStore,
        scope: CoroutineScope = this,
    ) = WidgetLayoutController(
        store = WidgetLayoutStore(backing),
        profile = walk,
        scope = scope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        onFailure = { failures += it },
    )

    @Test
    fun anEditBeforeTheLoadIsIgnoredAndTheStoredLayoutSurvives() =
        runTest {
            val backing = FakeStore(mapOf(walk to mapOf("widgets" to "coordinates")))
            val controller = controller(backing)

            controller.edit { it.add(GridWidget.SpeedMax) }
            testScheduler.advanceUntilIdle()

            assertEquals(WidgetLoadPhase.Loading, controller.state.value.phase)
            assertEquals(0, backing.writes)

            controller.load()
            assertEquals(WidgetLayout(listOf(GridWidget.Coordinates)), controller.state.value.layout)
            assertEquals(WidgetLoadPhase.Ready, controller.state.value.phase)
            assertEquals(0, backing.writes)
        }

    @Test
    fun anEditSavesTheLayoutItProducedBeforeAnythingElseRuns() =
        runTest {
            val backing = FakeStore()
            val controller = controller(backing)
            controller.load()

            controller.edit { it.remove(GridWidget.Altitude) }
            controller.edit { it.add(GridWidget.SpeedMax) }
            testScheduler.advanceUntilIdle()

            val expected = "gps_accuracy,selected_point,speed_max"
            assertEquals(expected, backing.values[walk]?.get("widgets"))
        }

    @Test
    fun aFailedLoadBlocksEditsWritesNothingAndCanBeRetried() =
        runTest {
            val backing = FakeStore(mapOf(bike to mapOf("widgets" to "altitude")), failure = AppError.PermissionDenied)
            val controller = controller(backing)

            controller.load()
            controller.edit { it.add(GridWidget.SpeedMax) }
            testScheduler.advanceUntilIdle()

            assertEquals(WidgetLoadPhase.Failed, controller.state.value.phase)
            assertEquals(WidgetLayout.DEFAULT, controller.state.value.layout)
            assertEquals(0, backing.writes)
            assertEquals(1, failures.size)

            backing.failure = null
            controller.load()
            controller.edit { it.add(GridWidget.SpeedMax) }
            testScheduler.advanceUntilIdle()

            assertEquals(WidgetLoadPhase.Ready, controller.state.value.phase)
            assertEquals("altitude", backing.values[bike]?.get("widgets"))
            assertTrue(backing.values[walk]?.get("widgets")?.endsWith("speed_max") == true)
        }

    @Test
    fun aSaveRequestedBeforeTheScreenLeavesStillFinishes() {
        val backing = FakeStore(blocksWrites = true)
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller =
            WidgetLayoutController(
                store = WidgetLayoutStore(backing),
                profile = walk,
                scope = processScope,
                ioDispatcher = Dispatchers.Default,
                onFailure = { failures += it },
            )
        try {
            runTest { controller.load() }
            val edited: Job = screenScope.launch { controller.edit { it.remove(GridWidget.Altitude) } }
            assertTrue(backing.writeStarted.await(AWAIT_S, TimeUnit.SECONDS))
            screenScope.cancel()
            assertTrue(edited.isCompleted)
            backing.releaseWrite.countDown()
            assertTrue(backing.writeFinished.await(AWAIT_S, TimeUnit.SECONDS))
            assertEquals("gps_accuracy,selected_point", backing.values[walk]?.get("widgets"))
        } finally {
            backing.releaseWrite.countDown()
            processScope.cancel()
        }
    }

    private class FakeStore(
        var values: ProfileValues = emptyMap(),
        var failure: AppError? = null,
        private val blocksWrites: Boolean = false,
    ) : ProfileValueStore {
        @Volatile var writes = 0
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val writeFinished = CountDownLatch(1)

        override fun read(): Outcome<ProfileValues> = failure?.let { Outcome.Failure(it) } ?: Outcome.Success(values)

        override fun write(values: ProfileValues): Outcome<Unit> {
            writes += 1
            if (blocksWrites) {
                writeStarted.countDown()
                releaseWrite.await(AWAIT_S, TimeUnit.SECONDS)
            }
            this.values = values
            writeFinished.countDown()
            return Outcome.Success(Unit)
        }
    }

    private companion object {
        const val AWAIT_S = 10L
    }
}
