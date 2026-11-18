package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ProfileId
import nl.r1a.grassmap.plugin.api.service.ProfileValueStore
import nl.r1a.grassmap.plugin.api.service.ProfileValues

/**
 * The widget layout of every profile (T-0605), on the per-profile key contract
 * of architecture section 20.
 *
 * Each profile holds one key, [LAYOUT_KEY], with the storage keys of its
 * widgets joined by commas in reading order. A profile with no value shows
 * [WidgetLayout.DEFAULT]. An empty string is a real value: the user removed
 * every widget. A key this build does not know is dropped on read, and a
 * duplicate is kept once.
 *
 * The store declares [SCHEMA_VERSION] and the persistence behind it enforces
 * it, so a layout file written by a newer build is never overwritten. A failed
 * [load] leaves every profile at the default.
 *
 * Construction does not read the store. [load] does, so the app runs it off the
 * Main thread. [save] loads first when nobody did, so it never replaces the
 * layouts of the other profiles with a partial view.
 */
class WidgetLayoutStore(
    private val store: ProfileValueStore?,
) {
    private var values: ProfileValues = emptyMap()
    private var isLoaded = false

    /** Reads the store. A failure leaves every profile at the default. */
    @Synchronized
    fun load(): Outcome<Unit> {
        val read = store?.read() ?: Outcome.Success(emptyMap())
        isLoaded = true
        return when (read) {
            is Outcome.Success -> {
                values = read.value
                Outcome.Success(Unit)
            }

            is Outcome.Failure -> {
                values = emptyMap()
                read
            }
        }
    }

    /** The layout of [profile], or the default when none was stored. */
    @Synchronized
    fun layoutOf(profile: ProfileId): WidgetLayout = values[profile]?.get(LAYOUT_KEY)?.let(::decode) ?: WidgetLayout.DEFAULT

    /** Stores [layout] for [profile] and leaves the other profiles untouched. */
    @Synchronized
    fun save(
        profile: ProfileId,
        layout: WidgetLayout,
    ): Outcome<Unit> {
        if (!isLoaded) {
            load()
        }
        val next = values + (profile to (values[profile].orEmpty() + (LAYOUT_KEY to encode(layout))))
        val target = store ?: return Outcome.Success(Unit).also { values = next }
        return target.write(next).also { if (it is Outcome.Success) values = next }
    }

    private fun encode(layout: WidgetLayout): String = layout.widgets.joinToString(SEPARATOR) { it.storageKey }

    private fun decode(stored: String): WidgetLayout =
        WidgetLayout(stored.split(SEPARATOR).mapNotNull(GridWidget::fromStorageKey).distinct())

    companion object {
        /** The schema version of the layout file. */
        const val SCHEMA_VERSION: Int = 1

        /** The id a failure of this store carries. */
        const val ERROR_ID: String = "ui.shell.widget_layout"

        private const val LAYOUT_KEY = "widgets"
        private const val SEPARATOR = ","
    }
}
