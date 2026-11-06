package nl.r1a.grassmap.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * The single activity. It composes the map surface from the process-scoped
 * container and leaves chrome to later tasks.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val application = application as GrassMapsApplication
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val switches = styleSwitchCount(intent, debuggable)
        setContent {
            MapScreen(container = application.container, styleSwitches = switches)
        }
    }
}
