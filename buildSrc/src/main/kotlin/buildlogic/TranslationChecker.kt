package buildlogic

import java.io.File
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Result of comparing a module's default and Ukrainian string resources.
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
 * Checks that every Android string key exists in English (`values`) and
 * Ukrainian (`values-uk`), that no key exists only in the translation, and that
 * both languages provide a non-blank value. The checker reads XML only; it does
 * not load Android resources, so it runs on the JVM.
 */
object TranslationChecker {
    private const val DEFAULT_DIRECTORY = "values"
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

            val defaultCatalog = readCatalog(defaultFile, errors)
            val ukrainianCatalog = readCatalog(ukrainianFile, errors)

            val allKeys = (defaultCatalog.keys + ukrainianCatalog.keys).toSortedSet()
            keysChecked += allKeys.size

            for (key in allKeys) {
                val inDefault = defaultCatalog.containsKey(key)
                val inUkrainian = ukrainianCatalog.containsKey(key)
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
                if (inDefault && defaultCatalog.getValue(key).isBlank()) {
                    errors += "${module.name}: key '$key' is blank in $DEFAULT_DIRECTORY/strings.xml"
                }
                if (inUkrainian && ukrainianCatalog.getValue(key).isBlank()) {
                    errors += "${module.name}: key '$key' is blank in $UKRAINIAN_DIRECTORY/strings.xml"
                }
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

    private fun readCatalog(
        file: File,
        errors: MutableList<String>,
    ): Map<String, String> {
        if (!file.isFile) {
            errors += "${file.path}: file not found"
            return emptyMap()
        }

        val document =
            try {
                newDocumentBuilder().parse(file)
            } catch (exception: Exception) {
                errors += "${file.path}: cannot parse XML: ${exception.message}"
                return emptyMap()
            }

        for (tag in UNSUPPORTED_TRANSLATABLE_TAGS) {
            val count = document.getElementsByTagName(tag).length
            if (count > 0) {
                errors +=
                    "${file.path}: <$tag> is not checked yet; add it to TranslationChecker " +
                    "before using it"
            }
        }

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

    private fun newDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        return factory.newDocumentBuilder()
    }

    private val UNSUPPORTED_TRANSLATABLE_TAGS = listOf("plurals", "string-array")
}
