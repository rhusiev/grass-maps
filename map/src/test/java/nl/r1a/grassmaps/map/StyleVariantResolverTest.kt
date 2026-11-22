package nl.r1a.grassmaps.map

import nl.r1a.grassmaps.plugin.api.model.StyleId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The style-variant resolver behind profile switches (T-0603). Meadow and
 * Trail, light and dark, share one base document, so moving between profiles
 * changes layers through a diff and never reloads the engine style.
 */
class StyleVariantResolverTest {
    private val base = "{\"version\":8,\"glyphs\":\"g\",\"sprite\":\"s\"}"

    @Test
    fun aSameBaseProfileSwitchIsANoOp() {
        val meadow = StyleVariant(StyleId("meadow"), base)
        val trail = StyleVariant(StyleId("trail"), base)

        assertFalse(needsStyleReload(meadow, trail))
        assertFalse(needsStyleReload(trail, meadow))
    }

    @Test
    fun aDarkVariantOfTheSameStyleStaysOnTheLoadedBase() {
        val light = StyleVariant(StyleId("meadow"), base)
        val dark = StyleVariant(StyleId("meadow-dark"), base)

        assertFalse(needsStyleReload(light, dark))
    }

    @Test
    fun aChangedBaseDocumentForcesOneReload() {
        val meadow = StyleVariant(StyleId("meadow"), base)
        val other = StyleVariant(StyleId("other"), base.replace("sprite", "icon"))

        assertTrue(needsStyleReload(meadow, other))
    }

    @Test
    fun theFirstStyleAlwaysLoads() {
        assertTrue(needsStyleReload(null, StyleVariant(StyleId("meadow"), base)))
    }
}
