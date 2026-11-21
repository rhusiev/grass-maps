package nl.r1a.grassmaps.plugins.raster

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HTTP transport follows a redirect only inside the request's own host. */
class RasterTileTransportTest {
    @Test
    fun aRedirectWithinTheSameHostIsFollowed() =
        runTest {
            ScriptedTileServer { _, path ->
                if (path == "/old") Reply(status = FOUND, headers = mapOf("Location" to "/new")) else Reply()
            }.use { server ->
                val outcome = HttpRasterTileTransport().get("${server.baseUrl}/old", AGENT)

                assertTrue(outcome is Outcome.Success)
                assertEquals(listOf("/old", "/new"), server.paths())
            }
        }

    @Test
    fun aRedirectToAnotherHostIsNotFollowed() =
        runTest {
            ScriptedTileServer { _, _ ->
                Reply(status = FOUND, headers = mapOf("Location" to "http://localhost:$otherPort/elsewhere"))
            }.use { server ->
                otherPort = server.port

                val outcome = HttpRasterTileTransport().get("${server.baseUrl}/old", AGENT)

                assertEquals(Outcome.Failure(AppError.Server(status = FOUND)), outcome)
                assertEquals(listOf("/old"), server.paths())
            }
        }

    @Test
    fun aRetryAfterHeaderBecomesTheServerErrorsDelay() =
        runTest {
            ScriptedTileServer { _, _ -> Reply(status = TOO_MANY, headers = mapOf("Retry-After" to "7")) }.use { server ->
                val outcome = HttpRasterTileTransport().get("${server.baseUrl}/t", AGENT)

                assertEquals(Outcome.Failure(AppError.Server(status = TOO_MANY, retryAfter = 7L)), outcome)
            }
        }

    private var otherPort = 0

    private companion object {
        const val AGENT = "GrassMapsTest"
        const val FOUND = 302
        const val TOO_MANY = 429
    }
}
