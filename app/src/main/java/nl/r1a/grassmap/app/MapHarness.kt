package nl.r1a.grassmap.app

import nl.r1a.grassmap.map.MapSurface

/**
 * Optional observation of a composed map surface. Production installs
 * [NoMapHarness]; a debug build installs its measurement harness, so no
 * benchmark extra or T-0020 log stays on the release path.
 */
internal fun interface MapHarness {
    suspend fun run(
        surface: MapSurface,
        bundle: MapBundle,
    )
}

internal val NoMapHarness = MapHarness { _, _ -> }
