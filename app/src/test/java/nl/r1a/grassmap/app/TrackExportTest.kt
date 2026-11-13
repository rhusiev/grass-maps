package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.DocumentSaveResult
import nl.r1a.grassmap.plugin.api.host.PlatformFile
import nl.r1a.grassmap.tracks.ImportReport
import nl.r1a.grassmap.tracks.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-0410 at the contract seam: the app builds one GPX document for a track or a
 * list and hands it to `PlatformActions`, which the test records instead of
 * starting an Android picker or chooser.
 */
class TrackExportTest {
    @Test
    fun oneStoredTrackBecomesOneGpxFileWithItsStyle() =
        runTest {
            val access = access()
            val record = import(access, "garmin-connect-hike.gpx").tracks.single()
            val actions = RecordingPlatformActions()
            val export = TrackExport(access, actions)

            val file =
                when (val outcome = export.fileFor(record.id)) {
                    is Outcome.Success -> outcome.value
                    is Outcome.Failure -> throw AssertionError("export failed: ${outcome.error}")
                }

            assertEquals("Lviv hills hike.gpx", file.fileName)
            assertEquals("application/gpx+xml", file.mimeType)
            val text = file.bytes.toString(Charsets.UTF_8)
            assertTrue(text.contains("<grassmap:colour>${record.style.colour}</grassmap:colour>"))
            assertTrue(text.contains("<grassmap:widthDp>${record.style.widthDp}</grassmap:widthDp>"))

            val reread = reread(access, file.bytes)
            assertEquals(
                record.track.name,
                reread.tracks
                    .single()
                    .track.name,
            )
            assertEquals(
                record.track.segments,
                reread.tracks
                    .single()
                    .track.segments,
            )
        }

    @Test
    fun oneTrackSharesAndSavesThroughThePlatformSeam() =
        runTest {
            val access = access()
            val record = import(access, "osmand-walk-two-segments.gpx").tracks.single()
            val actions = RecordingPlatformActions()
            val export = TrackExport(access, actions)
            val file = exported(export.fileFor(record.id))

            assertEquals(Outcome.Success(Unit), export.share(file))
            assertEquals(Outcome.Success(DocumentSaveResult.SAVED), export.save(file))

            assertEquals(
                listOf(
                    "file:${file.fileName}:${file.mimeType}:${file.bytes.size}",
                    "save:${file.fileName}:${file.mimeType}:${file.bytes.size}",
                ),
                actions.calls,
            )
        }

    @Test
    fun aSelectedListBecomesOneMultiTrackFile() =
        runTest {
            val access = access()
            val first = import(access, "garmin-connect-hike.gpx").tracks.single()
            val second = import(access, "strava-run.gpx").tracks.single()
            val actions = RecordingPlatformActions()

            val file =
                exported(
                    TrackExport(access, actions).fileForList(listOf(first.id, second.id)),
                )

            assertEquals("grass-maps-tracks.gpx", file.fileName)
            val reread = reread(access, file.bytes)
            assertEquals(listOf("Lviv hills hike", "Morning Run"), reread.tracks.map { it.name })
            assertEquals(2, reread.tracks.size)
        }

    @Test
    fun allStoredTracksExportAsOneFile() =
        runTest {
            val access = access()
            import(access, "garmin-connect-hike.gpx")
            import(access, "strava-run.gpx")
            import(access, "osmand-multi-track.gpx")

            val file = exported(TrackExport(access, RecordingPlatformActions()).fileForAll())

            assertEquals("grass-maps-tracks.gpx", file.fileName)
            val reread = reread(access, file.bytes)
            assertEquals(4, reread.tracks.size)
        }

    @Test
    fun anEmptyListDuplicateOrMissingTrackIsATypedRefusal() =
        runTest {
            val access = access()
            val record = import(access, "strava-run.gpx").tracks.single()
            val export = TrackExport(access, RecordingPlatformActions())

            assertEquals(Outcome.Failure(AppError.InvalidInput), export.fileForList(emptyList()))
            assertEquals(Outcome.Failure(AppError.InvalidInput), export.fileForList(listOf(record.id, record.id)))
            assertEquals(
                Outcome.Failure(AppError.NotFound),
                export.fileForList(listOf(TrackId("missing"))),
            )
        }

    private fun access(): TrackAccess =
        TrackAccess(
            registry =
                ProviderRegistry(
                    PluginHost(
                        plugins = GeneratedPlugins.plugins,
                        streams = emptyList(),
                        commands = emptyList(),
                        dispatcher = Dispatchers.Unconfined,
                    ),
                ),
            providers = GeneratedPlugins.providers,
            tracksRoot = null,
        )

    private suspend fun import(
        access: TrackAccess,
        file: String,
    ): ImportReport =
        when (
            val outcome =
                access.import(
                    fileName = file,
                    bytes = bytes("tracks/$file"),
                )
        ) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("$file did not import: ${outcome.error}")
        }

    private suspend fun reread(
        access: TrackAccess,
        bytes: ByteArray,
    ): ImportReport =
        when (val outcome = access.import("roundtrip.gpx", bytes)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("export did not import: ${outcome.error}")
        }

    private fun exported(outcome: Outcome<PlatformFile>): PlatformFile =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("export failed: ${outcome.error}")
        }

    private fun bytes(resource: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
            "missing test resource $resource"
        }.use { it.readBytes() }
}
