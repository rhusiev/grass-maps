package nl.r1a.grassmap.plugin.api.service

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ProfileId

/** One store's values: the outer key is the profile, the inner keys and values are stable strings. */
typealias ProfileValues = Map<ProfileId, Map<String, String>>

/**
 * The persistence behind one per-profile store (architecture section 20).
 *
 * [read] gives an empty map when nothing was stored, or when the stored data
 * is unreadable, because a setting must not stop the app. It fails with
 * [nl.r1a.grassmap.plugin.api.error.AppError.NewerSchema] when the data was
 * written by a newer build, and [write] refuses in the same case, so a newer
 * file is never overwritten.
 */
interface ProfileValueStore {
    fun read(): Outcome<ProfileValues>

    fun write(values: ProfileValues): Outcome<Unit>
}

/**
 * These values with [profile] holding [values] for the keys in [knownKeys] and
 * every other key it already held, so a build writes back the keys it does not
 * know. A profile the store already listed stays listed with no value, so
 * values of it that are not strings are kept, and a store writes no entry for
 * it. A profile it did not list is not added without a value.
 */
fun ProfileValues.withKnownValues(
    profile: ProfileId,
    knownKeys: Set<String>,
    values: Map<String, String>,
): ProfileValues {
    val unknown = this[profile].orEmpty().filterKeys { it !in knownKeys }
    val merged = unknown + values
    return if (merged.isEmpty() && profile !in this) this else this + (profile to merged)
}
