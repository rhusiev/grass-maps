package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.PlatformActions
import nl.r1a.grassmap.plugin.api.model.PlaceCardAction
import nl.r1a.grassmap.plugin.api.model.PlaceCardLink
import org.junit.Assert.assertEquals
import org.junit.Test

/** The card's links and shortcuts reach the right platform effect (T-0210). */
class PlaceCardLauncherTest {
    @Test
    fun theContactLinksOpenTheirExistingSchemes() {
        val actions = Recording()

        actions.launch(PlaceCardLink(PlaceCardAction.CALL, "tel:+380322970545"))
        actions.launch(PlaceCardLink(PlaceCardAction.SEND_EMAIL, "mailto:cafe@levada.example"))
        actions.launch(PlaceCardLink(PlaceCardAction.OPEN_IN, "geo:49.821740,24.023910"))

        assertEquals(
            listOf(
                "open:tel:+380322970545",
                "open:mailto:cafe@levada.example",
                "open:geo:49.821740,24.023910",
            ),
            actions.calls,
        )
    }

    @Test
    fun aWebsiteWithoutASchemeGetsHttps() {
        val actions = Recording()

        actions.launch(PlaceCardLink(PlaceCardAction.OPEN_WEBSITE, "levada.example"))
        actions.launch(PlaceCardLink(PlaceCardAction.OPEN_SOCIAL, "https://t.me/levada"))

        assertEquals(
            listOf(
                "open:https://levada.example",
                "open:https://t.me/levada",
            ),
            actions.calls,
        )
    }

    @Test
    fun copyAndShareReachTheClipboardAndTheShareSheet() {
        val actions = Recording()

        actions.launch(PlaceCardLink(PlaceCardAction.COPY_COORDINATES, "49.821740, 24.023910"))
        actions.launch(PlaceCardLink(PlaceCardAction.SHARE, "https://www.openstreetmap.org/node/42"))

        assertEquals(
            listOf(
                "copy:49.821740, 24.023910",
                "share:https://www.openstreetmap.org/node/42",
            ),
            actions.calls,
        )
    }

    @Test
    fun aWebUriKeepsItsScheme() {
        assertEquals("https://example.test/a", webUri("https://example.test/a"))
        assertEquals("http://example.test/a", webUri("http://example.test/a"))
        assertEquals("https://example.test/a", webUri("example.test/a"))
    }

    private class Recording : PlatformActions {
        val calls = mutableListOf<String>()

        override fun copyToClipboard(text: String): Outcome<Unit> {
            calls += "copy:$text"
            return Outcome.Success(Unit)
        }

        override fun shareText(
            text: String,
            subject: String?,
        ): Outcome<Unit> {
            calls += "share:$text"
            return Outcome.Success(Unit)
        }

        override fun openUri(uri: String): Outcome<Unit> {
            calls += "open:$uri"
            return Outcome.Success(Unit)
        }
    }
}
