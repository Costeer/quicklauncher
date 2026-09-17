package org.quicklauncher.host.backup.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactedSupportBundleTest {
    @Test
    fun `bounded log evicts old events and bundle contains only typed redacted fields`() {
        val log = BoundedBackupDiagnosticLog(capacity = 2)
        log.record(BackupDiagnosticEvent(1, BackupDiagnosticCode.FOLDER_AUTHORIZED))
        log.record(BackupDiagnosticEvent(2, BackupDiagnosticCode.EXPORT_FAILED, 3))
        log.record(BackupDiagnosticEvent(3, BackupDiagnosticCode.RESTORE_SUCCEEDED))

        val bundle = RedactedSupportBundle.encode(4, "0.1.0", 1, log.snapshot())
        val text = bundle.toString(Charsets.UTF_8)

        assertEquals(2, log.snapshot().size)
        assertFalse(text.contains("FOLDER_AUTHORIZED"))
        assertTrue(text.contains("EXPORT_FAILED|3"))
        assertTrue(text.contains("RESTORE_SUCCEEDED"))
        assertTrue(bundle.size <= RedactedSupportBundle.MAX_BYTES)
        assertEquals(64, RedactedSupportBundle.fingerprint(bundle).length)
    }

    @Test
    fun `private log codec round trips typed events and rejects corruption`() {
        val events = listOf(
            BackupDiagnosticEvent(10, BackupDiagnosticCode.AUTOMATIC_EXPORT_SUCCEEDED),
            BackupDiagnosticEvent(11, BackupDiagnosticCode.RETENTION_FAILED, 2),
        )
        val encoded = BackupDiagnosticLogCodec.encode(events)

        val decoded = BackupDiagnosticLogCodec.decode(encoded) as BackupDiagnosticLogDecodeResult.Decoded
        assertEquals(events, decoded.events)

        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        assertEquals(BackupDiagnosticLogDecodeResult.Rejected, BackupDiagnosticLogCodec.decode(encoded))
        assertEquals(BackupDiagnosticLogDecodeResult.Rejected, BackupDiagnosticLogCodec.decode(ByteArray(8)))
    }
}
