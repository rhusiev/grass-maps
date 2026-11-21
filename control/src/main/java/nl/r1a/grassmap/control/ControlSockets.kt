package nl.r1a.grassmaps.control

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One accepted control connection, reduced to what the accept loop needs.
 *
 * The interface exists so a JVM test can drive the real loop: the Android
 * socket classes are stubs in a unit test, and neither the live-connection
 * bookkeeping nor [ControlServer.stop] is observable without one.
 */
internal interface ControlPeer {
    /** The peer's Unix uid, or null when the socket cannot report it. */
    fun uid(): Int?

    /** The frame input; throws [IOException] when the socket is gone. */
    fun input(): InputStream

    /** The frame output; throws [IOException] when the socket is gone. */
    fun output(): OutputStream

    /** Closes the connection. A closed or broken socket is not an error. */
    fun close()
}

/** The listening half of the channel, behind the same test seam. */
internal interface ControlListener {
    /** Blocks until a peer connects; null when the listener has been closed. */
    fun accept(): ControlPeer?

    /** Stops listening. A closed or broken socket is not an error. */
    fun close()
}

/** Binds the listener behind the seam; null means the bind failed. */
internal fun interface ControlSocketFactory {
    fun listen(name: String): ControlListener?
}

/** The production listener over the Linux abstract socket. */
internal object AndroidControlSockets : ControlSocketFactory {
    override fun listen(name: String): ControlListener? {
        val server =
            try {
                LocalServerSocket(name)
            } catch (exception: IOException) {
                return null
            }
        return object : ControlListener {
            override fun accept(): ControlPeer? =
                try {
                    server.accept()?.let(::AndroidControlPeer)
                } catch (exception: IOException) {
                    null
                }

            override fun close() {
                closeQuietly { server.close() }
            }
        }
    }

    private class AndroidControlPeer(
        private val socket: LocalSocket,
    ) : ControlPeer {
        override fun uid(): Int? =
            try {
                socket.peerCredentials.uid
            } catch (exception: IOException) {
                null
            } catch (exception: RuntimeException) {
                null
            }

        override fun input(): InputStream = socket.inputStream

        override fun output(): OutputStream = socket.outputStream

        override fun close() {
            closeQuietly { socket.close() }
        }
    }

    private inline fun closeQuietly(close: () -> Unit) {
        try {
            close()
        } catch (exception: IOException) {
            // Closing a dead socket is best effort.
        }
    }
}
