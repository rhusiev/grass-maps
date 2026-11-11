package nl.r1a.grassmap.app

import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import java.time.Instant

/** One file the pack installer wrote, in bytes. */
internal data class PackFile(
    val name: String,
    val bytes: Long,
)

/** What the first-run pack install found and wrote. */
internal data class PackInstall(
    val directory: String,
    val files: List<PackFile>,
    val bytesWritten: Long,
    val copyMillis: Long,
    val copied: Boolean,
    val problem: String?,
) {
    val installed: Boolean = problem == null
}

/** The facts the report states about the app and the device. */
internal data class MeasurementContext(
    val packageName: String,
    val versionName: String,
    val runIndex: Int,
)

/**
 * The report the screen shows, shares and writes.
 *
 * Every number carries its definition here, because a number whose definition
 * is unclear is worse than no number. The definitions are the same strings the
 * screen draws. A run installs several reroutes; the report keeps all of them
 * with their fix indices and states which one the headline is.
 */
internal object MeasurementReport {
    const val SCHEMA = "grass-maps-measurement/2"
    const val PACK_DIRECTORY = "valhalla"
    const val MEASUREMENT_DIRECTORY = "measurement"
    const val FILE_NAME = "report.json"

    private const val PACK_ASSET_DIRECTORY = "valhalla-lviv"

    /** The gate `scripts/bench/reroute-latency.sh` uses, in seconds of travel. */
    const val REROUTE_GATE_SECONDS = 20.0

    val definitions: Map<String, String> =
        linkedMapOf(
            "cold_route_ms" to
                "Wall time of the first RoutingEngine.route() call in this app process. It includes " +
                "resolving the engine, inspecting the pack, writing the device config, mounting the " +
                "Valhalla tiles in the native actor and the /route request. It excludes the map, the " +
                "planner screen, drawing and the fix stream. Only the walk profile has it: bike runs " +
                "after walk, so bike's cold_ms is null.",
            "warm_route_ms" to
                "Median of the repeated route() calls on the already-mounted engine with the same " +
                "request, per profile. Each sample includes the /route call and the route-model " +
                "build; it excludes the engine mount, the map, the planner screen and drawing.",
            "reroute_first_ms" to
                "The headline reroute number: the median across runs of the FIRST reroute installed " +
                "in each run. Each value is the navigation session's own reroute_ms: wall time from " +
                "the fix that spends the last metre of the profile's off-route budget to the moment " +
                "the replacement route is installed. It includes the Valhalla /route call and the " +
                "route-model rebuild; it excludes the gate itself, fix pacing, map redraw and the " +
                "planner. The first reroute is the one the acceptance criterion names: a reroute " +
                "started by leaving the route.",
            "reroute_all_median_ms" to
                "The median of every reroute installed in every run, pooled. A run can install " +
                "several when the replay stays off the replacement line; each one is a separate " +
                "episode with its own fix indices in samples[].events.",
            "reroute_events" to
                "Samples[].events[] lists every installed reroute with index (1-based within the " +
                "run), episode_start_fix (first fix of that off-route streak), budget_fix (the fix " +
                "whose travel spent the gate), installed_fix (the fix being processed when the " +
                "replacement was installed; the request is awaited, so it equals budget_fix), " +
                "budget_m (travel the gate had banked), reroute_ms (the session's timing), and the " +
                "replacement's distance and point count. budget_m can exceed the gate because the " +
                "replay's geometry points are sparse and one fix step can spend more than 22 m: the " +
                "gate is a floor, not an upper bound.",
            "fix_tick_ms" to
                "Wall pacing between replayed fixes. The GPX timestamps advance with distance at the " +
                "profile's gate speed, so the gate is the profile's metres of travel whatever this " +
                "tick is; the tick only sets how long a sample takes.",
            "detour_m" to
                "Sideways offset of the replay's off-route segment. The route is cut in thirds and " +
                "the middle third is pushed sideways, tapered over the first fixes.",
            "not_measured" to
                "Rendering and frame pacing, map drawing, the route planner screen, CPU or memory " +
                "use, battery, and any real GNSS input. The fixes are a generated GPX replay at 5 m " +
                "reported accuracy.",
        )

    /** Builds the report for a finished run, or the failure that stopped it. */
    fun build(
        result: MeasurementResult?,
        failure: String?,
        pack: PackInstall,
        context: MeasurementContext,
    ): JsonObject =
        buildJsonObject {
            put("schema", SCHEMA)
            put("generated_at", Instant.now().toString())
            put("run_index_in_process", context.runIndex)
            put("app", app(context))
            put("device", device())
            put("pack", pack(pack))
            put("definitions", definitionsObject())
            if (result == null) {
                put("error", failure ?: "the measurement did not run")
            } else {
                put("profiles", profiles(result))
            }
        }

    private fun app(context: MeasurementContext): JsonObject =
        buildJsonObject {
            put("variant", "measure")
            put("package", context.packageName)
            put("version_name", context.versionName)
            put("signed_with", "debug key")
            put("releasable", false)
        }

    private fun device(): JsonObject =
        buildJsonObject {
            put("model", listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" "))
            put("android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            put("hardware", Build.HARDWARE)
            put("cpu_cores", Runtime.getRuntime().availableProcessors())
            put("emulator", isEmulator())
        }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu") ||
            Build.MODEL.lowercase().contains("sdk")

    private fun pack(install: PackInstall): JsonObject =
        buildJsonObject {
            put("assets", PACK_ASSET_DIRECTORY)
            put("installed_dir", install.directory)
            put("installed", install.installed)
            put("copied_this_run", install.copied)
            put("bytes_installed", install.files.sumOf { it.bytes })
            put("bytes_written_this_run", install.bytesWritten)
            put("copy_ms", install.copyMillis)
            if (install.problem != null) {
                put("problem", install.problem)
            }
            put(
                "files",
                buildJsonArray {
                    install.files.forEach { file ->
                        add(
                            buildJsonObject {
                                put("name", file.name)
                                put("bytes", file.bytes)
                            },
                        )
                    }
                },
            )
        }

    private fun profiles(result: MeasurementResult): JsonObject =
        buildJsonObject {
            result.profiles.forEach { measurement ->
                put(measurement.profile.label(), profile(measurement, result))
            }
        }

    private fun profile(
        measurement: ProfileMeasurement,
        result: MeasurementResult,
    ): JsonObject =
        buildJsonObject {
            put("profile", measurement.profile.label())
            put("gate_seconds", REROUTE_GATE_SECONDS)
            put("gate_meters", measurement.gateMeters)
            put("route", route(measurement))
            put("route_plan", routePlan(measurement, result))
            put("reroute", reroute(measurement, result))
        }

    private fun route(measurement: ProfileMeasurement): JsonObject =
        buildJsonObject {
            put(
                "from_lat",
                measurement.route.geometry
                    .first()
                    .latitude,
            )
            put(
                "from_lon",
                measurement.route.geometry
                    .first()
                    .longitude,
            )
            put(
                "to_lat",
                measurement.route.geometry
                    .last()
                    .latitude,
            )
            put(
                "to_lon",
                measurement.route.geometry
                    .last()
                    .longitude,
            )
            put("distance_m", measurement.route.distanceMeters)
            put("duration_s", measurement.route.durationSeconds)
            put("points", measurement.route.geometry.size)
        }

    private fun routePlan(
        measurement: ProfileMeasurement,
        result: MeasurementResult,
    ): JsonObject =
        buildJsonObject {
            put("cold_ms", JsonPrimitive(measurement.routePlan.coldMillis))
            if (measurement.routePlan.coldMillis == null) {
                put(
                    "cold_note",
                    "the ${result.profiles.first().profile.label()} profile mounted the engine first; " +
                        "this profile has no cold number",
                )
            }
            put("warm_runs", result.warmRuns)
            put("warm_median_ms", JsonPrimitive(measurement.routePlan.warmMedianMillis.oneDecimal()))
            put(
                "warm_samples_ms",
                buildJsonArray { measurement.routePlan.warmMillis.forEach { add(it) } },
            )
        }

    private fun reroute(
        measurement: ProfileMeasurement,
        result: MeasurementResult,
    ): JsonObject =
        buildJsonObject {
            put("first_reroute_ms", JsonPrimitive(measurement.firstRerouteMillis.oneDecimal()))
            put("all_reroutes_median_ms", JsonPrimitive(measurement.allReroutesMedianMillis.oneDecimal()))
            put("runs", result.rerouteRuns)
            put("fix_tick_ms", result.fixTickMillis)
            put("detour_m", result.detourMeters)
            put(
                "samples",
                buildJsonArray {
                    measurement.rerouteRuns.forEach { run ->
                        add(rerouteSample(run))
                    }
                },
            )
        }

    private fun rerouteSample(run: RerouteRun): JsonObject =
        buildJsonObject {
            put("fixes", run.fixCount)
            put("off_route_fixes", run.offRouteFixCount)
            put("detection_ms", JsonPrimitive(run.detectionMillis))
            put("first_reroute_ms", JsonPrimitive(run.firstRerouteMillis))
            if (run.error != null) {
                put("error", run.error)
            }
            put(
                "events",
                buildJsonArray {
                    run.events.forEach { event ->
                        add(
                            buildJsonObject {
                                put("index", event.index)
                                put("episode_start_fix", event.episodeStartFix)
                                put("budget_fix", event.budgetFix)
                                put("installed_fix", event.installedFix)
                                put("budget_m", event.budgetTravelMeters)
                                put("reroute_ms", event.durationMillis)
                                put("replacement_m", event.replacementDistanceMeters)
                                put("replacement_points", event.replacementPoints)
                            },
                        )
                    }
                },
            )
        }

    private fun definitionsObject(): JsonObject =
        buildJsonObject {
            definitions.forEach { (key, value) -> put(key, value) }
        }

    private fun RouteProfile.label(): String = name.lowercase()
}
