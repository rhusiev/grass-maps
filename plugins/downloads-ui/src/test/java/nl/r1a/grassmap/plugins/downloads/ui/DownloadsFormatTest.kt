package nl.r1a.grassmap.plugins.downloads.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The numbers the downloads screen draws (T-0504). */
class DownloadsFormatTest {
    @Test
    fun sizesAreDecimalAndDropTheFractionFromTenUpwards() {
        assertEquals("0 B", DownloadsFormat.size(0))
        assertEquals("999 B", DownloadsFormat.size(999))
        assertEquals("1 KB", DownloadsFormat.size(1_000))
        assertEquals("1.5 MB", DownloadsFormat.size(1_500_000))
        assertEquals("3.6 MB", DownloadsFormat.size(3_643_542))
        assertEquals("13 MB", DownloadsFormat.size(13_000_000))
        assertEquals("152 MB", DownloadsFormat.size(152_201_954))
        assertEquals("2.5 GB", DownloadsFormat.size(2_500_000_000))
        assertEquals("", DownloadsFormat.size(-1))
    }

    @Test
    fun theDataDateIsTheDayAndTheShortMonth() {
        assertEquals("3 Nov", DownloadsFormat.dataDate("2026-11-03", Locale.UK))
        assertEquals("3 Nov", DownloadsFormat.dataDate("2026-11-03", Locale.US))

        val ukrainian = DownloadsFormat.dataDate("2026-11-03", Locale.forLanguageTag("uk"))
        assertTrue("ukrainian date was $ukrainian", ukrainian != null && ukrainian.startsWith("3"))
    }

    @Test
    fun aVersionThatIsNotADateHasNoDate() {
        assertNull(DownloadsFormat.dataDate(null, Locale.UK))
        assertNull(DownloadsFormat.dataDate("staging", Locale.UK))
        assertNull(DownloadsFormat.dataDate("2026-11", Locale.UK))
        assertNull(DownloadsFormat.dataDate("2026-13-40", Locale.UK))
    }

    @Test
    fun progressIsBoundedAndUnknownWithoutATotal() {
        assertEquals(0, DownloadsFormat.progressPercent(0, 100))
        assertEquals(50, DownloadsFormat.progressPercent(50, 100))
        assertEquals(100, DownloadsFormat.progressPercent(150, 100))
        assertEquals(0, DownloadsFormat.progressPercent(-5, 100))
        assertNull(DownloadsFormat.progressPercent(10, 0))
    }
}
