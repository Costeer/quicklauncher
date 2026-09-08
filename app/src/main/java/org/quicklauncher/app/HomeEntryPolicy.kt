package org.quicklauncher.app

import org.quicklauncher.host.runtime.LauncherEntry

internal object HomeEntryPolicy {
    fun initial(isHomeIntent: Boolean, hasSavedState: Boolean): LauncherEntry = when {
        isHomeIntent -> LauncherEntry.HOME
        hasSavedState -> LauncherEntry.RESTORE
        else -> LauncherEntry.APP_ICON
    }
}
