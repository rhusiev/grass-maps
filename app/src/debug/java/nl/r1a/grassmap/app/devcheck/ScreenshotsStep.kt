package nl.r1a.grassmaps.app.devcheck

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.app.MapChromeSession
import nl.r1a.grassmaps.places.SearchScreenStatus
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BoundingBox
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmaps.plugin.api.provider.PlaceHit
import nl.r1a.grassmaps.plugin.api.provider.PlaceSource

private const val SETTLE_MS = 1_200L
private const val SCENE_ZOOM = 14.0
private const val SEARCH_WAIT_MS = 10_000L
private const val SEARCH_POLL_MS = 250L
private const val MAX_SEARCH_QUERIES = 4
private const val SEARCH_EXPAND_SETTLE_MS = 800L
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
    private val searchProblems = mutableListOf<String>()

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
        return screenshotsOutcome(context.screenshots.size, blankMaps, searchProblems, numbers)
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
        MapChromeSession.requestSearch(false)
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
        val queries = searchQueries()
        var hits = emptyList<PlaceHit>()
        var used = queries.firstOrNull() ?: (FALLBACK_SEARCH_QUERY to SEARCH_QUERY_FALLBACK)
        val attempts = mutableListOf<String>()
        for ((index, candidate) in queries.withIndex()) {
            used = candidate
            hits = search(candidate.first, index + 1L)
            attempts += "\"${candidate.first}\": ${hits.size}"
            if (hits.isNotEmpty()) {
                break
            }
        }
        val (query, source) = used
        if (hits.isEmpty()) {
            val note = searchIndexNote()
            skipScene("search_results", "no query found a place (tried ${attempts.joinToString()}), $note")
            skipScene("place_card", "no search hit to open: $note")
            return
        }
        val region = context.region
        val top = hits.firstOrNull { !it.isCoordinate }
        val offline = hits.filter { hit -> !hit.isCoordinate && hit.source == PlaceSource.OFFLINE }
        val opened =
            offline.firstOrNull { hit -> insideBounds(region?.bounds, hit.position) == true }
                ?: offline.firstOrNull()
        val insideTop = insideBounds(region?.bounds, top?.position)
        val insideOpened = insideBounds(region?.bounds, opened?.position)
        if (region != null && insideOpened != true) {
            searchProblems +=
                "no offline hit is inside ${region.id}'s bbox; " +
                "top hit ${top?.name ?: "none"} at " +
                "${top?.position?.latitude}, ${top?.position?.longitude}"
        }
        val bboxText = region?.bbox?.takeIf { it.isNotEmpty() }?.joinToString()
        MapChromeSession.requestSearch(true)
        delay(SEARCH_EXPAND_SETTLE_MS)
        shoot(
            "search_results",
            THEME_LIGHT,
            mapOf(
                "hits" to JsonPrimitive(hits.size),
                "search_query" to JsonPrimitive(query),
                "search_query_source" to JsonPrimitive(source),
                "search_sections" to JsonPrimitive(context.container.searchIndexes.state.value.indexes.size),
                "top_hit" to (top?.name?.let(::JsonPrimitive) ?: JsonNull),
                "top_hit_lat" to (top?.position?.latitude?.let(::JsonPrimitive) ?: JsonNull),
                "top_hit_lon" to (top?.position?.longitude?.let(::JsonPrimitive) ?: JsonNull),
                "top_hit_inside_bbox" to JsonPrimitive(insideLabel(insideTop)),
                "opened_hit" to (opened?.name?.let(::JsonPrimitive) ?: JsonNull),
                "opened_hit_region" to (opened?.regionBuild?.let(::JsonPrimitive) ?: JsonNull),
                "opened_hit_inside_bbox" to JsonPrimitive(insideLabel(insideOpened)),
                "region_bbox" to (bboxText?.let(::JsonPrimitive) ?: JsonNull),
            ),
        )
        MapChromeSession.requestSearch(false)
        if (opened == null) {
            skipScene("place_card", "every hit is a coordinate or online row")
            return
        }
        val shown =
            context.command(
                "core.place.card_show",
                buildJsonObject {
                    put("id", opened.id)
                    put("index", opened.regionBuild)
                },
            )
        if (shown is Outcome.Failure) {
            skipScene("place_card", "the card did not open: ${shown.error}")
        } else {
            shoot("place_card", THEME_LIGHT)
        }
        context.command("core.place.card_hide")
    }

    /**
     * What to type into the search screen, best first: up to [MAX_SEARCH_QUERIES]
     * place names the map draws around the downloaded region, then the region's
     * own catalogue name. A fixed name is tried only when no region was
     * downloaded, so a run with a pack never searches another region's places.
     */
    private suspend fun searchQueries(): List<Pair<String, String>> {
        val names = renderedPlaceNames().take(MAX_SEARCH_QUERIES)
        return buildList {
            names.forEach { add(it to SEARCH_QUERY_REGION_LABEL) }
            context.region
                ?.nameEn
                ?.takeIf { it.isNotBlank() }
                ?.let { add(it to SEARCH_QUERY_REGION_NAME) }
            if (context.region == null) {
                add(FALLBACK_SEARCH_QUERY to SEARCH_QUERY_FALLBACK)
            }
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

    /**
     * Runs one keystroke against the downloaded region's section and waits for
     * its terminal state, so an empty answer returns at once.
     */
    private suspend fun search(
        query: String,
        sequence: Long,
    ): List<PlaceHit> {
        val index = context.region?.id
        context.command(
            "core.search.screen",
            buildJsonObject {
                put("query", query)
                put("sequence", sequence)
                index?.let { put("index", it) }
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

    /**
     * What the query searched and whether the mounted packs hold the downloaded
     * region's section. A download with no usable section is named here instead
     * of blaming the query.
     */
    private suspend fun searchIndexNote(): String {
        context.container.startSearchIndexes()
        val set = context.container.searchIndexes.state.value
        val names = set.indexes.joinToString { index -> index.name }.ifEmpty { "none" }
        val region = context.region
        val regionPart =
            if (region != null) {
                val section = set.indexes.firstOrNull { it.name == region.id }
                val state =
                    when {
                        section == null -> "missing from the mounted packs"
                        section.directory.isDirectory -> "present"
                        else -> "missing on disk"
                    }
                "region \"${region.id}\" is $state at " +
                    "${section?.directory?.path ?: "no directory"}"
            } else {
                val root = context.container.places.externalIndexRoot()
                "no region was downloaded; the external search root is " +
                    "${root?.path ?: "absent"}"
            }
        return "the query searched every mounted section [$names], $regionPart, ${searchPartNote()}"
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
            "map_transparent_share" to JsonPrimitive(area.transparentShare),
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

/** Whether [point] lies in [bounds]; null when either side is unknown. */
internal fun insideBounds(
    bounds: BoundingBox?,
    point: GeoPoint?,
): Boolean? = if (bounds == null || point == null) null else bounds.contains(point)

private fun insideLabel(inside: Boolean?): String =
    when (inside) {
        true -> "inside"
        false -> "outside"
        null -> "unknown"
    }

/**
 * The screenshots step's verdict: no picture, a blank map area on a run that
 * installed a region, or a search hit outside that region fails it. The blank
 * rule catches a map surface that is not composited into the capture: that
 * shows as one color over the whole area, and a map with data never does. The
 * search rule catches a fuzzy hit from another pack, which a name search can
 * return when the wrong index answers.
 */
internal fun screenshotsOutcome(
    taken: Int,
    blankMaps: List<String>,
    searchProblems: List<String>,
    numbers: Map<String, JsonElement>,
): StepOutcome =
    when {
        taken == 0 -> StepOutcome.fail("no screenshot could be taken", numbers)
        blankMaps.isNotEmpty() -> StepOutcome.fail("the map area is blank in: ${blankMaps.joinToString()}", numbers)
        searchProblems.isNotEmpty() -> StepOutcome.fail(searchProblems.joinToString("; "), numbers)
        else -> StepOutcome.pass(numbers)
    }
