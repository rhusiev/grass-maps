package nl.r1a.grassmaps.control

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import nl.r1a.grassmaps.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The server's lifecycle over a fake socket layer: Java's unit tests replace the
 * Android socket classes with stubs, so the live-connection rule is only
 * observable through [ControlSocketFactory].
 */
class ControlServerTest {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val states = CopyOnWriteArrayList<ControlServerState>()
    private var server: ControlServer? = null

    @After
    fun releaseServer() {
        server?.stop()
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    @Test
    fun stopClosesTheConnectionItIsServing() {
        val factory = FakeSocketFactory()
        val started = start(factory)
        val connection = factory.current.peer
        assertTrue("the loop must be serving the peer", connection.awaitServing())

        started.stop()

        assertTrue("stop must close the live connection", connection.awaitClosed())
        assertTrue(states.contains(ControlServerState.STOPPED))
    }

    @Test
    fun theConnectionASessionLeftBehindIsClosedBeforeTheNextStart() {
        val factory = FakeSocketFactory()
        val started = start(factory)
        val stale = factory.current.peer
        assertTrue(stale.awaitServing())

        started.stop()
        assertTrue("a session must not outlive its stop", stale.awaitClosed())

        assertEquals(Outcome.Success(Unit), started.start())
        val fresh = factory.current.peer
        assertTrue("the new listener must serve the next peer", fresh.awaitServing())
        assertTrue("the new start must accept a new connection", fresh !== stale)
        assertEquals(2, factory.listeners.size)
    }

    private fun start(factory: FakeSocketFactory): ControlServer {
        val created =
            ControlServer(
                socketName = "nl.r1a.grassmaps.control.test",
                token = ControlTokens.generate(),
                endpoint =
                    ControlEndpoint(
                        catalog = ListCatalog(emptyList()),
                        invoker = RecordingInvoker(),
                        streams = FakeStreams(),
                    ),
                admittedUids = setOf(PEER_UID),
                onState = { state: ControlServerState -> states.add(state) },
                dispatcher = dispatcher,
                maxFailedAuth = ControlSession.DEFAULT_MAX_FAILED_AUTH,
                log = ControlLog.None,
                sockets = factory,
            )
        server = created
        assertEquals(Outcome.Success(Unit), created.start())
        return created
    }

    private class FakeSocketFactory : ControlSocketFactory {
        val listeners = CopyOnWriteArrayList<FakeListener>()
        val current: FakeListener get() = listeners.last()

        override fun listen(name: String): ControlListener = FakeListener().also { listener -> listeners += listener }
    }

    private class FakeListener : ControlListener {
        private val handedOut = AtomicBoolean(false)
        private val closed = CountDownLatch(1)
        val peer = FakePeer()

        override fun accept(): ControlPeer? {
            if (handedOut.compareAndSet(false, true)) {
                return peer
            }
            closed.await()
            return null
        }

        override fun close() {
            closed.countDown()
        }
    }

    private class FakePeer : ControlPeer {
        private val serving = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        private val input = BlockingInputStream(closed)

        fun awaitServing(): Boolean = serving.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        fun awaitClosed(): Boolean = closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        override fun uid(): Int {
            serving.countDown()
            return PEER_UID
        }

        override fun input(): InputStream = input

        override fun output(): OutputStream = OutputStream.nullOutputStream()

        override fun close() {
            closed.countDown()
        }
    }

    /**
     * A frame read that blocks like a socket read and fails once the peer is
     * closed, which is what makes a stop that forgets the connection detectable.
     */
    private class BlockingInputStream(
        private val closed: CountDownLatch,
    ) : InputStream() {
        override fun read(): Int {
            closed.await()
            throw IOException("the connection is closed")
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            closed.await()
            throw IOException("the connection is closed")
        }
    }

    private companion object {
        const val PEER_UID = 10_000
        const val TIMEOUT_SECONDS = 2L
    }
}
