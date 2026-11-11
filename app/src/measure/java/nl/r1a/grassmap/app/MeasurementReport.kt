package nl.r1a.grassmap.app

import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * screen draws.
 */
internal object MeasurementReport {
    const val SCHEMA = "grass-maps-measurement/1"
    const val PACK_DIRECTORY = "valhalla"
    const val MEASUREMENT_DIRECTORY = "measurement"
    const val FILE_NAME = "report.json"

    private const val PACK_ASSET_DIRECTORY = "valhalla-lviv"

    /** The gate `scripts/bench/reroute-latency.sh` uses: 20 s of walk at 1.1 m/s. */
    const val REROUTE_GATE_SECONDS = 20.0
    const val REROUTE_GATE_METERS = 22.0

    val definitions: Map<String, String> =
        linkedMapOf(
            "cold_route_ms" to
                "Wall time of the first RoutingEngine.route() call in this app process. It includes " +
                "resolving the engine, inspecting the pack, writing the device config, mounting the " +
                "Valhalla tiles in the native actor and the /route request. It excludes the map, the " +
                "planner screen, drawing and the fix stream.",
            "warm_route_ms" to
                "Median of the repeated route() calls on the already-mounted engine with the same " +
                "request. Each sample includes the /route call and the route-model build; it " +
                "excludes the engine mount, the map, the planner screen and drawing.",
            "reroute_ms" to
                "The navigation session's own reroute_ms: wall time from the fix that spends the last " +
                "metre of the off-route budget to the moment the replacement route is installed. It " +
                "includes the Valhalla /route call and the route-model rebuild. It excludes the " +
                "$REROUTE_GATE_SECONDS s travel gate, the fix pacing, the map redraw and the planner. " +
                "One fix stream is one sample.",
            "fix_tick_ms" to
                "Wall pacing between replayed fixes. The GPX timestamps advance with distance at the " +
                "profile's gate speed, so the gate is 22 m of travel whatever this tick is; the tick " +
                "only sets how long the sample takes.",
            "detour_m" to
                "Sideways offset of the replay's off-route segment. The route is cut in thirds and the " +
                "middle third is pushed sideways, tapered over the first fixes.",
            "not_measured" to
                "Rendering and frame pacing, map drawing, the route planner screen, CPU or memory use, " +
                "battery, and any real GNSS input. The fixes are a generated GPX replay at 5 m " +
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
                put("route", route(result))
                put("route_plan", routePlan(result))
                put("reroute", reroute(result))
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

    private fun route(result: MeasurementResult): JsonObject =
        buildJsonObject {
            put("profile", "walk")
            put(
                "from_lat",
                result.route.geometry
                    .first()
                    .latitude,
            )
            put(
                "from_lon",
                result.route.geometry
                    .first()
                    .longitude,
            )
            put(
                "to_lat",
                result.route.geometry
                    .last()
                    .latitude,
            )
            put(
                "to_lon",
                result.route.geometry
                    .last()
                    .longitude,
            )
            put("distance_m", result.route.distanceMeters)
            put("duration_s", result.route.durationSeconds)
            put("points", result.route.geometry.size)
        }

    private fun routePlan(result: MeasurementResult): JsonObject =
        buildJsonObject {
            put("cold_ms", result.routePlan.coldMillis)
            put("warm_runs", result.warmRuns)
            put("warm_median_ms", JsonPrimitive(result.routePlan.warmMedianMillis.oneDecimal()))
            put(
                "warm_samples_ms",
                buildJsonArray { result.routePlan.warmMillis.forEach { add(it) } },
            )
        }

    private fun reroute(result: MeasurementResult): JsonObject =
        buildJsonObject {
            put("profile", "walk")
            put("gate_seconds", REROUTE_GATE_SECONDS)
            put("gate_meters", REROUTE_GATE_METERS)
            put("fix_tick_ms", result.fixTickMillis)
            put("detour_m", result.detourMeters)
            put("runs", result.rerouteRuns)
            put("median_ms", JsonPrimitive(result.rerouteMedianMillis.oneDecimal()))
            put(
                "samples",
                buildJsonArray {
                    result.rerouteSamples.forEach { sample ->
                        add(
                            buildJsonObject {
                                put("reroute_ms", JsonPrimitive(sample.millis))
                                put("fixes", sample.fixCount)
                                put("off_route_fixes", sample.offRouteFixCount)
                                put("detection_ms", JsonPrimitive(sample.detectionMillis))
                                put("reroutes", sample.rerouteCount)
                                if (sample.error != null) {
                                    put("error", sample.error)
                                }
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
}
