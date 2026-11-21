package nl.r1a.grassmaps.plugin.api.host

/**
 * Where the mounted Valhalla routing pack lives. A routing engine plugin is
 * created with a no-argument constructor, so the host hands it this access
 * instead of the plugin reading the app's storage layout.
 *
 * The path is a directory holding `valhalla.json` and the tiles, as either
 * `tiles.tar` or a `tiles/` directory. An `admins.sqlite` or `timezones.sqlite`
 * the pack still ships is ignored: a route reads neither, so the engine is not
 * pointed at them (T-0515). It is null before a pack is mounted. A path says
 * only where to look: the engine inspects it and reports an absent, incomplete
 * or unreadable pack as
 * [nl.r1a.grassmaps.plugin.api.error.AppError.RoutingPackUnavailable].
 */
fun interface RoutingPackAccess {
    fun packDirectory(): String?
}

/** The access a host uses before a routing pack is mounted. */
object NoRoutingPacks : RoutingPackAccess {
    override fun packDirectory(): String? = null
}
