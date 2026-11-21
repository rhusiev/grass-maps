package nl.r1a.grassmaps.regions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentRangeTest {
    @Test
    fun aByteRangeWithATotalParses() {
        assertEquals(ContentRange(100, 199, 200), ContentRange.parse("bytes 100-199/200"))
    }

    @Test
    fun anUnknownTotalIsNull() {
        assertEquals(ContentRange(0, 9, null), ContentRange.parse("bytes 0-9/*"))
    }

    @Test
    fun theUnsatisfiedFormCarriesOnlyTheTotal() {
        assertEquals(ContentRange(-1, -1, 4096), ContentRange.parse("bytes */4096"))
    }

    @Test
    fun malformedHeadersAreNull() {
        assertNull(ContentRange.parse(null))
        assertNull(ContentRange.parse(""))
        assertNull(ContentRange.parse("bytes 10-5/20"))
        assertNull(ContentRange.parse("items 0-9/10"))
        assertNull(ContentRange.parse("bytes 0-9"))
        assertNull(ContentRange.parse("bytes a-b/c"))
    }
}
