package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Q56 P1-2: the chevron's sheet opens on a whole Saved tile row, and the only
 * Settings entry lies below it with nothing that hints the sheet scrolls. The
 * sheet now draws a scroll indicator while its rows can scroll. The geometry is
 * a plain function; the drawing is a Composable a JVM test cannot render, so
 * pin its wiring from source. Rendered layout is checked on the AVD.
 */
class SheetScrollIndicatorTest {
    @Test
    fun rowsThatFitDrawNoIndicator() {
        assertNull(sheetScrollThumb(viewportPx = VIEWPORT, maxScrollPx = 0f, scrollPx = 0f, minLengthPx = MIN))
    }

    @Test
    fun theThumbShowsTheVisibleShareAndStartsAtTheTop() {
        val thumb = requireNotNull(sheetScrollThumb(VIEWPORT, maxScrollPx = 100f, scrollPx = 0f, minLengthPx = MIN))

        assertEquals(0f, thumb.top, DELTA)
        assertEquals(VIEWPORT * VIEWPORT / (VIEWPORT + 100f), thumb.length, DELTA)
        assertTrue("the thumb must leave track below it to show more rows", thumb.top + thumb.length < VIEWPORT)
    }

    @Test
    fun theThumbReachesTheTrackEndWhenScrolledToTheEnd() {
        val thumb = requireNotNull(sheetScrollThumb(VIEWPORT, maxScrollPx = 100f, scrollPx = 100f, minLengthPx = MIN))

        assertEquals(VIEWPORT, thumb.top + thumb.length, DELTA)
    }

    @Test
    fun aLongListKeepsTheThumbAtItsMinimumLength() {
        val thumb = requireNotNull(sheetScrollThumb(VIEWPORT, maxScrollPx = 100_000f, scrollPx = 0f, minLengthPx = MIN))

        assertEquals(MIN, thumb.length, DELTA)
    }

    @Test
    fun theChevronSheetDrawsTheIndicatorOnItsScrollingRows() {
        val sheet = File(SHEET_FILE).readText()
        val chrome = File(CHROME_FILE).readText()
        val rows = chrome.substringAfter("header()").substringBefore("footer()")

        assertTrue("the chevron's sheet does not ask for the indicator", sheet.contains("scrollIndicator = true"))
        assertTrue("the indicator is not off by default", chrome.contains("scrollIndicator: Boolean = false"))
        assertTrue("the scrolling rows draw no indicator", rows.contains("sheetScrollIndicator("))
        assertTrue(
            "the indicator is drawn inside the scrolled content, so it scrolls away",
            rows.indexOf("sheetScrollIndicator(") < rows.indexOf(".verticalScroll("),
        )
    }

    private companion object {
        const val VIEWPORT = 400f
        const val MIN = 24f
        const val DELTA = 0.01f
        const val SHEET_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/WidgetsSavedSheet.kt"
        const val CHROME_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/ShellBottomSheet.kt"
    }
}
