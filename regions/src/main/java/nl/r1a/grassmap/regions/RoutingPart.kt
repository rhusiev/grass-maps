package nl.r1a.grassmap.regions

import java.io.File

/**
 * The routing part of one mounted region package. A package that declares no
 * `routing` component has none of these (`MountedRegion.routing` is null).
 */
sealed interface RoutingPart {
    /**
     * The `routing/` directory: `tiles/` with the Valhalla tile files and
     * `valhalla.json`. Mount checks that it exists; the installer verified every
     * file in it.
     */
    data class Directory(
        val directory: File,
        val sha256: String,
        /** The monthly tile build, from the routing component's `version`. */
        val build: String? = null,
    ) : RoutingPart

    /**
     * A `routing.tar` from before routing became a directory. It cannot be
     * merged with other packs, so the routing mount skips it and the user needs
     * to download the package again.
     */
    data object LegacyArchive : RoutingPart

    /** Declared in a format this app does not read, or the directory is missing. */
    data object Unusable : RoutingPart
}

/** The names the routing mount and the Valhalla plugin agree on. */
object RoutingLayout {
    const val KIND: String = "routing"
    const val FORMAT: String = "valhalla-tile-directory"
    const val FORMAT_VERSION: Int = 1

    /** Holds the tile files, one copy per relative path across all packs. */
    const val TILES_DIRECTORY: String = "tiles"

    /** The Valhalla config; the plugin replaces every path in it. */
    const val CONFIG_FILE: String = "valhalla.json"
}
