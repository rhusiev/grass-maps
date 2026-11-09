package nl.r1a.grassmap.places

import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(1, Regex("<grassmap:special>home</grassmap:special>").findAll(text).count())
        assertEquals(1, Regex("<grassmap:special>work</grassmap:special>").findAll(text).count())
        assertEquals(
            listOf(FavouriteSpecial.Home, FavouriteSpecial.Work, null),
            read(exported).snapshot.favourites.map { it.special },
        )
    }

    @Test
    fun anUnknownSpecialValueIsIgnored() {
        val bytes =
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:grassmap="https://grassmaps.app/gpx/1">
              <wpt lat="49.84" lon="24.03"><name>Flat</name>
                <extensions><grassmap:special>castle</grassmap:special></extensions>
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
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:grassmap="https://grassmaps.app/gpx/1">
              <wpt lat="49.84" lon="24.03"><name>First</name>
                <extensions><grassmap:special>home</grassmap:special></extensions>
              </wpt>
              <wpt lat="49.85" lon="24.04"><name>Second</name>
                <extensions><grassmap:special>home</grassmap:special></extensions>
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
