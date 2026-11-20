package nl.r1a.grassmap.app

import android.content.Context
import android.content.Intent

/**
 * The controller's path to the navigation foreground service.
 *
 * It is an interface so [NavigationController] stays free of Android types and
 * a test can observe starts and stops without a device. It never carries the
 * navigation state: the service reads [NavigationController.state] itself.
 */
interface NavigationServiceHandle {
    /** Starts the service and its foreground notification. */
    fun start()

    /** Stops the service and removes its notification. */
    fun stop()
}

/** The handle a build or a test uses when nothing can host a foreground service. */
object NoNavigationServiceHandle : NavigationServiceHandle {
    override fun start() = Unit

    override fun stop() = Unit
}

/** Starts and stops [NavigationService] in this app's process. */
class AndroidNavigationServiceHandle internal constructor(
    private val startService: () -> Unit,
    private val stopService: () -> Unit,
    private val gate: ForegroundStartGate,
) : NavigationServiceHandle {
    constructor(context: Context) : this(
        startService = {
            context.startForegroundService(
                Intent(context, NavigationService::class.java).setAction(NavigationService.ACTION_START),
            )
        },
        stopService = { context.stopService(Intent(context, NavigationService::class.java)) },
        gate = NavigationService.startGate,
    )

    override fun start() {
        gate.starting()
        try {
            startService()
        } catch (error: RuntimeException) {
            gate.reached()
            throw error
        }
    }

    override fun stop() {
        if (!gate.deferStopIfStarting()) {
            stopService()
        }
    }
}
