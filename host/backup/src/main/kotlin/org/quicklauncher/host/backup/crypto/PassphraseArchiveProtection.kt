package org.quicklauncher.host.backup.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.quicklauncher.host.backup.archive.PortableBackupArchiveCodec

enum class ProtectionProblem {
    EMPTY_PASSPHRASE,
    OVERSIZED,
    MALFORMED,
    UNSUPPORTED_VERSION,
    AUTHENTICATION_FAILED,
    CRYPTO_UNAVAILABLE,
}

sealed interface ProtectResult {
    class Protected(bytes: ByteArray) : ProtectResult {
        private val bytes = bytes.copyOf()
        fun bytesCopy(): ByteArray = bytes.copyOf()
    }

    data class Rejected(val problem: ProtectionProblem) : ProtectResult
}

sealed interface UnprotectResult {
    class Plaintext(bytes: ByteArray) : UnprotectResult {
        private val bytes = bytes.copyOf()
        fun bytesCopy(): ByteArray = bytes.copyOf()
    }

    data class Rejected(val problem: ProtectionProblem) : UnprotectResult
}

/** AES-256-GCM envelope with PBKDF2-HMAC-SHA256 derivation and authenticated parameters. */
class PassphraseArchiveProtection internal constructor(
    private val passphraseCopyCleared: (CharArray) -> Unit = {},
    private val randomBytes: (Int) -> ByteArray,
) {
    constructor() : this(randomBytes = { size -> ByteArray(size).also(SecureRandom()::nextBytes) })

    fun protect(plaintext: ByteArray, passphrase: CharArray): ProtectResult {
        if (passphrase.isEmpty()) return ProtectResult.Rejected(ProtectionProblem.EMPTY_PASSPHRASE)
        if (plaintext.size > PortableBackupArchiveCodec.MAX_ARCHIVE_BYTES) {
            return ProtectResult.Rejected(ProtectionProblem.OVERSIZED)
        }
        val salt = randomBytes(SALT_BYTES)
        val nonce = randomBytes(NONCE_BYTES)
        if (salt.size != SALT_BYTES || nonce.size != NONCE_BYTES) {
            salt.fill(0)
            nonce.fill(0)
            return ProtectResult.Rejected(ProtectionProblem.CRYPTO_UNAVAILABLE)
        }
        return try {
            val keyBytes = deriveFromWorkingCopy(passphrase, salt) ?: return ProtectResult.Rejected(
                ProtectionProblem.CRYPTO_UNAVAILABLE,
            )
            try {
                val authenticatedHeader = authenticatedHeader(salt, nonce)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(authenticatedHeader)
                val ciphertext = cipher.doFinal(plaintext)
                val sink = ByteArrayOutputStream(authenticatedHeader.size + 4 + ciphertext.size)
                DataOutputStream(sink).use { output ->
                    output.write(authenticatedHeader)
                    output.writeInt(ciphertext.size)
                    output.write(ciphertext)
                }
                ProtectResult.Protected(sink.toByteArray())
            } finally {
                keyBytes.fill(0)
            }
        } catch (_: GeneralSecurityException) {
            ProtectResult.Rejected(ProtectionProblem.CRYPTO_UNAVAILABLE)
        } finally {
            salt.fill(0)
            nonce.fill(0)
        }
    }

    fun unprotect(envelope: ByteArray, passphrase: CharArray): UnprotectResult {
        if (passphrase.isEmpty()) return UnprotectResult.Rejected(ProtectionProblem.EMPTY_PASSPHRASE)
        if (envelope.size > MAX_ENVELOPE_BYTES) return UnprotectResult.Rejected(ProtectionProblem.OVERSIZED)
        return try {
            val source = ByteArrayInputStream(envelope)
            val input = DataInputStream(source)
            val observedMagic = ByteArray(MAGIC.size).also(input::readFully)
            if (!observedMagic.contentEquals(MAGIC)) {
                return UnprotectResult.Rejected(ProtectionProblem.MALFORMED)
            }
            if (input.readUnsignedShort() != VERSION) {
                return UnprotectResult.Rejected(ProtectionProblem.UNSUPPORTED_VERSION)
            }
            val iterations = input.readInt()
            val saltLength = input.readUnsignedByte()
            val nonceLength = input.readUnsignedByte()
            if (iterations != ITERATIONS || saltLength != SALT_BYTES || nonceLength != NONCE_BYTES) {
                return UnprotectResult.Rejected(ProtectionProblem.MALFORMED)
            }
            val salt = ByteArray(saltLength).also(input::readFully)
            val nonce = ByteArray(nonceLength).also(input::readFully)
            val ciphertextLength = input.readInt()
            if (ciphertextLength < TAG_BYTES || ciphertextLength != source.available()) {
                salt.fill(0)
                nonce.fill(0)
                return UnprotectResult.Rejected(ProtectionProblem.MALFORMED)
            }
            val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
            try {
                val keyBytes = deriveFromWorkingCopy(passphrase, salt) ?: return UnprotectResult.Rejected(
                    ProtectionProblem.CRYPTO_UNAVAILABLE,
                )
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                    cipher.updateAAD(authenticatedHeader(salt, nonce))
                    val plaintext = cipher.doFinal(ciphertext)
                    if (plaintext.size > PortableBackupArchiveCodec.MAX_ARCHIVE_BYTES) {
                        plaintext.fill(0)
                        UnprotectResult.Rejected(ProtectionProblem.OVERSIZED)
                    } else {
                        UnprotectResult.Plaintext(plaintext)
                    }
                } finally {
                    keyBytes.fill(0)
                }
            } catch (_: AEADBadTagException) {
                UnprotectResult.Rejected(ProtectionProblem.AUTHENTICATION_FAILED)
            } catch (_: GeneralSecurityException) {
                UnprotectResult.Rejected(ProtectionProblem.CRYPTO_UNAVAILABLE)
            } finally {
                salt.fill(0)
                nonce.fill(0)
                ciphertext.fill(0)
            }
        } catch (_: EOFException) {
            UnprotectResult.Rejected(ProtectionProblem.MALFORMED)
        }
    }

    private fun deriveFromWorkingCopy(passphrase: CharArray, salt: ByteArray): ByteArray? {
        val workingCopy = passphrase.copyOf()
        return try {
            derive(workingCopy, salt)
        } finally {
            workingCopy.fill('\u0000')
            passphraseCopyCleared(workingCopy)
        }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray): ByteArray? {
        val specification = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded
        } catch (_: GeneralSecurityException) {
            null
        } finally {
            specification.clearPassword()
        }
    }

    private fun authenticatedHeader(salt: ByteArray, nonce: ByteArray): ByteArray {
        val sink = ByteArrayOutputStream(HEADER_BYTES)
        DataOutputStream(sink).use { output ->
            output.write(MAGIC)
            output.writeShort(VERSION)
            output.writeInt(ITERATIONS)
            output.writeByte(salt.size)
            output.writeByte(nonce.size)
            output.write(salt)
            output.write(nonce)
        }
        return sink.toByteArray()
    }

    companion object {
        const val ITERATIONS = 210_000
        private const val VERSION = 1
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val KEY_BITS = 256
        private const val HEADER_BYTES = 8 + 2 + 4 + 1 + 1 + SALT_BYTES + NONCE_BYTES
        private const val MAX_ENVELOPE_BYTES = PortableBackupArchiveCodec.MAX_ARCHIVE_BYTES + HEADER_BYTES + 4 + TAG_BYTES
        private val MAGIC = byteArrayOf('Q'.code.toByte(), 'L'.code.toByte(), 'C'.code.toByte(), 'R'.code.toByte(), 'Y'.code.toByte(), 'P'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())

        fun isProtected(bytes: ByteArray): Boolean =
            bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

        fun isSupportedEnvelope(bytes: ByteArray): Boolean {
            if (bytes.size !in (HEADER_BYTES + 4 + TAG_BYTES)..MAX_ENVELOPE_BYTES) return false
            return try {
                val source = ByteArrayInputStream(bytes)
                val input = DataInputStream(source)
                val observedMagic = ByteArray(MAGIC.size).also(input::readFully)
                observedMagic.contentEquals(MAGIC) &&
                    input.readUnsignedShort() == VERSION &&
                    input.readInt() == ITERATIONS &&
                    input.readUnsignedByte() == SALT_BYTES &&
                    input.readUnsignedByte() == NONCE_BYTES &&
                    input.skipBytes(SALT_BYTES + NONCE_BYTES) == SALT_BYTES + NONCE_BYTES &&
                    input.readInt().let { length -> length >= TAG_BYTES && length == source.available() }
            } catch (_: EOFException) {
                false
            }
        }
    }
}
