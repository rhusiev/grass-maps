package nl.r1a.grassmap.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The privacy prompt (T-0212) names exactly what enabling sends, and the
 * Ukrainian prompt says the same thing. The strings are pinned here so a later
 * wording change has to face the privacy claim.
 */
class SearchOnlineNoticeTest {
    @Test
    fun withTheSwitchOffAndNoPositionAllowanceThePromptNamesQueryAndIpOnly() {
        val notice = searchOnlineNotice(LABELS, onlineEnabled = false, positionAllowed = false)

        assertEquals(ENGLISH_OFF_NO_POSITION, notice.text)
        assertEquals("Enable online search", notice.action)
        assertEquals(SearchOnlineAction.ENABLE, notice.actionKind)
        assertEquals(
            "Online search is off. Enabling it sends your query text and your IP address to photon.komoot.io. Your map position is not sent.",
            notice.text,
        )
    }

    @Test
    fun withThePositionAllowedThePromptSaysThePositionIsSentToo() {
        val notice = searchOnlineNotice(LABELS, onlineEnabled = false, positionAllowed = true)

        assertEquals(ENGLISH_OFF_SENDS_POSITION, notice.text)
        assertEquals("Enable online search", notice.action)
        assertEquals(SearchOnlineAction.ENABLE, notice.actionKind)
    }

    @Test
    fun withTheSwitchOnThePromptNamesTheSourceAndOffersThePositionChoice() {
        val off = searchOnlineNotice(LABELS, onlineEnabled = true, positionAllowed = false)
        val on = searchOnlineNotice(LABELS, onlineEnabled = true, positionAllowed = true)

        assertEquals(ENGLISH_ON_NO_POSITION, off.text)
        assertEquals("Send my position", off.action)
        assertEquals(SearchOnlineAction.ALLOW_POSITION, off.actionKind)
        assertEquals(ENGLISH_ON_SENDS_POSITION, on.text)
        assertEquals("Stop sending my position", on.action)
        assertEquals(SearchOnlineAction.STOP_POSITION, on.actionKind)
    }

    @Test
    fun theUkrainianPromptCarriesTheSamePrivacyClaim() {
        val notice = searchOnlineNotice(UKRAINIAN_LABELS, onlineEnabled = false, positionAllowed = false)

        assertEquals(
            "Онлайн-пошук вимкнено. Його ввімкнення надсилає текст запиту та вашу IP-адресу до photon.komoot.io. Позиція на карті не надсилається.",
            notice.text,
        )
        assertEquals("Увімкнути онлайн-пошук", notice.action)
    }

    @Test
    fun bothLocaleFilesCarryTheSameOnlineSearchKeys() {
        val english = resources(ENGLISH_FILE)
        val ukrainian = resources(UKRAINIAN_FILE)

        assertEquals(ONLINE_KEYS, english.keys.intersect(ONLINE_KEYS))
        assertEquals(english.keys, ukrainian.keys)
        for (key in ONLINE_KEYS) {
            val value = english.getValue(key)
            assertEquals("$key changed; update the prompt test too", pinnedEnglish(key), value)
        }
    }

    private fun pinnedEnglish(key: String): String =
        when (key) {
            "search_online_off_sends_position" -> ENGLISH_OFF_SENDS_POSITION
            "search_online_off_no_position" -> ENGLISH_OFF_NO_POSITION
            "search_online_on_sends_position" -> ENGLISH_ON_SENDS_POSITION
            "search_online_on_no_position" -> ENGLISH_ON_NO_POSITION
            "search_online_enable" -> "Enable online search"
            "search_online_badge" -> "Online"
            "search_position_allow" -> "Send my position"
            "search_position_stop" -> "Stop sending my position"
            else -> error("unpinned key $key")
        }

    private fun resources(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val ENGLISH_FILE = "src/main/res/values/strings.xml"
        const val UKRAINIAN_FILE = "src/main/res/values-uk/strings.xml"

        const val ENGLISH_OFF_SENDS_POSITION =
            "Online search is off. Enabling it sends your query text, your IP address and your map position to photon.komoot.io."
        const val ENGLISH_OFF_NO_POSITION =
            "Online search is off. Enabling it sends your query text and your IP address to photon.komoot.io. Your map position is not sent."
        const val ENGLISH_ON_SENDS_POSITION =
            "Online results come from Photon (photon.komoot.io) and OpenStreetMap contributors. Your map position is sent."
        const val ENGLISH_ON_NO_POSITION =
            "Online results come from Photon (photon.komoot.io) and OpenStreetMap contributors. Your map position is not sent."

        val ONLINE_KEYS =
            setOf(
                "search_online_off_sends_position",
                "search_online_off_no_position",
                "search_online_on_sends_position",
                "search_online_on_no_position",
                "search_online_enable",
                "search_online_badge",
                "search_position_allow",
                "search_position_stop",
            )

        val LABELS =
            SearchScreenLabels(
                title = "Search places",
                hint = "Name, street or house number",
                close = "Close search",
                noMatches = "No matches",
                notReady = "No search index for this map",
                loading = "Searching",
                onlineOffSendsPosition = ENGLISH_OFF_SENDS_POSITION,
                onlineOffNoPosition = ENGLISH_OFF_NO_POSITION,
                onlineOnSendsPosition = ENGLISH_ON_SENDS_POSITION,
                onlineOnNoPosition = ENGLISH_ON_NO_POSITION,
                onlineEnable = "Enable online search",
                onlineBadge = "Online",
                positionAllow = "Send my position",
                positionStop = "Stop sending my position",
            )

        val UKRAINIAN_LABELS =
            SearchScreenLabels(
                title = "Пошук місць",
                hint = "Назва, вулиця або номер будинку",
                close = "Закрити пошук",
                noMatches = "Немає збігів",
                notReady = "Для цих карт немає пошукового індексу",
                loading = "Пошук",
                onlineOffSendsPosition =
                    "Онлайн-пошук вимкнено. Його ввімкнення надсилає текст запиту, вашу IP-адресу та позицію на карті до photon.komoot.io.",
                onlineOffNoPosition =
                    "Онлайн-пошук вимкнено. Його ввімкнення надсилає текст запиту та вашу IP-адресу до photon.komoot.io. Позиція на карті не надсилається.",
                onlineOnSendsPosition =
                    "Онлайн-результати надходять від Photon (photon.komoot.io) та учасників OpenStreetMap. Ваша позиція на карті надсилається.",
                onlineOnNoPosition =
                    "Онлайн-результати надходять від Photon (photon.komoot.io) та учасників OpenStreetMap. Ваша позиція на карті не надсилається.",
                onlineEnable = "Увімкнути онлайн-пошук",
                onlineBadge = "Онлайн",
                positionAllow = "Надсилати мою позицію",
                positionStop = "Припинити надсилати мою позицію",
            )
    }
}
