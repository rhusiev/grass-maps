package nl.r1a.grassmap.plugin.api.host

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint

/**
 * Terrain height from the mounted region data (architecture.md section 4.4,
 * the DEM-height half of `RegionAccess`).
 *
 * A point outside every mounted region is a success with a null height, not a
 * failure: the caller decides what to show. A failure means the height could
 * not be read, so the caller falls back to another source.
 */
fun interface ElevationAccess {
    suspend fun elevationMeters(position: GeoPoint): Outcome<Double?>
}

/** The height source a host uses before region data is mounted. */
object NoElevationAccess : ElevationAccess {
    override suspend fun elevationMeters(position: GeoPoint): Outcome<Double?> = Outcome.Success(null)
}
