package nl.r1a.grassmaps.app

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Backup screen registers its document launchers in composition. The
 * in-app language swaps `LocalContext` for a localized context, and the
 * launchers find the activity's result registry through that context, so the
 * screen must open inside [MainActivity] without crashing (final review 9 P0).
 */
@RunWith(AndroidJUnit4::class)
class BackupScreenInstrumentedTest {
    @After
    fun tearDown() {
        BackupScreenSession.hide()
    }

    @Test
    fun theBackupScreenOpensInsideTheMapActivity() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            BackupScreenSession.show()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(COMPOSE_SETTLE_MS)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    private companion object {
        /** Lets the recomposition that adds the Backup host run its first frame. */
        const val COMPOSE_SETTLE_MS = 1_000L
    }
}
