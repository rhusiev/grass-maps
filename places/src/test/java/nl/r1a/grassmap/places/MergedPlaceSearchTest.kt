package nl.r1a.grassmap.places

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.PlaceSource
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge contract (T-0212): offline order stays, online rows follow, a
 * repeat by name and proximity is dropped, and the position allowance gates
 * only the online request.
 */
class MergedPlaceSearchTest {
    @Test
    fun offlineOrderIsKeptAndOnlineRowsAreAppendedAfterThem() =
        runTest {
            val offline = listOf(hit("off-1", "Площа Ринок", 49.8413, 24.0312), hit("off-2", "Львівська кава", 49.8420, 24.0320))
            val online =
                listOf(
                    hit("on-1", "площа ринок!", 49.84132, 24.03122, PlaceSource.ONLINE),
                    hit("on-2", "АТБ", 49.8430, 24.0330, PlaceSource.ONLINE),
                    hit("on-3", "Львівська кава", 49.9000, 24.1000, PlaceSource.ONLINE),
                )
            val search = merged(offline, online)

            val result = (search.search(request()) as Outcome.Success).value

            assertEquals(listOf("off-1", "off-2", "on-2", "on-3"), result.map { it.id })
            assertEquals(
                listOf(PlaceSource.OFFLINE, PlaceSource.OFFLINE, PlaceSource.ONLINE, PlaceSource.ONLINE),
                result.map { it.source },
            )
        }

    @Test
    fun aDuplicateOnlineRowIsDroppedTooAndTheFirstOneWins() =
        runTest {
            val online =
                listOf(
                    hit("on-1", "Площа Ринок", 49.8413, 24.0312, PlaceSource.ONLINE),
                    hit("on-2", "ПЛОЩА РИНОК", 49.84131, 24.03121, PlaceSource.ONLINE),
                )
            val search = merged(emptyList(), online)

            val result = (search.search(request()) as Outcome.Success).value

            assertEquals(listOf("on-1"), result.map { it.id })
        }

    @Test
    fun aSameNamePlaceOutsideTheRadiusIsKept() {
        val existing = hit("a", "АТБ", 49.8413, 24.0312)
        val far = hit("b", "АТБ", 49.8513, 24.0312)

        assertTrue(MergedPlaceSearch.isDuplicate(existing, hit("c", "АТБ", 49.84131, 24.03121)))
        assertTrue(!MergedPlaceSearch.isDuplicate(existing, far))
    }

    @Test
    fun thePositionReachesTheOnlineProviderOnlyWhenTheAllowanceIsOn() =
        runTest {
            val point = GeoPoint(49.8419, 24.0316)
            var onlineContext: SearchContext? = null
            var offlineContext: SearchContext? = null
            val search =
                MergedPlaceSearch(
                    offline = { request ->
                        offlineContext = request.context
                        Outcome.Success(emptyList())
                    },
                    online = { request ->
                        onlineContext = request.context
                        Outcome.Success(emptyList())
                    },
                    onlineEnabled = { true },
                    positionAllowed = { false },
                    debounceMillis = 0,
                )

            search.search(request(context = SearchContext(position = point)))

            assertNull(onlineContext?.position)
            assertEquals(point, offlineContext?.position)
        }

    @Test
    fun thePositionIsForwardedWhenTheAllowanceIsOn() =
        runTest {
            val point = GeoPoint(49.8419, 24.0316)
            var onlineContext: SearchContext? = null
            val search =
                MergedPlaceSearch(
                    offline = { Outcome.Success(emptyList()) },
                    online = { request ->
                        onlineContext = request.context
                        Outcome.Success(emptyList())
                    },
                    onlineEnabled = { true },
                    positionAllowed = { true },
                    debounceMillis = 0,
                )

            search.search(request(context = SearchContext(position = point)))

            assertEquals(point, onlineContext?.position)
        }

    @Test
    fun aDisabledOnlineSearchDoesNotCallOrWait() =
        runTest {
            var onlineCalls = 0
            var pauses = 0
            val search =
                MergedPlaceSearch(
                    offline = { Outcome.Success(listOf(hit("off", "Площа Ринок", 49.8413, 24.0312))) },
                    online = {
                        onlineCalls += 1
                        Outcome.Success(emptyList())
                    },
                    onlineEnabled = { false },
                    positionAllowed = { true },
                    pause = { pauses += 1 },
                )

            val result = (search.search(request()) as Outcome.Success).value

            assertEquals(listOf("off"), result.map { it.id })
            assertEquals(0, onlineCalls)
            assertEquals(0, pauses)
        }

    @Test
    fun theOnlineCallWaitsForTheDebounce() =
        runTest {
            val pauses = mutableListOf<Long>()
            val search =
                MergedPlaceSearch(
                    offline = { Outcome.Success(emptyList()) },
                    online = { Outcome.Success(emptyList()) },
                    onlineEnabled = { true },
                    positionAllowed = { false },
                    pause = { millis -> pauses += millis },
                )

            search.search(request())

            assertEquals(listOf(MergedPlaceSearch.ONLINE_DEBOUNCE_MILLIS), pauses)
        }

    @Test
    fun anOfflineFailureStillShowsOnlineRows() =
        runTest {
            val search =
                MergedPlaceSearch(
                    offline = { Outcome.Failure(AppError.NotReady) },
                    online = { Outcome.Success(listOf(hit("on", "АТБ", 49.8413, 24.0312, PlaceSource.ONLINE))) },
                    onlineEnabled = { true },
                    positionAllowed = { false },
                    debounceMillis = 0,
                )

            val result = (search.search(request()) as Outcome.Success).value

            assertEquals(listOf("on"), result.map { it.id })
        }

    @Test
    fun anOnlineFailureDoesNotHideOfflineRows() =
        runTest {
            val search =
                MergedPlaceSearch(
                    offline = { Outcome.Success(listOf(hit("off", "Площа Ринок", 49.8413, 24.0312))) },
                    online = { Outcome.Failure(AppError.Network) },
                    onlineEnabled = { true },
                    positionAllowed = { false },
                    debounceMillis = 0,
                )

            val result = (search.search(request()) as Outcome.Success).value

            assertEquals(listOf("off"), result.map { it.id })
        }

    @Test
    fun aTypedCoordinateIsNeverSentOnline() =
        runTest {
            var onlineCalls = 0
            val coordinate =
                hit("coordinate", "49.84, 24.03", 49.84, 24.03).copy(isCoordinate = true, kind = "")
            val search =
                MergedPlaceSearch(
                    offline = { Outcome.Success(listOf(coordinate)) },
                    online = {
                        onlineCalls += 1
                        Outcome.Success(emptyList())
                    },
                    onlineEnabled = { true },
                    positionAllowed = { false },
                )

            val result = (search.search(request()) as Outcome.Success).value

            assertEquals(listOf("coordinate"), result.map { it.id })
            assertEquals(0, onlineCalls)
        }

    @Test
    fun cancellationDuringTheDebounceReachesTheProvider() =
        runTest {
            var cancelled = false
            var onlineCalls = 0
            val search =
                MergedPlaceSearch(
                    offline = { Outcome.Success(emptyList()) },
                    online = {
                        onlineCalls += 1
                        Outcome.Success(emptyList())
                    },
                    onlineEnabled = { true },
                    positionAllowed = { false },
                    pause = { cancelled = true },
                )

            val outcome =
                search.search(
                    request(cancellation = CancellationHandle { cancelled }),
                )

            assertEquals(Outcome.Failure(AppError.Cancelled), outcome)
            assertEquals(0, onlineCalls)
        }

    @Test
    fun cancellationInsideTheOnlineCallDiscardsItsResult() =
        runTest {
            var cancelled = false
            val search =
                MergedPlaceSearch(
                    offline = { Outcome.Success(emptyList()) },
                    online = {
                        cancelled = true
                        Outcome.Success(listOf(hit("on", "АТБ", 49.8413, 24.0312, PlaceSource.ONLINE)))
                    },
                    onlineEnabled = { true },
                    positionAllowed = { false },
                    debounceMillis = 0,
                )

            val outcome =
                search.search(
                    request(cancellation = CancellationHandle { cancelled }),
                )

            assertEquals(Outcome.Failure(AppError.Cancelled), outcome)
        }

    private fun merged(
        offline: List<PlaceHit>,
        online: List<PlaceHit>,
    ): MergedPlaceSearch =
        MergedPlaceSearch(
            offline = { Outcome.Success(offline) },
            online = { Outcome.Success(online) },
            onlineEnabled = { true },
            positionAllowed = { false },
            debounceMillis = 0,
        )

    private fun request(
        context: SearchContext = SearchContext(),
        cancellation: CancellationHandle = CancellationHandle.None,
        onPartial: suspend (List<PlaceHit>) -> Unit = {},
    ): SearchScreenRequest =
        SearchScreenRequest(
            query = "львів",
            index = "lviv",
            limit = 5,
            context = context,
            cancellation = cancellation,
            onPartial = onPartial,
        )

    private fun hit(
        id: String,
        name: String,
        latitude: Double,
        longitude: Double,
        source: PlaceSource = PlaceSource.OFFLINE,
    ): PlaceHit =
        PlaceHit(
            id = id,
            name = name,
            kind = "place=square",
            position = GeoPoint(latitude, longitude),
            address = "Львів",
            regionBuild = if (source == PlaceSource.ONLINE) "photon" else "lviv",
            score = 1f,
            source = source,
        )
}
