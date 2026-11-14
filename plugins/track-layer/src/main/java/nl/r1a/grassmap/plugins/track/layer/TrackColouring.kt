package nl.r1a.grassmap.plugins.track.layer

/**
 * What the track layer can colour a track by (T-0408).
 *
 * [Own] is the track's own sidecar colour, which is all the layer can draw. A
 * track carries no surface and no smoothness attribute (the gap T-0406 recorded)
 * and [nl.r1a.grassmap.plugin.api.model.MapTrack] carries geometry without
 * heights, so surface, slope, altitude and speed colouring cannot be decided
 * here. The layer says so at draw time instead of guessing a colour; a caller
 * that needs a gradient needs the track's analysis carried through the layer
 * first.
 */
enum class TrackColouring {
    Own,

    /** Refused: the layer has neither surface data nor per-point heights. */
    Gradient,
}
