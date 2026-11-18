package nl.r1a.grassmap.app.devcheck

import android.app.Application
import android.content.res.Configuration
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import nl.r1a.grassmap.app.AppContainer
import nl.r1a.grassmap.devtools.AndroidWindowCapture
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.Outcome

/** Everything the steps share while one check runs. */
internal class CheckContext(
    val app: Application,
    val container: AppContainer,
    val capture: AndroidWindowCapture,
    val secrets: List<String>,
    private val sink: (String) -> Unit,
) {
    val screenshots = mutableListOf<ScreenshotRecord>()
    val pngs = mutableListOf<ZipFileEntry>()
    val unavailable = mutableListOf<UnavailableScreen>()
    private val logLines = mutableListOf<String>()

    /** Appends one scrubbed line to the log that goes into the archive. */
    fun log(line: String) {
        val scrubbed = LogScrubber.scrub(line, secrets)
        synchronized(logLines) { logLines += scrubbed }
        sink(scrubbed)
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n", postfix = "\n") }

    /** Invokes one command through the same bus the control channel uses. */
    suspend fun command(
        id: String,
        params: JsonElement = JsonObject(emptyMap()),
    ): Outcome<JsonElement> = container.commandBus.invoke(CommandId(id), params)

    /** The window and font facts a screenshot record needs, read now. */
    fun displayFacts(): DisplayFacts {
        val resources = app.resources
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return DisplayFacts(
            density = resources.displayMetrics.density,
            fontScale = resources.configuration.fontScale,
            uiNightMode = night == Configuration.UI_MODE_NIGHT_YES,
        )
    }
}

/** The display and font settings the screenshots are taken under. */
internal data class DisplayFacts(
    val density: Float,
    val fontScale: Float,
    val uiNightMode: Boolean,
)
