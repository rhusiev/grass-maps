package nl.r1a.grassmap.plugins.synthetic.data

import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.CostModifier
import nl.r1a.grassmap.plugin.api.provider.TravelDirection
import nl.r1a.grassmap.plugin.api.provider.WayAttribute

/**
 * The synthetic T-0023 data set. Every value is authored here, not read from a
 * region package: the task proves the DataSource seam, while the contour layer
 * it publishes draws the real archive.
 */
internal object SyntheticFixture {
    const val REGION: String = "fixture-mini"
    const val REGION_BUILD: String = "fixture-mini-2026-11-03-1"
    const val ELEVATION_KIND: String = "elevation"
    const val REFRESH_POLICY: String = "static"

    val bounds: BoundingBox =
        BoundingBox(
            southWest = GeoPoint(latitude = 49.94, longitude = 23.94),
            northEast = GeoPoint(latitude = 49.96, longitude = 23.96),
        )

    /** Ways inside [bounds], in the source's stable order. */
    val ways: List<FixtureWay> =
        listOf(
            fixtureWay(900000001, 49.9410, 23.9410, elevationMeters = 320.5),
            fixtureWay(900000002, 49.9430, 23.9450, elevationMeters = 335.0),
            fixtureWay(900000003, 49.9450, 23.9490, elevationMeters = 341.25),
            fixtureWay(900000004, 49.9470, 23.9530, elevationMeters = 328.0),
            fixtureWay(900000005, 49.9490, 23.9570, elevationMeters = 352.75),
        )

    val modifiers: List<CostModifier> =
        listOf(
            CostModifier(
                osmWayId = 900000001,
                geometry =
                    listOf(
                        GeoPoint(49.9410, 23.9410),
                        GeoPoint(49.9415, 23.9418),
                        GeoPoint(49.9420, 23.9425),
                    ),
                direction = TravelDirection.Forward,
                regionBuild = REGION_BUILD,
                multiplier = MULTIPLIER,
            ),
            CostModifier(
                osmWayId = 900000003,
                geometry =
                    listOf(
                        GeoPoint(49.9450, 23.9490),
                        GeoPoint(49.9455, 23.9496),
                    ),
                direction = TravelDirection.Both,
                regionBuild = REGION_BUILD,
                addedSeconds = ADDED_SECONDS,
            ),
        )

    private const val MULTIPLIER = 1.2
    private const val ADDED_SECONDS = 45.0

    private fun fixtureWay(
        osmWayId: Long,
        latitude: Double,
        longitude: Double,
        elevationMeters: Double,
    ): FixtureWay =
        FixtureWay(
            position = GeoPoint(latitude, longitude),
            attribute =
                WayAttribute(
                    osmWayId = osmWayId,
                    direction = TravelDirection.Both,
                    validFromMillis = null,
                    validToMillis = null,
                    regionBuild = REGION_BUILD,
                    values =
                        mapOf(
                            ELEVATION_KIND to AttributeValue.NumberValue(elevationMeters),
                            "synthetic" to AttributeValue.BooleanValue(true),
                        ),
                ),
        )
}

internal data class FixtureWay(
    val position: GeoPoint,
    val attribute: WayAttribute,
)

/** True when [point] is inside this north-up rectangle, edges included. */
internal fun BoundingBox.contains(point: GeoPoint): Boolean =
    point.latitude in southWest.latitude..northEast.latitude &&
        point.longitude in southWest.longitude..northEast.longitude
