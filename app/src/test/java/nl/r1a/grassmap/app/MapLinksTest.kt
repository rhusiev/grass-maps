package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugins.links.LinkFailure
import nl.r1a.grassmap.plugins.links.LinkResult
import nl.r1a.grassmap.plugins.map.controls.CameraMode
import nl.r1a.grassmap.plugins.map.controls.CameraModeController
import nl.r1a.grassmap.plugins.map.controls.CameraModeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link-to-camera seam (T-0211). The Activity submits a URI before a surface
 * binds, and the map screen hands the parked move to the host exactly once.
 */
class MapLinksTest {
    @Test
    fun aGeoLinkMovesTheBoundCameraToTheParsedPoint() =
        runTest {
            val session = MapSessionHolder()
            val host = FakeMapHost(CAMERA)
            session.bind(host)
            val links = links(session)

            val result = links.submit("geo:49.821740,24.023910")
            links.applyPending(host)

            assertTrue(result is LinkResult.Success)
            assertEquals(
                listOf(CameraUpdate(center = GeoPoint(49.821740, 24.023910), zoom = 16.0)),
                host.moves,
            )
            assertNull(links.pendingMove)
        }

    @Test
    fun theMoveWaitsUntilASurfaceBinds() =
        runTest {
            val session = MapSessionHolder()
            val host = FakeMapHost(CAMERA)
            val links = links(session)

            links.submit("geo:50.4501,30.5234")
            assertTrue(host.moves.isEmpty())
            assertEquals(
                CameraUpdate(center = GeoPoint(50.4501, 30.5234), zoom = 16.0),
                links.pendingMove,
            )

            session.bind(host)
            links.applyPending(host)

            assertEquals(listOf(CameraUpdate(center = GeoPoint(50.4501, 30.5234), zoom = 16.0)), host.moves)
        }

    @Test
    fun aSecondApplySendsNothingBecauseTheMoveIsAlreadyTaken() =
        runTest {
            val session = MapSessionHolder()
            val host = FakeMapHost(CAMERA)
            session.bind(host)
            val links = links(session)

            links.submit("geo:49.5,24.5")
            links.applyPending(host)
            links.applyPending(host)

            assertEquals(1, host.moves.size)
        }

    @Test
    fun anOsmShortLinkKeepsItsEncodedZoom() =
        runTest {
            val session = MapSessionHolder()
            val host = FakeMapHost(CAMERA)
            session.bind(host)
            val links = links(session)

            links.submit("https://osm.org/go/0EEQjEEb")
            links.applyPending(host)

            assertEquals(16.0, host.moves.single().zoom)
        }

    @Test
    fun aZoomDeeperThanTheMapSupportsIsClamped() =
        runTest {
            val session = MapSessionHolder()
            val host = FakeMapHost(CAMERA)
            session.bind(host)
            val links = links(session)

            links.submit("https://osm.org/go/0hZWLPYD2s4--")
            links.applyPending(host)

            // The map accepts zoom 1..20; the link's 24 is clamped, not refused.
            assertEquals(20.0, host.moves.single().zoom)
        }

    @Test
    fun anElementLinkWithoutCoordinatesMovesNothingAndSaysWhy() =
        runTest {
            val session = MapSessionHolder()
            val host = FakeMapHost(CAMERA)
            session.bind(host)
            val messages = mutableListOf<String>()
            val links = links(session, messages)

            links.submit("https://www.openstreetmap.org/node/4815162342")
            links.applyPending(host)

            assertNull(links.pendingMove)
            assertTrue(host.moves.isEmpty())
            assertTrue(messages.any { "without a pin" in it })
        }

    @Test
    fun anElementLinkWithAMapFragmentMovesTheCamera() =
        runTest {
            val session = MapSessionHolder()
            val host = FakeMapHost(CAMERA)
            session.bind(host)
            val links = links(session)

            links.submit("https://osm.org/way/123#map=17/49.84/24.03")
            links.applyPending(host)

            assertEquals(
                listOf(CameraUpdate(center = GeoPoint(49.84, 24.03), zoom = 17.0)),
                host.moves,
            )
        }

    @Test
    fun aMalformedLinkIsAFailureAndMovesNothing() =
        runTest {
            val session = MapSessionHolder()
            val host = FakeMapHost(CAMERA)
            session.bind(host)
            val messages = mutableListOf<String>()
            val links = links(session, messages)

            assertEquals(LinkResult.Failure(LinkFailure.MALFORMED), links.submit("geo:abc"))
            links.applyPending(host)

            assertNull(links.pendingMove)
            assertTrue(host.moves.isEmpty())
            assertTrue(messages.any { "MALFORMED" in it })
        }

    @Test
    fun noIntentIsNotAFailure() =
        runTest {
            val links = links(MapSessionHolder())

            assertNull(links.submit(null))
        }

    @Test
    fun aLinkLeavesFollowModeSoTheNextFixCannotDragTheMapBack() =
        runTest {
            val session = MapSessionHolder()
            val host = FakeMapHost(CAMERA)
            session.bind(host)
            val modes = modes(session)
            val links = MapLinks(session, modes) {}
            modes.cycle()
            assertEquals(CameraMode.Follow, modes.mode.value)

            links.submit("geo:49.84,24.03")
            links.applyPending(host)

            assertEquals(CameraMode.Free, modes.mode.value)
            assertEquals(CameraMode.Free.trackingMode, host.trackingModes.last())
        }

    private fun links(
        session: MapSessionHolder,
        messages: MutableList<String> = mutableListOf(),
    ): MapLinks = MapLinks(session, modes(session)) { message -> messages += message }

    private fun modes(session: MapSessionHolder): CameraModeController =
        CameraModeController(
            host = session::hostOrNull,
            heading = MutableStateFlow(null),
            store = CameraModeStore(),
        )

    private companion object {
        val CAMERA = Camera(center = GeoPoint(49.8419, 24.0316), zoom = 12.5)
    }
}
