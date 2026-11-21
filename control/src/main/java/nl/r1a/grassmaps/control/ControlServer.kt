package nl.r1a.grassmaps.control

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import java.io.IOException

/** What the server reports to its owner. No state value carries a secret. */
enum class ControlServerState {
    LISTENING,
    BIND_FAILED,
    PEER_UID_UNAVAILABLE,
    PEER_REFUSED,
    CONNECTION_CLOSED,
    STOPPED,
}

/**
 * The Linux abstract-socket listener. Debug builds only: the app module depends
 * on this module in the debug configuration alone.
 *
 * Two gates run before a single frame is served: the peer UID from `SO_PEERCRED`
 * must be admitted ([ControlPeerGate]), and the session must receive the bearer
 * token. One controller is served at a time; the listener accepts the next
 * connection after the current one ends.
 *
 * [stop] closes the connection being served, not only the listener, so a session
 * a start left behind cannot answer a command after [stop] returned or overlap
 * the next [start].
 */
class ControlServer internal constructor(
    private val socketName: String,
    private val token: ControlToken,
    private val endpoint: ControlEndpoint,
    private val admittedUids: Set<Int>,
    private val onState: (ControlServerState) -> Unit,
    private val dispatcher: CoroutineDispatcher,
    private val maxFailedAuth: Int,
    private val log: ControlLog,
    private val sockets: ControlSocketFactory,
) {
    /**
     * The production shape: the Linux abstract socket, the default tuning and
     * the process log.
     */
    constructor(
        socketName: String,
        token: ControlToken,
        endpoint: ControlEndpoint,
        admittedUids: Set<Int>,
        onState: (ControlServerState) -> Unit = {},
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        maxFailedAuth: Int = ControlSession.DEFAULT_MAX_FAILED_AUTH,
        log: ControlLog = ControlLog.None,
    ) : this(
        socketName = socketName,
        token = token,
        endpoint = endpoint,
        admittedUids = admittedUids,
        onState = onState,
        dispatcher = dispatcher,
        maxFailedAuth = maxFailedAuth,
        log = log,
        sockets = AndroidControlSockets,
    )

    private val exceptionHandler =
        CoroutineExceptionHandler { _, error ->
            log.error("control server coroutine failed", error)
        }

    /**
     * The lifecycle fields share one lock: a connection is adopted only while a
     * start owns the server, and [stop] takes the live one to close it. Without
     * that, a connection accepted as the owner stops would be served on, unseen.
     */
    private val stateLock = Any()

    // A cancelled scope cannot accept again, so each start creates a fresh one.
    private var scope: CoroutineScope? = null
    private var listener: ControlListener? = null
    private var acceptJob: Job? = null
    private var live: ControlPeer? = null

    fun start(): Outcome<Unit> {
        if (acceptJob != null) {
            return Outcome.Success(Unit)
        }
        val listening =
            sockets.listen(socketName)
                ?: run {
                    onState(ControlServerState.BIND_FAILED)
                    return Outcome.Failure(AppError.Internal("control.bind"))
                }
        val running = CoroutineScope(SupervisorJob() + dispatcher + exceptionHandler)
        synchronized(stateLock) {
            listener = listening
            scope = running
            acceptJob = running.launch { acceptLoop(listening) }
        }
        onState(ControlServerState.LISTENING)
        return Outcome.Success(Unit)
    }

    fun stop() {
        val stopped =
            synchronized(stateLock) {
                val wasRunning = acceptJob != null
                acceptJob = null
                listener?.close()
                listener = null
                scope?.cancel()
                scope = null
                live?.close()
                live = null
                wasRunning
            }
        if (stopped) {
            onState(ControlServerState.STOPPED)
        }
    }

    private suspend fun acceptLoop(listening: ControlListener) {
        while (currentCoroutineContext().isActive) {
            val connection =
                try {
                    withContext(dispatcher) { listening.accept() }
                } catch (exception: IOException) {
                    break
                } ?: break
            if (!adopt(connection)) {
                connection.close()
                break
            }
            try {
                serve(connection)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                log.error("control session failed", exception)
            } catch (error: LinkageError) {
                log.error("control session failed to link", error)
            } finally {
                release(connection)
                connection.close()
            }
            onState(ControlServerState.CONNECTION_CLOSED)
        }
    }

    /**
     * Registers [connection] as the live one. False means the server stopped
     * first, so the caller must close it instead of serving it.
     */
    private fun adopt(connection: ControlPeer): Boolean =
        synchronized(stateLock) {
            if (acceptJob == null) {
                false
            } else {
                live = connection
                true
            }
        }

    private fun release(connection: ControlPeer) {
        synchronized(stateLock) {
            if (live === connection) {
                live = null
            }
        }
    }

    private suspend fun serve(connection: ControlPeer) {
        val uid = connection.uid()
        if (uid == null) {
            onState(ControlServerState.PEER_UID_UNAVAILABLE)
            return
        }
        if (!ControlPeerGate.admits(uid, admittedUids)) {
            onState(ControlServerState.PEER_REFUSED)
            return
        }

        val transport =
            try {
                StreamFrameTransport(
                    input = connection.input(),
                    output = connection.output(),
                    ioDispatcher = dispatcher,
                )
            } catch (exception: IOException) {
                return
            }
        try {
            ControlSession(
                endpoint = endpoint,
                token = token,
                transport = transport,
                maxFailedAuth = maxFailedAuth,
                log = log,
            ).run()
        } finally {
            transport.close()
        }
    }
}
