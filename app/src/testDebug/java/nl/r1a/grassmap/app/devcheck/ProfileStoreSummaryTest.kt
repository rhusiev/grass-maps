package nl.r1a.grassmap.app.devcheck

import java.io.File
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileStoreSummaryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val secretValue = "super-secret-value"

    private val text = """{"schema_version":2,"profiles":{"walk":{"a":"$secretValue","b":1},"bike":{"a":2}}}"""

    @Test
    fun countsKeysPerProfile() {
        val summary = ProfileStoreSummary.summarize(text)!!
        assertEquals(2, summary.schemaVersion)
        assertEquals(mapOf("walk" to 2, "bike" to 1), summary.keysPerProfile)
    }

    @Test
    fun rejectsTextThatIsNotAProfileFile() {
        assertNull(ProfileStoreSummary.summarize("not json"))
        assertNull(ProfileStoreSummary.summarize("""{"other":1}"""))
    }

    @Test
    fun theStepNeverCarriesAValue() {
        File(folder.root, "profiles").mkdirs()
        File(folder.root, "profiles/alerts.json").writeText(text)
        File(folder.root, "settings").mkdirs()
        File(folder.root, "settings/alerts.json").writeText("{}")

        val outcome = ProfileStoreSummary.run(folder.root)

        assertFalse(outcome.numbers.toString().contains(secretValue))
        assertEquals("present", outcome.numbers.getValue("legacy_alerts").jsonPrimitive.content)
        assertEquals("retired", outcome.numbers.getValue("legacy_routing_options").jsonPrimitive.content)
        assertEquals("absent", outcome.numbers.getValue("catalog").jsonPrimitive.content)
        assertEquals("2", outcome.numbers.getValue("alerts").jsonObject.getValue("profiles").jsonPrimitive.content)
    }
}
