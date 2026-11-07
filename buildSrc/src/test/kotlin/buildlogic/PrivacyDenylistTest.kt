package buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyDenylistTest {
    private val denylist =
        PrivacyDenylist.parse(
            """
            # synthetic fixture: one group and one module rule
            group com.google.android.gms

            module com.adjust.sdk:adjust-android
            """.trimIndent(),
            "fixture",
        )

    @Test
    fun playServicesArtifactFromGroupRuleFails() {
        val violations = denylist.violations(listOf("com.google.android.gms:play-services-location:21.4.0"))

        assertEquals(1, violations.size)
        assertEquals("group com.google.android.gms", violations.single().rule)
    }

    @Test
    fun playServicesAdsArtifactFails() {
        val violations = denylist.violations(listOf("com.google.android.gms:play-services-ads:23.6.0"))

        assertFalse(violations.isEmpty())
    }

    @Test
    fun trackerArtifactFromModuleRuleFails() {
        val violations = denylist.violations(listOf("com.adjust.sdk:adjust-android:5.0.2"))

        assertEquals(1, violations.size)
        assertEquals("module com.adjust.sdk:adjust-android", violations.single().rule)
    }

    @Test
    fun sameArtifactNameInAnotherGroupPasses() {
        val violations = denylist.violations(listOf("org.example:adjust-android:1.0.0"))

        assertTrue(violations.toString(), violations.isEmpty())
    }

    @Test
    fun unrelatedDependenciesPass() {
        val violations =
            denylist.violations(
                listOf(
                    "org.jetbrains.kotlin:kotlin-stdlib:2.4.21",
                    "org.maplibre.gl:android-sdk:11.8.0",
                    "androidx.activity:activity-compose:1.13.0",
                ),
            )

        assertTrue(violations.toString(), violations.isEmpty())
    }

    @Test
    fun emptyDenylistIsRejected() {
        val error =
            runCatching { PrivacyDenylist.parse("# only comments\n\n", "fixture") }
                .exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun malformedRuleNamesItsLine() {
        val error =
            runCatching { PrivacyDenylist.parse("deny everything\n", "fixture") }
                .exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("fixture:1"))
    }
}
