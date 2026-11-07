package nl.r1a.grassmap.navigation

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.DeviationKind
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.NavigationSession
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.Speed
import uniffi.ferrostar.TripState
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.createNavigationSession
import uniffi.ferrostar.stepAdvanceDistanceEntryAndExit
import uniffi.ferrostar.stepAdvanceDistanceToEndOfStep
import java.time.Instant

/**
 * The Ferrostar Rust core behind [RouteFollowerEngine]. This is the only file
 * that names a `uniffi.ferrostar` type.
 *
 * The core artifact is `core` only: it has no Compose UI and no map binding.
 * Its native library is loaded on first use, so construction stays cheap and a
 * JVM test never touches it.
 */
class FerrostarRouteFollower(
    private val config: NavigationConfig = NavigationConfig(),
    private val log: (String) -> Unit = {},
) : RouteFollowerEngine {
    override fun begin(route: Route): Outcome<RouteFollowerHandle> {
        val converted =
            FerrostarRouteMapper.toFerrostar(route)
                ?: return Outcome.Failure(AppError.InvalidInput)
        return try {
            val session = createNavigationSession(converted, controllerConfig(), emptyList())
            log(
                "ferrostar-session points=${converted.geometry.size} " +
                    "steps=${converted.steps.size} deviation_m=${config.maxDeviationMeters}",
            )
            Outcome.Success(FerrostarFollowerHandle(session))
        } catch (error: Exception) {
            Outcome.Failure(fail("begin", error))
        } catch (error: LinkageError) {
            Outcome.Failure(fail("begin", error))
        }
    }

    private fun controllerConfig(): NavigationControllerConfig =
        NavigationControllerConfig(
            WaypointAdvanceMode.WaypointWithinRange(config.waypointAdvanceMeters),
            stepAdvanceDistanceEntryAndExit(
                STEP_ADVANCE_ENTRY_METERS,
                STEP_ADVANCE_EXIT_METERS,
                STEP_ADVANCE_MIN_ACCURACY_METERS,
            ),
            stepAdvanceDistanceToEndOfStep(ARRIVAL_STEP_ADVANCE_METERS, STEP_ADVANCE_MIN_ACCURACY_METERS),
            RouteDeviationTracking.StaticThreshold(
                config.minimumHorizontalAccuracyMeters.toUShort(),
                config.maxDeviationMeters,
            ),
            CourseFiltering.RAW,
        )

    private fun fail(
        phase: String,
        error: Throwable,
    ): AppError {
        log("ferrostar $phase failed: ${error.javaClass.simpleName}: ${error.message}")
        return AppError.Unsupported
    }

    private class FerrostarFollowerHandle(
        private val session: NavigationSession,
    ) : RouteFollowerHandle {
        private var state: uniffi.ferrostar.NavState? = null

        @Synchronized
        override fun initialFix(fix: LocationFix): Outcome<FollowerSnapshot> =
            call {
                val initial = session.getInitialState(fix.toUserLocation())
                state = initial
                snapshotOf(initial)
            }

        @Synchronized
        override fun update(fix: LocationFix): Outcome<FollowerSnapshot> {
            val previous = state ?: return initialFix(fix)
            return call {
                val next = session.updateUserLocation(fix.toUserLocation(), previous)
                state = next
                snapshotOf(next)
            }
        }

        @Synchronized
        override fun close() {
            try {
                session.close()
            } catch (error: Exception) {
                // Closing a native handle must not fail the session.
            } catch (error: LinkageError) {
                // The library may already be gone; nothing left to release.
            }
        }

        private inline fun call(block: () -> FollowerSnapshot): Outcome<FollowerSnapshot> =
            try {
                Outcome.Success(block())
            } catch (error: Exception) {
                Outcome.Failure(AppError.Unsupported)
            } catch (error: LinkageError) {
                Outcome.Failure(AppError.Unsupported)
            }

        private fun snapshotOf(state: uniffi.ferrostar.NavState): FollowerSnapshot =
            when (val trip = state.tripState) {
                is TripState.Navigating -> {
                    FollowerSnapshot(
                        snappedPosition = trip.snappedUserLocation.coordinates.toGeoPoint(),
                        deviation = trip.deviation.toFollowerDeviation(),
                        deviationMeters = trip.deviation.toDeviationMeters(),
                        arrived = false,
                        remainingDistanceMeters = trip.progress.distanceRemaining,
                        remainingDurationSeconds = trip.progress.durationRemaining,
                        distanceToNextInstructionMeters = trip.progress.distanceToNextManeuver,
                        nextInstruction = trip.visualInstruction?.primaryContent?.text,
                    )
                }

                is TripState.Complete -> {
                    FollowerSnapshot(
                        snappedPosition = trip.userLocation.coordinates.toGeoPoint(),
                        arrived = true,
                        remainingDistanceMeters = 0.0,
                        remainingDurationSeconds = 0.0,
                    )
                }

                is TripState.Idle -> {
                    FollowerSnapshot(
                        snappedPosition = trip.userLocation?.coordinates?.toGeoPoint(),
                    )
                }
            }

        private fun RouteDeviation.toFollowerDeviation(): FollowerDeviation =
            when (this) {
                is RouteDeviation.Deviation -> {
                    when (kind) {
                        is DeviationKind.CompletelyOffRoute -> FollowerDeviation.OffRoute
                        is DeviationKind.OffStepOnRoute -> FollowerDeviation.OffStepOnRoute
                    }
                }

                is RouteDeviation.NoDeviation -> {
                    FollowerDeviation.None
                }
            }

        private fun RouteDeviation.toDeviationMeters(): Double? =
            when (this) {
                is RouteDeviation.Deviation -> {
                    when (val kind = kind) {
                        is DeviationKind.CompletelyOffRoute -> kind.deviationFromRouteLine
                        is DeviationKind.OffStepOnRoute -> kind.deviationFromStepLine
                    }
                }

                is RouteDeviation.NoDeviation -> {
                    null
                }
            }

        private fun GeographicCoordinate.toGeoPoint(): GeoPoint = GeoPoint(latitude = lat, longitude = lng)

        private fun LocationFix.toUserLocation(): UserLocation =
            UserLocation(
                coordinates = GeographicCoordinate(lat = position.latitude, lng = position.longitude),
                horizontalAccuracy = accuracyMeters,
                courseOverGround = null,
                timestamp = Instant.ofEpochMilli(timeMillis),
                speed = speedMetersPerSecond?.let { Speed(value = it, accuracy = null) },
            )
    }

    private companion object {
        const val STEP_ADVANCE_ENTRY_METERS: UShort = 30u
        const val STEP_ADVANCE_EXIT_METERS: UShort = 5u
        const val STEP_ADVANCE_MIN_ACCURACY_METERS: UShort = 32u
        const val ARRIVAL_STEP_ADVANCE_METERS: UShort = 10u
    }
}
