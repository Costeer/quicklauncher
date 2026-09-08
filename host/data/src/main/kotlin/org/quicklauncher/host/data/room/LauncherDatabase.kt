package org.quicklauncher.host.data.room

import androidx.room3.Database
import androidx.room3.AutoMigration
import androidx.room3.RoomDatabase

@Database(
    entities = [
        DestinationEntity::class,
        DestinationLayoutEntity::class,
        ModuleInstanceEntity::class,
        ConfigurationDocumentEntity::class,
        PlacementEntity::class,
        ContentItemEntity::class,
        FolderMemberEntity::class,
        AppOverrideEntity::class,
        WidgetPlacementEntity::class,
        ThemeProfileEntity::class,
        DestinationBackgroundEntity::class,
        CrashMarkerEntity::class,
        StoreMetadataEntity::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
internal abstract class LauncherDatabase : RoomDatabase() {
    internal abstract fun launcherDao(): LauncherDao
}
