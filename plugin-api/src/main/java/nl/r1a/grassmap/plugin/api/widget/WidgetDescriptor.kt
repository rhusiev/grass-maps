package nl.r1a.grassmap.plugin.api.widget

import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.contract.WidgetId

/**
 * The declarative half of a widget (plugin-spec.md section 5): a title, a value
 * type and the stream the value comes from. The shell owns layout and drawing;
 * a widget never draws itself.
 *
 * [title] is an i18n key, like a plugin manifest name. [valueType] is the
 * serialized name of the bound stream's value, so a host can reject a widget
 * whose stream declares a different type. [updateIntervalMillis] is the
 * smallest period between two value samples; the widget contract caps how
 * often a widget may update, it does not force an update when nothing changed.
 */
data class WidgetDescriptor(
    val id: WidgetId,
    val title: String,
    val valueType: String,
    val streamId: StreamId,
    val updateIntervalMillis: Long,
)

/**
 * The cadence bounds every widget shares. A widget never updates faster than
 * [MIN_INTERVAL_MILLIS] (10 Hz), which matches the 5-10 Hz sampling bound in
 * architecture.md section 7; the default is [DEFAULT_INTERVAL_MILLIS] (5 Hz).
 */
object WidgetCadence {
    const val MIN_INTERVAL_MILLIS: Long = 100L
    const val DEFAULT_INTERVAL_MILLIS: Long = 200L
}
