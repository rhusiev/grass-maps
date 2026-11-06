package nl.r1a.grassmap.control

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A bearer token for the control channel.
 *
 * The raw value is never exposed through a public member and [toString] is
 * redacted, so an accidental log call cannot leak it. Only [ControlTokenFile]
 * reads the raw value, to hand it to the host CLI.
 */
class ControlToken internal constructor(
    private val value: String,
) {
    /** Constant-time comparison: a wrong token cannot be found byte by byte. */
    fun matches(candidate: String): Boolean {
        val expected = value.toByteArray(Charsets.UTF_8)
        val actual = candidate.toByteArray(Charsets.UTF_8)
        return expected.size == actual.size && MessageDigest.isEqual(expected, actual)
    }

    internal fun rawValue(): String = value

    override fun toString(): String = REDACTED

    private companion object {
        const val REDACTED = "ControlToken(<redacted>)"
    }
}

/**
 * Generates and parses the 256-bit token. Parsing exists for the debug token
 * file, so a truncated or corrupted file is a typed failure, not a weak token.
 */
object ControlTokens {
    const val TOKEN_BYTES: Int = 32
    const val TOKEN_HEX_LENGTH: Int = TOKEN_BYTES * 2

    fun generate(random: SecureRandom = SecureRandom()): ControlToken {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return ControlToken(bytes.toHex())
    }

    fun fromHex(value: String): Outcome<ControlToken> {
        val trimmed = value.trim()
        if (trimmed.length != TOKEN_HEX_LENGTH || !trimmed.all(::isHexDigit)) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        return Outcome.Success(ControlToken(trimmed.lowercase()))
    }

    private fun isHexDigit(character: Char): Boolean = character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'

    private const val HEX_DIGITS = "0123456789abcdef"

    private fun ByteArray.toHex(): String =
        buildString(size * 2) {
            for (byte in this@toHex) {
                val value = byte.toInt() and BYTE_MASK
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and NIBBLE_MASK])
            }
        }

    private const val BYTE_MASK = 0xFF
    private const val NIBBLE_MASK = 0x0F
}

/**
 * Writes the token to an app-private file only the app's own UID can read.
 *
 * Debug builds use this so the host CLI can read the token with
 * `adb exec-out run-as <package> cat files/control-token`. The token is never
 * logged.
 */
object ControlTokenFile {
    const val FILE_NAME: String = "control-token"

    fun write(
        directory: File,
        token: ControlToken,
    ): Outcome<File> {
        val file = File(directory, FILE_NAME)
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) {
                return Outcome.Failure(AppError.Internal("control.token_dir"))
            }
            FileOutputStream(file).use { stream ->
                stream.write(token.rawValue().toByteArray(Charsets.UTF_8))
                stream.write('\n'.code)
                stream.getFD().sync()
            }
            restrictToOwner(file)
            Outcome.Success(file)
        } catch (exception: IOException) {
            Outcome.Failure(AppError.Internal("control.token_write"))
        } catch (exception: SecurityException) {
            Outcome.Failure(AppError.Internal("control.token_write"))
        }
    }

    fun read(file: File): Outcome<ControlToken> {
        val text =
            try {
                file.readText(Charsets.UTF_8)
            } catch (exception: IOException) {
                return Outcome.Failure(AppError.NotFound)
            }
        return ControlTokens.fromHex(text)
    }

    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}

/**
 * The peer credential rule.
 *
 * Android reports the connecting process through `SO_PEERCRED`. Two kinds of
 * peer are admitted:
 *
 * - the app's own UID, which is what an in-app client has;
 * - the adb daemon's UID, because `adb forward` proxies a host connection
 *   through adbd. On an emulator adbd runs as root (0), on a device as shell
 *   (2000). Admitting adbd adds no authority a token holder lacks: any process
 *   that can reach adbd can already read the app's private files with
 *   `adb run-as`, the token included.
 *
 * The bearer token is still required after this gate. A different app UID never
 * passes it.
 */
object ControlPeerGate {
    const val UID_ROOT: Int = 0
    const val UID_SHELL: Int = 2000

    val ADB_TRANSPORT_UIDS: Set<Int> = setOf(UID_ROOT, UID_SHELL)

    /** The UIDs this channel admits: the app itself plus the adb transport. */
    fun admittedUids(appUid: Int): Set<Int> = ADB_TRANSPORT_UIDS + appUid

    fun admits(
        peerUid: Int,
        admittedUids: Set<Int>,
    ): Boolean = peerUid >= 0 && peerUid in admittedUids
}
