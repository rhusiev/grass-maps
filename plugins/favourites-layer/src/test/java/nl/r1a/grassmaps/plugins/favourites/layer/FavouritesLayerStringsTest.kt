package nl.r1a.grassmaps.plugins.favourites.layer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
    fun everyShapeSpriteIsInTheShippedSheet() {
        val sprite = File(SPRITE).readText()
        val entries = Json.parseToJsonElement(sprite).jsonObject

        for (shape in MarkerShape.entries) {
            assertTrue("$shape sprite ${shape.sprite} is missing", shape.sprite in entries)
        }
        assertTrue("the neutral dot is missing", "osmc_fg_dot" in entries)
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
        const val SPRITE = "../../app/src/main/assets/sprites/standard.json"
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
