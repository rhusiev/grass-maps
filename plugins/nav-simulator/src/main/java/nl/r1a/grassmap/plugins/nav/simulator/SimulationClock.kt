package nl.r1a.grassmap.plugins.nav.simulator

/**
 * The simulator's clock. It advances only when the simulator moves to the next
 * fix, never from wall time, so every run has reproducible fix timestamps and a
 * JVM test can run a whole route without sleeping.
 */
class SimulationClock(
    private val startMillis: Long = NavSimulatorContract.START_EPOCH_MILLIS,
) {
    var nowMillis: Long = startMillis
        private set

    internal fun reset() {
        nowMillis = startMillis
    }

    internal fun advance(millis: Long) {
        require(millis >= 0) { "the simulation clock cannot move backwards" }
        nowMillis += millis
    }
}
