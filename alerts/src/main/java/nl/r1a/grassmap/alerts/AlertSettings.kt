package nl.r1a.grassmap.alerts

import kotlinx.serialization.Serializable

/**
 * The per-alert switches. Everything is off by default: navigation announces
 * nothing until the user turns a switch on.
 *
 * One switch covers both [AlertKind.SignalLost] and [AlertKind.SignalRegained],
 * because one gap produces both halves of one announcement. The others map to
 * exactly one kind each, including the three way alerts (T-0319), whose
 * features come from the region and are on by their own switches only. The
 * three proximity alerts (T-0413) have one switch each for the same reason: a
 * traveller may want favourites announced and places left silent.
 */
@Serializable
data class AlertSettings(
    val signal: Boolean = false,
    val reroute: Boolean = false,
    val offRoute: Boolean = false,
    val arrival: Boolean = false,
    val tunnel: Boolean = false,
    val railwayCrossing: Boolean = false,
    val hazard: Boolean = false,
    val favourite: Boolean = false,
    val trackWaypoint: Boolean = false,
    val poi: Boolean = false,
) {
    /** Whether [kind] may be announced with these switches. */
    fun isEnabled(kind: AlertKind): Boolean =
        when (kind) {
            AlertKind.SignalLost, AlertKind.SignalRegained -> signal
            AlertKind.Rerouted -> reroute
            AlertKind.OffRoute -> offRoute
            AlertKind.Arrived -> arrival
            AlertKind.Tunnel -> tunnel
            AlertKind.RailwayCrossing -> railwayCrossing
            AlertKind.Hazard -> hazard
            AlertKind.Favourite -> favourite
            AlertKind.TrackWaypoint -> trackWaypoint
            AlertKind.Poi -> poi
        }

    /** A copy with the switch that owns [kind] set to [enabled]. */
    fun with(
        kind: AlertKind,
        enabled: Boolean,
    ): AlertSettings =
        when (kind) {
            AlertKind.SignalLost, AlertKind.SignalRegained -> copy(signal = enabled)
            AlertKind.Rerouted -> copy(reroute = enabled)
            AlertKind.OffRoute -> copy(offRoute = enabled)
            AlertKind.Arrived -> copy(arrival = enabled)
            AlertKind.Tunnel -> copy(tunnel = enabled)
            AlertKind.RailwayCrossing -> copy(railwayCrossing = enabled)
            AlertKind.Hazard -> copy(hazard = enabled)
            AlertKind.Favourite -> copy(favourite = enabled)
            AlertKind.TrackWaypoint -> copy(trackWaypoint = enabled)
            AlertKind.Poi -> copy(poi = enabled)
        }

    companion object {
        /** The product default: every alert silent. */
        val ALL_OFF: AlertSettings = AlertSettings()
    }
}
