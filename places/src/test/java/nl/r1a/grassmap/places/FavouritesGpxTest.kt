package nl.r1a.grassmap.places

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OsmAnd dialect oracle. The field names are OsmAnd's own documented ones
 * (https://osmand.net/docs/technical/osmand-file-formats/osmand-gpx/), and the
 * fixtures are authored in that shape rather than copied from a device export.
 */
class FavouritesGpxTest {
    @Test
    fun anOsmAndExportKeepsGroupsColoursIconsAndShapes() {
        val snapshot = document(OSMAND_FIXTURE).snapshot

        assertEquals(
            listOf(
                FavouriteGroup("Lviv", "#FF0000", "historic_castle", FavouriteShape.Circle),
                FavouriteGroup("Parks", "#0000FF", "bridge_structure_arch", FavouriteShape.Octagon),
            ),
            snapshot.groups,
        )
        assertEquals(4, snapshot.favourites.size)
        assertEquals(
            Favourite(
                id = snapshot.favourites[0].id,
                latitude = 49.8419,
                longitude = 24.0316,
                name = "Rynok Square",
                description = "Town hall corner",
                group = "Lviv",
                color = "#FF0000",
                icon = "historic_castle",
                shape = FavouriteShape.Circle,
            ),
            snapshot.favourites[0],
        )
        assertEquals(
            "a point override wins over the group default",
            listOf("#ff4e4eff", "historic_castle", FavouriteShape.Square),
            listOf(
                snapshot.favourites[1].color,
                snapshot.favourites[1].icon ?: "",
                snapshot.favourites[1].shape,
            ),
        )
        assertEquals("#0000FF", snapshot.favourites[2].color)
        assertEquals("bridge_structure_arch", snapshot.favourites[2].icon)
        assertEquals(FavouriteShape.Octagon, snapshot.favourites[2].shape)
        assertEquals(
            "a point outside every group falls back to the documented defaults",
            listOf(Favourite.DEFAULT_COLOR, null, FavouriteShape.Circle, null),
            listOf(
                snapshot.favourites[3].color,
                snapshot.favourites[3].icon,
                snapshot.favourites[3].shape,
                snapshot.favourites[3].group,
            ),
        )
    }

    @Test
    fun importExportImportGivesEqualModels() {
        val first = document(OSMAND_FIXTURE).snapshot

        val exported = FavouritesGpx.write(first)
        val second = document(exported).snapshot

        assertEquals(first, second)
    }

    @Test
    fun theExportIsDeterministicAndIdempotent() {
        val first = document(OSMAND_FIXTURE).snapshot
        val bytes = FavouritesGpx.write(first)

        assertArrayEquals(bytes, FavouritesGpx.write(first))
        assertArrayEquals(bytes, FavouritesGpx.write(document(bytes).snapshot))
    }

    @Test
    fun aDocumentWithoutExtensionsUsesTheDocumentedDefaults() {
        val document = document(PLAIN_FIXTURE)
        val favourites = document.snapshot.favourites

        assertTrue(document.skipped.isEmpty())
        assertEquals(2, favourites.size)
        for (favourite in favourites) {
            assertEquals(Favourite.DEFAULT_COLOR, favourite.color)
            assertEquals(FavouriteShape.Circle, favourite.shape)
            assertNull(favourite.icon)
            assertNull(favourite.group)
        }
        assertEquals("Alpha", favourites[0].name)
        assertEquals("No extensions here", favourites[0].description)

        val exported = FavouritesGpx.write(document.snapshot)
        assertEquals(document.snapshot, document(exported).snapshot)
    }

    @Test
    fun malformedXmlIsCorruptData() {
        assertEquals(
            Outcome.Failure(AppError.CorruptData),
            FavouritesGpx.read("<?xml version=\"1.0\"?><gpx><wpt lat=\"49.8\">".toByteArray()),
        )
    }

    @Test
    fun aWrongRootElementIsCorruptData() {
        assertEquals(
            Outcome.Failure(AppError.CorruptData),
            FavouritesGpx.read("<kml><wpt lat=\"49.8\" lon=\"24.0\"><name>X</name></wpt></kml>".toByteArray()),
        )
    }

    @Test
    fun aTruncatedDocumentIsCorruptData() {
        val complete = fixture(OSMAND_FIXTURE)

        assertEquals(
            Outcome.Failure(AppError.CorruptData),
            FavouritesGpx.read(complete.copyOf(complete.size / 2)),
        )
    }

    @Test
    fun aDoctypeIsRejectedBeforeTheParserResolvesIt() {
        val bytes =
            """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x "y">]><gpx version="1.1"/>""".toByteArray()

        assertEquals(Outcome.Failure(AppError.CorruptData), FavouritesGpx.read(bytes))
    }

    @Test
    fun aWaypointWithoutCoordinatesOrANameIsSkippedAndReported() {
        val bytes =
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt><name>No position</name></wpt>
              <wpt lat="91.0" lon="24.0"><name>Off the map</name></wpt>
              <wpt lat="49.84" lon="24.03"></wpt>
              <wpt lat="49.84" lon="24.03"><name>Kept</name></wpt>
            </gpx>
            """.trimIndent().toByteArray()

        val document = document(bytes)

        assertEquals(listOf("Kept"), document.snapshot.favourites.map { it.name })
        assertEquals(
            listOf(
                SkippedFavourite(0, FavouriteSkipReason.MissingCoordinates),
                SkippedFavourite(1, FavouriteSkipReason.OutOfRangeCoordinates),
                SkippedFavourite(2, FavouriteSkipReason.MissingName),
            ),
            document.skipped,
        )
    }

    @Test
    fun identicalWaypointsGetDistinctIds() {
        val bytes =
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="49.84" lon="24.03"><name>Twice</name></wpt>
              <wpt lat="49.84" lon="24.03"><name>Twice</name></wpt>
            </gpx>
            """.trimIndent().toByteArray()

        val favourites = document(bytes).snapshot.favourites

        assertEquals(2, favourites.size)
        assertNotEquals(favourites[0].id, favourites[1].id)
        assertTrue(favourites[1].id.value.startsWith(favourites[0].id.value))
    }

    private fun document(name: String): FavouriteDocument = document(fixture(name))

    private fun document(bytes: ByteArray): FavouriteDocument =
        when (val outcome = FavouritesGpx.read(bytes)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("read failed: ${outcome.error}")
        }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("favourites/$name")) {
            "missing test resource favourites/$name"
        }.use { it.readBytes() }

    private companion object {
        const val OSMAND_FIXTURE = "osmand-favourites.gpx"
        const val PLAIN_FIXTURE = "plain-waypoints.gpx"
    }
}
