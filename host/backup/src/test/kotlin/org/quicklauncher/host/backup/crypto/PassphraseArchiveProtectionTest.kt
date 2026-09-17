package org.quicklauncher.host.backup.crypto

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.host.backup.archive.PortableBackupArchiveCodec

class PassphraseArchiveProtectionTest {
    @Test
    fun `authenticated envelope round trips and does not retain caller arrays`() {
        var seed = 1
        val protection = PassphraseArchiveProtection { size ->
            ByteArray(size) { (seed++).toByte() }
        }
        val plaintext = "portable archive".toByteArray()
        val passphrase = "correct horse".toCharArray()

        val protected = protection.protect(plaintext, passphrase).protectedBytes()
        plaintext.fill(0)
        passphrase.fill('x')

        assertTrue(PassphraseArchiveProtection.isProtected(protected))
        assertTrue(PassphraseArchiveProtection.isSupportedEnvelope(protected))
        val decoded = protection.unprotect(protected, "correct horse".toCharArray()).plaintextBytes()
        assertArrayEquals("portable archive".toByteArray(), decoded)
    }

    @Test
    fun `deterministic envelope matches independent OpenSSL vector`() {
        var nextByte = 1
        val protection = PassphraseArchiveProtection { size ->
            ByteArray(size) { (nextByte++).toByte() }
        }
        val expected = hex(
            "514c435259505431000100033450100c0102030405060708090a0b0c0d0e0f10" +
                "1112131415161718191a1b1c0000002246147af60c3b9874413e91c00078c334" +
                "3c79630caab4760f71b0bb66f3201880a9d3",
        )

        val actual = protection.protect(
            "independent vector".toByteArray(),
            "vector-passphrase".toCharArray(),
        ).protectedBytes()

        assertArrayEquals(expected, actual)
        assertArrayEquals(
            "independent vector".toByteArray(),
            protection.unprotect(expected, "vector-passphrase".toCharArray()).plaintextBytes(),
        )
    }

    @Test
    fun `wrong passphrase and ciphertext or authenticated-header tampering fail authentication`() {
        val protection = fixedProtection()
        val original = protection.protect(byteArrayOf(1, 2, 3), "secret".toCharArray()).protectedBytes()

        val wrong = protection.unprotect(original, "wrong".toCharArray()) as UnprotectResult.Rejected
        val changedCiphertext = original.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        val changedSalt = original.copyOf().also { it[16] = (it[16].toInt() xor 1).toByte() }

        assertEquals(ProtectionProblem.AUTHENTICATION_FAILED, wrong.problem)
        assertEquals(
            ProtectionProblem.AUTHENTICATION_FAILED,
            (protection.unprotect(changedCiphertext, "secret".toCharArray()) as UnprotectResult.Rejected).problem,
        )
        assertEquals(
            ProtectionProblem.AUTHENTICATION_FAILED,
            (protection.unprotect(changedSalt, "secret".toCharArray()) as UnprotectResult.Rejected).problem,
        )
    }

    @Test
    fun `version derivation parameters and declared lengths fail closed`() {
        val protection = fixedProtection()
        val envelope = protection.protect(byteArrayOf(1, 2, 3), "secret".toCharArray()).protectedBytes()

        assertProblem(envelope.copyOf().also { ByteBuffer.wrap(it).putShort(8, 2) }, ProtectionProblem.UNSUPPORTED_VERSION)
        assertProblem(envelope.copyOf().also { ByteBuffer.wrap(it).putInt(10, 209_999) }, ProtectionProblem.MALFORMED)
        assertProblem(envelope.copyOf().also { it[14] = 15 }, ProtectionProblem.MALFORMED)
        assertProblem(envelope.copyOf().also { it[15] = 13 }, ProtectionProblem.MALFORMED)
        assertProblem(envelope.copyOf().also { ByteBuffer.wrap(it).putInt(44, 15) }, ProtectionProblem.MALFORMED)
        assertProblem(envelope.copyOf().also { ByteBuffer.wrap(it).putInt(44, Int.MAX_VALUE) }, ProtectionProblem.MALFORMED)
    }

    @Test
    fun `truncation at every envelope field and trailing bytes are rejected`() {
        val protection = fixedProtection()
        val envelope = protection.protect(byteArrayOf(1, 2, 3), "secret".toCharArray()).protectedBytes()

        listOf(0, 7, 8, 9, 13, 14, 15, 16, 31, 32, 43, 44, 47, envelope.lastIndex).forEach { size ->
            assertProblem(envelope.copyOf(size), ProtectionProblem.MALFORMED)
        }
        assertProblem(envelope + 0, ProtectionProblem.MALFORMED)
    }

    @Test
    fun `empty passphrases malformed envelopes and invalid randomness fail closed`() {
        val protection = PassphraseArchiveProtection()
        assertEquals(
            ProtectionProblem.EMPTY_PASSPHRASE,
            (protection.protect(byteArrayOf(1), charArrayOf()) as ProtectResult.Rejected).problem,
        )
        assertEquals(
            ProtectionProblem.EMPTY_PASSPHRASE,
            (protection.unprotect(ByteArray(64), charArrayOf()) as UnprotectResult.Rejected).problem,
        )
        assertProblem(ByteArray(32), ProtectionProblem.MALFORMED)
        assertEquals(
            ProtectionProblem.CRYPTO_UNAVAILABLE,
            (PassphraseArchiveProtection { ByteArray(it - 1) }
                .protect(byteArrayOf(1), "secret".toCharArray()) as ProtectResult.Rejected).problem,
        )
        assertFalse(PassphraseArchiveProtection.isProtected(byteArrayOf(1, 2, 3)))
        assertFalse(PassphraseArchiveProtection.isSupportedEnvelope("QLCRYPT1".toByteArray()))
    }

    @Test
    fun `oversized plaintext is rejected before random generation`() {
        var randomRequested = false
        val result = PassphraseArchiveProtection {
            randomRequested = true
            ByteArray(it)
        }.protect(
            ByteArray(PortableBackupArchiveCodec.MAX_ARCHIVE_BYTES + 1),
            "secret".toCharArray(),
        )

        assertEquals(ProtectionProblem.OVERSIZED, (result as ProtectResult.Rejected).problem)
        assertFalse(randomRequested)
    }

    @Test
    fun `oversized envelope is rejected before parsing`() {
        val result = fixedProtection().unprotect(
            ByteArray(PortableBackupArchiveCodec.MAX_ARCHIVE_BYTES + 65),
            "secret".toCharArray(),
        )

        assertEquals(ProtectionProblem.OVERSIZED, (result as UnprotectResult.Rejected).problem)
    }

    @Test
    fun `internal passphrase working copies are cleared and caller arrays remain owned by caller`() {
        val clearedCopies = mutableListOf<CharArray>()
        val protection = PassphraseArchiveProtection(
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            passphraseCopyCleared = { clearedCopies += it.copyOf() },
        )
        val passphrase = "still mine".toCharArray()
        val expectedCallerValue = passphrase.copyOf()

        val envelope = protection.protect(byteArrayOf(4, 5, 6), passphrase).protectedBytes()
        protection.unprotect(envelope, passphrase).plaintextBytes()

        assertArrayEquals(expectedCallerValue, passphrase)
        assertEquals(2, clearedCopies.size)
        assertTrue(clearedCopies.all { copy -> copy.all { it == '\u0000' } })
    }

    private fun fixedProtection() =
        PassphraseArchiveProtection { size -> ByteArray(size) { (it + 7).toByte() } }

    private fun assertProblem(bytes: ByteArray, expected: ProtectionProblem) {
        val result = fixedProtection().unprotect(bytes, "secret".toCharArray())
        assertTrue(result is UnprotectResult.Rejected)
        assertEquals(expected, (result as UnprotectResult.Rejected).problem)
    }

    private fun ProtectResult.protectedBytes(): ByteArray {
        assertTrue(this is ProtectResult.Protected)
        return (this as ProtectResult.Protected).bytesCopy()
    }

    private fun UnprotectResult.plaintextBytes(): ByteArray {
        assertTrue(this is UnprotectResult.Plaintext)
        return (this as UnprotectResult.Plaintext).bytesCopy()
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
