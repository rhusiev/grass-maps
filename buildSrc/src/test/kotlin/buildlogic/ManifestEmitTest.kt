package buildlogic

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManifestEmitTest {
    @Test
    fun missingCapabilitiesEmitAnEmptyJsonObject() {
        assertEquals("JsonObject(emptyMap())", providerCapabilities(null))
    }

    @Test
    fun nonObjectCapabilitiesEmitAnEmptyJsonObject() {
        assertEquals("JsonObject(emptyMap())", providerCapabilities("on-demand"))
    }

    @Test
    fun capabilitiesObjectIsEmittedAsJson() {
        assertEquals(
            "JsonObject(mapOf(\"mode\" to JsonPrimitive(\"on-demand\")))",
            providerCapabilities(mapOf("mode" to "on-demand")),
        )
    }

    @Test
    fun unknownDispatcherFailsTheGenerator() {
        assertThrows(GradleException::class.java) {
            commandDispatcher("worker", "plugin.json")
        }
    }

    @Test
    fun unknownLocationFailsTheGenerator() {
        assertThrows(GradleException::class.java) {
            locationNeed("always", "plugin.json")
        }
    }

    @Test
    fun unknownScopeFailsTheGenerator() {
        assertThrows(GradleException::class.java) {
            settingScope("device", "plugin.json")
        }
    }

    @Test
    fun nonStringEnumValuesFailTheGenerator() {
        assertThrows(GradleException::class.java) {
            commandDispatcher(5, "plugin.json")
        }
        assertThrows(GradleException::class.java) {
            locationNeed(true, "plugin.json")
        }
        assertThrows(GradleException::class.java) {
            settingScope(5, "plugin.json")
        }
    }

    @Test
    fun nonBooleanUndoableFailsTheGenerator() {
        assertThrows(GradleException::class.java) {
            booleanFlag("true", "undoable", "test.command", "plugin.json")
        }
    }

    @Test
    fun nonBooleanControlAllowlistFailsTheGenerator() {
        assertThrows(GradleException::class.java) {
            booleanFlag(1, "allowlistedForControl", "test.command", "plugin.json")
        }
    }

    @Test
    fun nonStringRequiresSwitchFailsTheGenerator() {
        assertThrows(GradleException::class.java) {
            switchId(5, "test.command", "plugin.json")
        }
    }

    @Test
    fun absentCommandFlagsUseTheDefaults() {
        assertEquals(false, booleanFlag(null, "undoable", "test.command", "plugin.json"))
        assertEquals("null", switchId(null, "test.command", "plugin.json"))
    }

    @Test
    fun knownEnumValuesMapToTheGeneratedEnums() {
        assertEquals("CommandDispatcher.IO", commandDispatcher("io", "plugin.json"))
        assertEquals("LocationNeed.WHILE_IN_USE", locationNeed("while_in_use", "plugin.json"))
        assertEquals("SettingScope.PROFILE", settingScope("profile", "plugin.json"))
    }
}
