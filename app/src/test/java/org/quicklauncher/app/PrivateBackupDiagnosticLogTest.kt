package org.quicklauncher.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.quicklauncher.host.backup.library.BackupLibraryProblem
import org.quicklauncher.host.backup.library.BackupOperationResult
import org.quicklauncher.host.backup.support.BackupDiagnosticCode
import org.quicklauncher.host.backup.support.BackupDiagnosticEvent

class PrivateBackupDiagnosticLogTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `bounded events survive a fresh log instance`() {
        val file = File(temporaryFolder.root, PrivateBackupDiagnosticLog.FILE_NAME)
        val original = PrivateBackupDiagnosticLog(file, capacity = 2)
        assertTrue(original.record(event(1, BackupDiagnosticCode.FOLDER_AUTHORIZED)))
        assertTrue(original.record(event(2, BackupDiagnosticCode.EXPORT_FAILED)))
        assertTrue(original.record(event(3, BackupDiagnosticCode.AUTOMATIC_EXPORT_SUCCEEDED)))

        val recreated = PrivateBackupDiagnosticLog(file, capacity = 2)

        assertEquals(
            listOf(
                event(2, BackupDiagnosticCode.EXPORT_FAILED),
                event(3, BackupDiagnosticCode.AUTOMATIC_EXPORT_SUCCEEDED),
            ),
            recreated.snapshot(),
        )
    }

    @Test
    fun `corrupted storage fails closed and the next typed event replaces it`() {
        val file = File(temporaryFolder.root, PrivateBackupDiagnosticLog.FILE_NAME)
        file.writeText("content://private/tree and a user supplied name")
        val log = PrivateBackupDiagnosticLog(file)

        assertEquals(emptyList<BackupDiagnosticEvent>(), log.snapshot())
        assertTrue(log.record(event(7, BackupDiagnosticCode.EXPORT_FAILED)))
        assertEquals(listOf(event(7, BackupDiagnosticCode.EXPORT_FAILED)), PrivateBackupDiagnosticLog(file).snapshot())
        assertFalse(file.readText().contains("content://private/tree"))
    }

    @Test
    fun `successful export clears its prefix but keeps events recorded during export`() {
        val file = File(temporaryFolder.root, PrivateBackupDiagnosticLog.FILE_NAME)
        val log = PrivateBackupDiagnosticLog(file)
        val first = event(1, BackupDiagnosticCode.MANUAL_EXPORT_SUCCEEDED)
        val later = event(2, BackupDiagnosticCode.RETENTION_FAILED)
        log.record(first)
        val exported = log.snapshotForExport()
        log.record(later)

        assertEquals(listOf(first, later), log.snapshot())
        assertTrue(log.clearExported(exported))
        assertEquals(listOf(later), PrivateBackupDiagnosticLog(file).snapshot())
    }

    @Test
    fun `cancelled export keeps its prepared events`() {
        val file = File(temporaryFolder.root, PrivateBackupDiagnosticLog.FILE_NAME)
        val log = PrivateBackupDiagnosticLog(file)
        val retained = event(1, BackupDiagnosticCode.RESTORE_REJECTED)
        log.record(retained)

        log.snapshotForExport()

        assertEquals(listOf(retained), PrivateBackupDiagnosticLog(file).snapshot())
    }

    @Test
    fun `export clear fails closed when bounded eviction changed the prefix`() {
        val file = File(temporaryFolder.root, PrivateBackupDiagnosticLog.FILE_NAME)
        val log = PrivateBackupDiagnosticLog(file, capacity = 1)
        log.record(event(1, BackupDiagnosticCode.MANUAL_EXPORT_SUCCEEDED))
        val exported = log.snapshotForExport()
        val later = event(2, BackupDiagnosticCode.EXPORT_FAILED)
        log.record(later)

        assertFalse(log.clearExported(exported))
        assertEquals(listOf(later), PrivateBackupDiagnosticLog(file, capacity = 1).snapshot())
    }

    @Test
    fun `automatic backup outcomes distinguish success export failure and retention failure`() {
        assertEquals(
            BackupDiagnosticCode.AUTOMATIC_EXPORT_SUCCEEDED,
            automaticBackupDiagnosticCode(BackupOperationResult.Completed(Unit)),
        )
        assertEquals(
            BackupDiagnosticCode.EXPORT_FAILED,
            automaticBackupDiagnosticCode(BackupOperationResult.Failed(BackupLibraryProblem.WRITE_FAILED)),
        )
        assertEquals(
            BackupDiagnosticCode.RETENTION_FAILED,
            automaticBackupDiagnosticCode(BackupOperationResult.Failed(BackupLibraryProblem.DELETE_FAILED)),
        )
    }

    private fun event(timestamp: Long, code: BackupDiagnosticCode) =
        BackupDiagnosticEvent(timestamp, code)
}
