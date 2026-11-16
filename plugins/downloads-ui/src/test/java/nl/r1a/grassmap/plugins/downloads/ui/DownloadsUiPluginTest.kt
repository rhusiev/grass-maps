package nl.r1a.grassmap.plugins.downloads.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.DownloadProblem
import nl.r1a.grassmap.plugin.api.model.DownloadProblemKind
import nl.r1a.grassmap.plugin.api.model.DownloadRowStatus
import nl.r1a.grassmap.plugin.api.model.DownloadTransfer
import nl.r1a.grassmap.plugin.api.model.DownloadTransferState
import nl.r1a.grassmap.plugin.api.model.DownloadsState
import nl.r1a.grassmap.plugin.api.model.FirstRunOfferKind
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.MobileDataPolicy
import nl.r1a.grassmap.plugin.api.testing.fixture.CatalogFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The downloads plugin (T-0504): registration, the state the store drives, and
 * every command's effect. The store is the fake in [TestSupport]; the catalogue
 * is the pipeline's own file.
 */
class DownloadsUiPluginTest {
    @Test
    fun activationDeclaresEightCommandsAndOneStateStream() =
        runBlocking {
            val access = FakeDownloadsAccess()
            val context = context(access)

            assertEquals(Outcome.Success(Unit), DownloadsUiPlugin().activate(context))

            assertEquals(DownloadsUiContract.commands.toSet(), context.commands.map { it.id }.toSet())
            val stream = context.streams.single()
            assertEquals(DownloadsUiContract.stateStream, stream.id)
            assertEquals(DownloadsUiContract.STATE_VALUE_TYPE, stream.valueType)
            assertTrue(context.providers.isEmpty())
            assertTrue(context.widgets.isEmpty())
            assertTrue(context.commands.none { it.policy.undoable })
            assertEquals(
                CONTROL_ALLOWED,
                context.commands
                    .filter { it.policy.allowlistedForControl }
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(
                FakeDownloadsAccess.SERVER,
                context.stateFlow().first().serverUrl,
            )
        }

    @Test
    fun theScreenFollowsTheStoreCatalogue() =
        runBlocking {
            val access = FakeDownloadsAccess()
            val context = context(access)
            DownloadsUiPlugin().activate(context)

            access.useCatalog(CatalogTestData.real())

            val state =
                withTimeout(TIMEOUT_MS) {
                    context.stateFlow().first { it.availableRows.size == CatalogFixture.REGION_COUNT }
                }
            assertEquals(CatalogFixture.REGION_COUNT, state.availableRows.size)
            assertTrue(state.catalogLoaded)
            assertEquals("2026-11-03", state.availableRows.single { it.region == CatalogFixture.LVIV }.dataDate)
        }

    @Test
    fun theFirstFixNamesTheDetectedOblast() =
        runBlocking {
            val access = FakeDownloadsAccess()
            val context =
                context(
                    access,
                    fixes = flowOf(LocationFix(position = GeoPoint(49.84, 24.03), accuracyMeters = 5.0, timeMillis = 0L)),
                )
            DownloadsUiPlugin().activate(context)
            access.useCatalog(CatalogTestData.withWorld())

            val state = withTimeout(TIMEOUT_MS) { context.stateFlow().first { it.detectedRegion != null } }

            assertEquals(CatalogFixture.LVIV, state.detectedRegion)
            assertTrue(state.firstRun.any { it.kind == FirstRunOfferKind.REGION })
            assertTrue(state.firstRun.any { it.kind == FirstRunOfferKind.WORLD })
        }

    @Test
    fun downloadCommandQueuesTheRegionAndTheStorePublishesTheCompletedRow() =
        runBlocking {
            val access = FakeDownloadsAccess()
            val context = context(access)
            DownloadsUiPlugin().activate(context)
            access.useCatalog(CatalogTestData.real())

            val queued = stateOf(context.command(DownloadsUiContract.downloadRegion).invokeRaw(json("region" to CatalogFixture.LVIV)))

            assertEquals(listOf("refresh", "download:${CatalogFixture.LVIV}"), access.calls)
            val transfer = queued.availableRows.single { it.region == CatalogFixture.LVIV }.transfer
            assertEquals(210_943_205L, transfer?.bytesTotal ?: 0L)

            access.complete(CatalogFixture.LVIV, CatalogFixture.VERSION)
            val installed =
                withTimeout(TIMEOUT_MS) {
                    context.stateFlow().first { it.installedRows.size == 1 }
                }
            val row = installed.installedRows.single()
            assertEquals(DownloadRowStatus.INSTALLED, row.status)
            assertEquals("2026-11-03", row.dataDate)
        }

    @Test
    fun cancelCommandStopsTheTransferAndLeavesTheRowAvailable() =
        runBlocking {
            val access = FakeDownloadsAccess()
            val context = context(access)
            DownloadsUiPlugin().activate(context)
            access.useCatalog(CatalogTestData.real())
            context.command(DownloadsUiContract.downloadRegion).invokeRaw(json("region" to CatalogFixture.LVIV))

            val cancelled = stateOf(context.command(DownloadsUiContract.cancelRegion).invokeRaw(json("region" to CatalogFixture.LVIV)))

            assertTrue(access.calls.contains("cancel:${CatalogFixture.LVIV}"))
            assertNull(cancelled.availableRows.single { it.region == CatalogFixture.LVIV }.transfer)
            assertTrue(cancelled.installedRows.isEmpty())
        }

    @Test
    fun removeCommandDropsTheInstalledRow() =
        runBlocking {
            val access = FakeDownloadsAccess()
            val context = context(access)
            DownloadsUiPlugin().activate(context)
            access.useCatalog(CatalogTestData.real())
            access.complete(CatalogFixture.LVIV, CatalogFixture.VERSION)
            withTimeout(TIMEOUT_MS) { context.stateFlow().first { it.installedRows.size == 1 } }

            val removed = stateOf(context.command(DownloadsUiContract.removeRegion).invokeRaw(json("region" to CatalogFixture.LVIV)))

            assertTrue(access.calls.contains("remove:${CatalogFixture.LVIV}"))
            assertTrue(removed.installedRows.isEmpty())
            assertEquals(CatalogFixture.REGION_COUNT, removed.availableRows.size)
        }

    @Test
    fun setServerPassesTheTokenAndNeverEchoesIt() =
        runBlocking {
            val access = FakeDownloadsAccess()
            val context = context(access)
            DownloadsUiPlugin().activate(context)

            val state =
                stateOf(
                    context
                        .command(DownloadsUiContract.setServer)
                        .invokeRaw(json("url" to "https://maps.example", "token" to TOKEN)),
                )

            assertEquals("server:https://maps.example:present", access.calls.last())
            assertTrue(state.tokenPresent)
            assertEquals("https://maps.example", state.serverUrl)
            assertFalse(access.calls.any { it.contains(TOKEN) })
            assertFalse(state.toString().contains(TOKEN))
        }

    @Test
    fun theMobileDataPolicyIsSetAndAnUnknownValueIsRefused() =
        runBlocking {
            val access = FakeDownloadsAccess()
            val context = context(access)
            DownloadsUiPlugin().activate(context)
            val command = context.command(DownloadsUiContract.setMobileData)

            val state = stateOf(command.invokeRaw(json("policy" to "any")))
            val refused = command.invokeRaw(json("policy" to "always"))

            assertEquals(MobileDataPolicy.ANY, state.mobileDataPolicy)
            assertEquals(Outcome.Failure(AppError.InvalidInput), refused)
            assertEquals(listOf("mobile:ANY"), access.calls.filter { it.startsWith("mobile") })
        }

    @Test
    fun aTransferWaitingForWifiIsReportedAndTheMobileDataChoiceReachesTheStore() =
        runBlocking {
            val access = FakeDownloadsAccess()
            val context = context(access)
            DownloadsUiPlugin().activate(context)
            access.transfer(
                CatalogFixture.LVIV,
                DownloadTransfer(CatalogFixture.LVIV, DownloadTransferState.WAITING_FOR_WIFI),
            )

            val state = stateOf(context.command(DownloadsUiContract.useMobileDataNow).invokeRaw(JsonObject(emptyMap())))

            assertTrue(state.waitingForWifi)
            assertEquals(MobileDataPolicy.WIFI_ONLY, state.mobileDataPolicy)
            assertEquals("mobile-now", access.calls.last())
        }

    @Test
    fun anEmptyTokenClearsIt() =
        runBlocking {
            val access = FakeDownloadsAccess()
            val context = context(access)
            DownloadsUiPlugin().activate(context)

            val state =
                stateOf(
                    context
                        .command(DownloadsUiContract.setServer)
                        .invokeRaw(json("url" to "https://maps.example", "token" to "")),
                )

            assertEquals("server:https://maps.example:none", access.calls.last())
            assertFalse(state.tokenPresent)
        }

    @Test
    fun selectRegionOpensAndClosesTheSheet() =
        runBlocking {
            val access = FakeDownloadsAccess()
            val context = context(access)
            DownloadsUiPlugin().activate(context)
            access.useCatalog(CatalogTestData.real())

            val opened = stateOf(context.command(DownloadsUiContract.selectRegion).invokeRaw(json("region" to CatalogFixture.LVIV)))
            assertEquals(CatalogFixture.LVIV, opened.selectedRegion)
            assertEquals(CatalogFixture.LVIV, opened.selected?.region)

            val closed = stateOf(context.command(DownloadsUiContract.selectRegion).invokeRaw(json("region" to "")))
            assertNull(closed.selectedRegion)
            assertNull(closed.selected)
        }

    @Test
    fun aTokenProblemFromTheStoreReachesTheScreen() =
        runBlocking {
            val access = FakeDownloadsAccess()
            access.refreshProblem = DownloadProblem(DownloadProblemKind.TOKEN_REQUIRED, status = 401)
            val context = context(access)
            DownloadsUiPlugin().activate(context)

            val state = withTimeout(TIMEOUT_MS) { context.stateFlow().first { it.catalogProblem != null } }

            assertEquals(DownloadProblemKind.TOKEN_REQUIRED, state.catalogProblem?.kind)
            assertEquals(401, state.catalogProblem?.status)
            assertFalse(state.catalogLoaded)
            assertEquals(
                Outcome.Failure(AppError.Server(401)),
                context.command(DownloadsUiContract.refresh).invokeRaw(JsonObject(emptyMap())),
            )
        }

    @Test
    fun invalidParametersAreInvalidInputAndTouchNothing() =
        runBlocking {
            val access = FakeDownloadsAccess()
            val context = context(access)
            DownloadsUiPlugin().activate(context)
            val before = access.calls.size

            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                context.command(DownloadsUiContract.downloadRegion).invokeRaw(JsonObject(emptyMap())),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                context.command(DownloadsUiContract.cancelRegion).invokeRaw(JsonObject(emptyMap())),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                context.command(DownloadsUiContract.selectRegion).invokeRaw(JsonObject(emptyMap())),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                context.command(DownloadsUiContract.setServer).invokeRaw(json("token" to "abc")),
            )
            assertEquals(before, access.calls.size)
        }

    private fun context(
        access: FakeDownloadsAccess,
        fixes: Flow<LocationFix?> = flowOf(null),
    ): RecordingContext = RecordingContext(FakeServices(access, fixes))

    private fun RecordingContext.command(id: CommandId): Command<*, *> = commands.single { it.id == id }

    @Suppress("UNCHECKED_CAST")
    private fun stateOf(outcome: Outcome<CommandResult<JsonElement>>): DownloadsState =
        when (outcome) {
            is Outcome.Success -> Json.decodeFromJsonElement(DownloadsState.serializer(), outcome.value.value)
            is Outcome.Failure -> throw AssertionError("command failed: ${outcome.error}")
        }

    private fun json(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject {
            pairs.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val TOKEN = "test-token-0123456789abcdef"

        val CONTROL_ALLOWED =
            setOf(
                DownloadsUiContract.refresh,
                DownloadsUiContract.downloadRegion,
                DownloadsUiContract.cancelRegion,
                DownloadsUiContract.removeRegion,
                DownloadsUiContract.setServer,
                DownloadsUiContract.setMobileData,
                DownloadsUiContract.useMobileDataNow,
            )
    }
}
