package nl.r1a.grassmap.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nl.r1a.grassmap.navigation.NavigationPhase
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
 * The activity owns one location subscription while it is visible (T-0314).
 * The navigation foreground service owns the other one while guidance runs, so
 * leaving the app releases location unless navigation is active.
 *
 * The activity also receives the links another app sends (T-0211). It is
 * `singleTop`, so a `geo:` or osm.org link that arrives while the app is alive
 * reaches [onNewIntent] and the existing instance, instead of stacking a second
 * instance that would take the map host away from the first. A link the router
 * refuses, and an element link with no coordinates, answer with a message
 * instead of leaving the map unchanged and silent.
 *
 * A share from another app's sheet takes the same two entry points and is
 * imported before the link path (T-0416): a `content:` URI from another
 * provider is a document, which the link router never opens. A `file:` URI and
 * a `content:` URI under this app's own provider are refused instead.
 */
class MainActivity : ComponentActivity() {
    private val container: AppContainer by lazy { (application as GrassMapsApplication).container }
    private var notificationPermissionAsked = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted =
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            container.locationPermission.completePermission(granted)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * The system document picker for an export (T-0410). The coordinator keeps
     * the bytes and the pending answer, so a recreation still completes the
     * save; a dismissal is a null URI, not an error.
     */
    private val saveDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri =
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.data?.toString()
                } else {
                    null
                }
            container.documentSaver.complete(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val application = application as GrassMapsApplication
        val harness = application.mapHarness(this)
        intent
            ?.takeIf { it.hasExtra(ContoursSettings.EXTRA_ENABLED) }
            ?.let { launch ->
                container.contoursOverride = launch.getBooleanExtra(ContoursSettings.EXTRA_ENABLED, false)
            }
        container.locationPermission.bind(this) { requestLocationPermission() }
        container.documentSaver.bind(this) { file ->
            runOnUiThread { saveDocumentLauncher.launch(AndroidDocumentIntents.createDocument(file)) }
        }
        bindLocationToLifecycle()
        requestNotificationPermissionWhileNavigating()
        if (savedInstanceState == null) {
            if (!importSharedDocument(intent)) {
                openLink(intent?.dataString)
            }
        }
        setContent {
            MapScreen(container = container, harness = harness)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!importSharedDocument(intent)) {
            openLink(intent.dataString)
        }
    }

    override fun onDestroy() {
        container.locationPermission.unbind(this)
        container.documentSaver.unbind(this)
        super.onDestroy()
    }

    /**
     * Imports a GPX another app shared to this one (T-0416), and answers
     * whether this intent was one. The URI is read through the grant the intent
     * carries, never a storage permission, and the import runs off Main. A
     * first intent and a share that reaches the running instance take the same
     * path, because the activity is `singleTop`.
     */
    private fun importSharedDocument(intent: Intent?): Boolean {
        if (intent == null) {
            return false
        }
        val uri =
            TrackShareIntent.documentUri(
                action = intent.action,
                streamUri = streamUriOf(intent),
                dataUri = intent.dataString,
                ownProviderAuthority = trackFileProviderAuthority(packageName),
            ) ?: return false
        lifecycleScope.launch {
            container.trackShare.import(uri, ContentResolverSharedReader(this@MainActivity))
        }
        return true
    }

    /** The stream extra a share carries; the clip data holds it when the sender set one. */
    private fun streamUriOf(intent: Intent): String? {
        val clipped =
            intent.clipData
                ?.takeIf { data -> data.itemCount > 0 }
                ?.getItemAt(0)
                ?.uri
        if (clipped != null) {
            return clipped.toString()
        }
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        return stream?.toString()
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

    /**
     * Location belongs to the visible activity unless guidance runs. Stopping
     * at `STOPPED` cancels the cold source and releases the platform providers;
     * an active navigation session holds its own subscription in the service,
     * so a backgrounded app keeps location exactly when guidance needs it.
     */
    private fun bindLocationToLifecycle() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.locationController.start()
                try {
                    awaitCancellation()
                } finally {
                    container.locationController.stop()
                }
            }
        }
    }

    /**
     * Android 13 shows the foreground notification only with POST_NOTIFICATIONS.
     * The prompt follows the first active navigation and never appears on
     * launch; a refusal leaves guidance running without the notification.
     */
    private fun requestNotificationPermissionWhileNavigating() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.navigation.state
                    .map { state -> state.phase.isGuidanceActive() }
                    .distinctUntilChanged()
                    .collect { active -> if (active) requestNotificationPermission() }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (notificationPermissionAsked) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermissionAsked = true
        try {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (exception: IllegalStateException) {
            // A launcher that cannot start must not retry on every state.
        }
    }

    private companion object {
        val PERMISSIONS: Array<String> =
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )

        fun NavigationPhase.isGuidanceActive(): Boolean =
            this == NavigationPhase.Following || this == NavigationPhase.Rerouting || this == NavigationPhase.Paused
    }
}
