package nl.r1a.grassmap.map

/**
 * The language a map label shows (T-0110). [Default] keeps each style's own
 * naming rule, so the committed goldens stay valid. [English] and [Ukrainian]
 * show one name per label, from the tile's `name:en` or `name:uk` tag, and fall
 * back to the local name when the tag is missing.
 */
enum class LabelLanguage(
    val wireName: String,
    val nameKey: String?,
) {
    Default("default", null),
    English("en", "name:en"),
    Ukrainian("uk", "name:uk"),
    ;

    companion object {
        fun fromWireName(name: String?): LabelLanguage? = entries.firstOrNull { it.wireName == name }
    }
}

/** The label choices the user makes: the language and the text size in percent. */
data class LabelOptions(
    val language: LabelLanguage = LabelLanguage.Default,
    val sizePercent: Int = DEFAULT_SIZE_PERCENT,
) {
    /** The multiplier every label text size takes. */
    val sizeFactor: Double get() = sizePercent.toDouble() / DEFAULT_SIZE_PERCENT

    /** True when the options leave every layer as authored. */
    val isDefault: Boolean get() = language == LabelLanguage.Default && sizePercent == DEFAULT_SIZE_PERCENT

    companion object {
        const val DEFAULT_SIZE_PERCENT: Int = 100
        const val MIN_SIZE_PERCENT: Int = 50
        const val MAX_SIZE_PERCENT: Int = 200
    }
}
