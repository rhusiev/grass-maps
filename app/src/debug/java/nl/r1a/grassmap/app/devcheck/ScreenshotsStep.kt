package nl.r1a.grassmap.app.devcheck

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.app.MapChromeSession
import nl.r1a.grassmap.places.SearchScreenStatus
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.provider.PlaceHit

private const val SETTLE_MS = 1_200L
private const val SCENE_ZOOM = 14.0
private const val SEARCH_WAIT_MS = 10_000L
private const val SEARCH_POLL_MS = 250L
private const val MAX_SEARCH_QUERIES = 4
private const val PLAN_WAIT_MS = 30_000L
private const val ROUTE_ASSET = "device-check/screen-route.json"
private const val STYLE_LIGHT = "standard"
private const val STYLE_DARK = "standard-dark"
private const val THEME_LIGHT = "light"
private const val THEME_MAP_DARK = "map-dark"
private const val PLAN_START_LAT = 49.8419
private const val PLAN_START_LON = 24.0315
private const val PLAN_END_LAT = 49.8326
private const val PLAN_END_LON = 24.0230

private const val DARK_THEME_NOTE =
    "the dark shots use the dark map style only: the panels take their dark palette from the system night mode, and the check leaves the system theme alone"
private const val FALLBACK_SEARCH_QUERY = "Lviv"
private const val MAP_IDLE = "map_idle"
private const val SEARCH_QUERY_REGION_LABEL = "region_label"
private const val SEARCH_QUERY_REGION_NAME = "region_name"
private const val SEARCH_QUERY_FALLBACK = "fallback"

/** The screens this build does not have. They are listed so the report says so. */
internal val SCREENS_NOT_IN_THIS_BUILD = listOf("profiles create/copy/reset", "widget grid", "plugin manager")

/**
 * Walks the main screens through the debug commands and takes one window
 * screenshot of each. A scene that cannot be reached is recorded with the
 * reason and the walk goes on.
 */
internal class ScreenshotsStep(
    private val context: CheckContext,
    private val hidePanel: (Boolean) -> Unit,
) {
    private val blankMaps = mutableListOf<String>()

    suspend fun run(): StepOutcome {
        hidePanel(true)
        try {
            restore()
            walk()
        } finally {
            restore()
            hidePanel(false)
        }
        val numbers =
            mapOf(
                "taken" to JsonPrimitive(context.screenshots.size),
                "unavailable" to JsonPrimitive(context.unavailable.size),
                "dark_theme" to JsonPrimitive(DARK_THEME_NOTE),
            )
        return screenshotsOutcome(context.screenshots.size, blankMaps, numbers)
    }

    private suspend fun walk() {
        mapScenes()
        layersScene()
        searchAndPlaceScenes()
        plannerScene()
        navigationScenes()
        downloadsScenes()
        libraryScenes()
    }

    private suspend fun restore() {
        context.command("core.map.set_style", styleParams(STYLE_LIGHT))
        context.command("core.search.screen", buildJsonObject { put("clear", true) })
        context.command("route.planner.clear")
        context.command("core.nav.show_screen", buildJsonObject { put("clear", true) })
        MapChromeSession.requestLayers(false)
        MapChromeSession.requestTokenForm(false)
        listOf("core.place.card_hide", "core.downloads.hide", "core.favourites.lists_hide", "core.track.library_hide")
            .forEach { context.command(it) }
    }

    private suspend fun mapScenes() {
        val (lat, lon) = context.mapFocus()
        context.command("core.map.set_camera", camera(lat, lon, SCENE_ZOOM))
        context.command("core.map.set_style", styleParams(STYLE_LIGHT))
        shoot(MAP_IDLE, THEME_LIGHT, checkMap = true)
        context.command("core.map.set_style", styleParams(STYLE_DARK))
        shoot(MAP_IDLE, THEME_MAP_DARK, checkMap = true)
        context.command("core.map.set_style", styleParams(STYLE_LIGHT))
    }

    private suspend fun layersScene() {
        MapChromeSession.requestLayers(true)
        shoot("layers_open", THEME_LIGHT)
        MapChromeSession.requestLayers(false)
    }

    private suspend fun searchAndPlaceScenes() {
        var hits = emptyList<PlaceHit>()
        var used = FALLBACK_SEARCH_QUERY to SEARCH_QUERY_FALLBACK
        for ((index, candidate) in searchQueries().withIndex()) {
            used = candidate
            hits = search(candidate.first, index + 1L)
            if (hits.isNotEmpty()) {
                break
            }
        }
        val (query, source) = used
        if (hits.isEmpty()) {
            skipScene("search_results", "no query found a place: ${searchPartNote()}")
            skipScene("place_card", "no search hit to open")
            return
        }
        shoot(
            "search_results",
            THEME_LIGHT,
            mapOf(
                "hits" to JsonPrimitive(hits.size),
                "search_query" to JsonPrimitive(query),
                "search_query_source" to JsonPrimitive(source),
            ),
        )
        val place = hits.firstOrNull { !it.isCoordinate }
        if (place == null) {
            skipScene("place_card", "every hit is a coordinate row")
            return
        }
        val shown = context.command("core.place.card_show", buildJsonObject { put("id", place.id) })
        if (shown is Outcome.Failure) {
            skipScene("place_card", "the card did not open: ${shown.error}")
        } else {
            shoot("place_card", THEME_LIGHT)
        }
        context.command("core.place.card_hide")
    }

    /**
     * What to type into the search screen, best first: up to [MAX_SEARCH_QUERIES]
     * place names the map draws in the installed region, then the region's own
     * catalogue name, then a fixed name so a run without a downloaded region
     * still tries. A name the region does not hold finds nothing, so the scene
     * would otherwise show an empty screen on a phone that downloaded another
     * oblast.
     */
    private suspend fun searchQueries(): List<Pair<String, String>> {
        val names = renderedPlaceNames().take(MAX_SEARCH_QUERIES)
        return buildList {
            names.forEach { add(it to SEARCH_QUERY_REGION_LABEL) }
            context.region
                ?.nameEn
                ?.takeIf { it.isNotBlank() }
                ?.let { add(it to SEARCH_QUERY_REGION_NAME) }
            add(FALLBACK_SEARCH_QUERY to SEARCH_QUERY_FALLBACK)
        }.distinctBy { it.first }
    }

    /** The place names the map draws around the region at the label zoom, waiting for the style to load. */
    private suspend fun renderedPlaceNames(): List<String> {
        val (lat, lon) = context.mapFocus()
        context.command("core.map.set_camera", camera(lat, lon, LABEL_ZOOM))
        return withTimeoutOrNull(LABEL_WAIT_MS) {
            var names = placeNames(RenderedLabelReader(context).features())
            while (names.isEmpty()) {
                delay(SEARCH_POLL_MS)
                names = placeNames(RenderedLabelReader(context).features())
            }
            names
        }.orEmpty()
    }

    /** Runs one keystroke and waits for its terminal state, so an empty answer returns at once. */
    private suspend fun search(
        query: String,
        sequence: Long,
    ): List<PlaceHit> {
        context.command(
            "core.search.screen",
            buildJsonObject {
                put("query", query)
                put("sequence", sequence)
            },
        )
        return withTimeoutOrNull(SEARCH_WAIT_MS) {
            context.container.searchScreen.state
                .first { it.sequence >= sequence && it.status.isTerminal() }
                .hits
        }.orEmpty()
    }

    private fun SearchScreenStatus.isTerminal(): Boolean =
        this == SearchScreenStatus.RESULTS || this == SearchScreenStatus.EMPTY || this == SearchScreenStatus.FAILED

    private fun searchPartNote(): String {
        val kinds = context.region?.parts?.joinToString { it.wireKind }
        return "the installed region has these parts: ${kinds ?: "none known"}"
    }

    private suspend fun plannerScene() {
        val points = context.plannedPoints ?: PlanPoints(PLAN_START_LAT, PLAN_START_LON, PLAN_END_LAT, PLAN_END_LON)
        val driver = PlannerDriver(context)
        val state = driver.plan(points, PLAN_WAIT_MS)
        val status = state?.status?.name?.lowercase() ?: "timeout"
        val extra = mapOf("plan_status" to JsonPrimitive(status), "plan_failure" to JsonPrimitive(state?.failure?.toString()))
        if (state?.status == RoutePlanStatus.Ready) {
            shoot("route_planner_ready", THEME_LIGHT, extra)
        } else {
            context.unavailable += UnavailableScreen("route_planner_ready", "the plan ended as $status: ${state?.failure}")
            shoot("route_planner_$status", THEME_LIGHT, extra)
        }
        driver.clear()
    }

    private suspend fun navigationScenes() {
        val route =
            context.app.assets
                .open(ROUTE_ASSET)
                .use { Json.parseToJsonElement(it.reader().readText()) }
        navScene("nav_following", route, "collapsed", dark = false)
        navScene("nav_expanded", route, "trip", dark = false)
        navScene("nav_alert", route, "collapsed", dark = false, alerts = listOf("crossing"))
        navScene("nav_following", route, "collapsed", dark = true)
        context.command("core.nav.show_screen", buildJsonObject { put("clear", true) })
        context.command("core.map.set_style", styleParams(STYLE_LIGHT))
    }

    private suspend fun navScene(
        name: String,
        route: JsonElement,
        sheet: String,
        dark: Boolean,
        alerts: List<String> = emptyList(),
    ) {
        val params =
            buildJsonObject {
                put("route", route)
                put("phase", "following")
                put("sheet", sheet)
                put("dark", dark)
                put("alerts", JsonArray(alerts.map { JsonPrimitive(it) }))
            }
        val shown = context.command("core.nav.show_screen", params)
        if (shown is Outcome.Failure) {
            skipScene(name, "the navigation fixture was refused: ${shown.error}")
            return
        }
        val points = context.routeLayerPoints()
        shoot(name, if (dark) THEME_MAP_DARK else THEME_LIGHT, mapOf("route_layer_points" to JsonPrimitive(points)))
    }

    private suspend fun downloadsScenes() {
        context.command("core.downloads.show")
        shoot("downloads", THEME_LIGHT)
        MapChromeSession.requestTokenForm(true)
        shoot("maps_token_settings", THEME_LIGHT)
        MapChromeSession.requestTokenForm(false)
        context.command("core.downloads.hide")
    }

    private suspend fun libraryScenes() {
        context.command("core.favourites.lists_show")
        shoot("favourites_lists", THEME_LIGHT)
        context.command("core.favourites.lists_hide")
        context.command("core.track.library_show")
        shoot("track_library", THEME_LIGHT)
        context.command("core.track.library_hide")
    }

    private fun skipScene(
        screen: String,
        reason: String,
    ) {
        context.unavailable += UnavailableScreen(screen, reason)
        context.log("screen $screen unavailable: $reason")
    }

    private suspend fun shoot(
        screen: String,
        theme: String,
        extra: Map<String, JsonElement> = emptyMap(),
        checkMap: Boolean = false,
    ) {
        delay(SETTLE_MS)
        val captured = context.capture.capturePng()
        if (captured is Outcome.Failure) {
            skipScene(screen, "capture failed: ${captured.error}")
            return
        }
        val capture = (captured as Outcome.Success).value
        val size = PngSize.of(capture.png) ?: (0 to 0)
        val mapNumbers = if (checkMap) mapAreaNumbers(screen, theme, capture.png) else emptyMap()
        val facts = context.displayFacts()
        val file = "screenshots/%02d-%s-%s.png".format(context.screenshots.size + 1, screen, theme)
        context.pngs += ZipFileEntry(file, capture.png)
        context.screenshots +=
            ScreenshotRecord(
                file,
                screen,
                theme,
                facts.uiNightMode,
                size.first,
                size.second,
                facts.density,
                facts.fontScale,
                capture.source,
                extra + mapNumbers,
            )
    }

    private fun mapAreaNumbers(
        screen: String,
        theme: String,
        png: ByteArray,
    ): Map<String, JsonElement> {
        val area = MapPixels.checkPng(png) ?: return emptyMap()
        val installed = context.region != null
        if (area.isBlank && installed) {
            blankMaps += "$screen-$theme"
        }
        return mapOf(
            "map_dominant_color_share" to JsonPrimitive(area.dominantShare),
            "map_area_blank" to JsonPrimitive(area.isBlank),
            "map_blank_rule" to
                JsonPrimitive(
                    if (installed) {
                        "armed: ${context.region?.id} is installed"
                    } else {
                        "not armed: no region is installed, so a flat map is the empty style"
                    },
                ),
        )
    }

    private fun styleParams(style: String): JsonObject = buildJsonObject { put("style", style) }
}

/**
 * The screenshots step's verdict: no picture, or a blank map area on a run that
 * installed a region, fails it. The blank rule is what catches a map surface
 * that is not composited into the capture: that shows as one color over the
 * whole area, and a map with data never does.
 */
internal fun screenshotsOutcome(
    taken: Int,
    blankMaps: List<String>,
    numbers: Map<String, JsonElement>,
): StepOutcome =
    when {
        taken == 0 -> StepOutcome.fail("no screenshot could be taken", numbers)
        blankMaps.isNotEmpty() -> StepOutcome.fail("the map area is blank in: ${blankMaps.joinToString()}", numbers)
        else -> StepOutcome.pass(numbers)
    }
