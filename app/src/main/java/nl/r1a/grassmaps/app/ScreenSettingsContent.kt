package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.core.services.AppThemeChoice
import nl.r1a.grassmaps.core.services.NightMapMode
import nl.r1a.grassmaps.core.services.ScreenSettingsPageState
import nl.r1a.grassmaps.core.services.UiLanguage
import nl.r1a.grassmaps.plugin.api.service.Strings

internal const val SCREEN_ROW_APP_THEME = "settings.row.app_theme"
internal const val SCREEN_ROW_NIGHT_FOLLOW = "settings.row.night_follow"
internal const val SCREEN_ROW_NIGHT_SUN = "settings.row.night_sun"
internal const val SCREEN_ROW_KEEP_SCREEN_ON = "settings.row.keep_screen_on"
internal const val SCREEN_ROW_DISABLE_ANIMATIONS = "settings.row.disable_animations"
internal const val SCREEN_ROW_LANGUAGE = "settings.row.language"

internal const val THEME_SYSTEM = "settings.choice.system"
internal const val THEME_LIGHT = "settings.choice.light"
internal const val THEME_DARK = "settings.choice.dark"
internal const val LANGUAGE_SYSTEM = "settings.language.system"
internal const val LANGUAGE_EN = "settings.language.en"
internal const val LANGUAGE_EN_GB = "settings.language.en_gb"
internal const val LANGUAGE_UK = "settings.language.uk"

private const val TITLE_KEY = "settings.screen_title"
private const val SUBTITLE_KEY = "settings.screen_subtitle"
private const val BACK_KEY = "settings.screen_back"
private const val READ_ONLY_KEY = "settings.screen_read_only"
private const val SUMMARY_KEY = "settings.screen_summary"

private const val APP_THEME_DETAIL_KEY = "settings.row.app_theme_detail"
private const val NIGHT_FOLLOW_DETAIL_KEY = "settings.row.night_follow_detail"
private const val NIGHT_SUN_DETAIL_KEY = "settings.row.night_sun_detail"
private const val NIGHT_SUN_DETAIL_UNKNOWN_KEY = "settings.row.night_sun_detail_unknown"
private const val SUN_UNKNOWN_PLACEHOLDER = "\u2014"
private const val KEEP_SCREEN_ON_DETAIL_KEY = "settings.row.keep_screen_on_detail"
private const val ANIMATIONS_DETAIL_KEY = "settings.row.disable_animations_detail"
private const val LANGUAGE_DETAIL_KEY = "settings.row.language_detail"

internal enum class ScreenRowKind {
    CHOICE,
    SWITCH,
}

internal data class ScreenSettingsOption(
    val key: String,
    val label: String,
)

internal data class ScreenSettingsRow(
    val key: String,
    val title: String,
    val detail: String,
    val kind: ScreenRowKind,
    val options: List<ScreenSettingsOption> = emptyList(),
    val selected: Int = -1,
    val checked: Boolean = false,
)

internal data class ScreenSettingsContent(
    val title: String,
    val subtitle: String,
    val back: String,
    val readOnlyTitle: String,
    val summary: String,
    val rows: List<ScreenSettingsRow>,
)

/**
 * The six scorecard rows of T-0111. The two night-map switches are two views
 * of one stored mode, and the animations switch is the inverse of the stored
 * "animations enabled" value. This function only reads state; every change
 * goes back through the host callback.
 */
internal fun screenSettingsContent(
    state: ScreenSettingsPageState,
    strings: Strings,
): ScreenSettingsContent {
    val values = state.values
    val themeChoices =
        listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK).map { key -> ScreenSettingsOption(key, strings.resolve(key)) }
    val languageChoices =
        listOf(LANGUAGE_SYSTEM, LANGUAGE_EN, LANGUAGE_EN_GB, LANGUAGE_UK).map { key ->
            ScreenSettingsOption(key, strings.resolve(key))
        }
    return ScreenSettingsContent(
        title = strings.resolve(TITLE_KEY),
        subtitle = strings.resolve(SUBTITLE_KEY, listOf(state.profileLabel)),
        back = strings.resolve(BACK_KEY),
        readOnlyTitle = strings.resolve(READ_ONLY_KEY),
        summary =
            strings.resolve(
                SUMMARY_KEY,
                listOf(
                    strings.resolve(
                        when (values.appTheme) {
                            AppThemeChoice.SYSTEM -> THEME_SYSTEM
                            AppThemeChoice.LIGHT -> THEME_LIGHT
                            AppThemeChoice.DARK -> THEME_DARK
                        },
                    ),
                    strings.resolve(
                        when (values.nightMap) {
                            NightMapMode.FOLLOW_SYSTEM -> THEME_SYSTEM
                            NightMapMode.BY_SUN -> SCREEN_ROW_NIGHT_SUN
                        },
                    ),
                ),
            ),
        rows =
            listOf(
                ScreenSettingsRow(
                    key = SCREEN_ROW_APP_THEME,
                    title = strings.resolve(SCREEN_ROW_APP_THEME),
                    detail = strings.resolve(APP_THEME_DETAIL_KEY),
                    kind = ScreenRowKind.CHOICE,
                    options = themeChoices,
                    selected = values.appTheme.ordinal,
                ),
                ScreenSettingsRow(
                    key = SCREEN_ROW_NIGHT_FOLLOW,
                    title = strings.resolve(SCREEN_ROW_NIGHT_FOLLOW),
                    detail = strings.resolve(NIGHT_FOLLOW_DETAIL_KEY),
                    kind = ScreenRowKind.SWITCH,
                    checked = values.nightMap == NightMapMode.FOLLOW_SYSTEM,
                ),
                ScreenSettingsRow(
                    key = SCREEN_ROW_NIGHT_SUN,
                    title = strings.resolve(SCREEN_ROW_NIGHT_SUN),
                    detail =
                        if (state.sunSummary.isBlank() || state.sunSummary == SUN_UNKNOWN_PLACEHOLDER) {
                            strings.resolve(NIGHT_SUN_DETAIL_UNKNOWN_KEY)
                        } else {
                            strings.resolve(NIGHT_SUN_DETAIL_KEY, listOf(state.sunSummary))
                        },
                    kind = ScreenRowKind.SWITCH,
                    checked = values.nightMap == NightMapMode.BY_SUN,
                ),
                ScreenSettingsRow(
                    key = SCREEN_ROW_KEEP_SCREEN_ON,
                    title = strings.resolve(SCREEN_ROW_KEEP_SCREEN_ON),
                    detail = strings.resolve(KEEP_SCREEN_ON_DETAIL_KEY),
                    kind = ScreenRowKind.SWITCH,
                    checked = values.keepScreenOn,
                ),
                ScreenSettingsRow(
                    key = SCREEN_ROW_DISABLE_ANIMATIONS,
                    title = strings.resolve(SCREEN_ROW_DISABLE_ANIMATIONS),
                    detail = strings.resolve(ANIMATIONS_DETAIL_KEY),
                    kind = ScreenRowKind.SWITCH,
                    checked = !values.animationsEnabled,
                ),
                ScreenSettingsRow(
                    key = SCREEN_ROW_LANGUAGE,
                    title = strings.resolve(SCREEN_ROW_LANGUAGE),
                    detail = strings.resolve(LANGUAGE_DETAIL_KEY),
                    kind = ScreenRowKind.CHOICE,
                    options = languageChoices,
                    selected = values.language.ordinal,
                ),
            ),
    )
}
