package nl.r1a.grassmap.app

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import nl.r1a.grassmap.map.MapSurface
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.host.MapEvent
import nl.r1a.grassmap.plugin.api.model.StyleDiff

/**
 * The T-0020 measurement harness. It lives in the debug source set and is only
 * installed by [DebugApplication], so the release build has no launch-extra
 * branch and no first-frame logging.
 */
internal class DebugMapHarness(
    private val styleSwitches: Int,
) : MapHarness {
    override suspend fun run(
        surface: MapSurface,
        bundle: MapBundle,
    ) {
        var sawFullFrame = false
        var loggedFirstFrame = false
        var switchesDone = false
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

                else -> {
                    Unit
                }
            }
        }
    }

    companion object {
        private const val EXTRA_STYLE_SWITCHES = "t0020_style_switches"
        private const val SWITCH_SETTLE_MS = 3_000L
        private const val TAG = "T0020"

        fun from(intent: Intent): DebugMapHarness = DebugMapHarness(intent.getIntExtra(EXTRA_STYLE_SWITCHES, 0))
    }
}
