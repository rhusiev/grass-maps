package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.service.Strings

internal const val SETTINGS_INDEX_TITLE_KEY = "settings.screen_title"
internal const val SETTINGS_INDEX_SUBTITLE_KEY = "settings.index.subtitle"
internal const val SETTINGS_INDEX_BACK_KEY = "settings.screen_back"
internal const val SETTINGS_INDEX_USE_KEY = "settings.index.group.use"
internal const val SETTINGS_INDEX_DATA_KEY = "settings.index.group.data"

internal const val SETTINGS_INDEX_PROFILES_KEY = "settings.index.row.profiles"
internal const val SETTINGS_INDEX_LANGUAGE_KEY = "settings.index.row.language"
internal const val SETTINGS_INDEX_APPEARANCE_KEY = "settings.index.row.appearance"
internal const val SETTINGS_INDEX_DOWNLOADS_KEY = "settings.index.row.downloads"
internal const val SETTINGS_INDEX_SAVED_KEY = "settings.index.row.saved"
internal const val SETTINGS_INDEX_TRACKS_KEY = "settings.index.row.tracks"
internal const val SETTINGS_INDEX_PLUGINS_KEY = "settings.index.row.plugins"
internal const val SETTINGS_INDEX_BACKUP_KEY = "settings.index.row.backup"

internal enum class SettingsIndexGroup {
    USE,
    DATA_AND_PRIVACY,
}

/** The existing screens an index row can open. */
internal enum class SettingsIndexTarget {
    PROFILES,
    APPEARANCE,
    DOWNLOADS,
    SAVED,
    TRACKS,
    PLUGINS,
    BACKUP,
}

internal data class SettingsIndexRow(
    val key: String,
    val title: String,
    val group: SettingsIndexGroup,
    val target: SettingsIndexTarget,
)

internal data class SettingsIndexContent(
    val title: String,
    val subtitle: String,
    val back: String,
    val groupTitles: Map<SettingsIndexGroup, String>,
    val rows: List<SettingsIndexRow>,
)

/**
 * The Settings index of round 4/settings.html. Only rows with a real
 * destination are offered. Units, Gestures, Privacy, Storage and About have no
 * screen in this build, so they are not listed. Language opens the screen
 * settings subpage because the in-app language control lives there.
 * Saved uses this index until the map bar has the approved widgets and Saved tray.
 * Network, online search and auto-update have no settings screen of their own:
 * online search is a map-search action and auto-update lives inside downloads.
 */
internal fun settingsIndexContent(strings: Strings): SettingsIndexContent {
    fun row(
        key: String,
        group: SettingsIndexGroup,
        target: SettingsIndexTarget,
    ) = SettingsIndexRow(key, strings.resolve(key), group, target)

    return SettingsIndexContent(
        title = strings.resolve(SETTINGS_INDEX_TITLE_KEY),
        subtitle = strings.resolve(SETTINGS_INDEX_SUBTITLE_KEY),
        back = strings.resolve(SETTINGS_INDEX_BACK_KEY),
        groupTitles =
            mapOf(
                SettingsIndexGroup.USE to strings.resolve(SETTINGS_INDEX_USE_KEY),
                SettingsIndexGroup.DATA_AND_PRIVACY to strings.resolve(SETTINGS_INDEX_DATA_KEY),
            ),
        rows =
            listOf(
                row(SETTINGS_INDEX_PROFILES_KEY, SettingsIndexGroup.USE, SettingsIndexTarget.PROFILES),
                row(SETTINGS_INDEX_LANGUAGE_KEY, SettingsIndexGroup.USE, SettingsIndexTarget.APPEARANCE),
                row(SETTINGS_INDEX_APPEARANCE_KEY, SettingsIndexGroup.USE, SettingsIndexTarget.APPEARANCE),
                row(SETTINGS_INDEX_TRACKS_KEY, SettingsIndexGroup.USE, SettingsIndexTarget.TRACKS),
                row(SETTINGS_INDEX_PLUGINS_KEY, SettingsIndexGroup.USE, SettingsIndexTarget.PLUGINS),
                row(SETTINGS_INDEX_DOWNLOADS_KEY, SettingsIndexGroup.DATA_AND_PRIVACY, SettingsIndexTarget.DOWNLOADS),
                row(SETTINGS_INDEX_SAVED_KEY, SettingsIndexGroup.DATA_AND_PRIVACY, SettingsIndexTarget.SAVED),
                row(SETTINGS_INDEX_BACKUP_KEY, SettingsIndexGroup.DATA_AND_PRIVACY, SettingsIndexTarget.BACKUP),
            ),
    )
}
