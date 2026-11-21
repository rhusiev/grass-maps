package nl.r1a.grassmaps.app

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugins.raster.RasterAreaDownloadState
import nl.r1a.grassmaps.plugins.raster.RasterAreaPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The capture's download trigger plans the EOX 2016 preset and stays out of release builds. */
class RasterDownloadAreaCommandTest {
    private var planned: RasterAreaPlan? = null
    private var switch: Boolean? = null

    @Test
    fun aReleaseBuildRefusesBeforeAnythingChanges() =
        runTest {
            val outcome = command(debuggable = false).execute(params())

            assertEquals(Outcome.Failure(AppError.PermissionDenied), outcome)
            assertNull(planned)
            assertNull(switch)
        }

    @Test
    fun aBoxAndZoomsBecomeAnEoxTwentySixteenPlan() =
        runTest {
            val outcome = command().execute(params())

            assertTrue("$outcome", outcome is Outcome.Success)
            assertEquals(true, switch)
            val plan = checkNotNull(planned)
            assertEquals("eox-s2-2016", plan.source.id)
            assertEquals(TEMPLATE, plan.source.tileUrlTemplate)
            assertEquals(12, plan.minZoom)
            assertEquals(13, plan.maxZoom)
        }

    @Test
    fun aMissingFieldIsInvalid() =
        runTest {
            val outcome = command().execute(buildJsonObject { put("tiles", TEMPLATE) })

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
            assertNull(planned)
        }

    private fun command(debuggable: Boolean = true) =
        RasterDownloadAreaCommand(
            enableOnlineTiles = { enabled ->
                switch = enabled
                Outcome.Success(Unit)
            },
            download = { plan, _ ->
                planned = plan
                RasterAreaDownloadState.Done
            },
            debuggable = { debuggable },
        )

    private fun params(): JsonElement =
        buildJsonObject {
            put("tiles", TEMPLATE)
            put("south", 49.94)
            put("west", 23.94)
            put("north", 49.96)
            put("east", 23.96)
            put("minZoom", 12)
            put("maxZoom", 13)
            put("unused", JsonPrimitive("x"))
        }

    private companion object {
        const val TEMPLATE = "http://127.0.0.1:1/{z}/{x}/{y}.png"
    }
}
