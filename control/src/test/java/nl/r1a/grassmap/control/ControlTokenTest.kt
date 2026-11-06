package nl.r1a.grassmap.control

import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class ControlTokenTest {
    @Test
    fun generatesA256BitToken() {
        val token = ControlTokens.generate()
        val written = ControlTokenFile.write(createTempDir(), token)

        val text = (written as Outcome.Success).value.readText().trim()
        assertEquals(ControlTokens.TOKEN_HEX_LENGTH, text.length)
        assertTrue(text.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(token.matches(text))
    }

    @Test
    fun generatedTokensDiffer() {
        val first = rawValueOf(ControlTokens.generate())
        val second = rawValueOf(ControlTokens.generate())

        assertNotEquals(first, second)
    }

    @Test
    fun acceptsOnlyTheExactToken() {
        val token = ControlTokens.generate()
        val raw = rawValueOf(token)

        assertTrue(token.matches(raw))
        assertFalse(token.matches(raw.dropLast(1)))
        assertFalse(token.matches(raw.uppercase()))
        assertFalse(token.matches(""))
        assertFalse(token.matches(raw + "0"))
    }

    @Test
    fun neverPrintsTheToken() {
        val token = ControlTokens.generate()

        assertFalse(token.toString().contains(rawValueOf(token)))
        assertEquals("ControlToken(<redacted>)", token.toString())
    }

    @Test
    fun refusesATruncatedOrNonHexTokenFile() {
        assertTrue(ControlTokens.fromHex("abc") is Outcome.Failure)
        assertTrue(ControlTokens.fromHex("z".repeat(ControlTokens.TOKEN_HEX_LENGTH)) is Outcome.Failure)
        assertTrue(ControlTokens.fromHex("a".repeat(ControlTokens.TOKEN_HEX_LENGTH)) is Outcome.Success)
    }

    @Test
    fun tokenFileIsOwnerOnly() {
        val directory = createTempDir()
        val file = (ControlTokenFile.write(directory, ControlTokens.generate()) as Outcome.Success).value

        val permissions = Files.getPosixFilePermissions(file.toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions)
    }

    @Test
    fun tokenFileRoundTripsThroughRead() {
        val directory = createTempDir()
        val token = ControlTokens.generate()
        val file = (ControlTokenFile.write(directory, token) as Outcome.Success).value

        val read = ControlTokenFile.read(file)

        assertTrue(read is Outcome.Success)
        assertTrue(token.matches(rawValueOf((read as Outcome.Success).value)))
    }

    @Test
    fun peerGateAdmitsTheAppAndTheAdbTransportOnly() {
        val admitted = ControlPeerGate.admittedUids(appUid = 10123)

        assertTrue(ControlPeerGate.admits(peerUid = 10123, admittedUids = admitted))
        // adb forward proxies through adbd: root on an emulator, shell on a device.
        assertTrue(ControlPeerGate.admits(peerUid = ControlPeerGate.UID_ROOT, admittedUids = admitted))
        assertTrue(ControlPeerGate.admits(peerUid = ControlPeerGate.UID_SHELL, admittedUids = admitted))
        assertFalse(ControlPeerGate.admits(peerUid = 10124, admittedUids = admitted))
        assertFalse(ControlPeerGate.admits(peerUid = 10101, admittedUids = admitted))
        assertFalse(ControlPeerGate.admits(peerUid = -1, admittedUids = admitted))
    }

    private fun rawValueOf(token: ControlToken): String {
        val directory = createTempDir()
        val file = (ControlTokenFile.write(directory, token) as Outcome.Success).value
        return file.readText().trim()
    }

    private fun createTempDir(): File = Files.createTempDirectory("control-token").toFile().also { it.deleteOnExit() }
}
