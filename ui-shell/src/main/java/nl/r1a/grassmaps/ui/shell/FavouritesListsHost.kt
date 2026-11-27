package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import nl.r1a.grassmaps.plugin.api.model.FavouriteListsState
import nl.r1a.grassmaps.plugin.api.model.FavouriteSort
import nl.r1a.grassmaps.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors

/**
 * The stored coordinates of the selected place, or null when it has none. The
 * browse rows carry the position through from the persisted point (T-0304);
 * the full library is the fallback for a point a query has filtered out.
 */
internal fun selectedPlacePosition(
    state: FavouriteListsState,
    selectedPoint: String?,
): GeoPoint? {
    if (selectedPoint == null) return null
    return state.browse.rows
        .firstOrNull { it.id == selectedPoint }
        ?.position
        ?: state.points.firstOrNull { it.id == selectedPoint }?.position
}

/**
 * The Saved screen (T-0213, round 8 `x-saved`, round 9 `k-saved`). The plugin
 * owns the state and the actions; this shell draws rows and sends taps back. It
 * never reads a file or a GPX.
 *
 * Read at the top, press at the bottom: the header, the sort line and the
 * groups are read; search, Show on map, sort and + sit in the bottom bar. The
 * controls attached to one row stay on it: the eye hides a group, the colour
 * disc recolours a list, and a tapped place shows its own actions - Show on
 * map, Home, Work and a move to each other list. Creating a list is the + panel
 * (round 8 `x-saved-add`).
 *
 * T-0214's search and four-way sort ask the plugin for a new derived view; this
 * shell draws the rows it is given, so matching and ordering stay in one place.
 *
 * [hiddenGroups] is read from the persisted layer settings on every open, and
 * [onToggleGroupShown] writes an eye tap back through the same store. Neither
 * this host nor the Saved section keeps a second visibility state.
 *
 * The screen fills the window with a back chevron and opens the panels over
 * a scrim.
 *
 * [onShowOnMap] receives the selected place's coordinates. It has no default:
 * the app supplies the camera action, so an unwired screen cannot compile.
 * [onOpenTracks] opens the track library from the Tracks segment; without it
 * the segment shows where saved tracks are.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun FavouritesListsHost(
    state: FavouriteListsState,
    strings: Strings,
    hiddenGroups: Set<String>,
    onCreateList: (name: String, color: String) -> Unit,
    onMovePoint: (pointId: String, list: String) -> Unit,
    onRecolourList: (list: String, color: String) -> Unit,
    onSetSpecial: (pointId: String, special: FavouriteSpecial) -> Unit,
    onUndo: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (FavouriteSort) -> Unit,
    onClose: () -> Unit,
    onToggleGroupShown: (String, Boolean) -> Unit = { _, _ -> },
    onShowOnMap: (GeoPoint) -> Unit,
    onOpenTracks: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val actions =
        SavedHostActions(
            onCreateList = onCreateList,
            onMovePoint = onMovePoint,
            onRecolourList = onRecolourList,
            onSetSpecial = onSetSpecial,
            onUndo = onUndo,
            onQueryChange = onQueryChange,
            onSortChange = onSortChange,
            onClose = onClose,
            onToggleGroupShown = onToggleGroupShown,
            onShowOnMap = onShowOnMap,
            onOpenTracks = onOpenTracks,
        )
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(listScreenBackground(LocalShellColors.current))
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        contentAlignment = Alignment.TopCenter,
    ) {
        FavouritesListsBody(
            state = state,
            strings = strings,
            hiddenGroups = hiddenGroups,
            actions = actions,
            modifier = Modifier.widthIn(max = SCREEN_MAX_WIDTH_DP.dp).fillMaxSize(),
        )
    }
}

/** The commands the Saved screen sends back to the host. */
internal class SavedHostActions(
    val onCreateList: (name: String, color: String) -> Unit,
    val onMovePoint: (pointId: String, list: String) -> Unit,
    val onRecolourList: (list: String, color: String) -> Unit,
    val onSetSpecial: (pointId: String, special: FavouriteSpecial) -> Unit,
    val onUndo: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onSortChange: (FavouriteSort) -> Unit,
    val onClose: () -> Unit,
    val onToggleGroupShown: (String, Boolean) -> Unit,
    val onShowOnMap: (GeoPoint) -> Unit,
    val onOpenTracks: (() -> Unit)?,
)

/** The panel the bottom bar opened, if any. */
private enum class SavedPanel {
    NONE,
    NEW_GROUP,
    SORT,
}

/**
 * The Saved screen without its window chrome. The reading area scrolls between
 * the header and the fixed bottom controls.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun FavouritesListsBody(
    state: FavouriteListsState,
    strings: Strings,
    hiddenGroups: Set<String>,
    actions: SavedHostActions,
    modifier: Modifier = Modifier,
) {
    val lists = favouritesListsContent(state, strings)
    val browse = favouritesBrowseContent(state, strings)
    val text = savedSheetText(strings)
    var selectedPoint by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(PRESET_COLOURS.first()) }
    var query by remember { mutableStateOf(state.browse.query) }
    var segment by remember { mutableStateOf(SavedSegment.PLACES) }
    var panel by remember { mutableStateOf(SavedPanel.NONE) }
    val commands = SAVED_COMMANDS
    val savedContent = savedScreenContent(state, strings, commands, hiddenGroups, segment)
    val selectedPosition = selectedPlacePosition(state, selectedPoint)
    val showOnMap = { point: GeoPoint ->
        actions.onShowOnMap(point)
        // The screen hides the map, so the camera move is only seen once it closes.
        actions.onClose()
    }
    val rowActions =
        SavedRowActions(
            selectedPlace = selectedPoint,
            onSelectPlace = { place -> selectedPoint = if (selectedPoint == place.id) null else place.id },
            onRecolour = actions.onRecolourList,
            placeActions = { place ->
                PlaceActions(
                    text = text,
                    place = place,
                    moveTargets = savedMoveTargets(state, place.id, strings),
                    onShowOnMap = { place.position?.let(showOnMap) },
                    onSetHome = { actions.onSetSpecial(place.id, FavouriteSpecial.HOME) },
                    onSetWork = { actions.onSetSpecial(place.id, FavouriteSpecial.WORK) },
                    onMove = { list ->
                        selectedPoint = null
                        actions.onMovePoint(place.id, list)
                    },
                )
            },
        )
    val section: @Composable (Modifier) -> Unit = { sectionModifier ->
        SavedScreenSection(
            state = state,
            strings = strings,
            hiddenGroups = hiddenGroups,
            segment = segment,
            onToggleGroupShown = actions.onToggleGroupShown,
            rowActions = rowActions,
            onBack = actions.onClose,
            backLabel = lists.close,
            modifier = sectionModifier,
        )
    }
    val panelContent: @Composable (Modifier) -> Unit = { panelModifier ->
        when (panel) {
            SavedPanel.NEW_GROUP -> {
                NewGroupPanel(
                    text = text,
                    name = newName,
                    colour = newColor,
                    onNameChange = { newName = it },
                    onColourChange = { newColor = it },
                    onCreate = {
                        val name = newName.trim()
                        if (name.isNotEmpty()) {
                            actions.onCreateList(name, newColor)
                            newName = ""
                            panel = SavedPanel.NONE
                        }
                    },
                    onCancel = { panel = SavedPanel.NONE },
                    modifier = panelModifier,
                )
            }

            SavedPanel.SORT -> {
                SortPanel(
                    title = text.sortTitle,
                    cancel = text.cancel,
                    sorts = browse.sorts,
                    onChoose = { sort ->
                        panel = SavedPanel.NONE
                        actions.onSortChange(sort)
                    },
                    onCancel = { panel = SavedPanel.NONE },
                    modifier = panelModifier,
                )
            }

            SavedPanel.NONE -> {
                Unit
            }
        }
    }
    val bottom: @Composable (Modifier) -> Unit = { bottomModifier ->
        SavedBottomControls(
            content = savedContent,
            query = query,
            undo = lists.undo.takeIf { lists.undoAvailable },
            placeSelected = selectedPosition != null,
            onUndo = actions.onUndo,
            onChooseSegment = { chosen ->
                val openTracks = actions.onOpenTracks
                if (chosen == SavedSegment.TRACKS && openTracks != null) openTracks() else segment = chosen
            },
            onQueryChange = {
                query = it
                actions.onQueryChange(it)
            },
            onSort = { panel = SavedPanel.SORT },
            onAdd = { panel = SavedPanel.NEW_GROUP },
            onShowOnMap = { selectedPosition?.let(showOnMap) },
            modifier = bottomModifier,
        )
    }

    Box(modifier = modifier.statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            section(
                Modifier
                    .weight(WEIGHT_ONE)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SCREEN_PADDING_DP.dp),
            )
            bottom(Modifier.padding(horizontal = BAR_MARGIN_DP.dp, vertical = PANEL_GAP_DP.dp))
        }
        if (panel != SavedPanel.NONE) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(LocalShellColors.current.scrim)
                        .pointerInput(Unit) { detectTapGestures(onTap = { panel = SavedPanel.NONE }) },
                contentAlignment = Alignment.BottomCenter,
            ) {
                // The panel scrolls when the keyboard or a large font leaves it less height than it needs.
                panelContent(
                    Modifier
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private const val SCREEN_MAX_WIDTH_DP = 560
private const val SCREEN_PADDING_DP = 16
private const val BAR_MARGIN_DP = 12
private const val PANEL_GAP_DP = 8
private const val WEIGHT_ONE = 1f

/** Colours the + panel and the colour row offer; a custom hex picker is backlog B-053. */
internal val PRESET_COLOURS: List<String> =
    listOf("#E53935", "#1E88E5", "#43A047", "#FB8C00", "#8E24AA", "#546E7A")
