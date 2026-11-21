package nl.r1a.grassmaps.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.error.onFailure
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.ui.shell.WidgetLayout
import nl.r1a.grassmaps.ui.shell.WidgetLayoutStore

internal const val WIDGET_TAG = "WidgetGrid"

/** Whether the stored layout of a profile was read. */
internal enum class WidgetLoadPhase {
    /** The read has not finished. The layout is the default and cannot be edited. */
    Loading,

    /** The read succeeded. The layout is the stored one and edits are saved. */
    Ready,

    /** The read failed. The layout is the default, cannot be edited and nothing is written. */
    Failed,
}

/** The layout the grid shows and whether it may be edited. */
internal data class WidgetLayoutState(
    val layout: WidgetLayout = WidgetLayout.DEFAULT,
    val phase: WidgetLoadPhase = WidgetLoadPhase.Loading,
) {
    val isEditable: Boolean get() = phase == WidgetLoadPhase.Ready
}

/**
 * The widget layout of one profile (T-0605). It owns the edit rules the grid
 * relies on, so they hold without a screen and a test can exercise them:
 *
 * - An edit before the stored layout was read is ignored, so it can neither
 *   overwrite the stored layout nor be overwritten by the read.
 * - Saves run in [scope], which belongs to the process. The grid leaves the
 *   composition when navigation starts, and a save already requested still
 *   finishes.
 * - Every save writes the layout that is current when the write starts.
 *
 * The constructor reads nothing. [load] reads the store on [ioDispatcher].
 */
internal class WidgetLayoutController(
    private val store: WidgetLayoutStore,
    private val profile: ProfileId,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val onFailure: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(WidgetLayoutState())
    private val loadLock = Mutex()
    private val writer =
        LayoutWriter(
            currentLayout = { mutableState.value.layout },
            save = { layout -> withContext(ioDispatcher) { store.save(profile, layout) } },
        )

    /** The layout and the load phase. */
    val state: StateFlow<WidgetLayoutState> = mutableState.asStateFlow()

    /** Reads the stored layout unless a read already succeeded. A failed read can be retried. */
    suspend fun load() {
        loadLock.withLock {
            if (mutableState.value.phase == WidgetLoadPhase.Ready) {
                return
            }
            val read = withContext(ioDispatcher) { store.load().let { it to store.layoutOf(profile) } }
            read.first.onFailure { onFailure("widget layout not read: $it") }
            mutableState.value =
                when (read.first) {
                    is Outcome.Success -> WidgetLayoutState(read.second, WidgetLoadPhase.Ready)
                    is Outcome.Failure -> WidgetLayoutState(WidgetLayout.DEFAULT, WidgetLoadPhase.Failed)
                }
        }
    }

    /** Applies [change] to the layout and saves the result. It does nothing until the load succeeded. */
    fun edit(change: (WidgetLayout) -> WidgetLayout) {
        val before = mutableState.value
        if (!before.isEditable) {
            return
        }
        val next = change(before.layout)
        if (next == before.layout) {
            return
        }
        mutableState.value = before.copy(layout = next)
        scope.launch { writer.saveLatest().onFailure { onFailure("widget layout not saved: $it") } }
    }
}
