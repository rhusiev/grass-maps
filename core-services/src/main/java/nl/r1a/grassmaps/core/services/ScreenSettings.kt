package nl.r1a.grassmaps.core.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugin.api.service.ProfileSettingsGroup
import nl.r1a.grassmaps.plugin.api.service.ProfileValueStore
import nl.r1a.grassmaps.plugin.api.service.ProfileValues
import nl.r1a.grassmaps.plugin.api.service.withKnownValues
import java.time.Clock
import java.util.Locale

/** The app theme of the shell: follow the phone, or pin light or dark. */
enum class AppThemeChoice(
    val wire: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun of(value: String?): AppThemeChoice = entries.firstOrNull { it.wire == value } ?: SYSTEM
    }
}

/** How the map decides its night variant, independently of the shell. */
enum class NightMapMode(
    val wire: String,
) {
    FOLLOW_SYSTEM("follow_system"),
    BY_SUN("by_sun"),
    ;

    companion object {
        fun of(value: String?): NightMapMode = entries.firstOrNull { it.wire == value } ?: FOLLOW_SYSTEM
    }
}

/** The in-app UI language; SYSTEM keeps the phone's locale. */
enum class UiLanguage(
    val wire: String,
    val localeTag: String?,
) {
    SYSTEM("system", null),
    ENGLISH("en", "en"),
    ENGLISH_UK("en_GB", "en-GB"),
    UKRAINIAN("uk", "uk"),
    ;

    companion object {
        fun of(value: String?): UiLanguage = entries.firstOrNull { it.wire == value } ?: SYSTEM
    }
}

/** The six screen rows, stored as one value object per profile (T-0111). */
data class ScreenSettingsValues(
    val appTheme: AppThemeChoice = AppThemeChoice.SYSTEM,
    val nightMap: NightMapMode = NightMapMode.FOLLOW_SYSTEM,
    val keepScreenOn: Boolean = false,
    val animationsEnabled: Boolean = true,
    val language: UiLanguage = UiLanguage.SYSTEM,
) {
    /** Only the non-default rows, so an untouched profile stores nothing. */
    fun asStoredValues(): Map<String, String> =
        buildMap {
            if (appTheme != AppThemeChoice.SYSTEM) put(ScreenSettings.APP_THEME_KEY, appTheme.wire)
            if (nightMap != NightMapMode.FOLLOW_SYSTEM) put(ScreenSettings.NIGHT_MAP_KEY, nightMap.wire)
            if (keepScreenOn) put(ScreenSettings.KEEP_SCREEN_ON_KEY, "true")
            if (!animationsEnabled) put(ScreenSettings.ANIMATIONS_KEY, "false")
            if (language != UiLanguage.SYSTEM) put(ScreenSettings.LANGUAGE_KEY, language.wire)
        }
}

/** The two dark flags one screen needs: one for the shell, one for the map. */
data class ThemeDecision(
    val shellDark: Boolean,
    val mapDark: Boolean,
)

/**
 * Resolves the six choices into the two dark flags the UI draws with (T-0111).
 *
 * A capture's [pin] wins over every stored choice, which is the existing debug
 * contract (`IS_DARK_THEME`). The app theme controls only the shell; the night
 * map mode controls only the map, so a light shell can still show a night map.
 * BY_SUN needs sun times for the current location and falls back to the system
 * when no fix or no sun data exists.
 */
fun resolveTheme(
    values: ScreenSettingsValues,
    systemDark: Boolean,
    pin: Boolean?,
    sunTimes: SunTimes?,
    clock: Clock,
): ThemeDecision {
    val shellDark =
        pin ?: when (values.appTheme) {
            AppThemeChoice.SYSTEM -> systemDark
            AppThemeChoice.LIGHT -> false
            AppThemeChoice.DARK -> true
        }
    val mapDark =
        pin ?: when (values.nightMap) {
            NightMapMode.FOLLOW_SYSTEM -> systemDark
            NightMapMode.BY_SUN -> sunTimes?.isNight(clock.instant()) ?: systemDark
        }
    return ThemeDecision(shellDark, mapDark)
}

/** The Locale a chosen UI language resolves to, independent of the phone's locale. */
fun uiLocale(
    language: UiLanguage,
    systemLocale: Locale,
): Locale = language.localeTag?.let(Locale::forLanguageTag) ?: systemLocale

/** What the settings page shows; it holds no state and never writes a store. */
data class ScreenSettingsPageState(
    val values: ScreenSettingsValues,
    val systemDark: Boolean,
    val profileLabel: String,
    val sunSummary: String,
)

/**
 * The screen settings of T-0111. Six rows live in one versioned per-profile
 * file, so profile copy, reset and undo cover them with the other stores
 * (T-0603). Construction reads the file, so resolve the store off Main.
 */
class ScreenSettings(
    private val store: ProfileValueStore? = null,
    initialProfile: ProfileId = BuiltInProfiles.WALK,
) : ProfileSettingsGroup {
    override val id: String = ID

    private var values: ProfileValues = emptyMap()
    private var isLoaded = false
    private var activeProfile: ProfileId = initialProfile
    private val activeState = MutableStateFlow(ScreenSettingsValues())

    /** The values of the active profile that the screen and the theme read. */
    val active: StateFlow<ScreenSettingsValues> = activeState.asStateFlow()

    init {
        load()
    }

    /** Reads the store. A failure leaves every profile at the defaults. */
    @Synchronized
    fun load(): Outcome<Unit> {
        val read = store?.read() ?: Outcome.Success(emptyMap())
        return when (read) {
            is Outcome.Success -> {
                values = read.value
                isLoaded = true
                activeState.value = decode(values[activeProfile])
                Outcome.Success(Unit)
            }

            is Outcome.Failure -> {
                values = emptyMap()
                isLoaded = false
                activeState.value = ScreenSettingsValues()
                read
            }
        }
    }

    fun current(): ScreenSettingsValues = active.value

    /** The values of [profile]; the defaults when the user never chose a row. */
    @Synchronized
    fun forProfile(profile: ProfileId): ScreenSettingsValues = decode(values[profile])

    /** Makes [profile]'s values the published ones, with the other stores in the same switch. */
    @Synchronized
    fun activate(profile: ProfileId) {
        activeProfile = profile
        activeState.value = decode(values[profile])
    }

    /** Writes one row's [value] for the active profile and returns when the file holds it. */
    fun set(
        key: String,
        value: String,
    ): Outcome<Unit> = set(activeProfile, key, value)

    /** Writes one row's [value] for [profile] and leaves every other profile untouched. */
    @Synchronized
    fun set(
        profile: ProfileId,
        key: String,
        value: String,
    ): Outcome<Unit> {
        if (key !in KNOWN_KEYS) {
            return Outcome.Failure(nl.r1a.grassmaps.plugin.api.error.AppError.InvalidInput)
        }
        if (!isLoaded) {
            val read = load()
            if (read is Outcome.Failure) return read
        }
        val stored = decode(values[profile].orEmpty() + (key to value)).asStoredValues()
        val next = values.withKnownValues(profile, KNOWN_KEYS, stored)
        val target = store ?: return Outcome.Success(Unit).also { adopt(next, profile, stored) }
        return target.write(next).also { written ->
            if (written is Outcome.Success) {
                adopt(next, profile, stored)
            }
        }
    }

    override fun read(profile: ProfileId): Map<String, String> = decode(values[profile]).asStoredValues()

    override fun replace(
        profile: ProfileId,
        values: Map<String, String>,
    ): Outcome<Unit> {
        val stored = KNOWN_KEYS.associateWith { key -> values[key].orEmpty() }.filterValues { it.isNotEmpty() }
        if (!isLoaded) {
            val read = load()
            if (read is Outcome.Failure) return read
        }
        val next = this.values.withKnownValues(profile, KNOWN_KEYS, stored)
        val target = store ?: return Outcome.Success(Unit).also { adopt(next, profile, stored) }
        return target.write(next).also { written ->
            if (written is Outcome.Success) {
                adopt(next, profile, stored)
            }
        }
    }

    private fun adopt(
        next: ProfileValues,
        profile: ProfileId,
        stored: Map<String, String>,
    ) {
        values = next
        if (profile == activeProfile) {
            activeState.value = decode(stored)
        }
    }

    companion object {
        const val ID = "screens"
        const val SCHEMA_VERSION = 1
        const val ERROR_ID = "core.services.screen_settings"

        const val APP_THEME_KEY = "app_theme"
        const val NIGHT_MAP_KEY = "night_map"
        const val KEEP_SCREEN_ON_KEY = "keep_screen_on"
        const val ANIMATIONS_KEY = "animations"
        const val LANGUAGE_KEY = "language"

        private val KNOWN_KEYS =
            setOf(APP_THEME_KEY, NIGHT_MAP_KEY, KEEP_SCREEN_ON_KEY, ANIMATIONS_KEY, LANGUAGE_KEY)

        /** Opens the per-profile store; no legacy app-wide file exists for these rows. */
        fun open(file: java.io.File): ScreenSettings =
            ScreenSettings(
                ProfileValueFile(file = file, schemaVersion = SCHEMA_VERSION, errorId = ERROR_ID),
            )

        private fun decode(values: Map<String, String>?): ScreenSettingsValues {
            if (values == null) return ScreenSettingsValues()
            return ScreenSettingsValues(
                appTheme = AppThemeChoice.of(values[APP_THEME_KEY]),
                nightMap = NightMapMode.of(values[NIGHT_MAP_KEY]),
                keepScreenOn = values[KEEP_SCREEN_ON_KEY].toBoolean(),
                animationsEnabled = values[ANIMATIONS_KEY]?.toBoolean() != false,
                language = UiLanguage.of(values[LANGUAGE_KEY]),
            )
        }
    }
}
