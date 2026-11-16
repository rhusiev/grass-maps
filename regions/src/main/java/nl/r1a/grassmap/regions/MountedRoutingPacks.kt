package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.host.RoutingPackAccess
import java.io.File

/**
 * The routing pack directory for the installed regions: the merged view of
 * every mounted pack's routing part. It reads the store on each call, so a
 * pack installed since the last call is picked up. The Valhalla engine asks
 * before every route and reopens when the returned path differs (T-0511).
 */
class MountedRoutingPacks(
    regionsRoot: File,
    private val log: (String) -> Unit = {},
) : RoutingPackAccess {
    private val resolver = RegionMountResolver(regionsRoot)
    private val mount = RoutingMount(RegionPaths(regionsRoot).routingMountDirectory(), log)

    override fun packDirectory(): String? {
        val mounts = resolver.resolveAll()
        return when (val result = mount.ensure(mounts.installed)) {
            is RoutingMountResult.Ready -> {
                result.skipped.forEach { log("routing skips ${it.region}: ${it.reason}") }
                result.directory.absolutePath
            }

            is RoutingMountResult.Unavailable -> {
                log("routing mount unavailable: ${result.problem}")
                null
            }
        }
    }
}
