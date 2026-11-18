package nl.r1a.grassmap.app.devcheck

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.r1a.grassmap.app.AppContainer
import nl.r1a.grassmap.app.BuildConfig
import nl.r1a.grassmap.devtools.AndroidWindowCapture
import java.io.File
import java.time.Instant

private const val ARCHIVE_DIRECTORY = "device-check"
private const val ARCHIVE_PREFIX = "device-check-"
private const val ARCHIVE_MIME = "application/zip"
private const val REPORT_NAME = "report.json"
private const val LOG_NAME = "log.txt"

/** What the panel shows about the check. */
internal data class DeviceCheckUi(
    val running: Boolean = false,
    val stepNumber: Int = 0,
    val stepCount: Int = 0,
    val stepId: String = "",
    val finished: CheckReport? = null,
    val archiveName: String? = null,
    val error: String? = null,
)

/** Starts the check, packs the archive and opens the share sheet. One check runs at a time. */
internal class DeviceCheckController(
    private val app: Application,
    private val container: AppContainer,
    private val capture: AndroidWindowCapture,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableUi = MutableStateFlow(DeviceCheckUi())
    private val mutableCapturing = MutableStateFlow(false)
    private var job: Job? = null

    val ui: StateFlow<DeviceCheckUi> = mutableUi.asStateFlow()

    /** True while screenshots are taken, so the panel stays out of them. */
    val capturing: StateFlow<Boolean> = mutableCapturing.asStateFlow()

    fun start() {
        if (job?.isActive == true) {
            return
        }
        mutableUi.value = DeviceCheckUi(running = true)
        job =
            scope.launch {
                try {
                    execute()
                } catch (
                    @Suppress("TooGenericExceptionCaught") failure: Exception,
                ) {
                    mutableUi.update { it.copy(running = false, error = "${failure::class.java.simpleName}: ${failure.message.orEmpty()}") }
                }
            }
    }

    private suspend fun execute() {
        val secrets = listOfNotNull(BuildConfig.GRASSMAPS_DEBUG_TOKEN.takeIf { it.isNotBlank() })
        val context = CheckContext(app, container, capture, secrets) { }
        val startedAt = Instant.now().toString()
        val steps = DeviceCheckSteps.build(context) { mutableCapturing.value = it }
        val runner =
            DeviceCheckRunner(
                clockMs = SystemClock::elapsedRealtime,
                onStepStart = {
                    index,
                    step,
                    ->
                    mutableUi.update { it.copy(stepNumber = index + 1, stepCount = steps.size, stepId = step.id) }
                },
                log = context::log,
            )
        val results = runner.run(steps)
        val report = CheckReport(startedAt, results, context.screenshots.toList(), context.unavailable.toList(), SCREENS_NOT_IN_THIS_BUILD)
        val archive = pack(context, report, secrets)
        mutableUi.value = DeviceCheckUi(finished = report, archiveName = archive.name)
        share(archive)
    }

    private fun pack(
        context: CheckContext,
        report: CheckReport,
        secrets: List<String>,
    ): File {
        val directory = File(app.cacheDir, ARCHIVE_DIRECTORY).apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val archive = File(directory, "$ARCHIVE_PREFIX${BuildConfig.BUILD_COMMIT}-${System.currentTimeMillis()}.zip")
        val entries =
            listOf(
                ZipFileEntry(REPORT_NAME, report.toJson().toByteArray()),
                ZipFileEntry(LOG_NAME, context.logText().toByteArray()),
            ) + context.pngs
        archive.outputStream().use { ReportZip.write(it, entries, secrets) }
        return archive
    }

    private fun share(archive: File) {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.devcheck.files", archive)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(ARCHIVE_MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(chooser)
    }
}
