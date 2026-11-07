package buildlogic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestPrivacyRulesTest {
    @Test
    fun cleanManifestPasses() {
        val findings = ManifestPrivacyRules.check(cleanManifest(), MAIN_ACTIVITY)

        assertTrue(findings.toString(), findings.isEmpty())
    }

    @Test
    fun backgroundLocationPermissionFails() {
        val findings =
            ManifestPrivacyRules.check(
                manifest(
                    permissions = "<uses-permission android:name=\"android.permission.ACCESS_BACKGROUND_LOCATION\" />",
                ),
                MAIN_ACTIVITY,
            )

        assertTrue(findings.any { it.contains("ACCESS_BACKGROUND_LOCATION") })
    }

    @Test
    fun foregroundLocationPermissionPasses() {
        val findings =
            ManifestPrivacyRules.check(
                manifest(
                    permissions = "<uses-permission android:name=\"android.permission.ACCESS_FINE_LOCATION\" />",
                ),
                MAIN_ACTIVITY,
            )

        assertTrue(findings.toString(), findings.isEmpty())
    }

    @Test
    fun authenticateAccountsPermissionFails() {
        val findings =
            ManifestPrivacyRules.check(
                manifest(
                    permissions = "<uses-permission android:name=\"android.permission.AUTHENTICATE_ACCOUNTS\" />",
                ),
                MAIN_ACTIVITY,
            )

        assertTrue(findings.any { it.contains("AUTHENTICATE_ACCOUNTS") })
    }

    @Test
    fun manageAccountsPermissionFails() {
        val findings =
            ManifestPrivacyRules.check(
                manifest(
                    permissions = "<uses-permission android:name=\"android.permission.MANAGE_ACCOUNTS\" />",
                ),
                MAIN_ACTIVITY,
            )

        assertTrue(findings.any { it.contains("MANAGE_ACCOUNTS") })
    }

    @Test
    fun accountAuthenticatorServiceFails() {
        val findings =
            ManifestPrivacyRules.check(
                manifest(
                    applicationBody =
                        """
                        <service android:name="nl.r1a.grassmap.app.AuthenticatorService">
                            <intent-filter>
                                <action android:name="android.accounts.AccountAuthenticator" />
                            </intent-filter>
                        </service>
                        """.trimIndent(),
                ),
                MAIN_ACTIVITY,
            )

        assertTrue(findings.any { it.contains("AuthenticatorService") && it.contains("AccountAuthenticator") })
    }

    @Test
    fun missingPortraitLockFails() {
        val findings =
            ManifestPrivacyRules.check(
                cleanManifest(orientation = null),
                MAIN_ACTIVITY,
            )

        assertTrue(findings.any { it.contains("screenOrientation") })
    }

    @Test
    fun wrongOrientationFails() {
        val findings =
            ManifestPrivacyRules.check(
                cleanManifest(orientation = "landscape"),
                MAIN_ACTIVITY,
            )

        assertTrue(findings.any { it.contains("landscape") })
    }

    @Test
    fun missingMainActivityFails() {
        val findings = ManifestPrivacyRules.check(manifest(), "nl.r1a.grassmap.app.Missing")

        assertTrue(findings.any { it.contains("Missing") && it.contains("missing") })
    }

    @Test
    fun backupsDisabledFails() {
        val findings = ManifestPrivacyRules.check(cleanManifest(allowBackup = "false"), MAIN_ACTIVITY)

        assertTrue(findings.any { it.contains("allowBackup") })
    }

    @Test
    fun backupsUnsetFails() {
        val findings = ManifestPrivacyRules.check(cleanManifest(allowBackup = null), MAIN_ACTIVITY)

        assertTrue(findings.any { it.contains("allowBackup") })
    }

    @Test
    fun allowBackupTrueIsRequiredNotJustPresent() {
        val findings = ManifestPrivacyRules.check(cleanManifest(allowBackup = "true"), MAIN_ACTIVITY)

        assertFalse(findings.any { it.contains("allowBackup") })
    }

    private fun cleanManifest(
        orientation: String? = "portrait",
        allowBackup: String? = "true",
    ): String = manifest(orientation = orientation, allowBackup = allowBackup)

    private fun manifest(
        permissions: String = "",
        applicationBody: String = "",
        orientation: String? = "portrait",
        allowBackup: String? = "true",
    ): String {
        val orientationAttribute = orientation?.let { " android:screenOrientation=\"$it\"" }.orEmpty()
        val backupAttribute = allowBackup?.let { " android:allowBackup=\"$it\"" }.orEmpty()
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"nl.r1a.grassmap\">")
            if (permissions.isNotEmpty()) appendLine("    $permissions")
            appendLine("    <application$backupAttribute>")
            if (applicationBody.isNotEmpty()) appendLine(applicationBody.prependIndent("        "))
            appendLine("        <activity android:name=\"$MAIN_ACTIVITY\" android:exported=\"true\"$orientationAttribute />")
            appendLine("    </application>")
            appendLine("</manifest>")
        }
    }

    private companion object {
        const val MAIN_ACTIVITY = "nl.r1a.grassmap.app.MainActivity"
    }
}
