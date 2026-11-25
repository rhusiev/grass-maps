package nl.r1a.grassmaps.core.services

import kotlinx.serialization.json.JsonObject
import nl.r1a.grassmaps.plugin.api.contract.CommandId

/**
 * The command and parameters the quick-access button runs (T-0606). The
 * parameters are stored with the command so a future parameterized action can
 * keep its arguments across a restart.
 */
data class QuickAccessButton(
    val command: CommandId,
    val parameters: JsonObject = JsonObject(emptyMap()),
)

/**
 * The map control layout of one profile (T-0606).
 *
 * [slots] has exactly [SLOT_COUNT] entries in bar order; null is a free slot. A
 * command appears at most once. Placing or removing a command changes only its
 * own slot, so every other button keeps its index and its place on screen.
 *
 * [arc] arranges the placed commands along the one-thumb arc instead of the
 * top-end column. It is on by default: the arc is the bottom-anchored, thumb
 * arrangement required by the bottom-reach rule (T-0612). A stored profile that
 * explicitly turns it off keeps the old top-end column. The arc reads
 * [commands] from top to bottom, so the last placed command sits nearest the bar.
 */
data class MapButtonLayout(
    val slots: List<CommandId?>,
    val quickAccess: QuickAccessButton? = null,
    val arc: Boolean = true,
) {
    /** The placed commands in bar order. */
    val commands: List<CommandId> get() = slots.filterNotNull()

    /**
     * Puts [command] into the first free slot. A command that is already placed
     * or a layout with no free slot changes nothing.
     */
    fun place(command: CommandId): MapButtonLayout {
        if (command in commands) {
            return this
        }
        val free = slots.indexOfFirst { it == null }
        if (free < 0) {
            return this
        }
        return copy(slots = slots.toMutableList().apply { set(free, command) })
    }

    /** Frees the slot of [command]. An unplaced command changes nothing. */
    fun remove(command: CommandId): MapButtonLayout =
        if (command !in commands) this else copy(slots = slots.map { placed -> placed.takeUnless { it == command } })

    companion object {
        /** The number of bar slots: the five default controls and one free slot. */
        const val SLOT_COUNT: Int = 6

        /**
         * The layout a profile uses until it saves its own. The rail is
         * bottom-anchored through the one-thumb arc, so it stays inside the
         * bottom-reach rule (T-0612, final design-aware review P1 6). The arc's
         * faces fan up and left from the slider instead of forming a wide
         * column: a bottom column with the full command set reaches the centred
         * Layers button, while each arc element clears the bar, its chevron,
         * Layers, the credit and the scale bar. The tested proof is in
         * MapRailDefaultTest; every command stays in [MapControlCommands.CONFIGURABLE]
         * and reachable from the layout sheet.
         */
        val DEFAULT: MapButtonLayout =
            MapButtonLayout(MapControlCommands.DEFAULT_ORDER + null, arc = true)
    }
}
