package nl.r1a.grassmap.app.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.alerts.AlertChannels
import nl.r1a.grassmap.alerts.AlertSettings
import nl.r1a.grassmap.alerts.AlertSettingsStore
import nl.r1a.grassmap.alerts.ProfileAlertSettings
import nl.r1a.grassmap.app.FavouritesAccess
import nl.r1a.grassmap.app.MapStyleSettings
import nl.r1a.grassmap.app.TrackAccess
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.core.services.FilePluginEnabledStore
import nl.r1a.grassmap.core.services.OnlineSearchSettings
import nl.r1a.grassmap.core.services.ProfileCatalog
import nl.r1a.grassmap.core.services.ProfileValueFile
import nl.r1a.grassmap.core.services.RoutingOptionsSettings
import nl.r1a.grassmap.core.services.SearchHistorySettings
import nl.r1a.grassmap.core.services.backup.BackupBundle
import nl.r1a.grassmap.core.services.backup.BundleConflictChoice
import nl.r1a.grassmap.core.services.backup.BundleConflictDecisions
import nl.r1a.grassmap.core.services.backup.BundleZipReader
import nl.r1a.grassmap.core.services.backup.BundleZipWriter
import nl.r1a.grassmap.places.FavouriteShape
import nl.r1a.grassmap.places.FavouritesSnapshot
import nl.r1a.grassmap.places.SearchHistoryEntry
import nl.r1a.grassmap.places.SearchHistoryStore
import nl.r1a.grassmap.places.favourite
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RouteHistoryEntry
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.TrackKind
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import nl.r1a.grassmap.plugin.api.model.TrackStyle
import nl.r1a.grassmap.plugin.api.model.TrackWaypoint
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugins.layers.panel.MapStyleChoice
import nl.r1a.grassmap.plugins.manager.PluginGroups
import nl.r1a.grassmap.routing.RouteHistorySetting
import nl.r1a.grassmap.routing.RouteHistoryStore
import nl.r1a.grassmap.tracks.StoredTrack
import nl.r1a.grassmap.tracks.TrackId
import nl.r1a.grassmap.tracks.TrackMetadata
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * T-0608/T-0609 at the store seam: the real export, the real reader and the
 * real merge, over stores on temp files. No device and no UI.
 */
class BackupBundleRoundTripTest {
    private val sourceRoot: File = Files.createTempDirectory("backup-source").toFile()
    private val targetRoot: File = Files.createTempDirectory("backup-target").toFile()

    @After
    fun deleteRoots() {
        sourceRoot.deleteRecursively()
        targetRoot.deleteRecursively()
    }

    @Test
    fun aBundleRestoresEveryKindIntoAnEmptyStore() =
        runTest {
            val source = stores(sourceRoot)
            seed(source)
            val export = exportOf(source)
            assertEquals(listOf(1, 2, 4, 1, 1, 2, 1, 1, 1), export.counts.dropLast(1).map { count -> count.count })
            assertEquals(PluginGroups.switchableIds.size, export.counts.last().count)
            val exported = export.bytes

            val target = stores(targetRoot)
            val prepared = prepared(target, exported)
            val decisions = BundleConflictDecisions()
            while (true) {
                val conflict = decisions.next(prepared.plan) ?: break
                decisions.answer(conflict, BundleConflictChoice.INCOMING, applyToAllInKind = false)
            }
            applied(target, prepared, decisions)

            assertEquals(success(source.favourites.load()), success(target.favourites.load()))
            assertEquals(source.profileCatalog.load(), target.profileCatalog.load())
            assertEquals(source.routing.options.value, target.routing.options.value)
            assertEquals(alertSnapshots(source.alerts), alertSnapshots(target.alerts))
            assertEquals(searchEntries(source.search), searchEntries(target.search))
            assertEquals(routeEntries(source.route), routeEntries(target.route))
            assertEquals(source.style.current(), target.style.current())
            assertEquals(source.host.disabledIds(), target.host.disabledIds())
            assertEquals(trackSnapshots(source.tracks), trackSnapshots(target.tracks))
            assertTrue(source.onlineSearch.isPositionAllowed())
            assertTrue(target.onlineSearch.isPositionAllowed())
        }

    @Test
    fun aConflictKeptOnThisDeviceLeavesTheValueAlone() =
        runTest {
            val stores = stores(sourceRoot)
            stores.favourites.createGroup("Trip", "red", null)
            val exported = exported(stores)
            stores.favourites.recolourGroup("Trip", "blue")

            val prepared = prepared(stores, exported)
            assertEquals(1, prepared.plan.conflicts.size)
            assertEquals(
                "list:Trip",
                prepared.plan.conflicts
                    .single()
                    .entryId,
            )
            val decisions = BundleConflictDecisions()
            decisions.answer(prepared.plan.conflicts.single(), BundleConflictChoice.EXISTING, applyToAllInKind = false)

            val summary = applied(stores, prepared, decisions)

            assertEquals("blue", success(stores.favourites.load()).groups.single().color)
            assertEquals(1, summary.kept)
        }

    @Test
    fun aConflictAnsweredWithTheBundleValueReplacesIt() =
        runTest {
            val stores = stores(sourceRoot)
            stores.favourites.createGroup("Trip", "red", null)
            val exported = exported(stores)
            stores.favourites.recolourGroup("Trip", "blue")

            val prepared = prepared(stores, exported)
            val decisions = BundleConflictDecisions()
            decisions.answer(prepared.plan.conflicts.single(), BundleConflictChoice.INCOMING, applyToAllInKind = false)
            applied(stores, prepared, decisions)

            assertEquals("red", success(stores.favourites.load()).groups.single().color)
        }

    @Test
    fun theProducedZipAndEveryEntryDoNotContainAToken() =
        runTest {
            val token = "SECRET-REGION-TOKEN-1234567890"
            File(sourceRoot, "control-token").writeText(token)
            File(sourceRoot, "settings").mkdirs()
            File(sourceRoot, "settings/downloads.json").writeText("""{"mobileData":"ANY","token":"$token"}""")
            val stores = stores(sourceRoot)

            val bytes = exported(stores)
            val text = bytes.toString(Charsets.ISO_8859_1)
            assertFalse("the raw zip contains the token", text.contains(token))
            for (entry in entriesOf(bytes)) {
                assertFalse("entry ${entry.first} contains the token", entry.second.contains(token))
            }
            val manifest = entriesOf(bytes).first { entry -> entry.first == "manifest.json" }.second
            assertTrue(manifest.contains("\"excluded\""))
            assertTrue(manifest.contains("\"tokens\""))
            assertEquals(token, File(sourceRoot, "control-token").readText())
        }

    @Test
    fun aKindThisBuildDoesNotKnowIsSkippedAndTheKnownKindsStillRead() {
        val known =
            BackupBundle(
                formatVersion = 1,
                appVersion = "0.1.0",
                createdAtMillis = 1L,
                kinds =
                    listOf(
                        nl.r1a.grassmap.core.services.backup.BundleKind(
                            nl.r1a.grassmap.core.services.backup.BackupKinds.MAP_STYLE,
                            listOf(
                                nl.r1a.grassmap.core.services.backup.BundleEntry(
                                    "style",
                                    kotlinx.serialization.json.buildJsonObject { put("style", "touring") },
                                ),
                            ),
                        ),
                        nl.r1a.grassmap.core.services.backup.BundleKind(
                            "future-kind",
                            listOf(
                                nl.r1a.grassmap.core.services.backup.BundleEntry(
                                    "future",
                                    kotlinx.serialization.json.buildJsonObject { put("value", "x") },
                                ),
                            ),
                        ),
                    ),
            )
        val output = ByteArrayOutputStream()
        val written = BundleZipWriter.write(known, output)
        assertTrue(written is Outcome.Success)

        val read =
            when (val result = BundleZipReader.read(output.toByteArray())) {
                is Outcome.Success -> result.value
                is Outcome.Failure -> throw AssertionError("read failed: ${result.error}")
            }

        assertEquals(listOf("map-style"), read.kinds.map { kind -> kind.id })
        assertEquals(1, read.warnings.size)
    }

    private suspend fun seed(stores: Stores) {
        stores.favourites.createGroup("Trip", "red", "cafe")
        stores.favourites.add(
            favourite(
                latitude = 49.8400,
                longitude = 24.0290,
                name = "Cafe",
                description = "Coffee",
                group = "Trip",
                color = "red",
                icon = "cafe",
                shape = FavouriteShape.Circle,
            ),
        )
        stores.profileCatalog.load()
        stores.profileCatalog.create("Gravel", BuiltInProfiles.BIKE)
        stores.routing.set(
            BuiltInProfiles.BIKE,
            RouteOptions(hills = RoutePreference.Avoid, steps = RoutePreference.DontMind),
        )
        stores.alerts.load()
        stores.alerts.set(
            BuiltInProfiles.BIKE,
            ProfileAlertSettings(
                settings = AlertSettings(offRoute = true, railwayCrossing = true),
                channels = AlertChannels(banner = true, vibration = true),
                poiKinds = setOf("drinking_water"),
            ),
        )
        stores.onlineSearch.setPositionAllowed(true)
        stores.clock.nowMillis = 1_000L
        stores.search.remember(
            query = "cafe",
            index = "lviv",
            limit = 5,
            context = SearchContext(position = GeoPoint(49.84, 24.03), language = "uk"),
        )
        stores.clock.nowMillis = 2_000L
        stores.route.remember(routeEntry())
        stores.style.select(MapStyleChoice.TOURING)
        stores.host.setEnabled(PluginId("map.contours"), false)
        val report = stores.tracks.import("garmin-connect-hike.gpx", gpx("tracks/garmin-connect-hike.gpx"))
        val record = (report as Outcome.Success).value.tracks.single()
        stores.tracks.setMetadata(
            record.id,
            TrackMetadata(name = "Hike day", description = "A day out", listId = null),
        )
        stores.tracks.setStyle(record.id, TrackStyle("#12AB34", 6f))
        stores.tracks.setHidden(record.id, true)
    }

    private suspend fun exported(stores: Stores): ByteArray = exportOf(stores).bytes

    private suspend fun exportOf(stores: Stores): BackupExport =
        when (val exported = stores.coordinator.export()) {
            is Outcome.Success -> exported.value
            is Outcome.Failure -> throw AssertionError("export failed: ${exported.error}")
        }

    private suspend fun prepared(
        stores: Stores,
        bytes: ByteArray,
    ) = when (val prepared = stores.coordinator.prepare(ByteArrayInputStream(bytes))) {
        is Outcome.Success -> prepared.value
        is Outcome.Failure -> throw AssertionError("prepare failed: ${prepared.error}")
    }

    private suspend fun applied(
        stores: Stores,
        prepared: nl.r1a.grassmap.core.services.backup.PreparedBackup,
        decisions: BundleConflictDecisions,
    ) = when (val summary = stores.coordinator.apply(prepared, decisions)) {
        is Outcome.Success -> summary.value
        is Outcome.Failure -> throw AssertionError("apply failed: ${summary.error}")
    }

    private fun entriesOf(bytes: ByteArray): List<Pair<String, String>> {
        val entries = mutableListOf<Pair<String, String>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name to zip.readBytes().toString(Charsets.ISO_8859_1)
            }
        }
        return entries
    }

    private fun alertSnapshots(alerts: AlertSettingsStore): Map<String, Map<String, String>> {
        alerts.ensureLoaded()
        return alerts.storedProfiles().associate { profile -> profile.value to alerts.read(profile) }
    }

    private suspend fun searchEntries(store: SearchHistoryStore): List<SearchHistoryEntry> =
        when (val listed = store.list()) {
            is Outcome.Success -> listed.value
            is Outcome.Failure -> emptyList()
        }

    private suspend fun routeEntries(store: RouteHistoryStore): List<RouteHistoryEntry> =
        when (val listed = store.recent()) {
            is Outcome.Success -> listed.value
            is Outcome.Failure -> emptyList()
        }

    private suspend fun trackSnapshots(tracks: TrackAccess): List<TrackSnapshot> =
        tracks.storedTracks().map { record ->
            TrackSnapshot(
                id = record.id,
                name = record.name,
                description = record.description,
                listId = record.listId,
                style = record.style,
                hidden = record.hidden,
                kind = record.kind,
                segments = record.track.segments,
                waypoints = record.waypoints,
            )
        }

    private fun success(snapshot: Outcome<FavouritesSnapshot>): FavouritesSnapshot =
        when (snapshot) {
            is Outcome.Success -> snapshot.value
            is Outcome.Failure -> throw AssertionError("favourites failed: ${snapshot.error}")
        }

    private fun gpx(resource: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
            "missing test resource $resource"
        }.use { it.readBytes() }

    private fun stores(root: File): Stores {
        val trackHost =
            PluginHost(
                plugins = GeneratedPlugins.plugins,
                streams = emptyList(),
                commands = emptyList(),
                dispatcher = Dispatchers.Unconfined,
            )
        val tracks =
            TrackAccess(
                registry = ProviderRegistry(trackHost),
                providers = GeneratedPlugins.providers,
                tracksRoot = File(root, "tracks"),
            )
        val profileCatalog =
            ProfileCatalog(
                ProfileValueFile(
                    File(root, "profiles/catalog.json"),
                    ProfileCatalog.SCHEMA_VERSION,
                    ProfileCatalog.ERROR_ID,
                ),
            )
        val routing =
            RoutingOptionsSettings(
                ProfileValueFile(
                    File(root, "profiles/routing-options.json"),
                    RoutingOptionsSettings.SCHEMA_VERSION,
                    "test.routing",
                ),
            )
        val alerts =
            AlertSettingsStore(
                ProfileValueFile(
                    File(root, "profiles/alerts.json"),
                    AlertSettingsStore.SCHEMA_VERSION,
                    AlertSettingsStore.ERROR_ID,
                ),
            )
        val searchHistorySettings = SearchHistorySettings(File(root, "settings/search-history.json"))
        val onlineSearch = OnlineSearchSettings(File(root, "settings/online-search.json"))
        val clock = MutableClock(500L)
        val search =
            SearchHistoryStore(
                file = File(root, "history/search-history.json"),
                setting = { searchHistorySettings.isEnabled() },
                clock = clock,
            )
        val route =
            RouteHistoryStore(
                file = File(root, "history/route-history.json"),
                setting = RouteHistorySetting { searchHistorySettings.isEnabled() },
                clock = clock,
            )
        val style = MapStyleSettings(File(root, "settings/style.json"))
        val favouritesAccess = FavouritesAccess(root)
        val host =
            PluginHost(
                plugins = GeneratedPlugins.plugins,
                streams = emptyList(),
                commands = emptyList(),
                dispatcher = Dispatchers.Unconfined,
                enabledStore = FilePluginEnabledStore(File(root, "settings/plugins.json")),
                switchable = PluginGroups.switchableIds,
            )
        return Stores(
            favourites = favouritesAccess,
            tracks = tracks,
            profileCatalog = profileCatalog,
            routing = routing,
            alerts = alerts,
            searchHistorySettings = searchHistorySettings,
            onlineSearch = onlineSearch,
            search = search,
            route = route,
            style = style,
            host = host,
            clock = clock,
            coordinator =
                BackupCoordinator(
                    tracks = { tracks },
                    favourites = favouritesAccess,
                    profileCatalog = profileCatalog,
                    routing = routing,
                    alerts = alerts,
                    searchHistorySettings = searchHistorySettings,
                    onlineSearchSettings = onlineSearch,
                    searchHistory = search,
                    routeHistory = route,
                    mapStyle = style,
                    pluginHost = host,
                    switchablePluginIds = PluginGroups.switchableIds,
                    appVersion = "0.1.0",
                    clock = clock,
                ),
        )
    }

    private class Stores(
        val favourites: FavouritesAccess,
        val tracks: TrackAccess,
        val profileCatalog: ProfileCatalog,
        val routing: RoutingOptionsSettings,
        val alerts: AlertSettingsStore,
        val searchHistorySettings: SearchHistorySettings,
        val onlineSearch: OnlineSearchSettings,
        val search: SearchHistoryStore,
        val route: RouteHistoryStore,
        val style: MapStyleSettings,
        val host: PluginHost,
        val clock: MutableClock,
        val coordinator: BackupCoordinator,
    )

    private data class TrackSnapshot(
        val id: TrackId,
        val name: String?,
        val description: String?,
        val listId: String?,
        val style: TrackStyle,
        val hidden: Boolean,
        val kind: TrackKind,
        val segments: List<TrackSegment>,
        val waypoints: List<TrackWaypoint>,
    )

    private class MutableClock(
        var nowMillis: Long,
    ) : Clock {
        override fun nowMillis(): Long = nowMillis
    }

    private fun routeEntry(): RouteHistoryEntry =
        RouteHistoryEntry(
            start = RoutePlanPoint(GeoPoint(49.8290, 23.9520), "start"),
            destination = RoutePlanPoint(GeoPoint(49.8420, 24.0320), "destination"),
            vias = listOf(RoutePlanPoint(GeoPoint(49.8330, 23.9700), "Cafe")),
            distanceMeters = 7_100.0,
            durationSeconds = 1_500.0,
            preferences = RouteOptions(hills = RoutePreference.Avoid),
        )
}
