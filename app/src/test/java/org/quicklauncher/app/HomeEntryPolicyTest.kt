package org.quicklauncher.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.quicklauncher.host.runtime.LauncherEntry

class HomeEntryPolicyTest {
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
}
