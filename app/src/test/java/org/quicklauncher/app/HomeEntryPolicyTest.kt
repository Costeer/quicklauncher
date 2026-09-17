package org.quicklauncher.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.quicklauncher.host.backup.library.BackupDocument
import org.quicklauncher.host.backup.library.BackupInventory
import org.quicklauncher.host.runtime.LauncherEntry
import org.quicklauncher.host.runtime.theme.ThemeFontRole
import org.quicklauncher.host.settings.BackupRestoreReviewSetting
import org.quicklauncher.host.settings.BackupRecoverySetting
import org.quicklauncher.host.settings.BackupSnapshotSetting

class HomeEntryPolicyTest {
    @Test
    fun `backup inventory keeps pre restore safety snapshots visible`() {
        val safety = BackupDocument("safety", "Safety snapshot", 12L, 1L)

        val state = BackupUiState().withInventory(
            BackupInventory(emptyList(), emptyList(), listOf(safety)),
        )

        assertEquals(listOf("safety"), state.preRestore.map { it.id })
    }

    @Test
    fun `post restore recovery counts survive recreation and invalid bundle values fail safe`() {
        assertEquals(
            BackupRecoverySetting(2, 1, 3, listOf(10L, 11L)),
            BackupRecoveryLifecyclePolicy.restore(true, 2, 1, 3, listOf(10L, 11L)),
        )
        assertEquals(
            BackupRecoverySetting(0, 0, 0),
            BackupRecoveryLifecyclePolicy.restore(true, -1, -2, -3),
        )
        assertEquals(null, BackupRecoveryLifecyclePolicy.restore(false, 2, 1, 3))
    }

    @Test
    fun `unconsumed Home selects start while consumed recreation restores`() {
        assertEquals(
            LauncherEntry.HOME,
            HomeEntryPolicy.initial(isHomeIntent = true, hasSavedState = false),
        )
        assertEquals(
            LauncherEntry.HOME,
            HomeEntryPolicy.initial(isHomeIntent = true, hasSavedState = true),
        )
        assertEquals(
            LauncherEntry.RESTORE,
            HomeEntryPolicy.initial(isHomeIntent = false, hasSavedState = true),
        )
        assertEquals(
            LauncherEntry.APP_ICON,
            HomeEntryPolicy.initial(isHomeIntent = false, hasSavedState = false),
        )
    }

    @Test
    fun `theme picker request survives recreation and malformed state falls back safely`() {
        assertEquals(
            ThemeImportRequestState(ThemeImageAction.WALLPAPER, ThemeFontRole.TITLE),
            ThemeImportRequestState.restore("WALLPAPER", "TITLE"),
        )
        assertEquals(
            ThemeImportRequestState(ThemeImageAction.LAUNCHER_BACKGROUND, ThemeFontRole.BODY),
            ThemeImportRequestState.restore("unknown", null),
        )
    }

    @Test
    fun `restore review expires across recreation without losing the backup inventory`() {
        val manual = listOf(BackupSnapshotSetting("backup-1", "Manual backup", "20 bytes"))
        val state = BackupUiState(
            folderSelected = true,
            manual = manual,
            review = BackupRestoreReviewSetting(1, 2, 3, 4, 5),
            message = "Review the restore before applying it.",
        )

        val recreated = BackupRestoreLifecyclePolicy.afterRecreation(state, reviewWasPending = true)

        assertEquals(null, recreated.review)
        assertEquals(manual, recreated.manual)
        assertEquals(true, recreated.folderSelected)
        assertEquals(
            "Restore review expired after recreation. Preview the backup again.",
            recreated.message,
        )
    }

    @Test
    fun `repeated Home or Back cancellation is idempotent`() {
        val pending = BackupUiState(
            review = BackupRestoreReviewSetting(1, 2, 3, 4, 5),
            message = "Review the restore before applying it.",
        )

        val first = BackupRestoreLifecyclePolicy.cancel(stagedWasPresent = true, pending)
        val repeated = BackupRestoreLifecyclePolicy.cancel(stagedWasPresent = false, first)

        assertEquals(null, first.review)
        assertEquals("Restore cancelled; no launcher state changed.", first.message)
        assertEquals(first, repeated)
    }
}
