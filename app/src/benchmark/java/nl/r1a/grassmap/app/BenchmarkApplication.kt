package nl.r1a.grassmap.app

import android.app.Activity

/**
 * The benchmark build type's application (T-0020/T-0102 measurement only).
 *
 * It is the release application plus the T-0020 harness, so the g32 run reads
 * `first-full-frame-ms` from a release-like build. Nothing else from the debug
 * source set is installed.
 */
class BenchmarkApplication : GrassMapsApplication() {
    override fun mapHarness(activity: Activity): MapHarness = DebugMapHarness.from(activity.intent)
}
