package nl.r1a.grassmap.regions

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.DownloadsDefaults
import nl.r1a.grassmap.plugin.api.testing.fixture.CatalogFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The monthly background update (T-0508) with a fake clock, a local server and
 * the real region store: what is asked of the server, what is queued, and what
 * the notification says.
 */
class AutoUpdaterTest {
    private lateinit var root: Path
    private lateinit var paths: RegionPaths
    private lateinit var scope: CoroutineScope
    private val clock = MutableClock(START)
    private val metered = FakeMetered()
    private val ledger = InMemoryLedger()
    private val notices = Collections.synchronizedList(mutableListOf<AutoUpdateNotice>())
    private val catalogRequests = AtomicInteger()
    private val packageRequests = AtomicInteger()

    @Volatile
    private var catalogStatus = HTTP_OK

    @Volatile
    private var isSlow = false

    @Volatile
    private var catalog: ByteArray = ByteArray(0)

    private var switchOn = true

    @Before
    fun setUp() {
        root = Files.createTempDirectory("grass-auto-update")
        paths = RegionPaths(root.toFile())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        setCatalog(OLD_VERSION)
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    @Test
    fun thirtyOneDaysAfterTheLastCheckOnAnUnmeteredNetworkTheUpdateInstallsAndNotifies() =
        withUpdater { store, updater ->
            clock.advance(DAYS_31)
            val before = catalogRequests.get()

            val run = updater.runIfDue()

            assertTrue(run is AutoUpdateRun.Queued)
            updater.announce(run as AutoUpdateRun.Queued)
            assertEquals(before + 1, catalogRequests.get())
            assertEquals(listOf<AutoUpdateNotice>(AutoUpdateNotice.Installed(1)), notices)
            assertEquals(NEW_VERSION, store.data.value.installed(CatalogFixture.LVIV)?.version)
            assertEquals(clock.millis(), ledger.load().lastCheckMs)
        }

    @Test
    fun thirtyOneDaysAfterTheLastCheckOnMobileDataWithWifiOnlyWaitsWithoutARequest() =
        withUpdater(startMetered = true) { _, updater ->
            clock.advance(DAYS_31)
            val before = catalogRequests.get()

            val run = updater.runIfDue()

            assertEquals(AutoUpdateRun.WaitingForWifi, run)
            assertEquals(before, catalogRequests.get())
            assertTrue(notices.isEmpty())
            assertEquals(START.toEpochMilli(), ledger.load().lastCheckMs)
        }

    @Test
    fun theUpdateRunsOnTheFirstWindowThatHasAnUnmeteredNetwork() =
        withUpdater(startMetered = true) { store, updater ->
            clock.advance(DAYS_31)
            assertEquals(AutoUpdateRun.WaitingForWifi, updater.runIfDue())

            metered.metered.value = false
            withTimeout(TIMEOUT_MS) { store.data.first { !it.networkMetered } }
            val run = updater.runIfDue()

            assertTrue(run is AutoUpdateRun.Queued)
            updater.announce(run as AutoUpdateRun.Queued)
            assertEquals(listOf<AutoUpdateNotice>(AutoUpdateNotice.Installed(1)), notices)
        }

    @Test
    fun anUpdateHeldByAMeteredNetworkAfterTheCheckNotifiesThatItWaitsThenThatItInstalled() =
        withUpdater { store, updater ->
            clock.advance(DAYS_31)
            isSlow = true
            val run = updater.runIfDue() as AutoUpdateRun.Queued
            withTimeout(TIMEOUT_MS) { store.data.first { data -> data.transfers.any { it.bytesDone > 0 } } }
            metered.metered.value = true
            val announcing = scope.launch { updater.announce(run) }

            withTimeout(TIMEOUT_MS) { waitFor { notices.size == 1 } }
            assertEquals(AutoUpdateNotice.WaitingForWifi(1), notices[0])
            metered.metered.value = false
            announcing.join()

            assertEquals(listOf(AutoUpdateNotice.WaitingForWifi(1), AutoUpdateNotice.Installed(1)), notices)
        }

    @Test
    fun lessThanThirtyDaysAfterTheLastCheckNothingIsRequested() =
        withUpdater { _, updater ->
            clock.advance(DAYS_29)
            val before = catalogRequests.get()

            assertEquals(AutoUpdateRun.NotDue, updater.runIfDue())

            assertEquals(before, catalogRequests.get())
            assertEquals(0, packageRequests.get() - PACKAGE_REQUESTS_FOR_SETUP)
        }

    @Test
    fun withThePrivacySwitchOffNoRequestIsMade() =
        withUpdater { _, updater ->
            clock.advance(DAYS_31)
            switchOn = false
            val before = catalogRequests.get()

            assertEquals(AutoUpdateRun.SwitchOff, updater.runIfDue())

            assertEquals(before, catalogRequests.get())
            assertEquals(PACKAGE_REQUESTS_FOR_SETUP, packageRequests.get())
        }

    @Test
    fun withAutomaticUpdatesTurnedOffNoRequestIsMade() =
        withUpdater { store, updater ->
            clock.advance(DAYS_31)
            store.setAutoUpdate(false)
            val before = catalogRequests.get()

            assertEquals(AutoUpdateRun.Disabled, updater.runIfDue())

            assertEquals(before, catalogRequests.get())
        }

    @Test
    fun aFailedCheckIsRetriedAtTheNextWindowAndNotBefore() =
        withUpdater { _, updater ->
            clock.advance(DAYS_31)
            catalogStatus = HTTP_UNAVAILABLE
            val before = catalogRequests.get()

            assertEquals(AutoUpdateRun.Failed, updater.runIfDue())
            clock.advance(Duration.ofHours(1))
            assertEquals(AutoUpdateRun.BackingOff, updater.runIfDue())
            clock.advance(Duration.ofHours(RETRY_HOURS - 1).minusSeconds(1))
            assertEquals(AutoUpdateRun.BackingOff, updater.runIfDue())
            assertEquals(before + 1, catalogRequests.get())

            catalogStatus = HTTP_OK
            clock.advance(Duration.ofSeconds(1))
            val retry = updater.runIfDue()

            assertTrue(retry is AutoUpdateRun.Queued)
            assertEquals(before + 2, catalogRequests.get())
            assertEquals(START.toEpochMilli(), ledger.load().lastCheckMs)
        }

    @Test
    fun aCheckThatFindsNothingNewRecordsTheCheckAndStaysQuiet() =
        withUpdater(newVersionAvailable = false) { _, updater ->
            clock.advance(DAYS_31)

            assertEquals(AutoUpdateRun.UpToDate, updater.runIfDue())

            assertEquals(clock.millis(), ledger.load().lastCheckMs)
            assertTrue(notices.isEmpty())
            assertEquals(AutoUpdateRun.NotDue, updater.runIfDue())
        }

    @Test
    fun aClockSetBackBeforeTheLastCheckDoesNotHoldTheCheckOff() =
        withUpdater { _, updater ->
            ledger.save(AutoUpdateLedgerState(lastCheckMs = START.toEpochMilli() + DAYS_31.toMillis()))

            assertTrue(updater.runIfDue() is AutoUpdateRun.Queued)
        }

    @Test
    fun theFileLedgerKeepsBothTimesAndReadsAMalformedFileAsNeverChecked() {
        val file = root.resolve("settings/auto-update.json").toFile()
        FileAutoUpdateLedger(file).save(AutoUpdateLedgerState(lastCheckMs = 5L, lastAttemptMs = 7L))

        assertEquals(AutoUpdateLedgerState(5L, 7L), FileAutoUpdateLedger(file).load())
        file.writeText("not json")
        assertEquals(AutoUpdateLedgerState(), FileAutoUpdateLedger(file).load())
        assertNull(FileAutoUpdateLedger(null).load().lastCheckMs)
    }

    /**
     * Installs the lviv region at [OLD_VERSION], records the check at [START],
     * then makes [NEW_VERSION] the catalogue's version when asked.
     */
    private fun withUpdater(
        startMetered: Boolean = false,
        newVersionAvailable: Boolean = true,
        block: suspend (RegionStore, AutoUpdater) -> Unit,
    ) = runBlocking {
        server().use { server ->
            val store = store(server.baseUrl)
            try {
                assertTrue(store.refresh() is Outcome.Success)
                assertEquals(Outcome.Success(Unit), store.startDownload(CatalogFixture.LVIV))
                withTimeout(TIMEOUT_MS) { store.data.first { it.transfers.isEmpty() && it.installed.isNotEmpty() } }
                if (newVersionAvailable) setCatalog(NEW_VERSION)
                metered.metered.value = startMetered
                withTimeout(TIMEOUT_MS) { store.data.first { it.networkMetered == startMetered } }
                ledger.save(AutoUpdateLedgerState(lastCheckMs = START.toEpochMilli()))
                val updater =
                    AutoUpdater(
                        access = store,
                        ledger = ledger,
                        clock = clock,
                        isSwitchOn = { switchOn },
                        notifier = { notice -> notices += notice },
                    )
                block(store, updater)
            } finally {
                store.close()
            }
        }
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        while (!condition()) {
            kotlinx.coroutines.delay(POLL_MS)
        }
    }

    private fun setCatalog(version: String) {
        catalog =
            CatalogFixture.withCatalog { document ->
                val regions =
                    (document.getValue("regions") as JsonArray).map { element ->
                        JsonObject(element.jsonObject + ("version" to JsonPrimitive(version)))
                    }
                JsonObject(document + ("regions" to JsonArray(regions)))
            }
    }

    private fun server(): TestHttpServer =
        TestHttpServer { exchange ->
            val path = exchange.requestURI.path
            val match = PACKAGE_PATH.matchEntire(path)
            when {
                path == "/${DownloadsDefaults.CATALOG_FILE}" -> {
                    catalogRequests.incrementAndGet()
                    send(exchange, catalogStatus, if (catalogStatus == HTTP_OK) catalog else ByteArray(0))
                }

                match == null -> {
                    send(exchange, HTTP_NOT_FOUND, ByteArray(0))
                }

                else -> {
                    packageRequests.incrementAndGet()
                    val body = FixturePackages.at(match.groupValues[1], match.groupValues[2])
                    if (isSlow) sendSlowly(exchange, body) else send(exchange, HTTP_OK, body)
                }
            }
        }

    private fun sendSlowly(
        exchange: HttpExchange,
        body: ByteArray,
    ) {
        exchange.sendResponseHeaders(HTTP_OK, body.size.toLong())
        try {
            exchange.responseBody.use { out ->
                var offset = 0
                while (offset < body.size) {
                    val length = minOf(SLOW_CHUNK_BYTES, body.size - offset)
                    out.write(body, offset, length)
                    out.flush()
                    offset += length
                    Thread.sleep(SLOW_CHUNK_DELAY_MS)
                }
            }
        } catch (exception: IOException) {
            // The client paused and closed the connection.
        }
    }

    private suspend fun store(baseUrl: String): RegionStore {
        val store =
            RegionStore(
                paths = paths,
                policy = DownloadPolicy(SWITCH, connectTimeoutMs = 2_000, readTimeoutMs = 5_000, allowCleartextHttp = true),
                network =
                    object : NetworkAccess {
                        override suspend fun <T> request(
                            switch: SwitchId,
                            block: suspend () -> Outcome<T>,
                        ): Outcome<T> = block()
                    },
                scope = scope,
                metered = metered,
            )
        store.setServer(baseUrl, null)
        return store
    }

    private class MutableClock(
        start: Instant,
    ) : Clock() {
        private var now = start

        fun advance(by: Duration) {
            now = now.plus(by)
        }

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private class FakeMetered : MeteredNetworkMonitor {
        val metered = MutableStateFlow(false)
        override val isMetered: StateFlow<Boolean> = metered
    }

    private class InMemoryLedger : AutoUpdateLedger {
        private var state = AutoUpdateLedgerState()

        override fun load(): AutoUpdateLedgerState = state

        override fun save(state: AutoUpdateLedgerState) {
            this.state = state
        }
    }

    private companion object {
        val SWITCH: SwitchId = SwitchId("network.region_updates")
        val PACKAGE_PATH = Regex("""/([a-z0-9][a-z0-9._-]*)/([a-z0-9][a-z0-9._-]*)/package\.zip""")
        val START: Instant = Instant.parse("2026-11-01T08:00:00Z")
        val DAYS_29: Duration = Duration.ofDays(29)
        val DAYS_31: Duration = Duration.ofDays(31)
        const val RETRY_HOURS = 24L
        const val OLD_VERSION = "2026-10-01"
        const val NEW_VERSION = CatalogFixture.VERSION
        const val PACKAGE_REQUESTS_FOR_SETUP = 1
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
        const val HTTP_UNAVAILABLE = 503
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 20L
        const val SLOW_CHUNK_BYTES = 4096
        const val SLOW_CHUNK_DELAY_MS = 40L
    }
}
