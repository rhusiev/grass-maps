package nl.r1a.grassmap.app

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.model.ProfilesState
import nl.r1a.grassmap.plugins.profiles.ui.ProfilesPage
import nl.r1a.grassmap.plugins.profiles.ui.ProfilesScreenContent
import nl.r1a.grassmap.plugins.profiles.ui.ProfilesUiContract

private const val TAG = "ProfilesScreen"

/**
 * Hosts the profiles screen over the map while [ProfilesScreenSession] holds a
 * page (T-0602). The state comes from the `profiles.ui` stream and every action
 * goes through its commands, so the screen and the control channel share one
 * path.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ProfilesScreenHost(container: AppContainer) {
    val page by ProfilesScreenSession.page.collectAsState()
    val current = page ?: return
    val scope = rememberCoroutineScope()
    val flow =
        remember(container) {
            container.streamAccess.subscribe<ProfilesState>(ProfilesUiContract.stateStream)
        }
    val state by flow.collectAsState(initial = ProfilesState())
    val run: (CommandId, JsonObject) -> Unit = { id, params ->
        scope.launch { invokeProfilesCommand(container, id, params) }
    }
    BackHandler { ProfilesScreenSession.navigate(current.back()) }
    ProfilesScreenContent(
        state = state,
        page = current,
        onNavigate = ProfilesScreenSession::navigate,
        onCreate = { name, from ->
            run(
                ProfilesUiContract.create,
                buildJsonObject {
                    put("name", name)
                    put("from", from)
                },
            )
        },
        onCopySettings = { from, to, groups ->
            run(
                ProfilesUiContract.copySettings,
                buildJsonObject {
                    put("from", from)
                    put("to", to)
                    put("groups", JsonArray(groups.map(::JsonPrimitive)))
                },
            )
        },
        onReset = { profile -> run(ProfilesUiContract.reset, buildJsonObject { put("profile", profile) }) },
        onUndo = { run(ProfilesUiContract.undo, JsonObject(emptyMap())) },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Runs one profiles command and logs a typed failure; the screen keeps its state. */
private suspend fun invokeProfilesCommand(
    container: AppContainer,
    id: CommandId,
    params: JsonObject,
) {
    container.commandBus.invoke(id, params).onFailure { error ->
        Log.w(TAG, "profiles command $id failed: $error")
    }
}
