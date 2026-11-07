package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.core.services.InMemoryLocationPermissionStore
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.service.LocationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocationPermissionCoordinatorTest {
    @Test
    fun aDeniedAnswerAsksOnceAndNeverAgain() =
        runTest {
            val service = PermissionService()
            val store = InMemoryLocationPermissionStore()
            var requests = 0
            val coordinator =
                LocationPermissionCoordinator(service, store, UnconfinedTestDispatcher(testScheduler))
            coordinator.bind(this) {
                requests += 1
                false
            }

            assertEquals(Outcome.Success(false), coordinator.ensureAllowed())
            assertEquals(Outcome.Success(false), coordinator.ensureAllowed())

            assertEquals(1, requests)
            assertEquals(true, store.hasAsked())
        }

    @Test
    fun aGrantedAnswerEnablesTheService() =
        runTest {
            val service = PermissionService()
            val coordinator =
                LocationPermissionCoordinator(
                    service,
                    InMemoryLocationPermissionStore(),
                    UnconfinedTestDispatcher(testScheduler),
                )
            coordinator.bind(this) {
                service.granted = true
                true
            }

            assertEquals(Outcome.Success(true), coordinator.ensureAllowed())
            assertEquals(true, service.hasPermission())
        }

    @Test
    fun concurrentTapsShareOneDialog() =
        runTest {
            val service = PermissionService()
            var requests = 0
            val coordinator =
                LocationPermissionCoordinator(
                    service,
                    InMemoryLocationPermissionStore(),
                    UnconfinedTestDispatcher(testScheduler),
                )
            coordinator.bind(this) {
                requests += 1
                false
            }

            val first = launch { coordinator.ensureAllowed() }
            val second = launch { coordinator.ensureAllowed() }
            advanceUntilIdle()
            first.join()
            second.join()

            assertEquals(1, requests)
        }

    @Test
    fun withoutABoundActivityNothingIsAsked() =
        runTest {
            val service = PermissionService()
            val store = InMemoryLocationPermissionStore()
            val coordinator =
                LocationPermissionCoordinator(service, store, UnconfinedTestDispatcher(testScheduler))

            assertEquals(Outcome.Failure(AppError.NotReady), coordinator.ensureAllowed())
            assertFalse(store.hasAsked())
        }

    @Test
    fun aSourceWithoutAPermissionNeedsNoDialog() =
        runTest {
            val coordinator =
                LocationPermissionCoordinator(
                    PermissionService(requiresPermission = false),
                    InMemoryLocationPermissionStore(),
                    UnconfinedTestDispatcher(testScheduler),
                )

            assertEquals(Outcome.Success(true), coordinator.ensureAllowed())
        }

    @Test
    fun anAlreadyGrantedServiceNeedsNoDialog() =
        runTest {
            var requests = 0
            val coordinator =
                LocationPermissionCoordinator(
                    PermissionService(granted = true),
                    InMemoryLocationPermissionStore(),
                    UnconfinedTestDispatcher(testScheduler),
                )
            coordinator.bind(this) {
                requests += 1
                true
            }

            assertEquals(Outcome.Success(true), coordinator.ensureAllowed())
            assertEquals(0, requests)
        }

    private class PermissionService(
        var granted: Boolean = false,
        private val requiresPermission: Boolean = true,
    ) : LocationService {
        override val fixes: Flow<LocationFix?> = emptyFlow()

        override fun hasPermission(): Boolean = granted

        override fun requiresPermission(): Boolean = requiresPermission
    }
}
