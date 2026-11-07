package nl.r1a.grassmap.app

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.core.CommandDispatchers
import nl.r1a.grassmap.core.services.MapControlCommands
import nl.r1a.grassmap.core.services.NoLocationSource
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.CommandInvoker
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.TrackingMode
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapCommandsTest {
    @Test
    fun commandsReturnNotReadyBeforeABinding() =
        runTest {
            val session = MapSessionHolder()

            for (command in commands(session)) {
                assertEquals(Outcome.Failure(AppError.NotReady), command.invokeRaw(JsonNull))
            }
        }

    @Test
    fun zoomInMovesTheCameraByOneAndOnlyChangesZoom() =
        runTest {
            val session = MapSessionHolder()
            val host = FakeMapHost(Camera(center = ORIGIN, zoom = 10.0))
            session.bind(host)

            val result = zoomIn(session).execute(Unit)

            assertEquals(Outcome.Success(CommandResult(11.0)), result)
            assertEquals(listOf(CameraUpdate(zoom = 11.0)), host.animations)
        }

    @Test
    fun zoomOutMovesTheCameraByOne() =
        runTest {
            val session = MapSessionHolder()
            val host = FakeMapHost(Camera(center = ORIGIN, zoom = 10.0))
            session.bind(host)

            val result = zoomOut(session).execute(Unit)

            assertEquals(Outcome.Success(CommandResult(9.0)), result)
            assertEquals(listOf(CameraUpdate(zoom = 9.0)), host.animations)
        }

    @Test
    fun zoomIsClampedAtBothEnds() =
        runTest {
            val session = MapSessionHolder()

            val low = FakeMapHost(Camera(center = ORIGIN, zoom = 1.0))
            session.bind(low)
            assertEquals(Outcome.Success(CommandResult(1.0)), zoomOut(session).execute(Unit))

            val high = FakeMapHost(Camera(center = ORIGIN, zoom = 20.0))
            session.bind(high)
            assertEquals(Outcome.Success(CommandResult(20.0)), zoomIn(session).execute(Unit))

            val nearHigh = FakeMapHost(Camera(center = ORIGIN, zoom = 19.5))
            session.bind(nearHigh)
            assertEquals(Outcome.Success(CommandResult(20.0)), zoomIn(session).execute(Unit))
        }

    @Test
    fun northUpSetsBearingToZeroAndKeepsOtherFields() =
        runTest {
            val session = MapSessionHolder()
            val host =
                FakeMapHost(
                    Camera(center = ORIGIN, zoom = 12.0, bearing = 42.0, tilt = 15.0),
                )
            session.bind(host)

            val result = NorthUpCommand(session).execute(Unit)

            assertEquals(Outcome.Success(CommandResult(0.0)), result)
            assertEquals(listOf(CameraUpdate(bearing = 0.0)), host.moves)
        }

    @Test
    fun followTogglesTrackingModeAndReturnsTheNewState() =
        runTest {
            val session = MapSessionHolder()
            val host = FakeMapHost(Camera(center = ORIGIN, zoom = 10.0))
            session.bind(host)
            val controller = LocationController(NoLocationSource, session, this)
            val command = FollowCommand(controller)

            assertEquals(Outcome.Success(CommandResult(true)), command.execute(Unit))
            assertEquals(Outcome.Success(CommandResult(false)), command.execute(Unit))

            assertEquals(listOf(TrackingMode.Follow, TrackingMode.Free), host.trackingModes)
        }

    @Test
    fun followWithoutABoundHostIsNotReady() =
        runTest {
            val session = MapSessionHolder()
            val command = FollowCommand(LocationController(NoLocationSource, session, this))

            assertEquals(Outcome.Failure(AppError.NotReady), command.execute(Unit))
        }

    @Test
    fun aDeadHostReturnsNotReady() =
        runTest {
            val session = MapSessionHolder()
            val host = FakeMapHost(Camera(center = ORIGIN, zoom = 10.0))
            session.bind(host)
            session.unbind(host)

            assertEquals(Outcome.Failure(AppError.NotReady), zoomIn(session).execute(Unit))
        }

    @Test
    fun unbindOnlyClearsTheSameHost() =
        runTest {
            val session = MapSessionHolder()
            val first = FakeMapHost(Camera(center = ORIGIN, zoom = 10.0))
            val second = FakeMapHost(Camera(center = ORIGIN, zoom = 10.0))
            session.bind(first)
            session.bind(second)

            session.unbind(first)

            assertEquals(second, session.hostOrNull())
        }

    @Test
    fun theBusDispatchesHostCommandsThroughTheInvoker() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val container =
                AppContainer(
                    strings = KeyOnlyStrings,
                    dispatchers = CommandDispatchers(main = dispatcher, default = dispatcher, io = dispatcher),
                )
            val host = FakeMapHost(Camera(center = ORIGIN, zoom = 10.0, bearing = 30.0))
            container.mapSession.bind(host)
            val invoker: CommandInvoker = container.commandBus

            assertEquals(
                Outcome.Success(JsonPrimitive(11.0)),
                invoker.invoke(MapControlCommands.ZOOM_IN),
            )
            assertEquals(
                Outcome.Success(JsonPrimitive(10.0)),
                invoker.invoke(MapControlCommands.ZOOM_OUT),
            )
            assertEquals(
                Outcome.Success(JsonPrimitive(0.0)),
                invoker.invoke(MapControlCommands.NORTH_UP),
            )
            assertEquals(
                Outcome.Success(JsonPrimitive(true)),
                invoker.invoke(MapControlCommands.FOLLOW),
            )
            assertEquals(0.0, host.camera.value.bearing, 0.0)
            assertNull(container.pluginHost.statusOf(PluginId("core")))
        }

    @Test
    fun theBusReturnsNotReadyWithoutABinding() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val container =
                AppContainer(
                    strings = KeyOnlyStrings,
                    dispatchers = CommandDispatchers(main = dispatcher, default = dispatcher, io = dispatcher),
                )

            assertEquals(
                Outcome.Failure(AppError.NotReady),
                container.commandBus.invoke(MapControlCommands.ZOOM_IN),
            )
        }

    @Test
    fun moveControlSwapsWithTheNeighbour() {
        val layout = MapControlCommands.DEFAULT_ORDER

        assertEquals(
            listOf(
                MapControlCommands.ZOOM_OUT,
                MapControlCommands.ZOOM_IN,
                MapControlCommands.NORTH_UP,
                MapControlCommands.FOLLOW,
            ),
            moveControl(layout, MapControlCommands.ZOOM_IN, 1),
        )
    }

    @Test
    fun moveControlKeepsTheOrderAtTheEnds() {
        val layout = MapControlCommands.DEFAULT_ORDER

        assertEquals(layout, moveControl(layout, MapControlCommands.ZOOM_IN, -1))
        assertEquals(layout, moveControl(layout, MapControlCommands.FOLLOW, 1))
        assertEquals(layout, moveControl(layout, CommandId("core.map.missing"), 1))
    }

    private fun TestScope.commands(session: MapSessionHolder): List<Command<*, *>> =
        mapCommands(session, LocationController(NoLocationSource, session, backgroundScope))

    private fun zoomIn(session: MapSessionHolder): Command<Unit, Double> = ZoomCommand(session, ZOOM_STEP)

    private fun zoomOut(session: MapSessionHolder): Command<Unit, Double> = ZoomCommand(session, -ZOOM_STEP)

    private object KeyOnlyStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = key

        override fun contains(key: String): Boolean = false
    }

    private companion object {
        val ORIGIN = GeoPoint(latitude = 49.84, longitude = 24.03)
    }
}
