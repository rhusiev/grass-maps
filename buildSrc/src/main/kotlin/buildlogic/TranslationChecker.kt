package buildlogic

import java.io.File
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Result of comparing a module's string and plural resources.
 */
data class TranslationReport(
    val modulesChecked: Int,
    val keysChecked: Int,
    val errors: List<String>,
) {
    val isSuccess: Boolean
        get() = errors.isEmpty()
}

/**
 * Checks that every Android string key and `plurals` name exists in English
 * (`values`) and Ukrainian (`values-uk`), that no key exists only in the
 * translation, and that both languages provide a non-blank value. A
 * `values-en-rGB` file is an override: every entry there must exist in the
 * default file and must differ from it. The checker reads XML only; it does not
 * load Android resources, so it runs on the JVM.
 */
object TranslationChecker {
    private const val DEFAULT_DIRECTORY = "values"
    private const val BRITISH_DIRECTORY = "values-en-rGB"
    private const val UKRAINIAN_DIRECTORY = "values-uk"
    private const val RESOURCE_PATH = "src/main/res"

    fun check(files: Iterable<File>): TranslationReport {
        val errors = mutableListOf<String>()
        val modules = mutableSetOf<File>()

        for (file in files) {
            val module = moduleDirectory(file)
            if (module == null) {
                errors += "String resource is not under <module>/$RESOURCE_PATH: ${file.path}"
                continue
            }
            modules.add(module)
        }

        if (modules.isEmpty()) {
            return TranslationReport(
                modulesChecked = 0,
                keysChecked = 0,
                errors = listOf("No Android string resources found under <module>/$RESOURCE_PATH/values*/strings.xml"),
            )
        }

        var keysChecked = 0
        for (module in modules.sortedBy { it.path }) {
            val defaultFile =
                module.resolve("$RESOURCE_PATH/$DEFAULT_DIRECTORY/strings.xml")
            val ukrainianFile =
                module.resolve("$RESOURCE_PATH/$UKRAINIAN_DIRECTORY/strings.xml")
            val britishFile =
                module.resolve("$RESOURCE_PATH/$BRITISH_DIRECTORY/strings.xml")

            val defaultCatalog = readCatalog(defaultFile, errors)
            val ukrainianCatalog = readCatalog(ukrainianFile, errors)

            val defaultKeys = defaultCatalog.keys
            val ukrainianKeys = ukrainianCatalog.keys
            val allKeys = (defaultKeys + ukrainianKeys).toSortedSet()
            keysChecked += allKeys.size

            for (key in allKeys) {
                val inDefault = key in defaultKeys
                val inUkrainian = key in ukrainianKeys
                if (!inDefault && inUkrainian) {
                    errors +=
                        "${module.name}: key '$key' exists only in " +
                        "$UKRAINIAN_DIRECTORY/strings.xml; add it to $DEFAULT_DIRECTORY/strings.xml"
                }
                if (inDefault && !inUkrainian) {
                    errors +=
                        "${module.name}: key '$key' is missing from " +
                        "$UKRAINIAN_DIRECTORY/strings.xml"
                }
            }
            checkBlankValues(module, defaultCatalog, DEFAULT_DIRECTORY, errors)
            checkBlankValues(module, ukrainianCatalog, UKRAINIAN_DIRECTORY, errors)

            if (britishFile.isFile) {
                val britishCatalog = readCatalog(britishFile, errors)
                checkBritishOverrides(module, defaultCatalog, britishCatalog, errors)
            }
        }

        return TranslationReport(
            modulesChecked = modules.size,
            keysChecked = keysChecked,
            errors = errors,
        )
    }

    private fun moduleDirectory(file: File): File? {
        var directory = file.parentFile
        while (directory != null && directory.name != "src") {
            directory = directory.parentFile
        }
        return directory?.parentFile
    }

    private fun checkBlankValues(
        module: File,
        catalog: Catalog,
        directory: String,
        errors: MutableList<String>,
    ) {
        for ((key, value) in catalog.strings) {
            if (value.isBlank()) {
                errors += "${module.name}: key '$key' is blank in $directory/strings.xml"
            }
        }
    }

    private fun checkBritishOverrides(
        module: File,
        defaultCatalog: Catalog,
        britishCatalog: Catalog,
        errors: MutableList<String>,
    ) {
        for ((key, value) in britishCatalog.strings) {
            val defaultValue = defaultCatalog.strings[key]
            when {
                defaultValue == null ->
                    errors +=
                        "${module.name}: key '$key' in $BRITISH_DIRECTORY/strings.xml " +
                        "does not exist in $DEFAULT_DIRECTORY/strings.xml"
                value.isBlank() ->
                    errors += "${module.name}: key '$key' is blank in $BRITISH_DIRECTORY/strings.xml"
                value == defaultValue ->
                    errors +=
                        "${module.name}: key '$key' in $BRITISH_DIRECTORY/strings.xml is identical " +
                        "to $DEFAULT_DIRECTORY/strings.xml; remove the override"
            }
        }
        for ((key, plural) in britishCatalog.plurals) {
            val defaultPlural = defaultCatalog.plurals[key]
            if (defaultPlural == null) {
                errors +=
                    "${module.name}: plural '$key' in $BRITISH_DIRECTORY/strings.xml " +
                    "does not exist in $DEFAULT_DIRECTORY/strings.xml"
                continue
            }
            for ((quantity, value) in plural.quantities) {
                val defaultValue = defaultPlural.quantities[quantity]
                when {
                    defaultValue == null ->
                        errors +=
                            "${module.name}: plural '$key' in $BRITISH_DIRECTORY/strings.xml " +
                            "has quantity '$quantity' that $DEFAULT_DIRECTORY/strings.xml does not"
                    value == defaultValue ->
                        errors +=
                            "${module.name}: plural '$key' quantity '$quantity' in " +
                            "$BRITISH_DIRECTORY/strings.xml is identical to $DEFAULT_DIRECTORY/strings.xml"
                }
            }
        }
    }

    private fun readCatalog(
        file: File,
        errors: MutableList<String>,
    ): Catalog {
        if (!file.isFile) {
            errors += "${file.path}: file not found"
            return Catalog(emptyMap(), emptyMap())
        }

        val document =
            try {
                newDocumentBuilder().parse(file)
            } catch (exception: Exception) {
                errors += "${file.path}: cannot parse XML: ${exception.message}"
                return Catalog(emptyMap(), emptyMap())
            }

        for (tag in UNSUPPORTED_TRANSLATABLE_TAGS) {
            val count = document.getElementsByTagName(tag).length
            if (count > 0) {
                errors +=
                    "${file.path}: <$tag> is not checked yet; add it to TranslationChecker " +
                    "before using it"
            }
        }

        return Catalog(
            strings = readStrings(file, document, errors),
            plurals = readPlurals(file, document, errors),
        )
    }

    private fun readStrings(
        file: File,
        document: Document,
        errors: MutableList<String>,
    ): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        val nodes = document.getElementsByTagName("string")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val name = element.getAttribute("name").trim()
            if (name.isEmpty()) {
                errors += "${file.path}: <string> without a name"
                continue
            }
            if (entries.containsKey(name)) {
                errors += "${file.path}: duplicate key '$name'"
            }
            entries[name] = element.textContent.orEmpty().trim()
        }
        return entries
    }

    private fun readPlurals(
        file: File,
        document: Document,
        errors: MutableList<String>,
    ): Map<String, Plural> {
        val plurals = linkedMapOf<String, Plural>()
        val nodes = document.getElementsByTagName("plurals")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val name = element.getAttribute("name").trim()
            if (name.isEmpty()) {
                errors += "${file.path}: <plurals> without a name"
                continue
            }
            if (plurals.containsKey(name)) {
                errors += "${file.path}: duplicate plural '$name'"
                continue
            }
            val quantities = linkedMapOf<String, String>()
            val items = element.getElementsByTagName("item")
            for (itemIndex in 0 until items.length) {
                val item = items.item(itemIndex) as? Element ?: continue
                val quantity = item.getAttribute("quantity").trim()
                if (quantity.isEmpty()) {
                    errors += "${file.path}: plural '$name' has an <item> without a quantity"
                    continue
                }
                if (quantities.containsKey(quantity)) {
                    errors += "${file.path}: plural '$name' has duplicate quantity '$quantity'"
                    continue
                }
                val value = item.textContent.orEmpty().trim()
                if (value.isEmpty()) {
                    errors += "${file.path}: plural '$name' has a blank '$quantity' item"
                    continue
                }
                quantities[quantity] = value
            }
            if (quantities.isEmpty()) {
                errors += "${file.path}: plural '$name' has no items"
                continue
            }
            plurals[name] = Plural(quantities)
        }
        return plurals
    }

    private fun newDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        return factory.newDocumentBuilder()
    }

    private val UNSUPPORTED_TRANSLATABLE_TAGS = listOf("string-array")

    /** One resource catalogue: every `<string>` and every `<plurals>` block. */
    private data class Catalog(
        val strings: Map<String, String>,
        val plurals: Map<String, Plural>,
    ) {
        val keys: Set<String>
            get() = strings.keys + plurals.keys
    }

    /** One plural's text per quantity category. */
    private data class Plural(
        val quantities: Map<String, String>,
    )
}
