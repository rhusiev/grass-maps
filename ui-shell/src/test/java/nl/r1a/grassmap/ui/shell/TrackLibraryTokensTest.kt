package nl.r1a.grassmap.ui.shell

import androidx.compose.ui.graphics.Color
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenColors
import org.junit.Assert.assertEquals
import org.junit.Test

/** The track library palette's token wiring (5.6), without a device. */
class TrackLibraryTokensTest {
    @Test
    fun theLightDangerIsTheCanonDangerToken() {
        assertEquals(Color(NavigationScreenColors.DANGER), trackLibraryPalette(dark = false).danger)
    }
}
