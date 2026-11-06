package nl.r1a.grassmap.control

/**
 * The control channel's own enable flag.
 *
 * Deliberately local to this module: the shared settings service is T-0016's and
 * the debug channel must not depend on it. A debug build supplies an
 * implementation that reports the channel as enabled.
 */
fun interface ControlSettings {
    fun isEnabled(): Boolean
}
