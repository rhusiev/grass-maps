package nl.r1a.grassmap.plugins.point.info

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.r1a.grassmap.plugin.api.model.GeoPoint

/**
 * The point a long press selected, or null when nothing is selected.
 *
 * The store outlives a composition and an Activity: it is process-scoped, so a
 * recomposition or a map surface rebuild keeps the selection. The map gesture
 * writes it and the plugin reads it; a clear emits the empty state so the label
 * and the stream go away together.
 */
class SelectedPointStore(
    initial: GeoPoint? = null,
) {
    private val selected = MutableStateFlow(initial)

    /** The current selection. */
    val point: StateFlow<GeoPoint?> = selected.asStateFlow()

    /** Selects [point], replacing any previous selection. */
    fun select(point: GeoPoint) {
        selected.value = point
    }

    /** Clears the selection. */
    fun clear() {
        selected.value = null
    }
}

/**
 * The process-wide selected point. `app` writes it from the map gesture and the
 * plugin reads it, the same way `map.controls` owns the camera mode it publishes.
 */
object PointInfo {
    val selection: SelectedPointStore = SelectedPointStore()
}
