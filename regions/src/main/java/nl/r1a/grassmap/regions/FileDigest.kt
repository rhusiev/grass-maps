package nl.r1a.grassmaps.regions

import nl.r1a.grassmaps.plugin.api.contract.CancellationHandle
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/** SHA-256 of a file, read in blocks so a cancel is seen between them. */
internal object FileDigest {
    const val SHA256_ALGORITHM = "SHA-256"
    private const val HASH_BUFFER_BYTES = 64 * 1024

    fun sha256(
        file: File,
        cancellation: CancellationHandle,
    ): Outcome<String> {
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                while (true) {
                    if (cancellation.isCancelled()) {
                        return Outcome.Failure(AppError.Cancelled)
                    }
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read > 0) {
                        digest.update(buffer, 0, read)
                    }
                }
            }
        } catch (exception: IOException) {
            return Outcome.Failure(AppError.CorruptData)
        }
        return Outcome.Success(hex(digest.digest()))
    }

    fun sha256(text: String): String = hex(MessageDigest.getInstance(SHA256_ALGORITHM).digest(text.toByteArray(Charsets.UTF_8)))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
