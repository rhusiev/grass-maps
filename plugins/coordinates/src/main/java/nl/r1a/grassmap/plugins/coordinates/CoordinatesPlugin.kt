package nl.r1a.grassmap.plugins.coordinates

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The coordinate-reading plugin (T-0206).
 *
 * It owns the one parser for a coordinate typed into search. The host asks
 * [Coordinates.parser] before its index, so a typed coordinate becomes one
 * result row even when no region is installed. The plugin contributes no
 * command: reading text is not an action the user or the control channel
 * invokes, it is part of the search keystroke. It depends only on
 * `:plugin-api`, so the parser is pure Kotlin with no Android type.
 */
class CoordinatesPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> = Outcome.Success(Unit)
}
