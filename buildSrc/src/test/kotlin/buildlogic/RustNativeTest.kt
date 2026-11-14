package buildlogic

import java.io.File
import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RustNativeTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun matchingEnvironmentNdkIsAccepted() {
        val ndk = ndk("ndk", RustNative.NDK_VERSION)

        val resolved = RustNative.resolveNdk(RustNative.NDK_VERSION, null, ndk.absolutePath, null)

        assertEquals(ndk.canonicalFile, resolved.canonicalFile)
    }

    @Test
    fun mismatchedEnvironmentNdkNamesRequiredAndFound() {
        val ndk = ndk("ndk", "27.2.12479018")

        val failure =
            assertThrows(GradleException::class.java) {
                RustNative.resolveNdk(RustNative.NDK_VERSION, null, ndk.absolutePath, null)
            }

        assertTrue(failure.message, failure.message!!.contains(RustNative.NDK_VERSION))
        assertTrue(failure.message, failure.message!!.contains("27.2.12479018"))
    }

    @Test
    fun mismatchedEnvironmentDoesNotFallBackToSdk() {
        val sdk = sdk("sdk", RustNative.NDK_VERSION)
        val ndk = ndk("ndk", "27.2.12479018")

        val failure =
            assertThrows(GradleException::class.java) {
                RustNative.resolveNdk(RustNative.NDK_VERSION, sdk, ndk.absolutePath, null)
            }

        assertTrue(failure.message, failure.message!!.contains("27.2.12479018"))
    }

    @Test
    fun environmentWithoutSourcePropertiesNamesRequiredAndFound() {
        val ndk = ndk("ndk", null)

        val failure =
            assertThrows(GradleException::class.java) {
                RustNative.resolveNdk(RustNative.NDK_VERSION, null, ndk.absolutePath, null)
            }

        assertTrue(failure.message, failure.message!!.contains(RustNative.NDK_VERSION))
        assertTrue(failure.message, failure.message!!.contains("source.properties"))
    }

    @Test
    fun sdkPinnedNdkIsAcceptedWhenANewerOneIsInstalled() {
        val sdk = sdk("sdk", RustNative.NDK_VERSION)
        ndk(File(sdk, "ndk"), "29.0.14206865", name = "29.0.14206865")

        val resolved = RustNative.resolveNdk(RustNative.NDK_VERSION, sdk, null, null)

        assertEquals(File(sdk, "ndk/${RustNative.NDK_VERSION}").canonicalFile, resolved.canonicalFile)
    }

    @Test
    fun missingPinnedNdkListsInstalledVersions() {
        val sdk = sdk("sdk", "27.2.12479018")

        val failure =
            assertThrows(GradleException::class.java) {
                RustNative.resolveNdk(RustNative.NDK_VERSION, sdk, null, null)
            }

        assertTrue(failure.message, failure.message!!.contains(RustNative.NDK_VERSION))
        assertTrue(failure.message, failure.message!!.contains("27.2.12479018"))
    }

    @Test
    fun missingSdkNamesRequiredVersion() {
        val failure =
            assertThrows(GradleException::class.java) {
                RustNative.resolveNdk(RustNative.NDK_VERSION, null, null, null)
            }

        assertTrue(failure.message, failure.message!!.contains(RustNative.NDK_VERSION))
    }

    private fun sdk(
        parent: String,
        revision: String,
    ): File {
        val sdk = temporary.newFolder(parent)
        ndk(File(sdk, "ndk"), revision, name = revision)
        return sdk
    }

    private fun ndk(
        name: String,
        revision: String?,
    ): File = ndk(temporary.root, revision, name)

    private fun ndk(
        parent: File,
        revision: String?,
        name: String = "ndk",
    ): File {
        val ndk = File(parent, name)
        ndk.mkdirs()
        if (revision != null) {
            File(ndk, "source.properties").writeText(
                "Pkg.Desc = Android NDK\nPkg.Revision = $revision\n",
            )
        }
        return ndk
    }
}
