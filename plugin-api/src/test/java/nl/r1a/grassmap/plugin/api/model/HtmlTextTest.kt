package nl.r1a.grassmap.plugin.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hostile-input fixtures of the description renderer (T-0414): scripts,
 * images, frames, event handlers, address schemes, entities, malformed tags and
 * oversized input.
 */
class HtmlTextTest {
    @Test
    fun scriptAndStyleAndFramesDropTheirContent() {
        val rich =
            HtmlText.renderRich(
                "before<script>alert(1)</script>middle<style>p{color:red}</style>" +
                    "<iframe src=\"https://tracker.example/pixel\">framed</iframe>" +
                    "<svg onload=\"alert(2)\">drawn</svg><form action=\"https://example\">field</form>after",
            )

        assertEquals("beforemiddleafter", rich?.text)
        assertTrue(rich?.spans.orEmpty().isEmpty())
    }

    @Test
    fun anImageAndAnEventHandlerLeaveNoMarkupAndNoRemoteLoad() {
        val rich =
            HtmlText.renderRich(
                "text<img src=\"https://tracker.example/pixel.png\" onerror=\"alert(1)\">" +
                    "<p onclick=\"alert(2)\">more</p>",
            )

        assertEquals("text\nmore", rich?.text)
        assertTrue(rich?.spans.orEmpty().isEmpty())
    }

    @Test
    fun onlyAnHttpOrHttpsLinkKeepsItsAddress() {
        val rich =
            HtmlText.renderRich(
                "<a href=\"https://example.org/trail?a=1&amp;b=2\" onclick=\"alert(1)\">trail</a> " +
                    "<a href=\"javascript:alert(1)\">evil</a> <a href=\"data:text/html,x\">data</a>",
            )

        assertEquals("trail evil data", rich?.text)
        val links = rich?.spans?.filter { span -> span.style == RichTextStyle.LINK }.orEmpty()
        assertEquals(1, links.size)
        assertEquals("https://example.org/trail?a=1&b=2", links.single().href)
        assertEquals("trail", rich?.text?.substring(links.single().start, links.single().end))
    }

    @Test
    fun boldItalicStrongAndEmBecomeRuns() {
        val rich = HtmlText.renderRich("<b>bold</b> <strong>strong</strong> <i>i</i> <em>em</em>")

        assertEquals("bold strong i em", rich?.text)
        assertEquals(
            listOf(RichTextStyle.BOLD, RichTextStyle.BOLD, RichTextStyle.ITALIC, RichTextStyle.ITALIC),
            rich?.spans?.map { span -> span.style },
        )
    }

    @Test
    fun listsAndParagraphsBecomeNativeLines() {
        val rich =
            HtmlText.renderRich(
                "<p>One</p><ul><li>first</li><li>second</li></ul><ol><li>step</li></ol>",
            )

        assertEquals("One\n• first\n• second\n1. step", rich?.text)
    }

    @Test
    fun knownEntitiesDecodeAndAnUnknownOneStaysLiteral() {
        val rich = HtmlText.renderRich("caf&#233; &amp; &#x1F600; &unknown; &eacute;")

        assertEquals("café & 😀 &unknown; &eacute;", rich?.text)
    }

    @Test
    fun anUnclosedFormattingTagKeepsTheTextAndAMalformedTagDropsOnlyTheTail() {
        assertEquals("Bold to the end", HtmlText.renderRich("<b>Bold to the end")?.text)
        assertEquals(
            RichTextStyle.BOLD,
            HtmlText
                .renderRich("<b>Bold to the end")
                ?.spans
                ?.single()
                ?.style,
        )
        assertEquals("kept", HtmlText.renderRich("kept<b")?.text)
    }

    @Test
    fun anOversizedInputIsCappedWithoutFailing() {
        val rich = HtmlText.renderRich("<b>${"a".repeat(HtmlText.MAX_LENGTH * 4)}")

        assertEquals(HtmlText.MAX_LENGTH, rich?.text?.length)
        assertTrue(rich?.spans.orEmpty().isNotEmpty())
    }

    @Test
    fun anEmptyDescriptionRendersNothing() {
        assertNull(HtmlText.renderRich(null))
        assertNull(HtmlText.renderRich(""))
        assertNull(HtmlText.renderRich("<script>alert(1)</script>"))
    }

    @Test
    fun thePlainRendererReturnsTheSameTextWithoutTheRuns() {
        val raw = "<b>Steep</b> steps to <a href=\"https://example.org\">the ridge</a>"

        assertEquals("Steep steps to the ridge", HtmlText.render(raw))
        assertEquals(HtmlText.renderRich(raw)?.text, HtmlText.render(raw))
    }
}
