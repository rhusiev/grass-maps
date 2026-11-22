package nl.r1a.grassmaps.places

import nl.r1a.grassmaps.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Home and Work marker in the store's GPX dialect (T-0213). */
class FavouriteSpecialGpxTest {
    @Test
    fun specialsRoundTripInTheirOwnNamespace() {
        val snapshot =
            FavouritesSnapshot(
                favourites =
                    deriveIds(
                        listOf(
                            favourite(49.8419, 24.0316, "Flat").copy(special = FavouriteSpecial.Home),
                            favourite(49.8500, 24.0400, "Office").copy(special = FavouriteSpecial.Work),
                            favourite(49.8600, 24.0500, "Elsewhere"),
                        ),
                    ),
            )

        val exported = FavouritesGpx.write(snapshot)

        val text = String(exported, Charsets.UTF_8)
        assertEquals(1, Regex("<grassmaps:special>home</grassmaps:special>").findAll(text).count())
        assertEquals(1, Regex("<grassmaps:special>work</grassmaps:special>").findAll(text).count())
        assertEquals(
            listOf(FavouriteSpecial.Home, FavouriteSpecial.Work, null),
            read(exported).snapshot.favourites.map { it.special },
        )
    }

    @Test
    fun oldAndNewPrefixesImportAndNewPrefixIsWritten() {
        val oldPrefix =
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:grassmap="https://grassmaps.app/gpx/1">
              <wpt lat="49.84" lon="24.03"><name>Old</name>
                <extensions><grassmap:special>home</grassmap:special></extensions>
              </wpt>
            </gpx>
            """.trimIndent().toByteArray()
        val newPrefix =
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:grassmaps="https://grassmaps.app/gpx/1">
              <wpt lat="49.84" lon="24.03"><name>New</name>
                <extensions><grassmaps:special>home</grassmaps:special></extensions>
              </wpt>
            </gpx>
            """.trimIndent().toByteArray()

        assertEquals(
            FavouriteSpecial.Home,
            read(oldPrefix)
                .snapshot.favourites
                .single()
                .special,
        )
        assertEquals(
            FavouriteSpecial.Home,
            read(newPrefix)
                .snapshot.favourites
                .single()
                .special,
        )

        val snapshot =
            FavouritesSnapshot(
                favourites =
                    deriveIds(
                        listOf(favourite(49.84, 24.03, "Flat").copy(special = FavouriteSpecial.Home)),
                    ),
            )
        val text = String(FavouritesGpx.write(snapshot), Charsets.UTF_8)
        assertTrue(text.contains("<grassmaps:special>home</grassmaps:special>"))
        assertFalse(text.contains("xmlns:grassmap=\""))
    }

    @Test
    fun anUnknownSpecialValueIsIgnored() {
        val bytes =
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:grassmaps="https://grassmaps.app/gpx/1">
              <wpt lat="49.84" lon="24.03"><name>Flat</name>
                <extensions><grassmaps:special>castle</grassmaps:special></extensions>
              </wpt>
            </gpx>
            """.trimIndent().toByteArray()

        assertNull(
            read(bytes)
                .snapshot.favourites
                .single()
                .special,
        )
    }

    @Test
    fun aFileThatNamesTheSameSpecialTwiceKeepsTheFirst() {
        val bytes =
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:grassmaps="https://grassmaps.app/gpx/1">
              <wpt lat="49.84" lon="24.03"><name>First</name>
                <extensions><grassmaps:special>home</grassmaps:special></extensions>
              </wpt>
              <wpt lat="49.85" lon="24.04"><name>Second</name>
                <extensions><grassmaps:special>home</grassmaps:special></extensions>
              </wpt>
            </gpx>
            """.trimIndent().toByteArray()

        val favourites = read(bytes).snapshot.favourites

        assertEquals(FavouriteSpecial.Home, favourites[0].special)
        assertNull(favourites[1].special)
    }

    @Test
    fun anOsmAndStylePrefixIsNotReadAsOurSpecial() {
        val bytes =
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:osmand="https://osmand.net">
              <wpt lat="49.84" lon="24.03"><name>Flat</name>
                <extensions><osmand:special>home</osmand:special></extensions>
              </wpt>
            </gpx>
            """.trimIndent().toByteArray()

        assertNull(
            read(bytes)
                .snapshot.favourites
                .single()
                .special,
        )
    }

    private fun read(bytes: ByteArray): FavouriteDocument =
        when (val outcome = FavouritesGpx.read(bytes)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("read failed: ${outcome.error}")
        }
}
