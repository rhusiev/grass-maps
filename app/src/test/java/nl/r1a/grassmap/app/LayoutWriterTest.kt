package nl.r1a.grassmap.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.core.services.MapControlCommands
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LayoutWriterTest {
    @Test
    fun writesAreSerializedAndTheLatestLayoutIsReadAtWriteTime() =
        runTest {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            var current = MapControlCommands.DEFAULT_ORDER
            val saved = mutableListOf<List<CommandId>>()
            val writer =
                LayoutWriter(
                    currentLayout = { current },
                    save = { layout ->
                        saved += layout
                        if (saved.size == 1) {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                        }
                        Outcome.Success(Unit)
                    },
                )

            val first = launch { writer.saveLatest() }
            firstStarted.await()
            current =
                listOf(
                    MapControlCommands.ZOOM_OUT,
                    MapControlCommands.ZOOM_IN,
                    MapControlCommands.NORTH_UP,
                    MapControlCommands.FOLLOW,
                )
            val second = launch { writer.saveLatest() }
            runCurrent()

            assertEquals(1, saved.size)

            releaseFirst.complete(Unit)
            first.join()
            second.join()

            assertEquals(listOf(MapControlCommands.DEFAULT_ORDER, current), saved)
        }
}
