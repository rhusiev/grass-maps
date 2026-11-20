package nl.r1a.grassmap.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.core.services.ButtonLayoutStore
import nl.r1a.grassmap.core.services.MapButtonLayout
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.model.ProfileId

internal const val BUTTON_LAYOUT_TAG = "ButtonLayout"

/** The map control layout of one profile and whether the stored one was read. */
internal data class ButtonLayoutState(
    val layout: MapButtonLayout = MapButtonLayout.DEFAULT,
    val isLoaded: Boolean = false,
) {
    val isEditable: Boolean get() = isLoaded
}

/**
 * The map control layout of one profile (T-0606).
 *
 * - An edit before the stored layout was read is ignored, so it can neither
 *   overwrite the stored layout nor be overwritten by the read.
 * - Saves run in [scope], which belongs to the process. The map screen leaves
 *   the composition when navigation starts, and a save already requested still
 *   finishes.
 * - Every save writes the layout that is current when the write starts.
 *
 * The constructor reads nothing. [load] reads the store on [ioDispatcher].
 */
internal class ButtonLayoutController(
    private val store: ButtonLayoutStore,
    private val profile: ProfileId,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val onFailure: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(ButtonLayoutState())
    private val loadLock = Mutex()
    private val writer =
        LayoutWriter(
            currentLayout = { mutableState.value.layout },
            save = { layout -> withContext(ioDispatcher) { store.save(profile, layout) } },
        )

    /** The layout and whether it may be edited. */
    val state: StateFlow<ButtonLayoutState> = mutableState.asStateFlow()

    /** Reads the stored layout once. */
    suspend fun load() {
        loadLock.withLock {
            if (mutableState.value.isLoaded) {
                return
            }
            val layout = withContext(ioDispatcher) { store.load(profile) }
            mutableState.value = ButtonLayoutState(layout, isLoaded = true)
        }
    }

    /** Applies [change] to the layout and saves the result. It does nothing until the load succeeded. */
    fun edit(change: (MapButtonLayout) -> MapButtonLayout) {
        val before = mutableState.value
        if (!before.isEditable) {
            return
        }
        val next = change(before.layout)
        if (next == before.layout) {
            return
        }
        mutableState.value = before.copy(layout = next)
        scope.launch { writer.saveLatest().onFailure { onFailure("button layout not saved: $it") } }
    }
}
