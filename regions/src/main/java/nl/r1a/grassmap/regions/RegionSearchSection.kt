package nl.r1a.grassmap.regions

/**
 * The search section a region package declares (T-0620). The files sit in the
 * package's version directory, beside the map; the reader opens them by name
 * from that directory, so the directory itself is the section.
 *
 * The seven table files are required. A version-1 section has no `format.bin`
 * and still opens; a version-2 declaration must carry it. The feature
 * component gives a place row its OSM identity and tags, and the reader
 * refuses a section without one, so a declared section carries all eight.
 */
object RegionSearchSection {
    /** Every search table component's kind starts with this prefix. */
    const val KIND_PREFIX: String = "search-"

    /** The version header component; a version-1 section omits it. */
    const val FORMAT_KIND: String = "search-format"

    /** The format of every search table component. */
    const val FORMAT: String = "search-section"

    /** The first section version that carries `format.bin`. */
    const val FIRST_FORMAT_FILE_VERSION: Int = 2

    /** The section versions the reader opens; a newer one is unsupported. */
    val FORMAT_VERSIONS: IntRange = 1..FIRST_FORMAT_FILE_VERSION

    /** The feature component that gives a place row its OSM identity. */
    const val FEATURES_KIND: String = "features"
    const val FEATURES_FORMAT: String = "place-features"
    const val FEATURES_FORMAT_VERSION: Int = 1

    /** The table files every section carries, whatever its version. */
    val REQUIRED_KINDS: Set<String> =
        setOf(
            "search-terms",
            "search-postings",
            "search-places",
            "search-context",
            "search-entities",
            "search-kinds",
            "search-strings",
        )
}
