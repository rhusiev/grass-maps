package nl.r1a.grassmap.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nl.r1a.grassmap.app.devcheck.DeviceCheckSession
import nl.r1a.grassmap.app.devcheck.DeviceCheckUi
import nl.r1a.grassmap.plugins.dev.tools.DevToolsContract
import nl.r1a.grassmap.plugins.dev.tools.DevToolsPanelState

private val PANEL_BACKGROUND = Color(0xCC000000)
private val PANEL_FOREGROUND = Color.White
private const val PANEL_CORNER_DP = 8
private const val PANEL_HORIZONTAL_DP = 12
private const val PANEL_VERTICAL_DP = 8
private const val PANEL_GAP_DP = 6
private const val PANEL_FONT_SP = 13
private const val ACTION_VERTICAL_DP = 2
private const val BYTES_PER_MEGABYTE = 1024L * 1024L

/**
 * The development-tools panel. It binds the plugin's state stream and invokes
 * its start and stop commands; the app shows it only while AI control is on.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun DevToolsPanel(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val state by
        container.streamAccess
            .subscribe<DevToolsPanelState>(DevToolsContract.stateStream)
            .collectAsState(initial = DevToolsPanelState())
    val scope = rememberCoroutineScope()
    val application = LocalContext.current.applicationContext as Application
    val check = remember(container) { DeviceCheckSession.of(application, container) }
    val checkUi by check.ui.collectAsState()
    val capturing by check.capturing.collectAsState()
    if (capturing) {
        return
    }

    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
                .background(PANEL_BACKGROUND)
                .padding(horizontal = PANEL_HORIZONTAL_DP.dp, vertical = PANEL_VERTICAL_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
    ) {
        PanelText(stringResource(R.string.dev_tools_title))
        PanelText(
            stringResource(
                if (state.replayActive) {
                    R.string.dev_tools_replay_active
                } else {
                    R.string.dev_tools_replay_inactive
                },
            ),
        )
        PanelText(stringResource(R.string.dev_tools_fps, state.framesPerSecond))
        PanelText(
            stringResource(
                R.string.dev_tools_memory,
                state.usedMemoryBytes / BYTES_PER_MEGABYTE,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp)) {
            PanelAction(stringResource(R.string.dev_tools_start_replay)) {
                scope.launch { container.commandBus.invoke(DevToolsContract.startReplayCommand) }
            }
            PanelAction(stringResource(R.string.dev_tools_stop_replay)) {
                scope.launch { container.commandBus.invoke(DevToolsContract.stopReplayCommand) }
            }
        }
        DeviceCheckSection(checkUi, check::start)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PanelText(text: String) {
    BasicText(
        text = text,
        style = TextStyle(color = PANEL_FOREGROUND, fontSize = PANEL_FONT_SP.sp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PanelAction(
    label: String,
    onClick: () -> Unit,
) {
    BasicText(
        text = label,
        style = TextStyle(color = PANEL_FOREGROUND, fontSize = PANEL_FONT_SP.sp),
        modifier =
            Modifier
                .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = PANEL_GAP_DP.dp, vertical = ACTION_VERTICAL_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun DeviceCheckSection(
    ui: DeviceCheckUi,
    onStart: () -> Unit,
) {
    PanelText(
        stringResource(R.string.dev_check_build, BuildConfig.BUILD_COMMIT, BuildConfig.BUILD_COMMIT_TIME),
    )
    if (ui.running) {
        PanelText(stringResource(R.string.dev_check_running, ui.stepNumber, ui.stepCount, ui.stepId))
    } else {
        PanelAction(stringResource(R.string.dev_check_button), onStart)
    }
    ui.finished?.let { report ->
        val summary = stringResource(R.string.dev_check_summary, report.passedCount, report.failedCount, report.skippedCount)
        PanelText(stringResource(if (report.isPass) R.string.dev_check_pass else R.string.dev_check_fail, summary))
    }
    ui.error?.let { PanelText(stringResource(R.string.dev_check_error, it)) }
}
