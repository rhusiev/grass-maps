package nl.r1a.grassmap.app.devcheck

import android.app.Application
import android.content.res.Configuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import nl.r1a.grassmap.app.AppContainer
import nl.r1a.grassmap.app.DebugControl
import nl.r1a.grassmap.devtools.AndroidWindowCapture
import nl.r1a.grassmap.map.MaplibreComposeMapHost
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.CatalogRegion
import nl.r1a.grassmap.plugin.api.model.RoutePlanOption

private const val RELOAD_TIMEOUT_MS = 15_000L
private const val RELOAD_POLL_MS = 100L
private const val MAP_SETTLE_MS = 5_000L

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

    /** The region the download step installed or found, for the route and navigation steps. */
    var region: CatalogRegion? = null

    /** True when the check installed [region], so the cleanup step removes it. */
    var installedByCheck: Boolean = false

    /** The region-downloads switch as the owner had it, or null while the check has not touched it. */
    var downloadsSwitchWas: Boolean? = null

    /** The route the planning step produced, and the two points it was planned between. */
    var plannedRoute: RoutePlanOption? = null
    var plannedPoints: PlanPoints? = null

    /**
     * Where the map scenes look: the middle of the planned walk, else the middle
     * of the installed region, else Lviv. A camera outside the installed region
     * shows no data.
     */
    fun mapFocus(): Pair<Double, Double> {
        val walk = plannedPoints?.let { (it.startLat + it.endLat) / 2 to (it.startLon + it.endLon) / 2 }
        return walk ?: region?.let(::regionCentre) ?: (LVIV_LAT to LVIV_LON)
    }

    /**
     * Recreates the resumed activity so the map screen resolves its sources
     * again, then waits for the new window and the style load. The map reads
     * the installed regions once when it starts, so a pack installed later is
     * invisible until then. Returns false when no activity was resumed.
     */
    suspend fun reloadMap(): Boolean {
        val activity = DebugControl.resumedActivity?.get() ?: return false
        val before = activity.window
        withContext(Dispatchers.Main) { activity.recreate() }
        val replaced =
            withTimeoutOrNull(RELOAD_TIMEOUT_MS) {
                while (DebugControl.resumedActivity
                        ?.get()
                        ?.window
                        .let { it == null || it === before }
                ) {
                    delay(RELOAD_POLL_MS)
                }
                true
            } ?: false
        delay(MAP_SETTLE_MS)
        return replaced
    }

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

    /** The point count of the route the map layer holds now, or null when no map host is attached. */
    suspend fun routeLayerPoints(): Int? = (container.mapSession.hostOrNull() as? MaplibreComposeMapHost)?.routePointCount()

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
