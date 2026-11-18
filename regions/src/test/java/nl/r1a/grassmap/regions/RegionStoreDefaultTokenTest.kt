package nl.r1a.grassmap.regions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.DownloadsDefaults
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** The embedded debug token stands in for the user's only on the default server. */
class RegionStoreDefaultTokenTest {
    private lateinit var work: Path
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        work = Files.createTempDirectory("grass-default-token")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        RegionStores.delete(work)
    }

    @Test
    fun noEmbeddedTokenMeansNoTokenPresent() {
        assertFalse(store(null).data.value.tokenPresent)
        assertFalse(store("too-short").data.value.tokenPresent)
    }

    @Test
    fun anEmbeddedTokenIsPresentOnTheDefaultServer() {
        assertTrue(store(EMBEDDED).data.value.tokenPresent)
    }

    @Test
    fun anotherServerDoesNotGetTheEmbeddedToken() =
        runBlocking {
            val store = store(EMBEDDED)
            store.setServer(OTHER_SERVER, null)
            assertFalse(store.data.value.tokenPresent)
            store.setServer(DownloadsDefaults.SERVER_URL + "/", null)
            assertTrue(store.data.value.tokenPresent)
        }

    @Test
    fun aTokenTheUserEntersIsKeptOnAnyServer() =
        runBlocking {
            val store = store(EMBEDDED)
            store.setServer(OTHER_SERVER, USER_TOKEN)
            assertTrue(store.data.value.tokenPresent)
        }

    private fun store(defaultToken: String?): RegionStore {
        val refusing =
            object : NetworkAccess {
                override suspend fun <T> request(
                    switch: SwitchId,
                    block: suspend () -> Outcome<T>,
                ): Outcome<T> = Outcome.Failure(AppError.SwitchOff(switch))
            }
        return RegionStore(
            paths = RegionPaths(work.resolve("store").toFile()),
            policy = DownloadPolicy(switch = SwitchId("region-downloads"), allowCleartextHttp = false),
            network = refusing,
            scope = scope,
            transport = HttpDownloadTransport(),
            defaultToken = defaultToken,
        )
    }

    private companion object {
        const val EMBEDDED = "embedded-token-0123456789"
        const val USER_TOKEN = "user-entered-token-0123456789"
        const val OTHER_SERVER = "https://maps.example.org"
    }
}
