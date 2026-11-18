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
