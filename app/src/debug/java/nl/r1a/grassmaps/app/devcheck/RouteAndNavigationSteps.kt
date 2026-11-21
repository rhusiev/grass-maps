package nl.r1a.grassmaps.app.devcheck

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.navigation.NavigationPhase
import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmaps.routing.RouteJson
import nl.r1a.grassmaps.routing.toRoute

const val PLAN_TIMEOUT_MS = 90_000L
const val NAV_START_TIMEOUT_MS = 15_000L

/**
 * How long guidance runs before the stop. A stop within milliseconds of the
 * start reaches the foreground service before it has called startForeground,
 * and Android ends the process.
 */
const val NAV_HOLD_MS = 3_000L
private const val NAV_POLL_MS = 250L
private const val NAV_PROFILE = "walk"

/** Plans a walk between two points inside the downloaded region and records what the planner returned. */
internal class PlanStep(
    private val context: CheckContext,
) {
    suspend fun run(): StepOutcome {
        val region = context.region ?: return StepOutcome.skip("no region is installed: the download step did not provide one")
        val points = pickPlanPoints(region) ?: return StepOutcome.skip("${region.id} has no usable bounding box")
        context.plannedPoints = points
        val driver = PlannerDriver(context)
        val state = driver.plan(points, PLAN_TIMEOUT_MS)
        val option = state?.selected
        val numbers = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        numbers["region"] = JsonPrimitive(region.id)
        numbers["status"] = JsonPrimitive(state?.status?.name ?: "Timeout")
        if (state?.status != RoutePlanStatus.Ready || option == null) {
            driver.clear()
            return StepOutcome.fail("the plan ended as ${state?.status}: ${state?.failure}", numbers)
        }
        context.plannedRoute = option
        numbers["distance_m"] = JsonPrimitive(option.distanceMeters)
        numbers["duration_s"] = JsonPrimitive(option.durationSeconds)
        numbers["instructions"] = JsonPrimitive(option.instructions.size)
        numbers["geometry_points"] = JsonPrimitive(option.geometry.size)
        numbers["route_layer_points"] = JsonPrimitive(context.routeLayerPoints())
        return StepOutcome.pass(numbers)
    }
}

/** Starts guidance on the planned route, records whether it became active, and stops it again. */
internal class NavigationStep(
    private val context: CheckContext,
) {
    suspend fun run(): StepOutcome {
        val option = context.plannedRoute ?: return StepOutcome.skip("no planned route: the plan step did not produce one")
        val params: JsonObject =
            buildJsonObject {
                put("route", RouteJson.encode(option.toRoute()))
                put("profile", NAV_PROFILE)
            }
        try {
            context.log("navigation: starting guidance")
            val started = context.command("core.nav.start", params)
            val phase = awaitActive()
            val numbers =
                mapOf("start_result" to JsonPrimitive(started.toString().take(RESULT_CHARS)), "phase" to JsonPrimitive(phase.name))
            return when {
                started is nl.r1a.grassmap.plugin.api.error.Outcome.Failure -> {
                    StepOutcome.fail(
                        "core.nav.start failed: ${started.error}",
                        numbers,
                    )
                }

                phase == NavigationPhase.Following || phase == NavigationPhase.Rerouting -> {
                    StepOutcome.pass(numbers)
                }

                else -> {
                    StepOutcome.fail("navigation did not become active, phase is $phase", numbers)
                }
            }
        } finally {
            withContext(NonCancellable) {
                delay(NAV_HOLD_MS)
                context.command("core.nav.stop")
            }
        }
    }

    private suspend fun awaitActive(): NavigationPhase {
        withTimeoutOrNull(NAV_START_TIMEOUT_MS) {
            while (!isActive(context.container.navigation.state.value.phase)) {
                delay(NAV_POLL_MS)
            }
        }
        return context.container.navigation.state.value.phase
    }

    private fun isActive(phase: NavigationPhase) = phase == NavigationPhase.Following || phase == NavigationPhase.Rerouting

    private companion object {
        const val RESULT_CHARS = 200
    }
}

/** Removes what the check installed and puts the owner's switches back. */
internal class CleanupStep(
    private val context: CheckContext,
) {
    suspend fun run(): StepOutcome {
        val region = context.region
        val store = context.container.regionStore
        val removed = context.installedByCheck && region != null && store != null
        if (removed) {
            store?.remove(region!!.id)
        }
        context.downloadsSwitchWas?.let { context.container.setRegionDownloadsEnabled(it) }
        return StepOutcome.pass(mapOf("removed_pack" to JsonPrimitive(removed)))
    }
}
