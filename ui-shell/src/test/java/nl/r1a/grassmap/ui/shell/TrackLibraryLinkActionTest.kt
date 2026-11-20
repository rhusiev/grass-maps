package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.RichText
import nl.r1a.grassmap.plugin.api.model.RichTextSpan
import nl.r1a.grassmap.plugin.api.model.RichTextStyle
import nl.r1a.grassmap.plugin.api.model.TrackBrowseState
import nl.r1a.grassmap.plugin.api.model.TrackKind
import nl.r1a.grassmap.plugin.api.model.TrackLibraryEntry
import nl.r1a.grassmap.plugin.api.model.TrackLibraryRow
import nl.r1a.grassmap.plugin.api.model.TrackLibraryState
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The links inside a rich description (T-0414) as a reader meets them: a tap
 * on a drawn run is invisible to a screen reader, so each link becomes one
 * labelled action on the row. The label names the run's visible text, because
 * an address alone reads as a URL. The actions themselves are Compose
 * semantics and need a device; this keeps their labels and order honest.
 */
class TrackLibraryLinkActionTest {
    private val english = TestStringCatalog.read(ENGLISH).merge(TestStringCatalog.read(APP_ENGLISH)).resolver()
    private val ukrainian = TestStringCatalog.read(UKRAINIAN).merge(TestStringCatalog.read(APP_UKRAINIAN)).resolver()

    @Test
    fun eachLinkBecomesOneActionAndBoldStaysText() {
        val actions = descriptionLinkActions(DESCRIPTION, english)

        assertEquals(listOf("Open link: map", "Open link: notes"), actions.map { action -> action.label })
        assertEquals(listOf("https://example.org/map", "https://example.org/notes"), actions.map { action -> action.href })
    }

    @Test
    fun aDescriptionWithoutLinksOffersNoActions() {
        val bold = RichText(text = "Steep steps", spans = listOf(RichTextSpan(0, 5, RichTextStyle.BOLD)))

        assertTrue(descriptionLinkActions(bold, english).isEmpty())
        assertTrue(descriptionLinkActions(null, english).isEmpty())
        val hrefless = RichText(text = "map", spans = listOf(RichTextSpan(0, 3, RichTextStyle.LINK)))
        assertTrue(descriptionLinkActions(hrefless, english).isEmpty())
    }

    @Test
    fun aLinkWithoutVisibleTextSpeaksItsAddress() {
        val blank =
            RichText(
                text = "   ",
                spans = listOf(RichTextSpan(0, 3, RichTextStyle.LINK, href = "https://example.org/map")),
            )

        assertEquals("Open link: https://example.org/map", descriptionLinkActions(blank, english).single().label)
    }

    @Test
    fun theRowsCarryTheActionsOfTheirDescriptions() {
        val row = trackLibraryContent(state(), english, Locale.ENGLISH).rows.single()

        assertEquals(listOf("Open link: map", "Open link: notes"), row.linkActions.map { action -> action.label })
    }

    @Test
    fun theActionLabelResolvesInUkrainian() {
        val actions = descriptionLinkActions(DESCRIPTION, ukrainian)

        assertEquals(listOf("Відкрити посилання: map", "Відкрити посилання: notes"), actions.map { action -> action.label })
        assertNotEquals(actions.first().label, actions.first().href)
    }

    @Test
    fun theLabelIsAPhraseWithItsRunInBothLanguages() {
        val englishLabel = TestStringCatalog.read(ENGLISH).resolver().resolve(TRACK_LIBRARY_LINK_ACTION_KEY, listOf("map"))
        val ukrainianLabel = TestStringCatalog.read(UKRAINIAN).resolver().resolve(TRACK_LIBRARY_LINK_ACTION_KEY, listOf("map"))

        for (label in listOf(englishLabel, ukrainianLabel)) {
            assertTrue("'$label' is missing the run", label.contains("map"))
            assertTrue("'$label' is not a phrase", label.contains(' '))
        }
    }

    private fun state(): TrackLibraryState =
        TrackLibraryState(
            tracks = listOf(TrackLibraryEntry(id = "t1", name = "Znesinnia ridges", description = DESCRIPTION.text)),
            browse =
                TrackBrowseState(
                    rows =
                        listOf(
                            TrackLibraryRow(
                                id = "t1",
                                name = "Znesinnia ridges",
                                kind = TrackKind.Track,
                                description = DESCRIPTION,
                            ),
                        ),
                ),
        )

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
        const val APP_ENGLISH = "../app/src/main/res/values/strings.xml"
        const val APP_UKRAINIAN = "../app/src/main/res/values-uk/strings.xml"

        val DESCRIPTION =
            RichText(
                text = "Steep steps and a map with notes in it",
                spans =
                    listOf(
                        RichTextSpan(start = 0, end = 5, style = RichTextStyle.BOLD),
                        RichTextSpan(start = 18, end = 21, style = RichTextStyle.LINK, href = "https://example.org/map"),
                        RichTextSpan(start = 27, end = 32, style = RichTextStyle.LINK, href = "https://example.org/notes"),
                    ),
            )
    }
}
