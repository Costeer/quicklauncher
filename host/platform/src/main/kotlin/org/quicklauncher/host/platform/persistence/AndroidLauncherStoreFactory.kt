package org.quicklauncher.host.platform.persistence

import android.content.Context
import java.io.File
import org.quicklauncher.host.data.room.openRoomLauncherStore
import org.quicklauncher.host.data.store.CloseableLauncherStore
import org.quicklauncher.host.data.store.ConfigurationResolver
import org.quicklauncher.host.data.store.PlacementPolicy

/** Keeps Android path resolution outside the Room-backed persistence module. */
class AndroidLauncherStoreFactory(context: Context) {
    private val applicationContext = context.applicationContext

    fun open(name: String): CloseableLauncherStore = openRoomLauncherStore(databaseFile(name))

    fun open(
        name: String,
        configurationResolver: ConfigurationResolver,
        placementPolicy: PlacementPolicy,
    ): CloseableLauncherStore = openRoomLauncherStore(
        file = databaseFile(name),
        configurationResolver = configurationResolver,
        placementPolicy = placementPolicy,
    )

    private fun databaseFile(name: String): File {
        require(name.isNotBlank()) { "Launcher database name must not be blank" }
        require('/' !in name && '\\' !in name) {
            "Launcher database name must not contain path separators"
        }
        return applicationContext.getDatabasePath(name)
    }
}
