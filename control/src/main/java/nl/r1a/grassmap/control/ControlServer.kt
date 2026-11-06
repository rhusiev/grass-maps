package nl.r1a.grassmap.control

import android.net.LocalServerSocket
import android.net.LocalSocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
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
 */
class ControlServer(
    private val socketName: String,
    private val token: ControlToken,
    private val endpoint: ControlEndpoint,
    private val admittedUids: Set<Int>,
    private val onState: (ControlServerState) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxFailedAuth: Int = ControlSession.DEFAULT_MAX_FAILED_AUTH,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var serverSocket: LocalServerSocket? = null
    private var acceptJob: Job? = null

    fun start(): Outcome<Unit> {
        if (acceptJob != null) {
            return Outcome.Success(Unit)
        }
        val socket =
            try {
                LocalServerSocket(socketName)
            } catch (exception: IOException) {
                onState(ControlServerState.BIND_FAILED)
                return Outcome.Failure(AppError.Internal("control.bind"))
            }
        serverSocket = socket
        acceptJob = scope.launch { acceptLoop(socket) }
        onState(ControlServerState.LISTENING)
        return Outcome.Success(Unit)
    }

    fun stop() {
        val wasRunning = acceptJob != null
        acceptJob = null
        closeQuietly(serverSocket)
        serverSocket = null
        scope.cancel()
        if (wasRunning) {
            onState(ControlServerState.STOPPED)
        }
    }

    private suspend fun acceptLoop(socket: LocalServerSocket) {
        while (currentCoroutineContext().isActive) {
            val connection =
                try {
                    withContext(dispatcher) { socket.accept() }
                } catch (exception: IOException) {
                    break
                } ?: break
            try {
                serve(connection)
            } finally {
                closeQuietly(connection)
            }
            onState(ControlServerState.CONNECTION_CLOSED)
        }
    }

    private suspend fun serve(connection: LocalSocket) {
        val peerUid =
            try {
                Outcome.Success(connection.peerCredentials.uid)
            } catch (exception: IOException) {
                Outcome.Failure(AppError.NotReady)
            } catch (exception: RuntimeException) {
                Outcome.Failure(AppError.NotReady)
            }
        val uid =
            when (peerUid) {
                is Outcome.Success -> {
                    peerUid.value
                }

                is Outcome.Failure -> {
                    onState(ControlServerState.PEER_UID_UNAVAILABLE)
                    return
                }
            }
        if (!ControlPeerGate.admits(uid, admittedUids)) {
            onState(ControlServerState.PEER_REFUSED)
            return
        }

        val transport =
            try {
                StreamFrameTransport(
                    input = connection.inputStream,
                    output = connection.outputStream,
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
            ).run()
        } finally {
            transport.close()
        }
    }

    private fun closeQuietly(socket: LocalSocket?) {
        try {
            socket?.close()
        } catch (exception: IOException) {
            // Closing a dead socket is best effort.
        }
    }

    private fun closeQuietly(socket: LocalServerSocket?) {
        try {
            socket?.close()
        } catch (exception: IOException) {
            // Closing a dead listener is best effort.
        }
    }
}
