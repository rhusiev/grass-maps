package nl.r1a.grassmaps.app

/**
 * Orders a stop after the foreground start that precedes it.
 *
 * `startForegroundService` obliges the service to call `startForeground`.
 * A stop that destroys the service before its start command ran leaves that
 * promise broken, and Android kills the process with
 * `Context.startForegroundService() did not then call Service.startForeground()`.
 * While a start is pending, a stop is only recorded. The service reads the
 * record after its `startForeground` call and ends itself then.
 */
internal class ForegroundStartGate {
    private var starting = false
    private var stopDeferred = false

    /** Marks a start as pending and drops a stop recorded for an earlier session. */
    @Synchronized
    fun starting() {
        starting = true
        stopDeferred = false
    }

    /** Returns true when the stop was recorded for later, false when it may run now. */
    @Synchronized
    fun deferStopIfStarting(): Boolean {
        if (starting) {
            stopDeferred = true
        }
        return starting
    }

    /** Marks the pending start as done and returns whether a stop arrived meanwhile. */
    @Synchronized
    fun reached(): Boolean {
        starting = false
        return stopDeferred.also { stopDeferred = false }
    }
}
