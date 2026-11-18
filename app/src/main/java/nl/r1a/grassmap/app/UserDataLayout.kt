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

    /** The per-profile stores, one versioned file each (architecture sections 11 and 20). */
    const val PROFILES_DIRECTORY = "profiles"
    const val ROUTING_OPTIONS_PATH = "$PROFILES_DIRECTORY/routing-options.json"
    const val PROFILE_CATALOG_PATH = "$PROFILES_DIRECTORY/catalog.json"
    const val ALERT_SETTINGS_PATH = "$PROFILES_DIRECTORY/alerts.json"

    /** The unversioned files the profile stores migrate from, removed once the new file is verified. */
    const val LEGACY_ROUTING_OPTIONS_PATH = "settings/routing-options.json"
    const val LEGACY_ALERT_SETTINGS_PATH = "settings/alerts.json"
}
