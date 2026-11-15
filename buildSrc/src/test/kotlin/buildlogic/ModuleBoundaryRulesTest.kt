package buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleBoundaryRulesTest {
    private val release =
        mapOf(
            ":plugin-api" to setOf(":plugin-api"),
            ":core" to setOf(":plugin-api"),
            ":plugins:gpx" to setOf(":plugin-api", ":plugin-ui-api"),
            ":plugins:synthetic-data" to setOf(":plugin-api", ":plugin-ui-api"),
            ":app" to setOf(":plugin-api", ":core", ":plugins:gpx"),
        )
    private val debugOnly =
        mapOf(
            ":app" to setOf(":plugins:synthetic-data"),
        )
    private val test =
        mapOf(
            ":app" to setOf(":plugin-api"),
            ":plugins:gpx" to setOf(":plugin-api"),
        )

    private fun violations(
        module: String,
        configuration: String,
        dependency: String,
    ) = ModuleBoundaryRules.violations(
        listOf(ModuleBoundaryRules.Edge(module, configuration, dependency)),
        release,
        debugOnly,
        test,
    )

    @Test
    fun declaredEdgePasses() {
        assertTrue(violations(":plugins:gpx", "implementation", ":plugin-ui-api").isEmpty())
    }

    @Test
    fun pluginTakingCoreFails() {
        val found = violations(":plugins:gpx", "implementation", ":core")

        assertEquals(1, found.size)
        assertEquals(ModuleBoundaryRules.Lane.RELEASE, found.single().lane)
        assertTrue(
            found.single().toString(),
            found.single().toString() == ":plugins:gpx (implementation) -> :core: not in the release allowlist for :plugins:gpx",
        )
    }

    @Test
    fun testEdgeForAnUndeclaredModuleFails() {
        val found = violations(":plugins:gpx", "testImplementation", ":core")

        assertEquals(1, found.size)
        assertEquals(ModuleBoundaryRules.Lane.TEST, found.single().lane)
    }

    @Test
    fun developmentPluginOnAReleaseConfigurationFails() {
        val found = violations(":app", "implementation", ":plugins:synthetic-data")

        assertEquals(1, found.size)
        assertEquals(ModuleBoundaryRules.Lane.RELEASE, found.single().lane)
    }

    @Test
    fun developmentPluginOnADebugConfigurationPasses() {
        assertTrue(violations(":app", "debugImplementation", ":plugins:synthetic-data").isEmpty())
    }

    @Test
    fun unknownConfigurationShapeFails() {
        val found = violations(":plugins:gpx", "kaptTestImplementation", ":core")

        assertEquals(1, found.size)
        assertNull(found.single().lane)
        assertTrue(found.single().reason, found.single().reason.contains("not judged"))
    }

    @Test
    fun testConfigurationsAreRecognisedBySourceSetPrefix() {
        listOf(
            "testImplementation",
            "testDebugImplementation",
            "testRuntimeOnly",
            "testFixturesApi",
            "testFixturesReleaseCompileOnlyApi",
            "androidTestImplementation",
            "debugAndroidTestImplementation",
            "unitTestImplementation",
            "debugUnitTestImplementation",
        ).forEach { name -> assertTrue(name, ModuleBoundaryRules.isTestConfiguration(name)) }
    }

    @Test
    fun namesThatMerelyContainTestAreNotTestConfigurations() {
        listOf(
            "implementation",
            "debugImplementation",
            "releaseImplementation",
            "testbedImplementation",
            "latestVersions",
            "contestImplementation",
            "androidTestbedImplementation",
        ).forEach { name -> assertFalse(name, ModuleBoundaryRules.isTestConfiguration(name)) }
    }
}
