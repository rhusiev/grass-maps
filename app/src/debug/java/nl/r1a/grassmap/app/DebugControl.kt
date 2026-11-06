package nl.r1a.grassmap.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.control.ControlEndpoint
import nl.r1a.grassmap.control.ControlPeerGate
import nl.r1a.grassmap.control.ControlServer
import nl.r1a.grassmap.control.ControlSettings
import nl.r1a.grassmap.control.ControlTokenFile
import nl.r1a.grassmap.control.ControlTokens
import nl.r1a.grassmap.control.GeneratedControlCatalog
import nl.r1a.grassmap.control.PluginHostControlStreams
import nl.r1a.grassmap.devtools.AndroidWindowCapture
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * Starts the debug-only control channel.
 *
 * This file is in the debug source set and the app depends on `:control` and
 * `:devtools` in the debug configuration only, so a release build contains no
 * listener and no channel code. Everything runs off `Main`: the token file is
 * written there and only off the UI thread, and the socket is bound there.
 */
internal object DebugControl {
    const val SOCKET_NAME: String = "nl.r1a.grassmap.control"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ControlServer? = null

    fun start(
        application: Application,
        container: AppContainer,
        settings: ControlSettings,
    ) {
        if (server != null) {
            return
        }
        val capture = AndroidWindowCapture()
        application.registerActivityLifecycleCallbacks(WindowTracker(capture))
        scope.launch {
            if (!settings.isEnabled()) {
                Log.i(TAG, "control: disabled")
                return@launch
            }
            val token = ControlTokens.generate()
            if (ControlTokenFile.write(application.filesDir, token) !is Outcome.Success) {
                Log.w(TAG, "control: no token file, not listening")
                return@launch
            }
            Log.i(TAG, "control: token file ${ControlTokenFile.FILE_NAME}")
            val endpoint =
                ControlEndpoint(
                    catalog =
                        GeneratedControlCatalog(
                            commands = GeneratedPlugins.commands,
                            manifests = container.pluginHost.manifests(),
                        ),
                    invoker = container.commandBus,
                    streams = PluginHostControlStreams(container.pluginHost),
                    screenshot = capture,
                )
            val created =
                ControlServer(
                    socketName = SOCKET_NAME,
                    token = token,
                    endpoint = endpoint,
                    admittedUids = ControlPeerGate.admittedUids(Process.myUid()),
                    onState = { state -> Log.i(TAG, "control: ${state.name.lowercase()}") },
                )
            if (created.start() is Outcome.Success) {
                server = created
            }
        }
    }

    /** Debug builds enable the channel unconditionally until the switch lands (T-0114). */
    val settings: ControlSettings = ControlSettings { true }

    private class WindowTracker(
        private val capture: AndroidWindowCapture,
    ) : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            capture.attach(activity.window)
        }

        override fun onActivityPaused(activity: Activity) {
            capture.detach(activity.window)
        }

        override fun onActivityCreated(
            activity: Activity,
            savedInstanceState: Bundle?,
        ) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(
            activity: Activity,
            outState: Bundle,
        ) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private const val TAG = "GrassMapsControl"
}
