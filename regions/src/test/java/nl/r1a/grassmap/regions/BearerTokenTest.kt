package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BearerTokenTest {
    private val secret = "test-token-0123456789abcdef"

    @Test
    fun aUsableTokenBecomesABearerHeader() {
        val token = success(BearerToken.of(secret))

        assertEquals("Bearer $secret", token.authorizationHeader)
    }

    @Test
    fun theTokenNeverAppearsInItsStringForm() {
        val token = success(BearerToken.of(secret))

        assertFalse(token.toString().contains(secret))
        assertEquals("BearerToken(redacted)", token.toString())
    }

    @Test
    fun aShortTokenIsInvalid() {
        assertEquals(Outcome.Failure(AppError.InvalidInput), BearerToken.of("short"))
    }

    @Test
    fun whitespaceAndControlBytesAreRefused() {
        assertEquals(Outcome.Failure(AppError.InvalidInput), BearerToken.of("$secret\r\nX-Evil: 1"))
        assertEquals(Outcome.Failure(AppError.InvalidInput), BearerToken.of("$secret with space"))
        assertTrue(BearerToken.of(" $secret ").let { it is Outcome.Success })
    }

    private fun success(outcome: Outcome<BearerToken>): BearerToken =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("token refused: ${outcome.error}")
        }
}
