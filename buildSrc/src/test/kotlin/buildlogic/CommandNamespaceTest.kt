package buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandNamespaceTest {
    @Test
    fun aManifestCommandInTheReservedNamespaceIsRejected() {
        val errors =
            reservedNamespaceErrors(
                "plugin.json",
                listOf(mapOf("id" to "core.map.zoom_in")),
            )

        assertEquals(
            listOf("plugin.json: command id 'core.map.zoom_in' uses the reserved 'core' namespace"),
            errors,
        )
    }

    @Test
    fun exactlyCoreIsRejected() {
        val errors = reservedNamespaceErrors("plugin.json", listOf(mapOf("id" to "core")))

        assertEquals(
            listOf("plugin.json: command id 'core' uses the reserved 'core' namespace"),
            errors,
        )
    }

    @Test
    fun aPluginNamespacedCommandIsAccepted() {
        val errors =
            reservedNamespaceErrors(
                "plugin.json",
                listOf(
                    mapOf("id" to "example.set_value"),
                    mapOf("id" to "style.set_variant"),
                ),
            )

        assertTrue(errors.isEmpty())
    }

    @Test
    fun aLookalikePrefixIsAccepted() {
        val errors = reservedNamespaceErrors("plugin.json", listOf(mapOf("id" to "corex.set_value")))

        assertTrue(errors.isEmpty())
    }
}
