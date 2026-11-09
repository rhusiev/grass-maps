package nl.r1a.grassmap.plugins.favourites.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safe description renderer of T-0214.
 *
 * Every hostile case proves the same contract: no markup, no script content and
 * no attribute survives into the plain text the shell draws. Allowed formatting
 * keeps its text and its line breaks; everything else is dropped.
 */
class FavouritesDescriptionTest {
    @Test
    fun simpleFormattingKeepsItsTextAndLineBreaks() {
        assertEquals("bold and italic", render("<b>bold</b> and <i>italic</i>"))
        assertEquals("under", render("<u>under</u>"))
        assertEquals("strong and emphasis", render("<strong>strong</strong> and <em>emphasis</em>"))
        assertEquals("one\ntwo", render("one<br>two"))
        assertEquals("one\ntwo", render("<p>one</p><p>two</p>"))
        assertEquals("deep", render("<b><i>deep</i></b>"))
    }

    @Test
    fun aScriptDropsItsContentAsWellAsItsTags() {
        assertEquals("Hello", render("<script>alert('xss')</script>Hello"))
        assertEquals("before after", render("before<script type=\"text/javascript\">alert(1)</script> after"))
        assertNull(render("<script>alert(1)"))
        assertEquals("safe", render("safe<script src=\"evil.js\"/>"))
    }

    @Test
    fun aStyleDropsItsContent() {
        assertEquals("ok", render("<style>body{color:red}</style>ok"))
        assertNull(render("<style>body{color:red}"))
    }

    @Test
    fun anImageWithAnEventHandlerIsDroppedWhole() {
        assertEquals("Text", render("<img src=\"x\" onerror=\"alert(1)\">Text"))
        assertNull(render("<img src=x onerror=alert(1)>"))
    }

    @Test
    fun aJavascriptHrefIsNeverRenderedAsALink() {
        assertEquals("click", render("<a href=\"javascript:alert(1)\">click</a>"))
        assertEquals("web", render("<a href=\"https://example.org\">web</a>"))
        assertNull(render("<iframe src=\"javascript:alert(1)\"></iframe>"))
    }

    @Test
    fun eventHandlersAreDroppedEvenOnAnAllowedTag() {
        assertEquals("ok", render("<b onclick=\"alert(1)\">ok</b>"))
        assertEquals("kept", render("<span onmouseover=\"alert(1)\">kept</span>"))
    }

    @Test
    fun entitiesAreDecodedAndUnknownOnesStayLiteral() {
        assertEquals("Tom & Jerry <3 'q' \"x\"", render("Tom &amp; Jerry &lt;3 &#39;q&#39; &quot;x&quot;"))
        assertEquals("A B", render("A&nbsp;B")?.replace('\u00A0', ' '))
        assertEquals("&unknown;", render("&unknown;"))
        assertEquals("&#xZZ;", render("&#xZZ;"))
    }

    @Test
    fun malformedAndUnclosedTagsKeepTheReadableText() {
        assertEquals("bold", render("<b>bold"))
        assertEquals("one\ntwo", render("<p>one<p>two"))
        assertNull(render("<a href=\"unclosed"))
        assertEquals("5 < 3", render("5 < 3"))
        assertEquals("a > b", render("a > b"))
        assertEquals("text", render("<!-- comment -->text"))
        assertNull(render("<!-- unclosed comment"))
    }

    @Test
    fun anEmptyDescriptionStaysEmpty() {
        assertNull(render(null))
        assertNull(render(""))
        assertNull(render("<br><p></p>"))
        assertEquals("plain text", render("plain text"))
    }

    @Test
    fun aVeryLongDescriptionIsBounded() {
        val rendered = render("a".repeat(50_000))
        assertEquals(FavouritesBrowser.MAX_DESCRIPTION_LENGTH, rendered?.length)
    }

    @Test
    fun theBoundNeverSplitsASurrogatePair() {
        val rendered = render("\uD83D\uDE00".repeat(MAX_POINTS))
        assertTrue("rendered length ${rendered?.length}", (rendered?.length ?: 0) <= FavouritesBrowser.MAX_DESCRIPTION_LENGTH)
        assertEquals(0, rendered?.last()?.isHighSurrogate()?.let { if (it) 1 else 0 } ?: 0)
    }

    private fun render(raw: String?): String? = FavouritesBrowser.renderDescription(raw)

    private companion object {
        const val MAX_POINTS = 3000
    }
}
