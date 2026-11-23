package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The no-index dead end (blind review section 2, Top 10 item 7). A fresh
 * install must see why offline search needs a region and a direct action that
 * opens Downloads through the existing entry point. The action's wiring to the
 * callback is pure, so a JVM test pins it without a Compose runtime.
 */
class SearchNotReadyActionTest {
    @Test
    fun aMissingIndexOffersAnExplanationAndADownloadsAction() {
        val notice = searchNotReadyNotice(LABELS)

        assertEquals(
            "Offline search needs a downloaded map region with its search index.",
            notice.explanation,
        )
        assertEquals("Open downloads to get a region", notice.action)
        assertEquals(SearchNotReadyAction.OPEN_DOWNLOADS, notice.actionKind)
    }

    @Test
    fun theUkrainianNoticeCarriesTheSameReasonAndAction() {
        val notice = searchNotReadyNotice(UKRAINIAN_LABELS)

        assertEquals(
            "Для офлайн-пошуку потрібна завантажена мапа області з пошуковим індексом.",
            notice.explanation,
        )
        assertEquals("Відкрити завантаження, щоб отримати область", notice.action)
        assertEquals(SearchNotReadyAction.OPEN_DOWNLOADS, notice.actionKind)
    }

    @Test
    fun theNotReadyActionCallsTheDownloadsCallbackOnce() {
        val notice = searchNotReadyNotice(LABELS)
        var calls = 0

        runNotReadyAction(notice.actionKind) { calls++ }

        assertEquals(1, calls)
    }

    @Test
    fun aMissingIndexIsTheOnlyStateThatOffersTheAction() {
        val failed = searchScreenContent(emptyList(), query = "льв", loading = false, failed = true, labels = LABELS)
        val ready = searchScreenContent(emptyList(), query = "льв", loading = false, failed = false, labels = LABELS)

        assertNotNull(failed.notReady)
        assertEquals(LABELS.notReady, failed.notReady!!.explanation)
        assertNull(ready.notReady)
    }

    @Test
    fun bothBundlesCarryTheNotReadyExplanationAndAction() {
        val english = resources(ENGLISH)
        val ukrainian = resources(UKRAINIAN)

        assertEquals(
            "Offline search needs a downloaded map region with its search index.",
            english["search_not_ready"],
        )
        assertEquals("Open downloads to get a region", english["search_not_ready_action"])
        assertEquals(
            "Для офлайн-пошуку потрібна завантажена мапа області з пошуковим індексом.",
            ukrainian["search_not_ready"],
        )
        assertEquals("Відкрити завантаження, щоб отримати область", ukrainian["search_not_ready_action"])
    }

    private fun resources(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"

        val LABELS =
            SearchScreenLabels(
                title = "Search places",
                hint = "Name, street or house number",
                close = "Close search",
                noMatches = "No matches",
                notReady = "Offline search needs a downloaded map region with its search index.",
                notReadyAction = "Open downloads to get a region",
                loading = "Searching",
            )

        val UKRAINIAN_LABELS =
            LABELS.copy(
                notReady = "Для офлайн-пошуку потрібна завантажена мапа області з пошуковим індексом.",
                notReadyAction = "Відкрити завантаження, щоб отримати область",
            )
    }
}
