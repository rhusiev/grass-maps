package nl.r1a.grassmap.plugins.online.search

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.PlaceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Photon GeoJSON becomes the one PlaceHit shape the screen already draws. */
class PhotonMappingTest {
    @Test
    fun aFeatureCarriesNameKindAddressPositionAndBrand() {
        val outcome = PhotonMapping.toHits(FEATURE_COLLECTION, limit = 5)

        val hit = (outcome as Outcome.Success).value.single()
        assertEquals("АТБ-Маркет", hit.name)
        assertEquals("shop=supermarket", hit.kind)
        assertEquals("Городоцька 12, 79000 Львів, Україна", hit.address)
        assertEquals(49.8419, hit.position.latitude, 1e-9)
        assertEquals(24.0316, hit.position.longitude, 1e-9)
        assertEquals("АТБ", hit.brand)
        assertEquals("photon:node/123", hit.id)
        assertEquals(PlaceSource.ONLINE, hit.source)
        assertEquals(PhotonMapping.REGION_BUILD, hit.regionBuild)
    }

    @Test
    fun aFeatureWithoutANameIsSkippedAndTheRestSurvive() {
        val body =
            """
            {"features":[
              {"geometry":{"coordinates":[24.03,49.84]},"properties":{"osm_key":"shop"}},
              {"geometry":{"coordinates":[24.0316,49.8419]},"properties":{"name":"Площа Ринок","osm_key":"place","osm_value":"square"}}
            ]}
            """.trimIndent()

        val hits = (PhotonMapping.toHits(body, limit = 5) as Outcome.Success).value

        assertEquals(listOf("Площа Ринок"), hits.map { it.name })
    }

    @Test
    fun anAddressRowFallsBackToItsStreetAndHouseNumber() {
        val body =
            """
            {"features":[
              {"geometry":{"coordinates":[24.03,49.84]},"properties":{
                "street":"Городоцька","housenumber":"12","city":"Львів","osm_key":"place","osm_value":"house"
              }}
            ]}
            """.trimIndent()

        val hit = (PhotonMapping.toHits(body, limit = 5) as Outcome.Success).value.single()

        assertEquals("Городоцька 12", hit.name)
        assertEquals("place=house", hit.kind)
        assertNull(hit.brand)
    }

    @Test
    fun theOperatorStandsInForAMissingBrand() {
        val body =
            """
            {"features":[
              {"geometry":{"coordinates":[24.03,49.84]},"properties":{
                "name":"Кав'ярня","osm_key":"amenity","osm_value":"cafe","operator":"Світ кави"
              }}
            ]}
            """.trimIndent()

        val hit = (PhotonMapping.toHits(body, limit = 5) as Outcome.Success).value.single()

        assertEquals("Світ кави", hit.brand)
    }

    @Test
    fun aBodyWithoutFeaturesIsCorruptData() {
        assertEquals(Outcome.Failure(AppError.CorruptData), PhotonMapping.toHits("{}", limit = 5))
        assertEquals(Outcome.Failure(AppError.CorruptData), PhotonMapping.toHits("not json", limit = 5))
    }

    @Test
    fun aFeatureOutsideTheWgs84RangeIsSkipped() {
        val body =
            """
            {"features":[
              {"geometry":{"coordinates":[200.0,49.84]},"properties":{"name":"Nowhere"}}
            ]}
            """.trimIndent()

        val hits = (PhotonMapping.toHits(body, limit = 5) as Outcome.Success).value

        assertTrue(hits.isEmpty())
    }

    @Test
    fun theLimitClipsTheMappedRows() {
        val hits = (PhotonMapping.toHits(FEATURE_COLLECTION, limit = 0) as Outcome.Success).value

        assertTrue(hits.isEmpty())
    }

    private companion object {
        val FEATURE_COLLECTION =
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature",
               "geometry":{"type":"Point","coordinates":[24.0316,49.8419]},
               "properties":{
                 "name":"АТБ-Маркет",
                 "street":"Городоцька","housenumber":"12","postcode":"79000",
                 "city":"Львів","country":"Україна",
                 "osm_key":"shop","osm_value":"supermarket","osm_type":"node","osm_id":123,
                 "brand":"АТБ"
               }}
            ]}
            """.trimIndent()
    }
}
