package nl.r1a.grassmap.app

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.r1a.grassmap.map.MapSurface
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.host.MapEvent
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.StyleDiff

/**
 * The T-0020 measurement harness. Debug and benchmark install it through their
 * application classes ([DebugApplication], [BenchmarkApplication]); the shipped
 * release never instantiates it, so it has no first-frame logging in practice.
 */
internal class DebugMapHarness(
    private val styleSwitches: Int,
    private val initialBearing: Double,
) : MapHarness {
    override suspend fun run(
        surface: MapSurface,
        bundle: MapBundle,
    ) {
        if (initialBearing != 0.0) {
            surface.host.moveCamera(CameraUpdate(bearing = initialBearing))
        }
        var sawFullFrame = false
        var loggedFirstFrame = false
        var switchesDone = false
        coroutineScope {
            launch { logPuckWrites(surface) }
            surface.host.events.collect { event ->
                when (event) {
                    MapEvent.StyleLoaded -> {
                        Log.i(TAG, "style-loaded total=${surface.host.styleLoadCount}")
                    }

                    is MapEvent.FrameRendered -> {
                        if (event.full && !loggedFirstFrame) {
                            loggedFirstFrame = true
                            sawFullFrame = true
                            val elapsed = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
                            Log.i(TAG, "first-full-frame-ms=$elapsed")
                        }
                    }

                    MapEvent.Idle -> {
                        if (sawFullFrame && !switchesDone && styleSwitches > 0) {
                            switchesDone = true
                            Log.i(TAG, "switch-start")
                            repeat(styleSwitches) { index ->
                                val touring = index % 2 == 0
                                val style = if (touring) bundle.touring else bundle.style
                                val layers = if (touring) bundle.touringLayers else bundle.layers
                                surface.host.setStyle(style).onFailure { error ->
                                    Log.w(TAG, "style rejected: $error")
                                }
                                surface.host
                                    .applyStyleDiff(StyleDiff(style.id, layers))
                                    .onFailure { error ->
                                        Log.w(TAG, "style diff rejected: $error")
                                    }
                            }
                            delay(SWITCH_SETTLE_MS)
                            Log.i(
                                TAG,
                                "style-switches=$styleSwitches total-style-loads=${surface.host.styleLoadCount}",
                            )
                        }
                    }

                    is MapEvent.CameraChanged -> {
                        // Per-frame logging distorts the benchmark and would
                        // record the followed position; the harness stays quiet.
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }
    }

    private suspend fun logPuckWrites(surface: MapSurface) {
        var written = surface.host.puckWriteCount
        while (true) {
            delay(PUCK_LOG_INTERVAL_MS)
            val count = surface.host.puckWriteCount
            Log.i(PUCK_TAG, "puck-writes=$count delta=${count - written}")
            written = count
        }
    }

    companion object {
        private const val EXTRA_STYLE_SWITCHES = "t0020_style_switches"
        private const val EXTRA_INITIAL_BEARING = "t0021_initial_bearing"
        private const val SWITCH_SETTLE_MS = 3_000L
        private const val PUCK_LOG_INTERVAL_MS = 1_000L
        private const val TAG = "T0020"
        private const val PUCK_TAG = "T0103Puck"

        fun from(intent: Intent): DebugMapHarness =
            DebugMapHarness(
                styleSwitches = intent.getIntExtra(EXTRA_STYLE_SWITCHES, 0),
                initialBearing = intent.getDoubleExtra(EXTRA_INITIAL_BEARING, 0.0),
            )
    }
}
