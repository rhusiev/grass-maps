package nl.r1a.grassmap.tracks

import java.security.MessageDigest

/**
 * Content identity for a stored track. A one-track document is named by the
 * SHA-256 of its bytes, so the same file imported twice is the same record. A
 * document with several tracks appends `:<index>`, so each track keeps a stable
 * id while the document stays one file on disk.
 */
object TrackIdentity {
    private const val SHA_256 = "SHA-256"
    private const val BYTE_MASK = 0xff
    private const val INDEX_SEPARATOR = ':'

    /** The SHA-256 of [bytes] as lowercase hex. */
    fun documentHash(bytes: ByteArray): String =
        MessageDigest
            .getInstance(SHA_256)
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }

    /** The id of track [index] of [count] tracks read from [bytes]. */
    fun of(
        bytes: ByteArray,
        index: Int,
        count: Int,
    ): TrackId {
        val hash = documentHash(bytes)
        return TrackId(if (count == 1) hash else "$hash$INDEX_SEPARATOR$index")
    }

    /** The document file name stem of [id], without the track suffix. */
    fun documentHashOf(id: TrackId): String = id.value.substringBefore(INDEX_SEPARATOR)

    /** True when [id] is a hash, optionally with a track index suffix. */
    fun isWellFormed(id: TrackId): Boolean = PATTERN.matches(id.value)

    private val PATTERN = Regex("^[0-9a-f]{64}(:[0-9]+)?$")
}
