package nl.r1a.grassmaps.app

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.app.generated.GeneratedPlugins
import nl.r1a.grassmaps.core.CommandDispatchers
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.OsmType
import nl.r1a.grassmaps.plugin.api.model.PlaceCardAction
import nl.r1a.grassmaps.plugin.api.model.PlaceCardValue
import nl.r1a.grassmaps.plugin.api.provider.Place
import nl.r1a.grassmaps.plugin.api.provider.PlaceCardContract
import nl.r1a.grassmaps.plugin.api.provider.PlaceDetails
import nl.r1a.grassmaps.plugin.api.provider.PlaceHit
import nl.r1a.grassmaps.plugin.api.provider.PlaceSource
import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card host and the generated registry (T-0208). The real plugin is
 * resolved through `ProviderRegistry`, so this proves the manifest, the
 * activation and the mapping are wired, not only the mapper.
 */
class PlaceCardAccessTest {
    @Test
    fun theGeneratedRegistryCarriesThePlaceCardProvider() {
        val plugin =
            GeneratedPlugins.plugins.firstOrNull { it.manifest.id == PluginId(OSM_PLACES) }
                ?: throw AssertionError("osm.places is not in the generated registry")

        assertEquals("nl.r1a.grassmap.plugins.osm.places.OsmPlacesPlugin", plugin.manifest.implementation)
        val provider =
            plugin.manifest.contributes.providers
                .singleOrNull()
                ?: throw AssertionError("osm.places declares no provider")
        assertEquals(PlaceCardContract.NAME, provider.contract)
        assertEquals(PlaceCardContract.VERSION, provider.version)
        assertTrue(GeneratedPlugins.providers.any { it.contract == PlaceCardContract.REFERENCE })
    }

    @Test
    fun theCardIsBuiltFromTheDetailsTheProviderSeamReturns() =
        runTest {
            val seen = mutableListOf<Pair<String, String>>()
            val access =
                PlaceCardAccess(
                    details = { index, placeId, _ ->
                        seen += index to placeId
                        Outcome.Success(details(CAFE_TAGS))
                    },
                    providers = { container(testScheduler).providerRegistry },
                )

            val card = (access.card(INDEX, PLACE_ID) as Outcome.Success).value

            assertEquals(listOf(INDEX to PLACE_ID), seen)
            assertEquals("Кав'ярня Левада", card.name)
            assertEquals("cafe", card.kind)
            assertEquals(
                listOf("+380322970545"),
                card.contacts.map { (it.value as PlaceCardValue.Text).text },
            )
            assertEquals(
                listOf(PlaceCardValue.Label("osm_places.wheelchair_yes")),
                card.accessibility.map { it.value },
            )
            assertEquals(
                listOf(PlaceCardValue.Text("ukrainian")),
                card.attributes.map { it.value },
            )
            assertEquals(3, card.coordinates.size)
            assertEquals(
                listOf(
                    PlaceCardAction.COPY_COORDINATES,
                    PlaceCardAction.SHARE,
                    PlaceCardAction.OPEN_IN,
                ),
                card.shortcuts.map { it.link.action },
            )
            assertFalse("an offline card must not carry the online mark", card.online)
            assertTrue(
                card.shortcuts
                    .first { it.link.action == PlaceCardAction.SHARE }
                    .link.target
                    .contains("https://www.openstreetmap.org/node/4815162342"),
            )
            assertFalse(
                "a value the details did not carry reached the card",
                card.contacts.any { it.value == PlaceCardValue.Text("+380671112233") },
            )
        }

    @Test
    fun aDetailsFailureIsReturnedWithoutAskingTheCardProvider() =
        runTest {
            var providersAsked = false
            val access =
                PlaceCardAccess(
                    details = { _, _, _ -> Outcome.Failure(AppError.NotFound) },
                    providers = {
                        providersAsked = true
                        container(testScheduler).providerRegistry
                    },
                )

            assertEquals(Outcome.Failure(AppError.NotFound), access.card(INDEX, PLACE_ID))
            assertFalse(providersAsked)
        }

    @Test
    fun anOnlineCardIsBuiltWithoutADetailsCallOrTheProviderRegistry() =
        runTest {
            var detailsCalls = 0
            var providersAsked = false
            val access =
                PlaceCardAccess(
                    details = { _, _, _ ->
                        detailsCalls += 1
                        Outcome.Failure(AppError.NotFound)
                    },
                    providers = {
                        providersAsked = true
                        container(testScheduler).providerRegistry
                    },
                )

            val card = (access.card(onlineHit()) as Outcome.Success).value

            assertEquals(0, detailsCalls)
            assertFalse(providersAsked)
            assertTrue(card.online)
            assertEquals(
                listOf(
                    PlaceCardAction.COPY_COORDINATES,
                    PlaceCardAction.SHARE,
                    PlaceCardAction.OPEN_IN,
                ),
                card.shortcuts.map { it.link.action },
            )
        }

    @Test
    fun aPlaceWithNoTagsStillProducesACard() =
        runTest {
            val access =
                PlaceCardAccess(
                    details = { _, _, _ -> Outcome.Success(details(emptyMap())) },
                    providers = { container(testScheduler).providerRegistry },
                )

            val card = (access.card(INDEX, PLACE_ID) as Outcome.Success).value

            assertEquals("Кав'ярня Левада", card.name)
            assertTrue(card.contacts.isEmpty())
            assertTrue(card.accessibility.isEmpty())
            assertTrue(card.attributes.isEmpty())
        }

    private fun container(scheduler: TestCoroutineScheduler): AppContainer {
        val dispatcher = StandardTestDispatcher(scheduler)
        return AppContainer(
            strings = KeyOnlyStrings,
            dispatchers = CommandDispatchers(main = dispatcher, default = dispatcher, io = dispatcher),
        )
    }

    private fun onlineHit(): PlaceHit =
        PlaceHit(
            id = "photon:node/42",
            name = "Кав'ярня Левада",
            kind = "amenity=cafe",
            position = GeoPoint(49.821740, 24.023910),
            address = "вул. Стрийська 45",
            regionBuild = "photon",
            score = 1.0f,
            source = PlaceSource.ONLINE,
        )

    private fun details(tags: Map<String, String>): PlaceDetails =
        PlaceDetails(
            place =
                Place(
                    id = PLACE_ID,
                    name = "Кав'ярня Левада",
                    kind = "amenity=cafe",
                    position = GeoPoint(49.9419, 23.9419),
                    address = "смт Івано-Франкове, вул. Стрийська 45",
                    regionBuild = INDEX,
                ),
            osmType = OsmType.NODE,
            osmId = 4_815_162_342L,
            tags = tags,
        )

    private object KeyOnlyStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = key

        override fun contains(key: String): Boolean = true
    }

    private companion object {
        const val OSM_PLACES = "osm.places"
        const val INDEX = "fixture-mini"
        const val PLACE_ID = "fixture-mini-2026-11-03-1:0"

        val CAFE_TAGS =
            mapOf(
                "name" to "Кав'ярня Левада",
                "amenity" to "cafe",
                "phone" to "+380322970545",
                "wheelchair" to "yes",
                "cuisine" to "ukrainian",
            )
    }
}
