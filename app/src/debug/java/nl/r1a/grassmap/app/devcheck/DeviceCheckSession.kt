package nl.r1a.grassmaps.app.devcheck

import android.app.Application
import nl.r1a.grassmaps.app.AppContainer
import nl.r1a.grassmaps.app.DebugControl

/** The one controller of the process, so a recomposed panel does not lose a running check. */
internal object DeviceCheckSession {
    private var controller: DeviceCheckController? = null

    @Synchronized
    fun of(
        app: Application,
        container: AppContainer,
    ): DeviceCheckController =
        controller ?: DeviceCheckController(
            app = app,
            container = container,
            capture = DebugControl.windowCapture,
        ).also { controller = it }
}
