package nl.r1a.grassmap.places

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering and cancellation rules of the search screen (T-0203).
 *
 * The command bus cancels a running keystroke when the next one arrives, but a
 * caller can also reach the session directly, so the session keeps its own
 * sequence guard. These tests drive it with a controllable provider: a keystroke
 * can be held open, so the "slow older result" race is reproduced rather than
 * described.
 */
class SearchScreenSessionTest {
    @Test
    fun `a superseded keystroke publishes nothing`() =
        runBlocking {
            val first = CompletableDeferred<Outcome<List<PlaceHit>>>()
            val second = CompletableDeferred<Outcome<List<PlaceHit>>>()
            val firstStarted = CompletableDeferred<Unit>()
            val session =
                SearchScreenSession { request ->
                    when (request.query) {
                        "ab" -> {
                            firstStarted.complete(Unit)
                            first.await()
                        }

                        else -> {
                            second.await()
                        }
                    }
                }
            var older: Outcome<SearchScreenState>? = null
            var newer: Outcome<SearchScreenState>? = null
            val olderJob = launch { older = session.submit("ab", sequence = 1) }
            firstStarted.await()
            val newerJob = launch { newer = session.submit("abc", sequence = 2) }
            second.complete(Outcome.Success(listOf(hit("newer"))))
            newerJob.join()
            first.complete(Outcome.Success(listOf(hit("older"))))
            olderJob.join()

            assertEquals(
                listOf("newer"),
                session.state.value.hits
                    .map { it.id },
            )
            assertEquals(SearchScreenStatus.RESULTS, session.state.value.status)
            assertEquals(2L, session.state.value.sequence)
            assertEquals(Outcome.Failure(AppError.Cancelled), older)
            assertTrue("the newer keystroke must succeed", newer is Outcome.Success)
        }

    @Test
    fun `a newer keystroke cancels the older provider call`() =
        runBlocking {
            val handles = mutableListOf<nl.r1a.grassmap.plugin.api.contract.CancellationHandle>()
            val firstStarted = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            val session =
                SearchScreenSession { request ->
                    handles += request.cancellation
                    if (request.query == "ab") {
                        firstStarted.complete(Unit)
                        hold.await()
                    }
                    Outcome.Success(listOf(hit(request.query)))
                }
            val older = launch { session.submit("ab", sequence = 1) }
            firstStarted.await()
            assertFalse("no cancellation before a newer keystroke", handles.first().isCancelled())
            val newer = launch { session.submit("abc", sequence = 2) }
            newer.join()

            assertTrue("the older handle must flip", handles.first().isCancelled())
            assertEquals(
                listOf("abc"),
                session.state.value.hits
                    .map { it.id },
            )
            hold.complete(Unit)
            older.join()
            assertEquals(
                "the older result must not replace the newer one",
                listOf("abc"),
                session.state.value.hits
                    .map { it.id },
            )
        }

    @Test
    fun `a blank query goes idle without searching`() =
        runBlocking {
            var calls = 0
            val session =
                SearchScreenSession { request ->
                    calls += 1
                    Outcome.Success(listOf(hit(request.query)))
                }
            session.submit("львів", sequence = 1)
            val outcome = session.submit("   ", sequence = 2)

            assertTrue(outcome is Outcome.Success)
            assertEquals(1, calls)
            assertEquals(SearchScreenStatus.IDLE, session.state.value.status)
            assertTrue(
                session.state.value.hits
                    .isEmpty(),
            )
        }

    @Test
    fun `a failure publishes its error and the retry can succeed`() =
        runBlocking {
            var fail = true
            val session =
                SearchScreenSession { request ->
                    if (fail) {
                        Outcome.Failure(AppError.NotReady)
                    } else {
                        Outcome.Success(listOf(hit(request.query)))
                    }
                }
            assertEquals(Outcome.Failure(AppError.NotReady), session.submit("львів", sequence = 1))
            assertEquals(SearchScreenStatus.FAILED, session.state.value.status)
            assertEquals(AppError.NotReady, session.state.value.error)

            fail = false
            assertTrue(session.submit("львів", sequence = 2) is Outcome.Success)
            assertEquals(SearchScreenStatus.RESULTS, session.state.value.status)
            assertEquals(null, session.state.value.error)
        }

    @Test
    fun `a cancelled provider result is not published`() =
        runBlocking {
            val session = SearchScreenSession { Outcome.Failure(AppError.Cancelled) }
            val outcome = session.submit("львів", sequence = 1)

            assertEquals(Outcome.Failure(AppError.Cancelled), outcome)
            assertEquals(SearchScreenStatus.LOADING, session.state.value.status)
            assertTrue(
                session.state.value.hits
                    .isEmpty(),
            )
        }

    @Test
    fun `a keystroke older than the last started one is refused`() =
        runBlocking {
            val session = SearchScreenSession { request -> Outcome.Success(listOf(hit(request.query))) }
            assertTrue(session.submit("львів", sequence = 4) is Outcome.Success)
            assertEquals(Outcome.Failure(AppError.Cancelled), session.submit("льв", sequence = 3))
            assertEquals(4L, session.state.value.sequence)
        }

    @Test
    fun `clear empties the screen and cancels the running keystroke`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            var handle: nl.r1a.grassmap.plugin.api.contract.CancellationHandle? = null
            val session =
                SearchScreenSession { request ->
                    handle = request.cancellation
                    started.complete(Unit)
                    hold.await()
                    Outcome.Success(listOf(hit(request.query)))
                }
            val running = launch { session.submit("львів", sequence = 1) }
            started.await()
            session.clear()

            assertTrue("clear must cancel the running call", handle?.isCancelled() == true)
            assertEquals(SearchScreenStatus.IDLE, session.state.value.status)
            hold.complete(Unit)
            running.join()
            assertEquals("a cleared screen must stay empty", SearchScreenStatus.IDLE, session.state.value.status)
        }

    private fun hit(id: String): PlaceHit =
        PlaceHit(
            id = id,
            name = id,
            kind = "place=street",
            position = GeoPoint(49.8419, 24.0316),
            address = null,
            regionBuild = "test",
            score = 1f,
        )
}
