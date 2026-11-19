package nl.r1a.grassmap.core.services.backup

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The merge rule: by id, never silently over an existing value, ask per conflict. */
class BackupMergeTest {
    @Test
    fun anImportPlansAdditionsUnchangedEntriesAndOneConflictPerId() =
        runTest {
            val source = FakeSource(BackupKinds.FAVOURITES, listOf(entry("a", "old"), entry("c", "same")))
            val importer = importer(source)
            val prepared = prepare(importer, favourites(entry("a", "new"), entry("c", "same"), entry("b", "added")))

            assertEquals(listOf("a"), prepared.plan.conflicts.map { conflict -> conflict.entryId })
            assertEquals(1, prepared.plan.additionsCount)
            assertEquals(1, prepared.plan.unchanged)
            assertEquals(
                "old",
                prepared.plan.conflicts
                    .single()
                    .existing
                    .string("name"),
            )
            assertEquals(
                "new",
                prepared.plan.conflicts
                    .single()
                    .incoming
                    .string("name"),
            )
        }

    @Test
    fun aConflictAnsweredWithTheExistingValueNeverWritesIt() =
        runTest {
            val source = FakeSource(BackupKinds.FAVOURITES, listOf(entry("a", "old")))
            val importer = importer(source)
            val prepared = prepare(importer, favourites(entry("a", "new")))
            val decisions = BundleConflictDecisions()

            decisions.answer(prepared.plan.conflicts.single(), BundleConflictChoice.EXISTING, applyToAllInKind = false)
            val summary = applied(importer, prepared, decisions)

            assertTrue(source.applied.isEmpty())
            assertEquals(
                "old",
                source.entries
                    .single()
                    .value
                    .string("name"),
            )
            assertEquals(1, summary.kept)
            assertEquals(0, summary.replaced)
        }

    @Test
    fun applyToAllAnswersTheRestOfThatKindAndLeavesOtherKindsToAsk() =
        runTest {
            val favourites = FakeSource(BackupKinds.FAVOURITES, listOf(entry("a", "old"), entry("b", "old")))
            val plugins = FakeSource(BackupKinds.PLUGINS, listOf(entry("p", "on")))
            val importer = importer(favourites, plugins)
            val prepared =
                prepare(
                    importer,
                    BackupBundle(
                        formatVersion = 1,
                        appVersion = "0.1.0",
                        createdAtMillis = 1L,
                        kinds =
                            listOf(
                                BundleKind(
                                    BackupKinds.FAVOURITES,
                                    listOf(entry("a", "new"), entry("b", "new")),
                                ),
                                BundleKind(BackupKinds.PLUGINS, listOf(entry("p", "off"))),
                            ),
                    ),
                )
            val decisions = BundleConflictDecisions()

            assertEquals("a", decisions.next(prepared.plan)?.entryId)
            decisions.answer(prepared.plan.conflicts.first(), BundleConflictChoice.INCOMING, applyToAllInKind = true)
            assertEquals("p", decisions.next(prepared.plan)?.entryId)
            decisions.answer(prepared.plan.conflicts.last(), BundleConflictChoice.EXISTING, applyToAllInKind = false)
            assertNull(decisions.next(prepared.plan))

            val summary = applied(importer, prepared, decisions)

            assertEquals(2, favourites.applied.single().size)
            assertTrue(plugins.applied.isEmpty())
            assertEquals(2, summary.replaced)
            assertEquals(1, summary.kept)
        }

    @Test
    fun anUnansweredConflictWritesNothing() =
        runTest {
            val source = FakeSource(BackupKinds.PROFILES, listOf(entry("walk", "old")))
            val importer = importer(source)
            val prepared = prepare(importer, profiles(entry("walk", "new")))

            assertEquals(
                Outcome.Failure(AppError.Conflict),
                importer.apply(prepared, BundleConflictDecisions()),
            )
            assertTrue(source.applied.isEmpty())
        }

    @Test
    fun anUnknownKindIsSkippedAndTheKnownKindStillRestores() =
        runTest {
            val source = FakeSource(BackupKinds.MAP_STYLE, emptyList())
            val importer = importer(source)
            val prepared =
                prepare(
                    importer,
                    BackupBundle(
                        formatVersion = 1,
                        appVersion = "0.1.0",
                        createdAtMillis = 1L,
                        kinds =
                            listOf(
                                BundleKind(BackupKinds.MAP_STYLE, listOf(entry("style", "trail"))),
                                BundleKind("from-a-newer-build", listOf(entry("x", "y"))),
                            ),
                    ),
                )

            val summary = applied(importer, prepared, BundleConflictDecisions())

            assertEquals(listOf(BackupWarning.UnknownKind("from-a-newer-build")), summary.warnings)
            assertEquals(1, source.applied.single().size)
            assertEquals(
                "trail",
                source.entries
                    .single()
                    .value
                    .string("value"),
            )
        }

    @Test
    fun aKindThatFailsDoesNotStopAnotherKind() =
        runTest {
            val broken =
                object : BundleKindSource {
                    override val kind: String = BackupKinds.PLUGINS

                    override suspend fun read(): Outcome<List<BundleEntry>> = Outcome.Success(emptyList())

                    override suspend fun apply(entries: List<BundleEntry>): Outcome<Unit> =
                        Outcome.Failure(AppError.Internal("test", "write failed"))
                }
            val style = FakeSource(BackupKinds.MAP_STYLE, emptyList())
            val importer = BackupImporter(listOf(broken, style))
            val prepared =
                prepare(
                    importer,
                    BackupBundle(
                        formatVersion = 1,
                        appVersion = "0.1.0",
                        createdAtMillis = 1L,
                        kinds =
                            listOf(
                                BundleKind(BackupKinds.PLUGINS, listOf(entry("p", "off"))),
                                BundleKind(BackupKinds.MAP_STYLE, listOf(entry("style", "trail"))),
                            ),
                    ),
                )

            val summary = applied(importer, prepared, BundleConflictDecisions())

            assertTrue(summary.failed)
            assertEquals(AppError.Internal("test", "write failed"), summary.kinds.first { it.kind == BackupKinds.PLUGINS }.error)
            assertEquals(1, style.applied.single().size)
        }

    private fun importer(vararg sources: BundleKindSource): BackupImporter {
        val ordered =
            sources.sortedBy { source ->
                BackupKinds.ALL.indexOf(source.kind).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
            }
        return BackupImporter(ordered)
    }

    private suspend fun prepare(
        importer: BackupImporter,
        bundle: BackupBundle,
    ): PreparedBackup {
        val output = ByteArrayOutputStream()
        when (val written = BundleZipWriter.write(bundle, output)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> throw AssertionError("write failed: ${written.error}")
        }
        return when (val prepared = importer.prepare(output.toByteArray())) {
            is Outcome.Success -> prepared.value
            is Outcome.Failure -> throw AssertionError("prepare failed: ${prepared.error}")
        }
    }

    private suspend fun applied(
        importer: BackupImporter,
        prepared: PreparedBackup,
        decisions: BundleConflictDecisions,
    ): BundleImportSummary =
        when (val summary = importer.apply(prepared, decisions)) {
            is Outcome.Success -> summary.value
            is Outcome.Failure -> throw AssertionError("apply failed: ${summary.error}")
        }

    private fun favourites(vararg entries: BundleEntry): BackupBundle =
        BackupBundle(
            formatVersion = 1,
            appVersion = "0.1.0",
            createdAtMillis = 1L,
            kinds = listOf(BundleKind(BackupKinds.FAVOURITES, entries.toList())),
        )

    private fun profiles(vararg entries: BundleEntry): BackupBundle =
        BackupBundle(
            formatVersion = 1,
            appVersion = "0.1.0",
            createdAtMillis = 1L,
            kinds = listOf(BundleKind(BackupKinds.PROFILES, entries.toList())),
        )

    private fun entry(
        id: String,
        value: String,
    ): BundleEntry = BundleEntry(id, jsonOf("name" to value, "value" to value))

    private fun jsonOf(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject {
            pairs.forEach { (key, value) -> put(key, value) }
        }

    private fun JsonObject.string(key: String): String = (this[key] as kotlinx.serialization.json.JsonPrimitive).content

    private class FakeSource(
        override val kind: String,
        initial: List<BundleEntry>,
    ) : BundleKindSource {
        var entries: List<BundleEntry> = initial
        val applied = mutableListOf<List<BundleEntry>>()

        override suspend fun read(): Outcome<List<BundleEntry>> = Outcome.Success(entries)

        override suspend fun apply(entries: List<BundleEntry>): Outcome<Unit> {
            applied += entries
            val merged = this.entries.associateBy { entry -> entry.id }.toMutableMap()
            entries.forEach { entry -> merged[entry.id] = entry }
            this.entries = merged.values.sortedBy { entry -> entry.id }
            return Outcome.Success(Unit)
        }
    }
}
