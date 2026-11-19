package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.flatMap
import nl.r1a.grassmap.plugin.api.host.ProfileSettingsAccess
import nl.r1a.grassmap.plugin.api.model.ProfileAction
import nl.r1a.grassmap.plugin.api.model.ProfileActionKind
import nl.r1a.grassmap.plugin.api.model.ProfileId
import nl.r1a.grassmap.plugin.api.model.ProfileSummary
import nl.r1a.grassmap.plugin.api.model.ProfilesState
import nl.r1a.grassmap.plugin.api.service.ProfileSettingsGroup

/**
 * Creates, copies and resets profiles (T-0602) over the [ProfileCatalog] and
 * every per-profile settings group.
 *
 * Copying a profile's settings means [ProfileSettingsGroup.replace] on the
 * target with what [ProfileSettingsGroup.read] gives for the source, so a group
 * that is at its defaults copies as the defaults. A reset goes back to the
 * profile a user profile was copied from, as that profile is now, and to the
 * defaults for a built-in profile or when the source is gone. Every action
 * keeps what it replaced and what it wrote. [undo] puts the replaced values
 * back only while the profile still holds what the action wrote, and refuses
 * with [AppError.Conflict] after another change, which also drops that entry.
 * The last [UNDO_LIMIT] actions can be undone, newest first. The history lives
 * in memory only.
 *
 * A group writes through [ProfileSettingsGroup.replace] and reports the real
 * outcome, so an action succeeds only when every write reached the disk. When a
 * write fails, the groups already written are put back. When that fails too, the
 * result is [AppError.Internal] with detail [ROLLBACK_FAILED], and the history
 * is cleared because it no longer matches the stored values. [ensureReady] runs
 * before every action that reads or writes a group, and its failure refuses the
 * action.
 */
class ProfileSettingsService(
    private val catalog: ProfileCatalog,
    private val groups: List<ProfileSettingsGroup>,
    private val ensureReady: () -> Outcome<Unit> = { Outcome.Success(Unit) },
) : ProfileSettingsAccess {
    private val history = ArrayDeque<Undoable>()

    @Synchronized
    override fun state(): Outcome<ProfilesState> = Outcome.Success(snapshotState())

    @Synchronized
    override fun create(
        name: String,
        from: String,
    ): Outcome<ProfilesState> {
        val source = ProfileId(from)
        val ready = ensureReady()
        if (ready is Outcome.Failure) return ready
        val created =
            when (val result = catalog.create(name, source)) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value
            }
        val copied = replaceAll(created.id, groups.associateWith { it.read(source) })
        if (copied is Outcome.Failure) return removeAfterFailure(created.id, copied)
        return record(
            Undoable(
                ProfileAction(ProfileActionKind.CREATED, created.id.value, from),
                created.id,
                before = groups.associateWith { emptyMap() },
                after = groups.associateWith { it.read(created.id) },
                isNew = true,
            ),
        )
    }

    /** Takes a profile whose settings could not be written out of the catalog again. */
    private fun removeAfterFailure(
        id: ProfileId,
        failure: Outcome.Failure,
    ): Outcome<ProfilesState> =
        if (failure.error == rollbackFailed() || catalog.remove(id) is Outcome.Success) failure else brokenRollback()

    @Synchronized
    override fun copySettings(
        from: String,
        to: String,
        groups: Set<String>,
    ): Outcome<ProfilesState> {
        val source = ProfileId(from)
        val target = ProfileId(to)
        val chosen = chooseGroups(groups) ?: return Outcome.Failure(AppError.InvalidInput)
        val ready = ensureReady()
        if (ready is Outcome.Failure) return ready
        if (source == target) return Outcome.Failure(AppError.InvalidInput)
        if (!exists(source) || !exists(target)) return Outcome.Failure(AppError.NotFound)
        return change(ProfileAction(ProfileActionKind.COPIED, to, from), target, chosen.associateWith { it.read(source) })
    }

    @Synchronized
    override fun reset(
        profile: String,
        groups: Set<String>,
    ): Outcome<ProfilesState> {
        val target = ProfileId(profile)
        val chosen = chooseGroups(groups) ?: return Outcome.Failure(AppError.InvalidInput)
        val ready = ensureReady()
        if (ready is Outcome.Failure) return ready
        val entry = catalog.load().firstOrNull { it.id == target } ?: return Outcome.Failure(AppError.NotFound)
        val baseline = entry.copiedFrom?.takeIf(::exists)
        val values = chosen.associateWith { group -> baseline?.let(group::read).orEmpty() }
        return change(ProfileAction(ProfileActionKind.RESET, profile, baseline?.value), target, values)
    }

    @Synchronized
    override fun undo(): Outcome<ProfilesState> {
        val last = history.lastOrNull() ?: return Outcome.Failure(AppError.NotFound)
        if (!holds(last)) {
            history.removeLast()
            return Outcome.Failure(AppError.Conflict)
        }
        val restored = replaceAll(last.profile, last.before)
        if (restored is Outcome.Failure) return restored
        if (last.isNew) {
            val removed = catalog.remove(last.profile)
            if (removed is Outcome.Failure) return restoreAfterFailure(last, removed)
        }
        history.removeLast()
        return Outcome.Success(snapshotState())
    }

    /** True while the profile still holds what [entry] wrote. */
    private fun holds(entry: Undoable): Boolean = entry.after.all { (group, values) -> group.read(entry.profile) == values }

    /** The catalog kept a profile whose values were restored, so write the values the action made again. */
    private fun restoreAfterFailure(
        entry: Undoable,
        failure: Outcome.Failure,
    ): Outcome<ProfilesState> = if (replaceAll(entry.profile, entry.after) is Outcome.Success) failure else brokenRollback()

    private fun change(
        action: ProfileAction,
        profile: ProfileId,
        values: Map<ProfileSettingsGroup, Map<String, String>>,
    ): Outcome<ProfilesState> {
        val before = values.mapValues { (group, _) -> group.read(profile) }
        return replaceAll(profile, values).flatMap {
            val after = values.keys.associateWith { it.read(profile) }
            record(Undoable(action, profile, before, after, isNew = false))
        }
    }

    private fun record(entry: Undoable): Outcome<ProfilesState> {
        history.addLast(entry)
        while (history.size > UNDO_LIMIT) history.removeFirst()
        return Outcome.Success(snapshotState())
    }

    /** Writes every group, and puts the groups already written back when one fails. */
    private fun replaceAll(
        profile: ProfileId,
        values: Map<ProfileSettingsGroup, Map<String, String>>,
    ): Outcome<Unit> {
        val written = mutableListOf<Pair<ProfileSettingsGroup, Map<String, String>>>()
        for ((group, next) in values) {
            val previous = group.read(profile)
            val result = group.replace(profile, next)
            if (result is Outcome.Failure) {
                val restored = written.map { (done, old) -> done.replace(profile, old) }
                return if (restored.any { it is Outcome.Failure }) brokenRollback() else result
            }
            written += group to previous
        }
        return Outcome.Success(Unit)
    }

    private fun brokenRollback(): Outcome.Failure {
        history.clear()
        return Outcome.Failure(rollbackFailed())
    }

    private fun rollbackFailed(): AppError = AppError.Internal(ERROR_ID, ROLLBACK_FAILED)

    private fun chooseGroups(ids: Set<String>): List<ProfileSettingsGroup>? =
        if (ids.isEmpty()) groups else groups.filter { it.id in ids }.takeIf { it.size == ids.size }

    private fun exists(id: ProfileId): Boolean = catalog.load().any { it.id == id }

    private fun snapshotState(): ProfilesState =
        ProfilesState(
            profiles =
                catalog.load().map { entry ->
                    ProfileSummary(entry.id.value, entry.name, entry.isBuiltIn, entry.copiedFrom?.value)
                },
            groups = groups.map { it.id },
            undo = history.lastOrNull()?.action,
        )

    private class Undoable(
        val action: ProfileAction,
        val profile: ProfileId,
        val before: Map<ProfileSettingsGroup, Map<String, String>>,
        val after: Map<ProfileSettingsGroup, Map<String, String>>,
        val isNew: Boolean,
    )

    companion object {
        /** How many actions [undo] can still revert. */
        const val UNDO_LIMIT = 20

        /** The [AppError.Internal] detail when a failed action could not be put back. */
        const val ROLLBACK_FAILED = "rollback_failed"
        const val ERROR_ID = "core.services.profile_settings"
    }
}
