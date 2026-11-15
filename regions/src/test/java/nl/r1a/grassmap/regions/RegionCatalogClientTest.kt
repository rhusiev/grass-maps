package nl.r1a.grassmap.regions

import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.RegionCatalog
import nl.r1a.grassmap.plugin.api.testing.fixture.CatalogFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue request over the real transport (T-0504): the same bearer token
 * rule as a package, a status that is not a success, and a body that is not a
 * catalogue.
 */
class RegionCatalogClientTest {
    private val token = "test-token-0123456789abcdef"

    @Test
    fun theCatalogueIsFetchedWithoutATokenFromATokenlessServer() =
        runBlocking {
            TestHttpServer { exchange -> send(exchange, 200, CatalogFixture.bytes()) }.use { server ->
                val outcome = client().fetch(server.baseUrl, token = null, policy = policy())

                assertTrue(outcome is Outcome.Success)
                assertEquals(CatalogFixture.REGION_COUNT, (outcome as Outcome.Success<RegionCatalog>).value.regions.size)
                assertEquals(listOf<String?>(null), server.authorizations())
            }
        }

    @Test
    fun aServerWithTokensSeesTheBearerTokenItWasGiven() =
        runBlocking {
            TestHttpServer { exchange ->
                if (exchange.requestHeaders.getFirst(TestHttpServer.AUTHORIZATION) != "Bearer $token") {
                    send(exchange, 401, ByteArray(0))
                } else {
                    send(exchange, 200, CatalogFixture.bytes())
                }
            }.use { server ->
                val refused = client().fetch(server.baseUrl, token = null, policy = policy())
                assertEquals(Outcome.Failure(AppError.Server(401)), refused)

                val accepted = client().fetch(server.baseUrl, bearer(), policy())
                assertTrue(accepted is Outcome.Success)
                assertEquals(listOf<String?>(null, "Bearer $token"), server.authorizations())
            }
        }

    @Test
    fun aBodyThatIsNotACatalogueIsCorruptData() =
        runBlocking {
            TestHttpServer { exchange -> send(exchange, 200, "<html>not a catalogue</html>".toByteArray()) }.use { server ->
                assertEquals(
                    Outcome.Failure(AppError.CorruptData),
                    client().fetch(server.baseUrl, token = null, policy = policy()),
                )
            }
        }

    @Test
    fun aMissingCatalogueIsTheServersStatus() =
        runBlocking {
            TestHttpServer { exchange -> send(exchange, 404, ByteArray(0)) }.use { server ->
                assertEquals(
                    Outcome.Failure(AppError.Server(404)),
                    client().fetch(server.baseUrl, token = null, policy = policy()),
                )
            }
        }

    @Test
    fun anEmptyBaseUrlIsInvalidInput() =
        runBlocking {
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                client().fetch("   ", token = null, policy = policy()),
            )
        }

    private fun client(): RegionCatalogClient = RegionCatalogClient(HttpDownloadTransport())

    private fun bearer(): BearerToken =
        when (val outcome = BearerToken.of(token)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("token refused: ${outcome.error}")
        }

    private fun policy(): DownloadPolicy =
        DownloadPolicy(
            switch = SWITCH,
            connectTimeoutMs = 2_000,
            readTimeoutMs = 5_000,
            allowCleartextHttp = true,
        )

    private companion object {
        val SWITCH: SwitchId = SwitchId("network.region_updates")
    }
}
