package nl.r1a.grassmap.ui.shell

/**
 * The on-map info widgets a profile can show (design handoff, round 8). The
 * order of the entries is the order the picker lists the widgets that are not
 * shown. [storageKey] is the stable string the profile store keeps, so a rename
 * of the enum entry never changes a stored layout.
 */
enum class GridWidget(
    val storageKey: String,
) {
    Altitude("altitude"),
    GpsAccuracy("gps_accuracy"),
    Coordinates("coordinates"),
    SpeedCurrent("speed_current"),
    SpeedAverage("speed_average"),
    SpeedMax("speed_max"),
    SelectedPoint("selected_point"),
    ;

    companion object {
        /** The widget stored under [key], or null for a key this build does not know. */
        fun fromStorageKey(key: String): GridWidget? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * The widgets one profile shows, in reading order from the left. A widget
 * appears at most once. Every operation returns a new layout and leaves the
 * receiver unchanged, so an edit is a value and a test can compare layouts.
 */
data class WidgetLayout(
    val widgets: List<GridWidget>,
) {
    /** The widgets the picker offers: every widget that is not shown. */
    val hidden: List<GridWidget>
        get() = GridWidget.entries.filter { it !in widgets }

    /** Appends [widget] at the end. A widget that is already shown stays where it is. */
    fun add(widget: GridWidget): WidgetLayout = if (widget in widgets) this else WidgetLayout(widgets + widget)

    /** Removes [widget]. A widget that is not shown changes nothing. */
    fun remove(widget: GridWidget): WidgetLayout = WidgetLayout(widgets - widget)

    /**
     * Moves [widget] by [delta] places, negative towards the start. A move that
     * would leave the row, or a widget that is not shown, changes nothing.
     */
    fun move(
        widget: GridWidget,
        delta: Int,
    ): WidgetLayout {
        val from = widgets.indexOf(widget)
        val to = from + delta
        if (from < 0 || to !in widgets.indices) {
            return this
        }
        val moved = widgets.toMutableList()
        moved.add(to, moved.removeAt(from))
        return WidgetLayout(moved)
    }

    companion object {
        /**
         * What a profile shows until the user edits it: altitude and GPS
         * accuracy from the round-8 layout, and the selected point, so the label
         * a long press already showed keeps its place.
         */
        val DEFAULT: WidgetLayout =
            WidgetLayout(listOf(GridWidget.Altitude, GridWidget.GpsAccuracy, GridWidget.SelectedPoint))
    }
}
