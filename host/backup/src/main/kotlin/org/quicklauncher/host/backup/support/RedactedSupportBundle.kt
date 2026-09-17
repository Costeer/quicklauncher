package org.quicklauncher.host.backup.support

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.ArrayDeque

enum class BackupDiagnosticCode {
    FOLDER_AUTHORIZED,
    FOLDER_UNAVAILABLE,
    MANUAL_EXPORT_SUCCEEDED,
    AUTOMATIC_EXPORT_SUCCEEDED,
    EXPORT_FAILED,
    PREVIEW_REJECTED,
    RESTORE_SUCCEEDED,
    RESTORE_REJECTED,
    RETENTION_FAILED,
}

data class BackupDiagnosticEvent(
    val timestampEpochMillis: Long,
    val code: BackupDiagnosticCode,
    val numericDetail: Int? = null,
) {
    init {
        require(timestampEpochMillis >= 0L)
        require(numericDetail == null || numericDetail >= 0)
    }
}

/** Bounded diagnostics containing no paths, URIs, labels, package names, or payload data. */
class BoundedBackupDiagnosticLog(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val events = ArrayDeque<BackupDiagnosticEvent>(capacity)

    init {
        require(capacity in 1..MAX_CAPACITY)
    }

    @Synchronized
    fun record(event: BackupDiagnosticEvent) {
        while (events.size >= capacity) events.removeFirst()
        events.addLast(event)
    }

    @Synchronized
    fun snapshot(): List<BackupDiagnosticEvent> = events.toList()

    companion object {
        const val DEFAULT_CAPACITY = 128
        const val MAX_CAPACITY = 512
    }
}

sealed interface BackupDiagnosticLogDecodeResult {
    data class Decoded(val events: List<BackupDiagnosticEvent>) : BackupDiagnosticLogDecodeResult
    data object Rejected : BackupDiagnosticLogDecodeResult
}

/** Private-storage wire format for the typed diagnostic log. */
object BackupDiagnosticLogCodec {
    const val MAX_BYTES = 16 * 1024

    private const val MAGIC = 0x514C4447
    private const val VERSION = 1
    private const val DIGEST_BYTES = 32

    fun encode(events: List<BackupDiagnosticEvent>): ByteArray {
        require(events.size <= BoundedBackupDiagnosticLog.MAX_CAPACITY)
        val body = ByteArrayOutputStream().also { sink ->
            DataOutputStream(sink).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(events.size)
                events.forEach { event ->
                    output.writeLong(event.timestampEpochMillis)
                    output.writeUTF(event.code.name)
                    output.writeInt(event.numericDetail ?: -1)
                }
            }
        }.toByteArray()
        val encoded = body + MessageDigest.getInstance("SHA-256").digest(body)
        require(encoded.size <= MAX_BYTES)
        return encoded
    }

    fun decode(bytes: ByteArray): BackupDiagnosticLogDecodeResult {
        if (bytes.size !in (12 + DIGEST_BYTES)..MAX_BYTES) return BackupDiagnosticLogDecodeResult.Rejected
        val body = bytes.copyOfRange(0, bytes.size - DIGEST_BYTES)
        val expectedDigest = bytes.copyOfRange(bytes.size - DIGEST_BYTES, bytes.size)
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(body)
        if (!MessageDigest.isEqual(expectedDigest, actualDigest)) return BackupDiagnosticLogDecodeResult.Rejected
        return try {
            DataInputStream(ByteArrayInputStream(body)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    return BackupDiagnosticLogDecodeResult.Rejected
                }
                val count = input.readInt()
                if (count !in 0..BoundedBackupDiagnosticLog.MAX_CAPACITY) {
                    return BackupDiagnosticLogDecodeResult.Rejected
                }
                val events = buildList(count) {
                    repeat(count) {
                        val timestamp = input.readLong()
                        val code = runCatching { BackupDiagnosticCode.valueOf(input.readUTF()) }.getOrNull()
                            ?: return BackupDiagnosticLogDecodeResult.Rejected
                        val detail = input.readInt()
                        if (timestamp < 0L || detail < -1) return BackupDiagnosticLogDecodeResult.Rejected
                        add(BackupDiagnosticEvent(timestamp, code, detail.takeIf { it >= 0 }))
                    }
                }
                if (input.available() != 0) BackupDiagnosticLogDecodeResult.Rejected
                else BackupDiagnosticLogDecodeResult.Decoded(events)
            }
        } catch (_: Exception) {
            BackupDiagnosticLogDecodeResult.Rejected
        }
    }
}

object RedactedSupportBundle {
    const val MAX_BYTES = 64 * 1024

    fun encode(
        generatedAtEpochMillis: Long,
        appVersion: String,
        archiveFormatMajor: Int,
        events: List<BackupDiagnosticEvent>,
    ): ByteArray {
        require(generatedAtEpochMillis >= 0L)
        require(appVersion.matches(Regex("[A-Za-z0-9._-]{1,40}")))
        require(archiveFormatMajor >= 1)
        val bounded = events.takeLast(BoundedBackupDiagnosticLog.MAX_CAPACITY)
        val text = buildString {
            appendLine("quicklauncher-support-v1")
            appendLine("generated=${Instant.ofEpochMilli(generatedAtEpochMillis)}")
            appendLine("appVersion=$appVersion")
            appendLine("archiveFormatMajor=$archiveFormatMajor")
            appendLine("eventCount=${bounded.size}")
            bounded.forEach { event ->
                append(event.timestampEpochMillis)
                append('|')
                append(event.code.name)
                event.numericDetail?.let { detail ->
                    append('|')
                    append(detail)
                }
                appendLine()
            }
        }.toByteArray(StandardCharsets.UTF_8)
        require(text.size <= MAX_BYTES) { "Support bundle exceeds its export bound" }
        return text
    }

    fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
}
