package nl.r1a.grassmap.app

import android.app.Activity
import android.app.Application
import nl.r1a.grassmap.core.services.AndroidStrings

/**
 * Application entry point. The container is process-scoped and built lazily;
 * only the first activation event pays for the plugin host.
 */
open class GrassMapsApplication : Application() {
    val container: AppContainer by lazy {
        AppContainer(strings = AndroidStrings(this))
    }

    /** The measurement harness for this build; the release app observes nothing. */
    internal open fun mapHarness(activity: Activity): MapHarness = NoMapHarness
}
