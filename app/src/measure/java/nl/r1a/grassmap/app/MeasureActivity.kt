package nl.r1a.grassmap.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * The one-button measurement screen of the `measure` build type.
 *
 * The build is signed with the debug key and is not a release: it exists so a
 * phone that is away from adb can still report the cold and warm route-plan
 * latency and the reroute mediation. Nothing here is part of `release` or
 * `benchmark`; those variants do not include this source set or its assets.
 */
class MeasureActivity : ComponentActivity() {
    private val container: AppContainer by lazy { (application as GrassMapsApplication).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { prettyPrint = true }

    private lateinit var status: TextView
    private lateinit var definitions: TextView
    private lateinit var reportView: TextView
    private lateinit var runButton: Button
    private lateinit var shareButton: Button

    private var pack: PackInstall? = null
    private var reportFile: File? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildLayout()
        // targetSdk 36 draws under the system bars; keep the title clear of the
        // status bar and the JSON clear of the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                bars.left + dp(PADDING_DP),
                bars.top + dp(PADDING_DP),
                bars.right + dp(PADDING_DP),
                bars.bottom + dp(PADDING_DP),
            )
            insets
        }
        setContentView(root)
        installPack()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildLayout(): LinearLayout {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP))
            }
        root.addView(
            TextView(this).apply {
                text = getString(R.string.measure_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        status =
            TextView(this).apply {
                text = getString(R.string.measure_pack_checking)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
                setPadding(0, dp(SMALL_GAP_DP), 0, 0)
            }
        root.addView(status)

        val buttons =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(SMALL_GAP_DP), 0, dp(SMALL_GAP_DP))
            }
        runButton =
            Button(this).apply {
                text = getString(R.string.measure_run)
                isEnabled = false
                setOnClickListener { startMeasurement() }
            }
        shareButton =
            Button(this).apply {
                text = getString(R.string.measure_share)
                isEnabled = false
                setOnClickListener { shareReport() }
            }
        buttons.addView(runButton)
        buttons.addView(shareButton)
        root.addView(buttons)

        definitions =
            TextView(this).apply {
                text = definitionsText()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, DEFINITION_SP)
                setPadding(0, 0, 0, dp(SMALL_GAP_DP))
            }
        reportView =
            TextView(this).apply {
                text = getString(R.string.measure_idle)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, REPORT_SP)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        column.addView(definitions)
        column.addView(reportView)
        root.addView(
            ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                addView(column)
            },
        )
        return root
    }

    private fun definitionsText(): String =
        buildString {
            appendLine(getString(R.string.measure_definitions_title))
            MeasurementReport.definitions.forEach { (key, value) ->
                appendLine()
                appendLine("$key - $value")
            }
        }

    /** Copies the bundled pack on the first run and reports what it wrote. */
    private fun installPack() {
        scope.launch {
            val installed =
                withContext(Dispatchers.IO) {
                    PackInstaller(
                        assets = assets,
                        externalFiles = getExternalFilesDir(null),
                        filesDir = filesDir,
                        log = { message -> Log.i(TAG, message) },
                    ).install()
                }
            pack = installed
            status.text =
                if (installed.installed) {
                    buildString {
                        append(
                            getString(
                                R.string.measure_pack_ready,
                                installed.directory,
                                megabytes(installed.files.sumOf { it.bytes }),
                            ),
                        )
                        if (installed.copied) {
                            append(" ")
                            append(getString(R.string.measure_pack_copied, installed.copyMillis))
                        }
                    }
                } else {
                    getString(R.string.measure_pack_failed, installed.problem.orEmpty())
                }
            runButton.isEnabled = installed.installed
        }
    }

    private fun startMeasurement() {
        if (running) {
            return
        }
        val installed = pack ?: return
        if (!installed.installed) {
            return
        }
        running = true
        runButton.isEnabled = false
        shareButton.isEnabled = false
        reportFile = null
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            status.text = getString(R.string.measure_running)
            val runner =
                MeasurementRunner(
                    engineProvider = { container.routingEngine() },
                    waypoints = LVIV_BENCH_WAYPOINTS,
                    log = { message -> Log.i(TAG, message) },
                )
            val outcome =
                withContext(Dispatchers.IO) {
                    runner.run { message ->
                        runOnUiThread { status.text = message }
                    }
                }
            processRuns += 1
            val result = outcome.getOrNull()
            val failure = outcome.exceptionOrNull()?.message
            val report =
                MeasurementReport.build(
                    result = result,
                    failure = failure,
                    pack = installed,
                    context =
                        MeasurementContext(
                            packageName = packageName,
                            versionName = versionName(),
                            runIndex = processRuns,
                        ),
                )
            val text = json.encodeToString(JsonObject.serializer(), report)
            reportView.text = text
            reportFile = writeReport(text)
            status.text =
                if (outcome.isFailure) {
                    getString(R.string.measure_failed, failure.orEmpty())
                } else {
                    summary(result, (SystemClock.elapsedRealtime() - started) / MILLIS_PER_SECOND)
                }
            running = false
            runButton.isEnabled = true
            shareButton.isEnabled = reportFile != null
            Log.i(TAG, "measure-report ${reportFile?.absolutePath}")
        }
    }

    /** The two profile lines the status bar shows after a run. */
    private fun summary(
        result: MeasurementResult?,
        seconds: Long,
    ): String {
        val measured = result ?: return getString(R.string.measure_failed, "no result")
        return buildString {
            measured.profiles.forEach { profile ->
                val label = profile.profile.name.lowercase()
                if (profile.routePlan.coldMillis != null) {
                    appendLine(
                        getString(
                            R.string.measure_profile_cold,
                            label,
                            profile.routePlan.coldMillis,
                            decimal(profile.routePlan.warmMedianMillis),
                            decimal(profile.firstRerouteMillis),
                        ),
                    )
                } else {
                    appendLine(
                        getString(
                            R.string.measure_profile_warm,
                            label,
                            decimal(profile.routePlan.warmMedianMillis),
                            decimal(profile.firstRerouteMillis),
                        ),
                    )
                }
            }
            append(getString(R.string.measure_total, seconds))
        }
    }

    private fun decimal(value: Double?): String = value?.oneDecimal()?.toString() ?: "-"

    private fun writeReport(text: String): File? {
        val root = getExternalFilesDir(null) ?: filesDir
        val directory = File(root, MeasurementReport.MEASUREMENT_DIRECTORY)
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("cannot create ${directory.absolutePath}")
            }
            val target = File(directory, MeasurementReport.FILE_NAME)
            target.writeText(text)
            target
        } catch (exception: IOException) {
            Log.w(TAG, "measure-report-failed ${exception.message}")
            null
        }
    }

    private fun shareReport() {
        val file = reportFile ?: return
        val uri =
            FileProvider.getUriForFile(
                this,
                "$packageName$FILE_PROVIDER_SUFFIX",
                file,
            )
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = JSON_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Grass Maps measurement ${Instant.now()}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        startActivity(Intent.createChooser(intent, getString(R.string.measure_share)))
    }

    private fun versionName(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (exception: android.content.pm.PackageManager.NameNotFoundException) {
            "unknown"
        }

    private fun megabytes(bytes: Long): String = String.format(java.util.Locale.US, "%.1f MB", bytes / MEGABYTE)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "GrassMapsMeasure"
        const val PADDING_DP = 12
        const val SMALL_GAP_DP = 8
        const val TITLE_SP = 20f
        const val BODY_SP = 13f
        const val DEFINITION_SP = 11f
        const val REPORT_SP = 10f
        const val MILLIS_PER_SECOND = 1_000L
        const val MEGABYTE = 1024.0 * 1024.0
        const val FILE_PROVIDER_SUFFIX = ".measure.files"
        const val JSON_MIME_TYPE = "application/json"

        /** How many times this process has run the measurement; cold needs 1. */
        private var processRuns = 0
    }
}
