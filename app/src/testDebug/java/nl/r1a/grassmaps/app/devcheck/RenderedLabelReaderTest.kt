package nl.r1a.grassmaps.app.devcheck

import org.junit.Assert.assertEquals
import org.junit.Test

private val LABELS_JSON =
    """[{"layer":"place-town","text":"Kakhovka","names":{"name":"Каховка","name:en":"Kakhovka"}},
        |{"layer":"poi-shop","text":"ATB","names":{"name:en":"ATB"}},
        |{"layer":"road-name","text":"Soborna","names":{"name":"Соборна"}},
        |{"layer":"place-town","text":"","names":{"name":" "}}]
    """.trimMargin()

class RenderedLabelReaderTest {
    @Test
    fun `the command's array is read into features`() {
        val features = parseLabels(LABELS_JSON)

        assertEquals(4, features.size)
        assertEquals("place-town", features.first().layer)
        assertEquals("Kakhovka", features.first().text)
        assertEquals("Kakhovka", features.first().names["name:en"])
    }

    @Test
    fun `a body that is not the command's array reads as nothing`() {
        assertEquals(emptyList<LabelFeature>(), parseLabels("not json"))
        assertEquals(emptyList<LabelFeature>(), parseLabels("{}"))
    }

    @Test
    fun `a place name comes before a road name`() {
        assertEquals(listOf("Kakhovka", "ATB", "Soborna"), placeNames(parseLabels(LABELS_JSON)))
    }
}
