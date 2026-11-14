package nl.r1a.grassmap.app

/**
 * The app-private paths of the user-data stores, relative to `filesDir`.
 *
 * The stores wire these paths and `res/xml/data_extraction_rules.xml` and
 * `res/xml/backup_rules.xml` name the same ones, so a store that moves takes
 * its backup rule with it. `BackupRulesTest` reads these constants instead of
 * copying the strings, which would let the two drift apart.
 */
internal object UserDataLayout {
    const val FAVOURITES_PATH = "favourites/favourites.gpx"
    const val TRACKS_DIRECTORY = "tracks"
    const val REGIONS_DIRECTORY = "regions"
    const val SEARCH_HISTORY_PATH = "history/search-history.json"
    const val ROUTE_HISTORY_PATH = "history/route-history.json"
}
