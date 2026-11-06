package nl.r1a.grassmap.core.services

/**
 * Identifies one settings profile. Profiles themselves arrive with T-0601; until
 * then the app uses [DEFAULT_PROFILE].
 */
@JvmInline
value class ProfileId(
    val value: String,
) {
    override fun toString(): String = value
}

val DEFAULT_PROFILE: ProfileId = ProfileId("default")
