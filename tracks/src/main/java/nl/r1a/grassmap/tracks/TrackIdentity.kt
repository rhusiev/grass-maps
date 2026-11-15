package nl.r1a.grassmap.tracks

import java.security.MessageDigest

/**
 * Content identity for a stored track: the SHA-256 of its document's bytes and
 * its position in that document. The first record is the bare hash and each
 * later one appends `:<index>`, so a record's id does not depend on how many
 * records the document holds. A build that starts reading another element
 * family appends records instead of renaming the ones already stored, and the
 * document stays one file on disk.
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

    /** The id of record [index] of the document [bytes]. */
    fun of(
        bytes: ByteArray,
        index: Int,
    ): TrackId {
        val hash = documentHash(bytes)
        return TrackId(if (index == 0) hash else "$hash$INDEX_SEPARATOR$index")
    }

    /** The document file name stem of [id], without the track suffix. */
    fun documentHashOf(id: TrackId): String = id.value.substringBefore(INDEX_SEPARATOR)

    /** True when [id] is a hash, optionally with a track index suffix. */
    fun isWellFormed(id: TrackId): Boolean = PATTERN.matches(id.value)

    private val PATTERN = Regex("^[0-9a-f]{64}(:[0-9]+)?$")
}
