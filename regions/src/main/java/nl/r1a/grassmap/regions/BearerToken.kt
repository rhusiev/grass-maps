package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The credential a download request carries in its `Authorization` header.
 *
 * The secret is reachable only inside `regions`: [toString] is redacted, so a
 * token cannot reach a log line through interpolation or an exception message.
 * A token with whitespace or non-ASCII bytes is refused, because it could split
 * or corrupt the header.
 */
@JvmInline
value class BearerToken private constructor(
    private val secret: String,
) {
    internal val authorizationHeader: String
        get() = "$SCHEME $secret"

    override fun toString(): String = "BearerToken(redacted)"

    companion object {
        const val SCHEME: String = "Bearer"

        /** The shortest token accepted; a token is a random 256-bit value. */
        const val MIN_LENGTH: Int = 16

        private const val PRINTABLE_FIRST: Int = 0x21
        private const val PRINTABLE_LAST: Int = 0x7e

        /** Accepts [raw] when it is a usable header value, else [AppError.InvalidInput]. */
        fun of(raw: String): Outcome<BearerToken> {
            val token = raw.trim()
            if (token.length < MIN_LENGTH) {
                return Outcome.Failure(AppError.InvalidInput)
            }
            if (token.any { it.code !in PRINTABLE_FIRST..PRINTABLE_LAST }) {
                return Outcome.Failure(AppError.InvalidInput)
            }
            return Outcome.Success(BearerToken(token))
        }
    }
}
