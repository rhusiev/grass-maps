package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanOption
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.model.TrackKind
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.tracks.StoredTrack
import nl.r1a.grassmap.tracks.TrackId
import nl.r1a.grassmap.tracks.TrackIdentity
import nl.r1a.grassmap.tracks.storedGeometry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The Save action on the route preview (T-0411), through the real seams: the
 * displayed plan becomes one library record whose document is the `file.gpx`
 * provider's `<rte>` and whose `.route.json` sidecar carries the plan's own
 * profile and options.
 *
 * No test here reaches a tile pack or a network.
 */
class RouteSaveTest {
    private val root: File = Files.createTempDirectory("grass-route-save").toFile()

    @After
    fun deleteStore() {
        root.deleteRecursively()
    }

    @Test
    fun theDisplayedRouteIsSavedWithItsProfileAndOptions() =
        runTest {
            val access = access()
            val plan = readyPlan()

            assertEquals(DESTINATION_LABEL, saved(saveRoute(access, plan)).name)

            val summary = access.summaries().single()
            assertEquals(TrackKind.Route, summary.kind)
            assertEquals(DESTINATION_LABEL, summary.name)

            val record = stored(access, summary.id)
            assertEquals(RouteProfile.Hike, record.recompute?.profile)
            assertEquals(PLAN_OPTIONS, record.recompute?.options)
            assertEquals(
                plan.stops,
                record.recompute?.let { request -> listOf(request.start) + request.vias + listOf(request.destination) },
            )
            assertEquals(GEOMETRY, record.storedGeometry())

            val sidecar =
                root
                    .listFiles()
                    .orEmpty()
                    .single { file -> file.name.endsWith(".route.json") }
                    .readText()
            assertTrue(sidecar.contains("\"version\":1"))
            assertTrue(sidecar.contains("\"profile\":\"hike\""))
            assertTrue(sidecar.contains("\"hills\":\"avoid\""))
            assertTrue(sidecar.contains("\"rough_surfaces\":\"dont_mind\""))
        }

    @Test
    fun savingTheSameRouteTwiceKeepsOneRecordBecauseTheIdIsTheDocumentHash() =
        runTest {
            val access = access()

            val first = saved(saveRoute(access, readyPlan()))
            val second = saved(saveRoute(access, readyPlan()))

            assertEquals("one record, not two", 1, access.summaries().size)
            assertEquals(first.id, second.id)
            val document = root.listFiles().orEmpty().single { file -> file.name.endsWith(".gpx") }
            assertEquals("the id is the document's hash", first.id.value, TrackIdentity.documentHash(document.readBytes()))
            assertEquals(document.nameWithoutExtension, first.id.value)
        }

    @Test
    fun aPlanWithoutARouteFailsTypedAndSavesNothing() =
        runTest {
            val access = access()
            val picking =
                RoutePlanState(
                    start = RoutePlanPoint(GEOMETRY.first(), "Start"),
                    status = RoutePlanStatus.PickingDestination,
                )

            for (plan in listOf(RoutePlanState(), picking)) {
                assertEquals(
                    "${plan.status} has no route to save",
                    RouteSaveResult.Failed(AppError.InvalidInput),
                    saveRoute(access, plan),
                )
            }

            assertTrue("no record", access.summaries().isEmpty())
            assertTrue("no file", root.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun aBlankDestinationLabelSavesTheRouteUnnamed() =
        runTest {
            val access = access()

            val plan = readyPlan().copy(destination = RoutePlanPoint(GEOMETRY.last(), "  "))

            assertNull("no made-up name", saved(saveRoute(access, plan)).name)
            assertNull("no made-up name", access.summaries().single().name)
        }

    @Test
    fun theSaveStringsExistInEnglishAndUkrainianAndNameTheFailure() {
        val english = strings(ENGLISH)
        val ukrainian = strings(UKRAINIAN)

        for (key in SAVE_KEYS) {
            assertTrue("$key in English", english[key]?.isNotBlank() == true)
            assertTrue("$key in Ukrainian", ukrainian[key]?.isNotBlank() == true)
        }
        assertTrue("the failure names its reason", english.getValue(ROUTE_SAVE_FAILED_KEY).contains("%1\$s"))
        assertTrue("the failure names its reason", ukrainian.getValue(ROUTE_SAVE_FAILED_KEY).contains("%1\$s"))
    }

    private fun access(): TrackAccess =
        TrackAccess(
            registry =
                ProviderRegistry(
                    PluginHost(
                        plugins = GeneratedPlugins.plugins,
                        streams = emptyList(),
                        commands = emptyList(),
                        dispatcher = Dispatchers.Unconfined,
                    ),
                ),
            providers = GeneratedPlugins.providers,
            tracksRoot = root,
        )

    private fun readyPlan(): RoutePlanState =
        RoutePlanState(
            start = RoutePlanPoint(GEOMETRY.first(), "Start"),
            destination = RoutePlanPoint(GEOMETRY.last(), DESTINATION_LABEL),
            status = RoutePlanStatus.Ready,
            options = listOf(option()),
            vias = listOf(RoutePlanPoint(GEOMETRY[1], "Spring")),
            profile = RouteProfile.Hike,
            overridePreferences = PLAN_OPTIONS,
        )

    private fun option(): RoutePlanOption =
        RoutePlanOption(
            geometry = GEOMETRY,
            distanceMeters = 1_200.0,
            durationSeconds = 900.0,
            arrivalEpochMillis = 0L,
        )

    private suspend fun stored(
        access: TrackAccess,
        id: TrackId,
    ): StoredTrack =
        when (val outcome = access.stored(id)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("stored route missing: ${outcome.error}")
        }

    private fun saved(result: RouteSaveResult): RouteSaveResult.Saved =
        result as? RouteSaveResult.Saved ?: throw AssertionError("save failed: $result")

    private fun strings(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { match -> match.groupValues[1] to match.groupValues[2] }

    private companion object {
        const val DESTINATION_LABEL = "Kyivka"
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
        val GEOMETRY =
            listOf(
                GeoPoint(49.8400, 24.0300),
                GeoPoint(49.8425, 24.0350),
                GeoPoint(49.8450, 24.0400),
            )
        val PLAN_OPTIONS = RouteOptions(hills = RoutePreference.Avoid, roughSurfaces = RoutePreference.DontMind)
        val SAVE_KEYS = setOf("route_preview_save", ROUTE_SAVED_KEY, ROUTE_SAVE_FAILED_KEY)
    }
}
