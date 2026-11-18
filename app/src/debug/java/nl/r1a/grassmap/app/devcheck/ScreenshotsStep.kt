package nl.r1a.grassmap.app.devcheck

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.app.MapChromeSession
import nl.r1a.grassmap.map.MaplibreComposeMapHost
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugins.route.planner.RoutePlannerContract

private const val SETTLE_MS = 1_200L
private const val SCENE_ZOOM = 14.0
private const val SEARCH_QUERY = "Lviv"
private const val SEARCH_WAIT_MS = 10_000L
private const val SEARCH_POLL_MS = 250L
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
    suspend fun run(): StepOutcome {
        hidePanel(true)
        try {
            walk()
        } finally {
            restore()
            hidePanel(false)
        }
        val numbers =
            mapOf(
                "taken" to JsonPrimitive(context.screenshots.size),
                "unavailable" to JsonPrimitive(context.unavailable.size),
            )
        return if (context.screenshots.isEmpty()) StepOutcome.fail("no screenshot could be taken", numbers) else StepOutcome.pass(numbers)
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
        context.command("core.map.set_camera", camera(LVIV_LAT, LVIV_LON, SCENE_ZOOM))
        context.command("core.map.set_style", styleParams(STYLE_LIGHT))
        shoot("map_idle", THEME_LIGHT)
        context.command("core.map.set_style", styleParams(STYLE_DARK))
        shoot("map_idle", THEME_MAP_DARK)
        context.command("core.map.set_style", styleParams(STYLE_LIGHT))
    }

    private suspend fun layersScene() {
        MapChromeSession.requestLayers(true)
        shoot("layers_open", THEME_LIGHT)
        MapChromeSession.requestLayers(false)
    }

    private suspend fun searchAndPlaceScenes() {
        val request = buildJsonObject {
            put("query", SEARCH_QUERY)
            put("sequence", 1)
        }
        context.command("core.search.screen", request)
        val hits = withTimeoutOrNull(SEARCH_WAIT_MS) { awaitHits() }.orEmpty()
        if (hits.isEmpty()) {
            skipScene("search_results", "the search for \"$SEARCH_QUERY\" returned no hit: no search pack is installed")
            skipScene("place_card", "no search hit to open")
            return
        }
        shoot("search_results", THEME_LIGHT, mapOf("hits" to JsonPrimitive(hits.size)))
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

    private suspend fun awaitHits() =
        context.container.searchScreen.state
            .first { it.hits.isNotEmpty() }
            .hits

    private suspend fun plannerScene() {
        context.command("route.planner.begin")
        context.command("route.planner.set_point", pointParams("start", PLAN_START_LAT, PLAN_START_LON))
        context.command("route.planner.set_point", pointParams("destination", PLAN_END_LAT, PLAN_END_LON))
        context.command("route.planner.plan")
        val state = withTimeoutOrNull(PLAN_WAIT_MS) { awaitPlanEnd() }
        val status = state?.status?.name?.lowercase() ?: "timeout"
        val extra = mapOf("plan_status" to JsonPrimitive(status), "plan_failure" to JsonPrimitive(state?.failure?.toString()))
        if (state?.status == RoutePlanStatus.Ready) {
            shoot("route_planner_ready", THEME_LIGHT, extra)
        } else {
            context.unavailable += UnavailableScreen("route_planner_ready", "the plan ended as $status: ${state?.failure}")
            shoot("route_planner_$status", THEME_LIGHT, extra)
        }
        context.command("route.planner.clear")
    }

    private suspend fun awaitPlanEnd(): RoutePlanState =
        context.container.streamAccess
            .subscribe<RoutePlanState>(RoutePlannerContract.stateStream)
            .first { it.status == RoutePlanStatus.Ready || it.status == RoutePlanStatus.Failed }

    private fun pointParams(
        role: String,
        lat: Double,
        lon: Double,
    ): JsonObject =
        buildJsonObject {
            put("role", role)
            put("lat", lat)
            put("lon", lon)
        }

    private suspend fun navigationScenes() {
        val route = context.app.assets.open(ROUTE_ASSET).use { Json.parseToJsonElement(it.reader().readText()) }
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
        val points = routeLayerPoints()
        shoot(name, if (dark) THEME_MAP_DARK else THEME_LIGHT, mapOf("route_layer_points" to JsonPrimitive(points)))
    }

    private suspend fun routeLayerPoints(): Int? =
        (context.container.mapSession.hostOrNull() as? MaplibreComposeMapHost)?.routePointCount()

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
    ) {
        delay(SETTLE_MS)
        val captured = context.capture.capturePng()
        if (captured is Outcome.Failure) {
            skipScene(screen, "capture failed: ${captured.error}")
            return
        }
        val capture = (captured as Outcome.Success).value
        val size = PngSize.of(capture.png) ?: (0 to 0)
        val facts = context.displayFacts()
        val file = "screenshots/%02d-%s-%s.png".format(context.screenshots.size + 1, screen, theme)
        context.pngs += ZipFileEntry(file, capture.png)
        context.screenshots +=
            ScreenshotRecord(file, screen, theme, facts.uiNightMode, size.first, size.second, facts.density, facts.fontScale, capture.source, extra)
    }

    private fun styleParams(style: String): JsonObject = buildJsonObject { put("style", style) }
}
