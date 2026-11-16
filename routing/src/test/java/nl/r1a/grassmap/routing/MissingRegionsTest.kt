package nl.r1a.grassmap.routing

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.RegionDownloadsAccess
import nl.r1a.grassmap.plugin.api.host.RoutingPackAccess
import nl.r1a.grassmap.plugin.api.model.CatalogPart
import nl.r1a.grassmap.plugin.api.model.CatalogPartKind
import nl.r1a.grassmap.plugin.api.model.CatalogRegion
import nl.r1a.grassmap.plugin.api.model.DownloadProblem
import nl.r1a.grassmap.plugin.api.model.DownloadProblemKind
import nl.r1a.grassmap.plugin.api.model.DownloadTransfer
import nl.r1a.grassmap.plugin.api.model.DownloadTransferState
import nl.r1a.grassmap.plugin.api.model.DownloadsData
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.InstalledPack
import nl.r1a.grassmap.plugin.api.model.RegionCatalog
import nl.r1a.grassmap.plugin.api.model.RegionProblem
import nl.r1a.grassmap.plugin.api.model.RoutePlanFailure
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picking the missing oblasts, the prompt state and "Download and route" (T-0511). */
class MissingRegionsTest {
    @Test
    fun aLvivToTernopilRouteListsTheTernopilPartsWithTheirSizes() {
        val data = data(installed = listOf(pack(LVIV)))

        val missing = MissingRegionFinder.find(listOf(LVIV_POINT, TERNOPIL_POINT), data)

        val region = missing?.regions?.single() ?: error("expected a missing region")
        assertEquals(TERNOPIL, region.region)
        assertEquals(
            listOf(
                CatalogPartKind.MAP to 35_302_857L,
                CatalogPartKind.SEARCH to 7_453_448L,
                CatalogPartKind.ELEVATION to 2_357_984L,
                CatalogPartKind.ROUTING to 60_784_640L,
                CatalogPartKind.CONTOURS to 11_127_269L,
            ),
            region.parts.map { it.kind to it.bytes },
        )
        assertEquals(117_026_198L, missing.bytes)
    }

    @Test
    fun anInstalledOblastWithoutItsRoutingPartIsStillMissingThatPart() {
        val partial = InstalledPack(TERNOPIL, "1", parts = listOf(CatalogPartKind.MAP, CatalogPartKind.SEARCH))
        val data = data(installed = listOf(pack(LVIV), partial))

        val missing = MissingRegionFinder.find(listOf(LVIV_POINT, TERNOPIL_POINT), data)

        val kinds =
            missing
                ?.regions
                ?.single()
                ?.parts
                ?.map { it.kind }
        assertEquals(listOf(CatalogPartKind.ELEVATION, CatalogPartKind.ROUTING, CatalogPartKind.CONTOURS), kinds)
    }

    @Test
    fun noMissingRegionWhenEveryStopIsCoveredOrOutsideTheCatalogue() {
        val covered = data(installed = listOf(pack(LVIV), pack(TERNOPIL)))
        val outside = GeoPoint(52.0, 13.0)

        assertNull(MissingRegionFinder.find(listOf(LVIV_POINT, TERNOPIL_POINT), covered))
        assertNull(MissingRegionFinder.find(listOf(LVIV_POINT, outside), data(installed = listOf(pack(LVIV)))))
        assertNull(MissingRegionFinder.find(listOf(LVIV_POINT, TERNOPIL_POINT), DownloadsData()))
    }

    @Test
    fun aStopInAnOverlapIsCoveredByAnyInstalledOblastAndElseTheNearestCentreIsMissing() {
        val overlap = GeoPoint(49.5, 24.9)

        assertNull(MissingRegionFinder.find(listOf(overlap), data(installed = listOf(pack(LVIV)))))
        val missing = MissingRegionFinder.find(listOf(overlap), data(installed = emptyList()))
        assertEquals(TERNOPIL, missing?.regions?.single()?.region)
    }

    @Test
    fun aCoveredStopPairListsTheOblastsTheStraightSegmentCrosses() {
        val data = data(installed = listOf(pack(LVIV), pack(EAST)))

        val missing = MissingRegionFinder.find(listOf(LVIV_POINT, EAST_POINT), data)

        assertEquals(listOf(TERNOPIL, MIDDLE), missing?.regions?.map { it.region })
    }

    @Test
    fun aCoveredStraightSegmentListsNothing() {
        val data = data(installed = listOf(pack(LVIV), pack(TERNOPIL), pack(MIDDLE), pack(EAST)))

        assertNull(MissingRegionFinder.find(listOf(LVIV_POINT, EAST_POINT), data))
        assertNull(MissingRegionFinder.find(listOf(LVIV_POINT, LVIV_POINT), data))
    }

    @Test
    fun segmentSamplesAreListedOnceInRouteOrderWhicheverWayTheRouteRuns() {
        val data = data(installed = listOf(pack(LVIV), pack(EAST)))

        val forward = MissingRegionFinder.find(listOf(LVIV_POINT, EAST_POINT), data)
        val back = MissingRegionFinder.find(listOf(EAST_POINT, LVIV_POINT, EAST_POINT), data)

        assertEquals(listOf(TERNOPIL, MIDDLE), forward?.regions?.map { it.region })
        assertEquals(listOf(MIDDLE, TERNOPIL), back?.regions?.map { it.region })
    }

    @Test
    fun theDownloadQueuesBothRegionsThenWaitsThenRebuildsTheMount() =
        runTest {
            val store = FakeStore(data(installed = listOf(pack(LVIV))))
            val packs = FakePacks(store.events)
            val missing = MissingRegionFinder.find(listOf(LVIV_POINT, TERNOPIL_POINT), store.data.value)!!
            val progress = mutableListOf<Long>()
            val result =
                async { MissingRegionsDownloader(store, packs).run(missing) { progress += it.bytesDone } }

            runCurrent()
            store.transferred(TERNOPIL, 50_000_000L)
            runCurrent()
            store.install(TERNOPIL)
            runCurrent()

            assertEquals(MissingDownloadResult.Ready, result.await())
            assertEquals(listOf("start:$TERNOPIL", "mount"), store.events)
            assertTrue(progress.contains(50_000_000L))
        }

    @Test
    fun aRecordedProblemEndsTheDownloadWithoutMountingAnything() =
        runTest {
            val store = FakeStore(data(installed = listOf(pack(LVIV))))
            val packs = FakePacks(store.events)
            val missing = MissingRegionFinder.find(listOf(TERNOPIL_POINT), store.data.value)!!
            val result = async { MissingRegionsDownloader(store, packs).run(missing) {} }

            runCurrent()
            store.fail(TERNOPIL, DownloadProblem(DownloadProblemKind.NETWORK))

            val failed = result.await() as MissingDownloadResult.Failed
            assertEquals(DownloadProblemKind.NETWORK, failed.progress.problem?.kind)
            assertEquals(listOf("start:$TERNOPIL"), store.events)
        }

    @Test
    fun aTransferThatVanishesWithoutAnInstallIsStopped() =
        runTest {
            val store = FakeStore(data(installed = listOf(pack(LVIV))))
            val missing = MissingRegionFinder.find(listOf(TERNOPIL_POINT), store.data.value)!!
            val result = async { MissingRegionsDownloader(store, FakePacks(store.events)).run(missing) {} }

            runCurrent()
            store.vanish(TERNOPIL)

            assertEquals(MissingDownloadResult.Stopped, result.await())
        }

    @Test
    fun aNullMountAfterTheInstallIsReported() =
        runTest {
            val store = FakeStore(data(installed = listOf(pack(LVIV))))
            val missing = MissingRegionFinder.find(listOf(TERNOPIL_POINT), store.data.value)!!
            val result = async { MissingRegionsDownloader(store, FakePacks(store.events, mounted = false)).run(missing) {} }

            runCurrent()
            store.install(TERNOPIL)

            assertEquals(MissingDownloadResult.MountUnavailable, result.await())
        }

    @Test
    fun aFailedRouteWithAMissingOblastBecomesThePromptAndOtherFailuresDoNot() =
        runTest {
            val store = FakeStore(data(installed = listOf(pack(LVIV))))
            val planner = planner(store, failWith = AppError.Internal("routing.valhalla.route"))

            val state = routeLvivToTernopil(planner)

            assertEquals(RoutePlanStatus.Failed, state.status)
            assertEquals(RoutePlanFailure.RegionsMissing, state.failure)
            assertEquals(117_026_198L, state.missing?.bytes)
            assertNull(state.missingDownload)

            val unusable =
                planner(
                    store,
                    failWith = AppError.RoutingPackUnavailable(AppError.RoutingPackUnavailable.Reason.Unusable, "x"),
                )
            val other = routeLvivToTernopil(unusable)
            assertEquals(RoutePlanFailure.PackUnusable, other.failure)
            assertNull(other.missing)
        }

    @Test
    fun downloadAndRouteRunsDownloadThenMountThenRoute() =
        runTest {
            val store = FakeStore(data(installed = listOf(pack(LVIV))))
            val planner = planner(store, failWith = AppError.Internal("routing.valhalla.route"), succeedAfterMount = true)
            routeLvivToTernopil(planner)

            val started = async { planner.downloadMissing() }
            runCurrent()
            assertNotNull(planner.state.value.missingDownload)
            store.install(TERNOPIL)
            runCurrent()
            started.await()

            assertEquals(listOf("route", "start:$TERNOPIL", "mount", "route"), store.events)
            val state = planner.state.value
            assertEquals(RoutePlanStatus.Ready, state.status)
            assertNull(state.failure)
            assertNull(state.missing)
            assertNull(state.missingDownload)
        }

    @Test
    fun aFailedDownloadKeepsThePromptWithTheProblem() =
        runTest {
            val store = FakeStore(data(installed = listOf(pack(LVIV))))
            val planner = planner(store, failWith = AppError.Internal("routing.valhalla.route"))
            routeLvivToTernopil(planner)

            val started = async { planner.downloadMissing() }
            runCurrent()
            store.fail(TERNOPIL, DownloadProblem(DownloadProblemKind.SERVER, 503))
            started.await()

            val state = planner.state.value
            assertEquals(RoutePlanFailure.RegionsMissing, state.failure)
            assertEquals(503, state.missingDownload?.problem?.status)
            assertEquals(1, store.events.count { it == "route" })
        }

    @Test
    fun downloadMissingWithoutAPromptIsRejected() =
        runTest {
            val planner = planner(FakeStore(DownloadsData()), failWith = AppError.NotFound)

            assertTrue(planner.downloadMissing() is Outcome.Failure)
        }

    private suspend fun routeLvivToTernopil(planner: RoutePlanner) =
        run {
            planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(LVIV_POINT, "Lviv"))
            planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(TERNOPIL_POINT, "Ternopil"))
            planner.state.value
        }

    private fun planner(
        store: FakeStore,
        failWith: AppError,
        succeedAfterMount: Boolean = false,
    ): RoutePlanner {
        val packs = FakePacks(store.events)
        return RoutePlanner(
            requestRoute = { _: RouteRequest, _ ->
                store.events += "route"
                if (succeedAfterMount && "mount" in store.events) {
                    Outcome.Success(listOf(ROUTE))
                } else {
                    Outcome.Failure(failWith)
                }
            },
            downloads = store,
            packs = packs,
        )
    }

    private class FakePacks(
        private val events: MutableList<String>,
        private val mounted: Boolean = true,
    ) : RoutingPackAccess {
        override fun packDirectory(): String? {
            events += "mount"
            return if (mounted) "/mount" else null
        }
    }

    private class FakeStore(
        initial: DownloadsData,
    ) : RegionDownloadsAccess {
        val events = mutableListOf<String>()
        private val flow = MutableStateFlow(initial)
        override val data: StateFlow<DownloadsData> = flow

        override suspend fun startDownload(region: String): Outcome<Unit> {
            events += "start:$region"
            val transfer = DownloadTransfer(region, DownloadTransferState.WAITING, 0, CATALOG_BYTES)
            flow.value = flow.value.copy(transfers = flow.value.transfers + transfer)
            return Outcome.Success(Unit)
        }

        fun transferred(
            region: String,
            done: Long,
        ) {
            val transfer = DownloadTransfer(region, DownloadTransferState.DOWNLOADING, done, CATALOG_BYTES)
            flow.value = flow.value.copy(transfers = flow.value.transfers.filter { it.region != region } + transfer)
        }

        fun install(region: String) {
            flow.value =
                flow.value.copy(
                    installed = flow.value.installed + pack(region),
                    transfers = flow.value.transfers.filter { it.region != region },
                )
        }

        fun fail(
            region: String,
            problem: DownloadProblem,
        ) {
            flow.value =
                flow.value.copy(
                    problems = listOf(RegionProblem(region, problem)),
                    transfers = flow.value.transfers.filter { it.region != region },
                )
        }

        fun vanish(region: String) {
            flow.value = flow.value.copy(transfers = flow.value.transfers.filter { it.region != region })
        }

        override suspend fun setServer(
            url: String,
            token: String?,
        ): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun refresh(): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun cancel(region: String): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun remove(region: String): Outcome<Unit> = Outcome.Success(Unit)
    }

    private companion object {
        const val LVIV = "ua-46"
        const val TERNOPIL = "ua-61"
        const val CATALOG_BYTES = 117_026_198L
        val LVIV_POINT = GeoPoint(49.84, 24.03)
        val TERNOPIL_POINT = GeoPoint(49.55, 25.59)
        const val MIDDLE = "ua-18"
        const val EAST = "ua-32"
        val EAST_POINT = GeoPoint(49.84, 32.0)
        val ROUTE = Route(listOf(LVIV_POINT, TERNOPIL_POINT), 128_000.0, 17_400.0)
        val ALL_KINDS =
            listOf(
                CatalogPartKind.CONTOURS to 11_127_269L,
                CatalogPartKind.ELEVATION to 2_357_984L,
                CatalogPartKind.MAP to 35_302_857L,
                CatalogPartKind.ROUTING to 60_784_640L,
                CatalogPartKind.SEARCH to 7_453_448L,
            )

        fun region(
            id: String,
            name: String,
            bbox: List<Double>,
        ) = CatalogRegion(
            id = id,
            nameEn = name,
            version = "1",
            bytes = CATALOG_BYTES,
            bbox = bbox,
            parts = ALL_KINDS.map { (kind, bytes) -> CatalogPart(kind, kind.name.lowercase(), "1", bytes) },
        )

        fun pack(id: String) = InstalledPack(id, "1", CATALOG_BYTES, ALL_KINDS.map { it.first })

        fun data(installed: List<InstalledPack>) =
            DownloadsData(
                catalog =
                    RegionCatalog(
                        format = 1,
                        regions =
                            listOf(
                                region(LVIV, "Lviv", listOf(22.64, 48.72, 25.43, 50.65)),
                                region(TERNOPIL, "Ternopil", listOf(24.72, 48.51, 26.44, 50.27)),
                                region(MIDDLE, "Middle", listOf(27.0, 49.0, 30.0, 51.0)),
                                region(EAST, "East", listOf(30.0, 49.0, 33.0, 51.0)),
                            ),
                    ),
                installed = installed,
            )
    }
}
