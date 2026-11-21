package nl.r1a.grassmaps.ui.shell

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbArcTest {
    @Test
    fun noButtonsNeedNoArc() {
        assertEquals(emptyList<ThumbArcOffset>(), thumbArcOffsets(0))
    }

    @Test
    fun oneButtonSitsAtTheThumbEnd() {
        assertEquals(listOf(ThumbArcOffset(0.dp, -20.dp)), thumbArcOffsets(1))
    }

    @Test
    fun theFirstButtonIsTheFarTopEndAndTheLastIsTheThumbEnd() {
        val offsets = thumbArcOffsets(4)

        assertEquals(4, offsets.size)
        assertEquals(-160f, offsets.first().y.value, 0.001f)
        assertEquals(0f, offsets.first().x.value, 0.001f)
        assertEquals(-20f, offsets.last().y.value, 0.001f)
        assertEquals(-140f, offsets.last().x.value, 0.001f)
        assertTrue(offsets.first().y < offsets.last().y)
        assertTrue(offsets.first().x > offsets.last().x)
    }

    @Test
    fun everyNextButtonIsLowerAndFurtherTowardsTheStart() {
        val offsets = thumbArcOffsets(5)

        offsets.zipWithNext { earlier, later ->
            assertTrue("$later must be lower than $earlier", later.y > earlier.y)
            assertTrue("$later must be further left than $earlier", later.x < earlier.x)
        }
    }
}
