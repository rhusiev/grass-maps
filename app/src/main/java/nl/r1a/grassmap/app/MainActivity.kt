package nl.r1a.grassmap.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

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

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted =
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            container.locationPermission.completePermission(granted)
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
        container.locationPermission.unbind(this)
        super.onDestroy()
    }

    private suspend fun requestLocationPermission(): Boolean? =
        container.locationPermission.requestPermission {
            try {
                permissionLauncher.launch(PERMISSIONS)
            } catch (exception: IllegalStateException) {
                // A launcher that cannot start must not leave the request pending.
                container.locationPermission.completePermission(false)
            }
        }

    private companion object {
        val PERMISSIONS: Array<String> =
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
    }
}
