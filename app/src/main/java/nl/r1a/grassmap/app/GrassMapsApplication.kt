package nl.r1a.grassmap.app

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
}
