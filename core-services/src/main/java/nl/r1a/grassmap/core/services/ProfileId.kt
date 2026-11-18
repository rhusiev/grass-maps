package nl.r1a.grassmap.core.services

/** The profile id type moved to `plugin-api` so a feature module can key by it; this name keeps old imports valid. */
typealias ProfileId = nl.r1a.grassmap.plugin.api.model.ProfileId

/** The profile the app used before profiles existed; the stores that predate T-0601 still read it. */
val DEFAULT_PROFILE: ProfileId = ProfileId("default")
