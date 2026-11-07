package nl.r1a.grassmap.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single activity. It composes the map surface from the process-scoped
 * container and owns the only path to the system location dialog.
 *
 * The dialog is launched from a position-control tap, never from
 * [onCreate]. The answer completes the request the command is waiting on; the
 * activity never asks twice, because the coordinator persists the fact that it
 * asked.
 */
class MainActivity : ComponentActivity() {
    private val container: AppContainer by lazy { (application as GrassMapsApplication).container }

    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted =
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            pendingPermission?.complete(granted)
            pendingPermission = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val application = application as GrassMapsApplication
        val harness = application.mapHarness(this)
        container.locationPermission.bind(this) { requestLocationPermission() }
        setContent {
            MapScreen(container = container, harness = harness)
        }
    }

    override fun onDestroy() {
        pendingPermission?.complete(false)
        pendingPermission = null
        container.locationPermission.unbind(this)
        super.onDestroy()
    }

    private suspend fun requestLocationPermission(): Boolean {
        val deferred =
            pendingPermission ?: CompletableDeferred<Boolean>().also { created ->
                pendingPermission = created
                try {
                    permissionLauncher.launch(PERMISSIONS)
                } catch (exception: IllegalStateException) {
                    pendingPermission = null
                    created.complete(false)
                }
            }
        val answer = withTimeoutOrNull(PERMISSION_TIMEOUT_MILLIS) { deferred.await() }
        if (pendingPermission === deferred) {
            pendingPermission = null
        }
        return answer == true
    }

    private companion object {
        val PERMISSIONS: Array<String> =
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        const val PERMISSION_TIMEOUT_MILLIS: Long = 5 * 60_000L
    }
}
