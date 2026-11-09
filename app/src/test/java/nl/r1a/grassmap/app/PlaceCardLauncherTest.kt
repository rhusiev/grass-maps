package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.PlatformActions
import nl.r1a.grassmap.plugin.api.model.PlaceCardAction
import nl.r1a.grassmap.plugin.api.model.PlaceCardLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card's links and shortcuts reach the right platform effect, and a
 * website or social value with an untrusted scheme never reaches `ACTION_VIEW`
 * (T-0210).
 */
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
    fun aWebsiteWithAnUntrustedSchemeIsRefusedWithoutOpening() {
        val actions = Recording()
        val refused =
            listOf(
                "intent://scan/#Intent;scheme=zxing;package=com.example;end",
                "file:///data/local/tmp/page.html",
                "content://com.example.provider/place/1",
                "javascript:alert(1)",
                "javascript://example.test/%0aalert(1)",
                "javascript:80",
                "intent:80",
                "data:text/html,<script>alert(1)</script>",
                "ftp://example.test/file",
            )

        for (target in refused) {
            assertEquals(
                "OPEN_WEBSITE must refuse $target",
                Outcome.Failure(AppError.Unsupported),
                actions.launch(PlaceCardLink(PlaceCardAction.OPEN_WEBSITE, target)),
            )
            assertEquals(
                "OPEN_SOCIAL must refuse $target",
                Outcome.Failure(AppError.Unsupported),
                actions.launch(PlaceCardLink(PlaceCardAction.OPEN_SOCIAL, target)),
            )
        }
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun theWebTargetPolicyAllowsOnlyHttpAndHttpsOrABareHost() {
        assertEquals(Outcome.Success("https://example.test/a"), webUri("https://example.test/a"))
        assertEquals(Outcome.Success("http://example.test/a"), webUri("http://example.test/a"))
        assertEquals(Outcome.Success("https://example.test/a"), webUri("HTTPS://example.test/a"))
        assertEquals(Outcome.Success("https://example.test/a"), webUri("  example.test/a  "))
        assertEquals(Outcome.Success("https://example.test:8080/a"), webUri("example.test:8080/a"))
        assertEquals(Outcome.Success("https://localhost:8080"), webUri("localhost:8080"))
        assertEquals(Outcome.Success("https://example.test"), webUri("//example.test"))

        for (refused in listOf("intent://x", "file:///x", "content://x", "javascript:alert(1)", "ftp://x")) {
            assertEquals(refused, Outcome.Failure(AppError.Unsupported), webUri(refused))
        }
        assertEquals(Outcome.Failure(AppError.Unsupported), webUri("   "))
        assertEquals(Outcome.Failure(AppError.Unsupported), webUri("not a host"))
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
