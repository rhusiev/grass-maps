package buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CheckReleaseSigningTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val backupPath = "/hdd/drive/" + "Recover"
    private val keystoreName = "grassmaps-" + "release.jks"

    @Test
    fun theDebugConfigFailsWhenTheSigningVariablesArePresent() {
        val findings =
            releaseSigningFindings(ReleaseSigning.State.COMPLETE, "debug", emptyList())

        assertEquals(1, findings.size)
        assertTrue(findings.single().contains("debug"))
        assertTrue(findings.single().contains(ReleaseSigning.CONFIG_NAME))
    }

    @Test
    fun theReleaseConfigPassesWhenTheSigningVariablesArePresent() {
        val findings =
            releaseSigningFindings(
                ReleaseSigning.State.COMPLETE,
                ReleaseSigning.CONFIG_NAME,
                emptyList(),
            )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun aSigningConfigFailsWhenTheSigningVariablesAreAbsent() {
        val findings = releaseSigningFindings(ReleaseSigning.State.ABSENT, "debug", emptyList())

        assertEquals(1, findings.size)
        assertTrue(findings.single().contains("must stay unsigned"))
    }

    @Test
    fun noSigningConfigPassesWhenTheSigningVariablesAreAbsent() {
        assertTrue(releaseSigningFindings(ReleaseSigning.State.ABSENT, null, emptyList()).isEmpty())
    }

    @Test
    fun anIncompleteEnvironmentNamesTheMissingVariables() {
        val findings =
            releaseSigningFindings(
                ReleaseSigning.State.PARTIAL,
                null,
                listOf(ReleaseSigning.KEY_PASSWORD),
            )

        assertEquals(1, findings.size)
        assertTrue(findings.single().contains(ReleaseSigning.KEY_PASSWORD))
    }

    @Test
    fun theStateIsCompleteOnlyWhenAllFourVariablesAreSet() {
        val complete =
            mapOf(
                ReleaseSigning.KEYSTORE to "keystore",
                ReleaseSigning.KEYSTORE_PASSWORD to "store password",
                ReleaseSigning.KEY_ALIAS to "alias",
                ReleaseSigning.KEY_PASSWORD to "key password",
            )
        val partial = complete - ReleaseSigning.KEY_PASSWORD

        assertEquals(ReleaseSigning.State.COMPLETE, ReleaseSigning.state(complete))
        assertEquals(ReleaseSigning.State.PARTIAL, ReleaseSigning.state(partial))
        assertEquals(
            ReleaseSigning.State.ABSENT,
            ReleaseSigning.state(complete.mapValues { "" }),
        )
        assertEquals(listOf(ReleaseSigning.KEY_PASSWORD), ReleaseSigning.missing(partial))
    }

    @Test
    fun aCommittedKeyPathFails() {
        val file =
            write(
                "app/build.gradle.kts",
                """storeFile = file("$backupPath/$keystoreName")""",
            )

        val findings = committedSecretFindings(temporary.root, listOf(file), "")

        assertTrue(findings.any { it.contains("key path") })
        assertTrue(findings.all { it.contains("app/build.gradle.kts") })
        assertFalse(findings.any { it.contains(backupPath) })
    }

    @Test
    fun aHardcodedKeystorePathFailsWithoutPrintingIt() {
        val file =
            write(
                "app/build.gradle.kts",
                """storeFile = file("/keys/other.jks")""",
            )

        val findings = committedSecretFindings(temporary.root, listOf(file), "")

        assertEquals(1, findings.size)
        assertTrue(findings.single().contains("keystore path"))
        assertFalse(findings.any { it.contains("/keys/other.jks") })
    }

    @Test
    fun aCommittedKeystoreFileFails() {
        val file = temporary.newFile("release.jks")

        val findings = committedSecretFindings(temporary.root, listOf(file), "")

        assertEquals(1, findings.size)
        assertTrue(findings.single().contains("keystore file"))
        assertTrue(findings.single().contains("release.jks"))
    }

    @Test
    fun aHardcodedPasswordAssignmentFailsWithoutPrintingIt() {
        val file =
            write(
                "app/build.gradle.kts",
                """storePassword = "hunter2"""",
            )

        val findings = committedSecretFindings(temporary.root, listOf(file), "")

        assertEquals(1, findings.size)
        assertTrue(findings.single().contains("storePassword"))
        assertFalse(findings.any { it.contains("hunter2") })
    }

    @Test
    fun aCommittedPassphraseFileFails() {
        val file = temporary.newFile("release.pass")

        val findings = committedSecretFindings(temporary.root, listOf(file), "")

        assertEquals(1, findings.size)
        assertTrue(findings.single().contains("passphrase file"))
        assertTrue(findings.single().contains("release.pass"))
    }

    @Test
    fun aSingleQuotedPasswordAssignmentFails() {
        val file = write("build.gradle", "keyPassword = 'hunter2'\n")

        val findings = committedSecretFindings(temporary.root, listOf(file), "")

        assertEquals(1, findings.size)
        assertTrue(findings.single().contains("keyPassword"))
        assertFalse(findings.any { it.contains("hunter2") })
    }

    @Test
    fun anInterpolatedPasswordAssignmentIsNotALiteral() {
        val file =
            write(
                "app/build.gradle.kts",
                """storePassword = "${'$'}releasePassword"""",
            )

        assertTrue(committedSecretFindings(temporary.root, listOf(file), "").isEmpty())
    }

    @Test
    fun aPasswordPropertyFails() {
        val file =
            write(
                "local.properties",
                "grassmaps.keystore.password=hunter2\n",
            )

        val findings = committedSecretFindings(temporary.root, listOf(file), "")

        assertEquals(1, findings.size)
        assertTrue(findings.single().contains("password property"))
        assertFalse(findings.any { it.contains("hunter2") })
    }

    @Test
    fun aCommittedPassphraseIsNamedWithoutPrintingIt() {
        val passphrase = "correct horse battery staple"
        val file = write("notes.md", "The passphrase is $passphrase\n")

        val findings = committedSecretFindings(temporary.root, listOf(file), passphrase)

        assertEquals(1, findings.size)
        assertTrue(findings.single().contains("notes.md"))
        assertFalse(findings.any { it.contains(passphrase) })
    }

    @Test
    fun aCleanTreeWithVariableNamesHasNoFindings() {
        val file =
            write(
                "README.md",
                "Set ${ReleaseSigning.KEYSTORE} and ${ReleaseSigning.KEYSTORE_PASSWORD} in the environment.\n",
            )

        assertTrue(committedSecretFindings(temporary.root, listOf(file), "").isEmpty())
    }

    private fun write(
        relativePath: String,
        content: String,
    ) = temporary.root.resolve(relativePath).apply {
        parentFile.mkdirs()
        writeText(content)
    }
}
