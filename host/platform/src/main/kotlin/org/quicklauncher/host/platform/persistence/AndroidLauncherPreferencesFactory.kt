package org.quicklauncher.host.platform.persistence

import android.content.Context
import java.io.File
import org.quicklauncher.host.data.preferences.FileLauncherPreferencesStore
import org.quicklauncher.host.data.preferences.LauncherPreferencesStore

/** Keeps Android-owned preference paths outside the persistence module. */
class AndroidLauncherPreferencesFactory(context: Context) {
    private val applicationContext = context.applicationContext

    fun open(name: String): LauncherPreferencesStore {
        require(name.isNotBlank()) { "Launcher preference name must not be blank" }
        require('/' !in name && '\\' !in name && name != "." && name != "..") {
            "Launcher preference name must identify one ordinary file"
        }
        val directory = File(applicationContext.filesDir, DATASTORE_DIRECTORY)
        check(directory.exists() || directory.mkdirs()) {
            "Launcher preference directory could not be created"
        }
        return FileLauncherPreferencesStore.open(File(directory, name))
    }

    private companion object {
        const val DATASTORE_DIRECTORY = "datastore"
    }
}
