package nl.r1a.grassmap.regions

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.NoRegionAccess
import nl.r1a.grassmap.plugin.api.host.RegionAccess
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RouteRelations
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmap.plugin.api.model.WayAlertHit
import nl.r1a.grassmap.plugin.api.provider.Route
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The logical region access over an installed store (T-0516): an alert and a
 * relation from each pack, a shared feature and relation served once, a pack
 * that cannot answer not hiding a working one, and one pack answering exactly
 * as the single-mount reader does.
 */
class RegionReadersTest {
    private val work: Path = Files.createTempDirectory("grass-region-readers")
    private val store: Path = RegionStores.create(work)

    @After
    fun cleanUp() {
        RegionStores.delete(work)
    }

    @Test
    fun anAlertAndARelationFromTheSecondPackResolve() =
        runTest {
            install(FIRST, alerts = feature(HAZARD, FIRST_ALERT, 23.9430), relations = relation(FIRST_RELATION, 1, "first"))
            install(SECOND, alerts = feature(CROSSING, SECOND_ALERT, 23.9530), relations = relation(SECOND_RELATION, 2, "second"))

            val hits = hits(access())
            assertEquals(listOf(FIRST_ALERT, SECOND_ALERT), hits.map { it.feature.osmId })

            val relations = relations(access())
            assertEquals(listOf(FIRST_RELATION, SECOND_RELATION), relations.relations.map { it.id })
            assertEquals(setOf(1L, 2L), relations.ways.keys)
        }

    @Test
    fun aSharedFeatureAndRelationAreReturnedOnceFromTheFirstPack() =
        runTest {
            install(
                FIRST,
                alerts = feature(HAZARD, SHARED_ALERT, 23.9480),
                relations = relation(SHARED_RELATION, 3, "first"),
            )
            install(
                SECOND,
                alerts = feature(CROSSING, SHARED_ALERT, 23.9480),
                relations = relation(SHARED_RELATION, 4, "second"),
            )

            val shared = hits(access()).filter { it.feature.osmId == SHARED_ALERT }
            assertEquals("a shared feature is one corridor hit", 1, shared.size)
            assertEquals("the first pack's copy serves it", WayAlertFeatureKind.Hazard, shared.single().feature.kind)

            val relations = relations(access())
            val relation = relations.relations.single { it.id == SHARED_RELATION }
            assertEquals("the first pack's relation serves it", "first", relation.name)
            assertEquals(listOf(3L), relation.members.map { it.wayId })
            assertTrue("both packs' geometry is still present", relations.ways.keys.containsAll(listOf(3L, 4L)))
        }

    @Test
    fun aCorruptFirstPackDoesNotHideTheSecond() =
        runTest {
            install(FIRST, alerts = BROKEN_DOCUMENT, relations = BROKEN_DOCUMENT)
            install(SECOND, alerts = feature(CROSSING, SECOND_ALERT, 23.9530), relations = relation(SECOND_RELATION, 2, "second"))

            assertEquals(listOf(SECOND_ALERT), hits(access()).map { it.feature.osmId })
            assertEquals(listOf(SECOND_RELATION), relations(access()).relations.map { it.id })
        }

    @Test
    fun aCorruptSecondPackDoesNotHideTheFirst() =
        runTest {
            install(FIRST, alerts = feature(HAZARD, FIRST_ALERT, 23.9430), relations = relation(FIRST_RELATION, 1, "first"))
            install(SECOND, alerts = BROKEN_DOCUMENT, relations = BROKEN_DOCUMENT)

            assertEquals(listOf(FIRST_ALERT), hits(access()).map { it.feature.osmId })
            assertEquals(listOf(FIRST_RELATION), relations(access()).relations.map { it.id })
        }

    @Test
    fun whenNoPackAnswersTheFirstFailureIsReported() =
        runTest {
            install(FIRST, alerts = BROKEN_DOCUMENT, relations = BROKEN_DOCUMENT)
            install(SECOND, alerts = BROKEN_DOCUMENT, relations = BROKEN_DOCUMENT)

            assertEquals(Outcome.Failure(AppError.CorruptData), access().wayAlertFeatures(ROUTE, CORRIDOR_METERS))
            assertEquals(Outcome.Failure(AppError.CorruptData), access().routeRelations())
        }

    @Test
    fun aPackWithoutAComponentLeavesTheOtherPacksComponent() =
        runTest {
            install(FIRST, alerts = feature(HAZARD, FIRST_ALERT, 23.9430))
            install(SECOND, relations = relation(SECOND_RELATION, 2, "second"))

            assertEquals(listOf(FIRST_ALERT), hits(access()).map { it.feature.osmId })
            assertEquals(listOf(SECOND_RELATION), relations(access()).relations.map { it.id })
        }

    @Test
    fun packsAnswerInRegionIdOrderWhateverTheMountOrder() =
        runTest {
            install(SECOND, alerts = feature(CROSSING, SECOND_ALERT, 23.9530))
            install(FIRST, alerts = feature(HAZARD, FIRST_ALERT, 23.9430))

            val access = RegionReaders.open(mounts().reversed())

            assertEquals(listOf(FIRST, SECOND), (access as RegionReaders).regions)
            assertEquals(listOf(FIRST_ALERT, SECOND_ALERT), hits(access).map { it.feature.osmId })
        }

    @Test
    fun onePackAnswersExactlyAsTheSingleMountReader() =
        runTest {
            install(FIRST, alerts = feature(HAZARD, FIRST_ALERT, 23.9430), relations = relation(FIRST_RELATION, 1, "first"))
            install(SECOND, alerts = feature(CROSSING, SECOND_ALERT, 23.9530))
            val mount = mounts().first { it.region == FIRST }
            val single = RegionReader(mount.directory)
            val logical = RegionReaders.open(listOf(mount))

            assertEquals(
                single.wayAlertFeatures(ROUTE, CORRIDOR_METERS),
                logical.wayAlertFeatures(ROUTE, CORRIDOR_METERS),
            )
            assertEquals(single.routeRelations(), logical.routeRelations())
        }

    @Test
    fun anEmptyStoreIsTheInertSource() =
        runTest {
            val access = RegionReaders.open(emptyList())

            assertSame(NoRegionAccess, access)
            assertEquals(Outcome.Success(emptyList<WayAlertHit>()), access.wayAlertFeatures(ROUTE, CORRIDOR_METERS))
            assertEquals(Outcome.Success(RouteRelations.EMPTY), access.routeRelations())
        }

    private fun access(): RegionAccess = RegionReaders.open(mounts())

    private fun mounts(): List<MountedRegion> = RegionMountResolver(store.toFile()).resolveAll().installed

    private fun install(
        region: String,
        alerts: String? = null,
        relations: String? = null,
    ) {
        RegionStores.install(store, region, VERSION, packFiles(region, alerts, relations))
    }

    private suspend fun hits(access: RegionAccess): List<WayAlertHit> =
        when (val outcome = access.wayAlertFeatures(ROUTE, CORRIDOR_METERS)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> error("the alert query failed: ${outcome.error}")
        }

    private suspend fun relations(access: RegionAccess): RouteRelations =
        when (val outcome = access.routeRelations()) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> error("the relation query failed: ${outcome.error}")
        }

    /**
     * The fixture package with a fresh manifest: its map and features, plus the
     * components the test gives it. The map is a real PMTiles archive, so the
     * pack mounts; a component left null is absent, as in an older package.
     */
    private fun packFiles(
        region: String,
        alerts: String?,
        relations: String?,
    ): LinkedHashMap<String, ByteArray> {
        val fixture = FixturePackages.files()
        val features = fixture.getValue(FEATURES_FILE)
        val map = fixture.getValue(MAP_FILE)
        val files = linkedMapOf(FEATURES_FILE to features, MAP_FILE to map)
        val components =
            mutableListOf(
                component("features", FEATURES_FILE, "place-features", 1, features),
                component("map", MAP_FILE, "pmtiles", 3, map),
            )
        if (alerts != null) {
            val bytes = alerts.toByteArray()
            files[ALERTS_FILE] = bytes
            components += component("way-alerts", ALERTS_FILE, "way-alerts-geojson", 1, bytes)
        }
        if (relations != null) {
            val bytes = relations.toByteArray()
            files[RELATIONS_FILE] = bytes
            components += component("relations", RELATIONS_FILE, "route-relations-json", 1, bytes)
        }
        files[RegionIdentifiers.MANIFEST_FILE] = manifest(region, components).toByteArray()
        return files
    }

    private fun manifest(
        region: String,
        components: List<String>,
    ): String =
        """
        {
          "region": "$region",
          "version": "$VERSION",
          "packageFormat": 1,
          "osmTimestamp": "2026-11-03T21:24:23Z",
          "components": [
            ${components.joinToString(",\n    ")}
          ]
        }
        """.trimIndent()

    private fun component(
        kind: String,
        file: String,
        format: String,
        formatVersion: Int,
        bytes: ByteArray,
    ): String =
        """
        {
          "kind": "$kind",
          "file": "$file",
          "format": "$format",
          "formatVersion": $formatVersion,
          "version": "$VERSION",
          "bytes": ${bytes.size},
          "sha256": "${sha256(bytes)}",
          "standIn": false
        }
        """.trimIndent()

    private fun feature(
        kind: String,
        osmId: Long,
        longitude: Double,
    ): String =
        """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"kind":"$kind","osmType":"node","osmId":$osmId},
           "geometry":{"type":"Point","coordinates":[$longitude,$LATITUDE]}}
        ]}
        """.trimIndent()

    private fun relation(
        id: Long,
        way: Long,
        name: String,
    ): String =
        """
        {"format":"grass-maps-route-relations","version":1,
         "ways":[{"id":$way,"coordinates":[[23.9430,$LATITUDE],[23.9440,$LATITUDE]]}],
         "relations":[{"id":$id,"type":"route","route":"hiking","network":"lwn","name":"$name",
                       "members":[{"way":$way,"role":""}]}]}
        """.trimIndent()

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val FIRST = "pack-a"
        const val SECOND = "pack-b"
        const val VERSION = FixturePackages.FIXTURE_VERSION
        const val FEATURES_FILE = "features.bin"
        const val MAP_FILE = "map.pmtiles"
        const val ALERTS_FILE = "way-alerts.geojson"
        const val RELATIONS_FILE = "relations.json"
        const val HAZARD = "hazard"
        const val CROSSING = "railway_crossing"
        const val BROKEN_DOCUMENT = "{not json"
        const val LATITUDE = 49.9490
        const val CORRIDOR_METERS = 30.0
        const val FIRST_ALERT = 9001L
        const val SECOND_ALERT = 9002L
        const val SHARED_ALERT = 9003L
        const val FIRST_RELATION = 9201L
        const val SECOND_RELATION = 9202L
        const val SHARED_RELATION = 9203L
        val ROUTE =
            Route(
                geometry = listOf(GeoPoint(LATITUDE, 23.9430), GeoPoint(LATITUDE, 23.9530)),
                distanceMeters = 0.0,
                durationSeconds = 0.0,
            )
    }
}
