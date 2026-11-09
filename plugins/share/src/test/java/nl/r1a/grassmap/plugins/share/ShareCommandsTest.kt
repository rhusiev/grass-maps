package nl.r1a.grassmap.plugins.share

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share commands over a recording [RecordingActions] (T-0210). The fake
 * proves that copy, share and open receive the value the builder produced, and
 * the location command proves the fix-then-centre choice.
 */
class ShareCommandsTest {
    @Test
    fun copyWritesTheDecimalPairByDefault() =
        runTest {
            val actions = RecordingActions()
            val result = CopyCoordinatesCommand(actions).result(pointParams())

            assertEquals("49.821740, 24.023910", result)
            assertEquals(listOf("copy:49.821740, 24.023910"), actions.calls)
        }

    @Test
    fun copyCanWriteDmsOrDdm() =
        runTest {
            val actions = RecordingActions()

            CopyCoordinatesCommand(actions).result(pointParams("format" to "dms"))
            CopyCoordinatesCommand(actions).result(pointParams("format" to "ddm"))

            assertEquals(
                listOf(
                    "copy:49°49'18.26\"N, 24°01'26.08\"E",
                    "copy:49°49.3044'N, 24°01.4346'E",
                ),
                actions.calls,
            )
        }

    @Test
    fun anUnknownFormatIsATypedInputError() =
        runTest {
            val actions = RecordingActions()
            val command = CopyCoordinatesCommand(actions)

            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                command.invokeRaw(pointParams("format" to "utm")),
            )
            assertTrue(actions.calls.isEmpty())
        }

    @Test
    fun sharePlaceCarriesTheOsmElementAndTheSamePoint() =
        runTest {
            val actions = RecordingActions()
            val result =
                SharePlaceCommand(actions).result(
                    pointParams(
                        "name" to "Кав'ярня Левада",
                        "osmType" to "node",
                        "osmId" to "4815162342",
                    ),
                )

            assertTrue(result.contains("https://www.openstreetmap.org/node/4815162342"))
            assertTrue(result.contains("49.821740, 24.023910"))
            assertEquals(listOf("share:Кав'ярня Левада:$result"), actions.calls)
        }

    @Test
    fun sharePlaceWithoutAnIdentityUsesTheGeoUri() =
        runTest {
            val actions = RecordingActions()
            val result = SharePlaceCommand(actions).result(pointParams())

            assertTrue(result.contains("geo:49.821740,24.023910"))
            assertTrue("openstreetmap.org" !in result)
        }

    @Test
    fun anUnknownOsmTypeIsATypedInputError() =
        runTest {
            val actions = RecordingActions()
            val command = SharePlaceCommand(actions)

            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                command.invokeRaw(pointParams("osmType" to "area", "osmId" to "3")),
            )
            assertTrue(actions.calls.isEmpty())
        }

    @Test
    fun shareLocationPrefersTheFixOverTheMapCentre() =
        runTest {
            val actions = RecordingActions()
            val fix =
                LocationFix(
                    position = GeoPoint(49.840000, 24.030000),
                    accuracyMeters = 5.0,
                    timeMillis = 1_000L,
                )
            val camera = Camera(center = GeoPoint(10.0, 20.0), zoom = 12.0)
            val result =
                ShareLocationCommand(actions, FakeServices(flowOf(fix), flowOf(camera), actions))
                    .result(emptyParams())

            assertTrue(result.contains("49.840000, 24.030000"))
            assertTrue("geo:49.840000,24.030000" in result)
        }

    @Test
    fun shareLocationFallsBackToTheMapCentre() =
        runTest {
            val actions = RecordingActions()
            val camera = Camera(center = GeoPoint(10.0, 20.0), zoom = 12.0)
            val result =
                ShareLocationCommand(actions, FakeServices(emptyFlow(), flowOf(camera), actions))
                    .result(emptyParams())

            assertTrue(result.contains("10.000000, 20.000000"))
        }

    @Test
    fun shareLocationWithoutAFixOrCameraIsNotReady() =
        runTest {
            val actions = RecordingActions()
            val command = ShareLocationCommand(actions, FakeServices(platformActions = actions))

            assertEquals(Outcome.Failure(AppError.NotReady), command.invokeRaw(emptyParams()))
            assertTrue(actions.calls.isEmpty())
        }

    @Test
    fun shareLocationAcceptsAnExplicitPoint() =
        runTest {
            val actions = RecordingActions()
            val result =
                ShareLocationCommand(actions, FakeServices(platformActions = actions))
                    .result(pointParams())

            assertTrue(result.contains("49.821740, 24.023910"))
        }

    @Test
    fun openPointSendsTheGeoUriWithTheLabel() =
        runTest {
            val actions = RecordingActions()
            val uri =
                OpenPointCommand(actions).result(
                    pointParams("label" to "Rynok Square"),
                )

            assertEquals(
                "geo:49.821740,24.023910?q=49.821740,24.023910(Rynok%20Square)",
                uri,
            )
            assertEquals(listOf("open:$uri"), actions.calls)
        }

    @Test
    fun aPlatformFailureIsReturnedUnchanged() =
        runTest {
            val actions =
                RecordingActions().apply { result = Outcome.Failure(AppError.Unsupported) }

            assertEquals(
                Outcome.Failure(AppError.Unsupported),
                CopyCoordinatesCommand(actions).invokeRaw(pointParams()),
            )
        }

    @Test
    fun thePluginRegistersTheFourShareCommands() =
        runTest {
            val context = RecordingContext(FakeServices())
            val outcome = SharePlugin().activate(context)

            assertEquals(Outcome.Success(Unit), outcome)
            assertEquals(ShareContract.commands, context.commands.map { it.id })
            assertTrue(context.commands.all { it.policy.allowlistedForControl })
            assertTrue(context.commands.none { it.policy.undoable })
        }

    private fun pointParams(vararg extra: Pair<String, String>): JsonObject =
        buildJsonObject {
            put("latitude", "49.821740")
            put("longitude", "24.023910")
            for ((key, value) in extra) {
                put(key, value)
            }
        }

    private fun emptyParams(): JsonObject = buildJsonObject { }

    private suspend fun <R : Any> Command<JsonElement, R>.result(params: JsonElement): R {
        val outcome = execute(params)
        val success = outcome as? Outcome.Success ?: throw AssertionError("command failed: $outcome")
        return success.value.value
    }
}
