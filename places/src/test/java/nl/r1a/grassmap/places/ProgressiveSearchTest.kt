package nl.r1a.grassmap.places

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.PlaceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progressive publication (T-0212): the offline half reaches the screen while
 * the online debounce is still held, and a stale online half cannot append to
 * a newer keystroke.
 */
class ProgressiveSearchTest {
    @Test
    fun theOfflineRowsReachTheScreenBeforeTheOnlineDebounceReleases() =
        runTest {
            val debounceHeld = CompletableDeferred<Unit>()
            val releaseOnline = CompletableDeferred<Unit>()
            val merged =
                MergedPlaceSearch(
                    offline = { Outcome.Success(listOf(hit("offline"))) },
                    online = {
                        releaseOnline.await()
                        Outcome.Success(listOf(hit("online", PlaceSource.ONLINE)))
                    },
                    onlineEnabled = { true },
                    positionAllowed = { false },
                    pause = {
                        debounceHeld.complete(Unit)
                        releaseOnline.await()
                    },
                )
            val session = SearchScreenSession { request -> merged.search(request) }

            val running = launch { session.submit("львів", sequence = 1) }
            debounceHeld.await()

            assertEquals(SearchScreenStatus.RESULTS, session.state.value.status)
            assertEquals(
                listOf("offline"),
                session.state.value.hits
                    .map { it.id },
            )
            assertTrue("the online half must still be held", running.isActive)

            releaseOnline.complete(Unit)
            running.join()

            assertEquals(
                listOf("offline", "online"),
                session.state.value.hits
                    .map { it.id },
            )
        }

    @Test
    fun anEmptyOfflineHalfKeepsSearchingInsteadOfSayingNoMatches() =
        runTest {
            val debounceHeld = CompletableDeferred<Unit>()
            val releaseOnline = CompletableDeferred<Unit>()
            val merged =
                MergedPlaceSearch(
                    offline = { Outcome.Success(emptyList()) },
                    online = {
                        releaseOnline.await()
                        Outcome.Success(listOf(hit("online", PlaceSource.ONLINE)))
                    },
                    onlineEnabled = { true },
                    positionAllowed = { false },
                    pause = {
                        debounceHeld.complete(Unit)
                        releaseOnline.await()
                    },
                )
            val session = SearchScreenSession { request -> merged.search(request) }

            val running = launch { session.submit("львів", sequence = 1) }
            debounceHeld.await()

            assertEquals(SearchScreenStatus.LOADING, session.state.value.status)
            assertTrue(
                session.state.value.hits
                    .isEmpty(),
            )

            releaseOnline.complete(Unit)
            running.join()

            assertEquals(
                listOf("online"),
                session.state.value.hits
                    .map { it.id },
            )
            assertEquals(SearchScreenStatus.RESULTS, session.state.value.status)
        }

    @Test
    fun aStaleOnlineHalfCannotAppendToANewerQuery() =
        runTest {
            val oldStarted = CompletableDeferred<Unit>()
            val newStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val releaseNew = CompletableDeferred<Unit>()
            val session =
                SearchScreenSession { request ->
                    when (request.query) {
                        "old" -> {
                            request.onPartial(listOf(hit("old-offline")))
                            oldStarted.complete(Unit)
                            releaseOld.await()
                            Outcome.Success(
                                listOf(hit("old-offline"), hit("old-online", PlaceSource.ONLINE)),
                            )
                        }

                        else -> {
                            request.onPartial(listOf(hit("new-offline")))
                            newStarted.complete(Unit)
                            releaseNew.await()
                            Outcome.Success(
                                listOf(hit("new-offline"), hit("new-online", PlaceSource.ONLINE)),
                            )
                        }
                    }
                }

            val old = launch { session.submit("old", sequence = 1) }
            oldStarted.await()
            val newer = launch { session.submit("new", sequence = 2) }
            newStarted.await()

            releaseOld.complete(Unit)
            old.join()

            assertEquals(
                "the stale online half must not touch the newer rows",
                listOf("new-offline"),
                session.state.value.hits
                    .map { it.id },
            )

            releaseNew.complete(Unit)
            newer.join()

            assertEquals(
                listOf("new-offline", "new-online"),
                session.state.value.hits
                    .map { it.id },
            )
        }

    @Test
    fun aPartialArrivingAfterClearCannotRepublishRows() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val session =
                SearchScreenSession { request ->
                    started.complete(Unit)
                    release.await()
                    request.onPartial(listOf(hit("late")))
                    Outcome.Success(listOf(hit("late")))
                }

            val running = launch { session.submit("львів", sequence = 1) }
            started.await()
            session.clear()
            release.complete(Unit)
            running.join()

            assertEquals(SearchScreenStatus.IDLE, session.state.value.status)
            assertTrue(
                session.state.value.hits
                    .isEmpty(),
            )
        }

    private fun hit(
        id: String,
        source: PlaceSource = PlaceSource.OFFLINE,
    ): PlaceHit =
        PlaceHit(
            id = id,
            name = id,
            kind = "place=street",
            position = GeoPoint(49.8419, 24.0316),
            address = "Львів",
            regionBuild = if (source == PlaceSource.ONLINE) "photon" else "lviv",
            score = 1f,
            source = source,
        )
}
