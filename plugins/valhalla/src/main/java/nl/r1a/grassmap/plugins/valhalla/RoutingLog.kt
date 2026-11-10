package nl.r1a.grassmap.plugins.valhalla

/**
 * The engine's one diagnostic line. The app binds this to logcat; a test binds
 * it to a list, so the module itself has no Android logging dependency.
 */
fun interface RoutingLog {
    fun info(message: String)

    companion object {
        val None: RoutingLog = RoutingLog { }
    }
}
