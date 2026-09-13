package org.quicklauncher.app

import android.app.Application
import org.quicklauncher.host.data.preferences.LauncherPreferencesStore
import org.quicklauncher.host.platform.persistence.AndroidLauncherPreferencesFactory

/** Owns process-wide resources that must survive activity replacement. */
class QuicklauncherApplication : Application() {
    val launcherPreferences: LauncherPreferencesStore? by lazy {
        try {
            AndroidLauncherPreferencesFactory(this).open(MainActivity.PREFERENCES_NAME)
        } catch (_: Exception) {
            null
        }
    }
}
