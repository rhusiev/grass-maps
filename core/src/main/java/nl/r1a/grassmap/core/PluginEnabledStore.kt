package nl.r1a.grassmap.core

import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * Persists which plugins the user turned off (T-0607). Both calls do file I/O,
 * so the host calls them off Main.
 */
interface PluginEnabledStore {
    /**
     * The plugins the user turned off. A missing file is an empty set. A file
     * the store cannot decode, or one a newer build wrote, is a typed failure
     * instead: the host then leaves every plugin on and refuses switches, so a
     * file it cannot read is never overwritten with a guess.
     */
    fun loadDisabled(): Outcome<Set<PluginId>>

    /** Stores [disabled], or answers the typed reason the store refused. */
    fun saveDisabled(disabled: Set<PluginId>): Outcome<Unit>
}

/** The store before the app wires a file: nothing is off and nothing persists. */
object NoPluginEnabledStore : PluginEnabledStore {
    override fun loadDisabled(): Outcome<Set<PluginId>> = Outcome.Success(emptySet())

    override fun saveDisabled(disabled: Set<PluginId>): Outcome<Unit> = Outcome.Success(Unit)
}
