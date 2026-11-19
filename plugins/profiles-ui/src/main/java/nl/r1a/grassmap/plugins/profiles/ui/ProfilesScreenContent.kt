package nl.r1a.grassmap.plugins.profiles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.r1a.grassmap.plugin.api.model.ProfileAction
import nl.r1a.grassmap.plugin.api.model.ProfileActionKind
import nl.r1a.grassmap.plugin.api.model.ProfileSummary
import nl.r1a.grassmap.plugin.api.model.ProfilesState

/**
 * The profiles screen (T-0602), drawn from the published state.
 *
 * The screen keeps no facts of its own. [state] carries the profiles, the
 * settings groups and the action undo reverts. [page] says what is open and
 * the shell owns it, so system back and the header chevron both call
 * [onNavigate] with [ProfilesPage.back]. Every action goes back to the
 * plugin's commands through the callbacks.
 *
 * Not in this step: the pinned-profile bar, icons and colours, rename, delete
 * and the editor rows for settings the later tasks add.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun ProfilesScreenContent(
    state: ProfilesState,
    page: ProfilesPage,
    onNavigate: (ProfilesPage?) -> Unit,
    onCreate: (name: String, from: String) -> Unit,
    onCopySettings: (from: String, to: String, groups: Set<String>) -> Unit,
    onReset: (profile: String) -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var actions by remember { mutableIntStateOf(0) }
    val act: (() -> Unit) -> Unit = { run ->
        run()
        actions += 1
    }
    Box(modifier = modifier.fillMaxSize().background(SURFACE)) {
        Page(state, page, onNavigate, onReset = { profile -> act { onReset(profile) } })
        Notice(state.undo, state, actions, onUndo = { act(onUndo) })
        when (page) {
            ProfilesPage.NewProfile -> {
                NewProfileSheet(state, onCancel = { onNavigate(page.back()) }, onCreate = { name, from ->
                    act { onCreate(name, from) }
                    onNavigate(page.back())
                })
            }

            is ProfilesPage.CopySettings -> {
                CopySettingsSheet(state, page.profile, onCancel = { onNavigate(page.back()) }, onCopy = { from, groups ->
                    act { onCopySettings(from, page.profile, groups) }
                    onNavigate(page.back())
                })
            }

            else -> {
                Unit
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Page(
    state: ProfilesState,
    page: ProfilesPage,
    onNavigate: (ProfilesPage?) -> Unit,
    onReset: (String) -> Unit,
) {
    val editing =
        when (page) {
            is ProfilesPage.Editor -> page.profile
            is ProfilesPage.CopySettings -> page.profile
            else -> null
        }
    val profile = editing?.let { ProfilesScreen.find(state, it) }
    Column(
        modifier =
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .widthIn(max = MAX_WIDTH_DP.dp)
                .fillMaxSize()
                .padding(SCREEN_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
    ) {
        if (profile == null) {
            ProfileList(state, onNavigate)
        } else {
            ProfileEditor(state, profile, onNavigate, onReset)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ColumnScope.ProfileList(
    state: ProfilesState,
    onNavigate: (ProfilesPage?) -> Unit,
) {
    Header(stringResource(R.string.profiles_title), "", onBack = { onNavigate(ProfilesPage.List.back()) })
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        for (profile in state.profiles) {
            NavigationRow(
                title = profileName(profile),
                subtitle = profileSubtitle(profile, state.profiles),
                onClick = { onNavigate(ProfilesPage.Editor(profile.id)) },
            )
        }
    }
    ActionButton(
        text = stringResource(R.string.profiles_new),
        onClick = { onNavigate(ProfilesPage.NewProfile) },
        modifier = Modifier.fillMaxWidth(),
        filled = true,
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ColumnScope.ProfileEditor(
    state: ProfilesState,
    profile: ProfileSummary,
    onNavigate: (ProfilesPage?) -> Unit,
    onReset: (String) -> Unit,
) {
    Header(
        title = stringResource(R.string.profiles_edit_title),
        subtitle = profileName(profile) + " - " + profileSubtitle(profile, state.profiles),
        onBack = { onNavigate(ProfilesPage.Editor(profile.id).back()) },
    )
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        BasicText(text = stringResource(R.string.profiles_section_profile), style = LABEL_STYLE)
        NavigationRow(
            title = stringResource(R.string.profiles_copy_settings),
            subtitle = "",
            onClick = { onNavigate(ProfilesPage.CopySettings(profile.id)) },
        )
        NavigationRow(
            title = resetLabel(profile, state.profiles),
            subtitle = "",
            onClick = { onReset(profile.id) },
            showChevron = false,
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun resetLabel(
    profile: ProfileSummary,
    all: List<ProfileSummary>,
): String {
    val source = profile.copiedFrom?.let { id -> all.firstOrNull { it.id == id } }
    return if (source != null) {
        stringResource(R.string.profiles_reset_to_profile, profileName(source))
    } else {
        stringResource(R.string.profiles_reset_to_default)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Notice(
    undo: ProfileAction?,
    state: ProfilesState,
    actions: Int,
    onUndo: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(actions) {
        if (actions > 0) {
            visible = true
            delay(SNACKBAR_MS)
            visible = false
        }
    }
    if (!visible || undo == null) {
        return
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(SCREEN_PADDING_DP.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SNACKBAR_CORNER_DP.dp))
                    .background(SNACKBAR)
                    .padding(SCREEN_PADDING_DP.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(
                text = noticeText(undo, state),
                style = BUTTON_STYLE.copy(color = SNACKBAR_TEXT),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            BasicText(
                text = stringResource(R.string.profiles_undo),
                style = BUTTON_STYLE.copy(color = SNACKBAR_ACTION),
                modifier =
                    Modifier.clickable(role = Role.Button) {
                        visible = false
                        onUndo()
                    },
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun noticeText(
    undo: ProfileAction,
    state: ProfilesState,
): String {
    val notice = profileNotice(undo)
    val names = notice.profileIds.map { id -> ProfilesScreen.find(state, id)?.let { profileName(it) } ?: id }
    return stringResource(notice.key, *names.toTypedArray())
}

/** The snackbar message as a resource key and the profiles it names. */
internal data class ProfileNotice(
    val key: Int,
    val profileIds: List<String>,
)

/**
 * The snackbar line that the polite live region announces. Pure so the named
 * profile can be checked off-device.
 */
internal fun profileNotice(undo: ProfileAction): ProfileNotice =
    when (undo.kind) {
        ProfileActionKind.CREATED -> {
            ProfileNotice(R.string.profiles_notice_created, listOf(undo.profile))
        }

        ProfileActionKind.COPIED -> {
            ProfileNotice(R.string.profiles_notice_copied, listOfNotNull(undo.source))
        }

        ProfileActionKind.RESET -> {
            ProfileNotice(R.string.profiles_notice_reset, emptyList())
        }
    }
