package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.service.Strings
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads a module's `strings.xml` on the JVM, including its `<plurals>` blocks,
 * and resolves them with Android's English and Ukrainian quantity rules. Tests
 * use it to assert the text a device would show without Robolectric or an
 * emulator.
 */
internal class TestStringCatalog private constructor(
    private val strings: Map<String, String>,
    private val plurals: Map<String, Map<String, String>>,
    private val language: String,
) {
    fun resolver(): Strings = CatalogStrings()

    /** Merges another module's catalogue into this one, keeping this language. */
    fun merge(other: TestStringCatalog): TestStringCatalog =
        TestStringCatalog(
            strings = strings + other.strings,
            plurals = plurals + other.plurals,
            language = language,
        )

    private inner class CatalogStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = format(strings[key] ?: key, arguments)

        override fun contains(key: String): Boolean = key in strings || key in plurals

        override fun resolveQuantity(
            key: String,
            quantity: Int,
            arguments: List<String>,
        ): String {
            val items = plurals[key] ?: return resolve(key, arguments)
            val template = items[pluralCategory(language, quantity)] ?: items["other"] ?: return key
            return format(template, arguments)
        }

        private fun format(
            template: String,
            arguments: List<String>,
        ): String = if (arguments.isEmpty()) template else String.format(Locale.ROOT, template, *arguments.toTypedArray())
    }

    companion object {
        fun read(path: String): TestStringCatalog {
            val document =
                DocumentBuilderFactory
                    .newInstance()
                    .apply {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                        setFeature("http://xml.org/sax/features/external-general-entities", false)
                        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                        isXIncludeAware = false
                        isExpandEntityReferences = false
                    }.newDocumentBuilder()
                    .parse(File(path))
            val strings = linkedMapOf<String, String>()
            val stringNodes = document.getElementsByTagName("string")
            for (index in 0 until stringNodes.length) {
                val element = stringNodes.item(index) as? Element ?: continue
                strings[element.getAttribute("name")] = unescape(element.textContent.orEmpty()).trim()
            }
            val plurals = linkedMapOf<String, Map<String, String>>()
            val pluralNodes = document.getElementsByTagName("plurals")
            for (index in 0 until pluralNodes.length) {
                val element = pluralNodes.item(index) as? Element ?: continue
                val items = linkedMapOf<String, String>()
                val itemNodes = element.getElementsByTagName("item")
                for (itemIndex in 0 until itemNodes.length) {
                    val item = itemNodes.item(itemIndex) as? Element ?: continue
                    items[item.getAttribute("quantity")] = unescape(item.textContent.orEmpty()).trim()
                }
                plurals[element.getAttribute("name")] = items
            }
            return TestStringCatalog(
                strings = strings,
                plurals = plurals,
                language = if (path.contains("values-uk")) "uk" else "en",
            )
        }

        private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")

        private fun unescape(text: String): String =
            text
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace(UNICODE_ESCAPE) { match ->
                    match.groupValues[1]
                        .toInt(16)
                        .toChar()
                        .toString()
                }
    }
}

/** The quantity category Android selects for a whole number in [language]. */
internal fun pluralCategory(
    language: String,
    quantity: Int,
): String =
    when (language) {
        "uk" -> {
            when {
                quantity % 10 == 1 && quantity % 100 != 11 -> "one"
                quantity % 10 in 2..4 && quantity % 100 !in 12..14 -> "few"
                else -> "many"
            }
        }

        else -> {
            if (quantity == 1) "one" else "other"
        }
    }
