package nl.r1a.grassmap.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import nl.r1a.grassmap.plugins.links.LinkResult
import nl.r1a.grassmap.plugins.links.LinkTarget

/**
 * The single activity. It composes the map surface from the process-scoped
 * container and owns the only path to the system location dialog.
 *
 * The dialog is launched from a position-control tap, never from
 * [onCreate]. The answer completes the request the command is waiting on; the
 * activity never asks twice, because the coordinator persists the fact that it
 * asked.
 *
 * The activity also receives the links another app sends (T-0211). It is
 * `singleTop`, so a `geo:` or osm.org link that arrives while the app is alive
 * reaches [onNewIntent] and the existing instance, instead of stacking a second
 * instance that would take the map host away from the first. A link the router
 * refuses, and an element link with no coordinates, answer with a message
 * instead of leaving the map unchanged and silent.
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
        if (savedInstanceState == null) {
            openLink(intent?.dataString)
        }
        setContent {
            MapScreen(container = container, harness = harness)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openLink(intent.dataString)
    }

    override fun onDestroy() {
        container.locationPermission.unbind(this)
        super.onDestroy()
    }

    /** Parks the link's camera move, or tells the user why the map will not move. */
    private fun openLink(uri: String?) {
        when (val result = container.links.submit(uri)) {
            null -> {
                Unit
            }

            is LinkResult.Failure -> {
                showLinkMessage(R.string.link_not_opened)
            }

            is LinkResult.Success -> {
                val target = result.target
                if (target is LinkTarget.Element && target.point == null) {
                    showLinkMessage(R.string.link_without_coordinates)
                }
            }
        }
    }

    private fun showLinkMessage(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
