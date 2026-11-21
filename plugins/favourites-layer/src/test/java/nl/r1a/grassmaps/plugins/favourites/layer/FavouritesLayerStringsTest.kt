package nl.r1a.grassmaps.plugins.favourites.layer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The card follows the saved/збережене glossary and carries all three locales. */
class FavouritesLayerStringsTest {
    @Test
    fun everyLocaleHasTheFourControlLabels() {
        for (path in LOCALES) {
            val text = File(path).readText()
            for (key in KEYS) {
                assertTrue("$path is missing $key", "<string name=\"$key\">" in text)
            }
        }
    }

    @Test
    fun theUkrainianCopyUsesTheGlossaryTerms() {
        val text = File(UKRAINIAN).readText()
        assertTrue("збережене", "збереженого" in text)
        assertTrue("мапа", "мапі" in text)
        assertFalse("карта", "карт" in text)
    }

    @Test
    fun theShapeRowOffersTheStoreShapeAndThreeShapes() {
        assertEquals(
            listOf(null, MarkerShape.Circle, MarkerShape.Square, MarkerShape.Octagon),
            favouriteShapeChoices(),
        )
    }

    @Test
    fun aHiddenGroupIsNotShownAndAGroupWithNoSettingIsShown() {
        val settings = FavouriteLayerSettings(hiddenGroups = setOf("Home"))

        assertFalse(isGroupShown(settings, "Home"))
        assertTrue(isGroupShown(settings, "Work"))
    }

    private companion object {
        // English (UK) has no overrides here: none of the copy differs from the
        // US default, and checkTranslations refuses an identical override.
        val LOCALES =
            listOf(
                "src/main/res/values/strings.xml",
                "src/main/res/values-uk/strings.xml",
            )
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
        val KEYS =
            listOf(
                "plugin.favourites_layer.name",
                "favourites_layer_icons",
                "favourites_layer_shape_title",
                "favourites_layer_names",
                "favourites_layer_group_show",
                "favourites_layer_group_hide",
            )
    }
}
