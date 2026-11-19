package nl.r1a.grassmap.plugins.profiles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.r1a.grassmap.plugin.api.model.ProfilesState

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SheetFrame(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SCRIM)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = SHEET_CORNER_DP.dp, topEnd = SHEET_CORNER_DP.dp))
                    .background(SURFACE)
                    // Swallow taps on the sheet body; a disabled clickable
                    // would expose the whole sheet as a disabled button.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(SCREEN_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        ) {
            BasicText(text = title, style = TITLE_STYLE)
            content()
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SheetButtons(
    confirm: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
        ActionButton(stringResource(R.string.profiles_cancel), onCancel, Modifier.weight(1f))
        ActionButton(confirm, onConfirm, Modifier.weight(2f), filled = true, enabled = confirmEnabled)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SourceChoices(
    state: ProfilesState,
    target: String?,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    val options = if (target == null) state.profiles else ProfilesScreen.sources(state, target)
    for (profile in options) {
        RadioRow(profileName(profile), profile.id == selected) { onSelect(profile.id) }
    }
}

/** The sheet that names a new profile and the profile it starts as a copy of. */
@Suppress("ktlint:standard:function-naming")
@Composable
fun NewProfileSheet(
    state: ProfilesState,
    onCancel: () -> Unit,
    onCreate: (name: String, from: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var from by remember { mutableStateOf(ProfilesScreen.initialSource(state.profiles, null)) }
    val trimmed = name.trim()
    SheetFrame(stringResource(R.string.profiles_new), onCancel) {
        BasicText(text = stringResource(R.string.profiles_name_label), style = LABEL_STYLE)
        BasicTextField(
            value = name,
            onValueChange = { name = it.take(NAME_MAX_LENGTH) },
            singleLine = true,
            textStyle = ROW_TITLE_STYLE,
            cursorBrush = SolidColor(ACCENT),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FIELD_CORNER_DP.dp))
                    .background(FIELD)
                    .padding(ROW_PADDING_DP.dp),
        )
        BasicText(text = stringResource(R.string.profiles_start_from), style = LABEL_STYLE)
        SourceChoices(state, null, from) { from = it }
        SheetButtons(
            confirm = stringResource(R.string.profiles_create),
            onCancel = onCancel,
            onConfirm = { from?.let { onCreate(trimmed, it) } },
            confirmEnabled = trimmed.isNotEmpty() && from != null,
        )
    }
}

/** The sheet that picks a source profile and the settings groups to copy from it into [target]. */
@Suppress("ktlint:standard:function-naming")
@Composable
fun CopySettingsSheet(
    state: ProfilesState,
    target: String,
    onCancel: () -> Unit,
    onCopy: (from: String, groups: Set<String>) -> Unit,
) {
    val options = ProfilesScreen.sources(state, target)
    var from by remember { mutableStateOf(ProfilesScreen.initialSource(options, null)) }
    var chosen by remember { mutableStateOf(state.groups.toSet()) }
    SheetFrame(stringResource(R.string.profiles_copy_sheet_title), onCancel) {
        SourceChoices(state, target, from) { from = it }
        BasicText(text = stringResource(R.string.profiles_what_to_copy), style = LABEL_STYLE)
        for (group in state.groups) {
            CheckRow(groupLabel(group), group in chosen) {
                chosen = if (group in chosen) chosen - group else chosen + group
            }
        }
        SheetButtons(
            confirm = stringResource(R.string.profiles_copy),
            onCancel = onCancel,
            onConfirm = { from?.let { onCopy(it, chosen) } },
            confirmEnabled = from != null && chosen.isNotEmpty(),
        )
    }
}
