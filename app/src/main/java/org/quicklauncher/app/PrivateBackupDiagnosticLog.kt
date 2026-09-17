package org.quicklauncher.app

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.quicklauncher.host.backup.library.BackupLibraryProblem
import org.quicklauncher.host.backup.library.BackupOperationResult
import org.quicklauncher.host.backup.support.BackupDiagnosticCode
import org.quicklauncher.host.backup.support.BackupDiagnosticEvent
import org.quicklauncher.host.backup.support.BackupDiagnosticLogCodec
import org.quicklauncher.host.backup.support.BackupDiagnosticLogDecodeResult
import org.quicklauncher.host.backup.support.BoundedBackupDiagnosticLog

internal data class BackupDiagnosticExportSnapshot(
    val events: List<BackupDiagnosticEvent>,
)

internal class PendingSupportBundle(
    val bytes: ByteArray,
    val diagnosticSnapshot: BackupDiagnosticExportSnapshot,
)

/** A private, bounded diagnostic log. Its API cannot accept free-form or identifying values. */
internal class PrivateBackupDiagnosticLog(
    private val file: File,
    private val capacity: Int = BoundedBackupDiagnosticLog.DEFAULT_CAPACITY,
) {
    init {
        require(capacity in 1..BoundedBackupDiagnosticLog.MAX_CAPACITY)
    }

    fun record(event: BackupDiagnosticEvent): Boolean = synchronized(FILE_LOCK) {
        val bounded = (readEvents() + event).takeLast(capacity)
        writeEvents(bounded)
    }

    fun snapshot(): List<BackupDiagnosticEvent> = synchronized(FILE_LOCK) {
        readEvents()
    }

    fun snapshotForExport(): BackupDiagnosticExportSnapshot =
        BackupDiagnosticExportSnapshot(snapshot())

    /**
     * Removes only the exported prefix. Events recorded after export preparation remain in the log.
     * If storage changed unexpectedly, nothing is removed.
     */
    fun clearExported(snapshot: BackupDiagnosticExportSnapshot): Boolean = synchronized(FILE_LOCK) {
        if (snapshot.events.isEmpty()) return@synchronized true
        val current = readEvents()
        if (current.size < snapshot.events.size || current.take(snapshot.events.size) != snapshot.events) {
            return@synchronized false
        }
        writeEvents(current.drop(snapshot.events.size))
    }

    private fun readEvents(): List<BackupDiagnosticEvent> {
        if (!file.isFile) return emptyList()
        val bytes = try {
            if (file.length() !in 1..BackupDiagnosticLogCodec.MAX_BYTES.toLong()) return emptyList()
            file.readBytes()
        } catch (_: Exception) {
            return emptyList()
        }
        return when (val decoded = BackupDiagnosticLogCodec.decode(bytes)) {
            is BackupDiagnosticLogDecodeResult.Decoded -> decoded.events.takeLast(capacity)
            BackupDiagnosticLogDecodeResult.Rejected -> emptyList()
        }
    }

    private fun writeEvents(events: List<BackupDiagnosticEvent>): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val temporary = try {
            File.createTempFile("backup-diagnostics-", ".tmp", parent)
        } catch (_: Exception) {
            return false
        }
        return try {
            FileOutputStream(temporary).use { output ->
                output.write(BackupDiagnosticLogCodec.encode(events))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            temporary.delete()
        }
    }

    internal companion object {
        const val FILE_NAME = "backup-diagnostics-v1.bin"
        private val FILE_LOCK = Any()
    }
}

internal fun automaticBackupDiagnosticCode(
    result: BackupOperationResult<*>,
): BackupDiagnosticCode = when (result) {
    is BackupOperationResult.Completed -> BackupDiagnosticCode.AUTOMATIC_EXPORT_SUCCEEDED
    is BackupOperationResult.Failed -> if (result.problem == BackupLibraryProblem.DELETE_FAILED) {
        BackupDiagnosticCode.RETENTION_FAILED
    } else {
        BackupDiagnosticCode.EXPORT_FAILED
    }
}
