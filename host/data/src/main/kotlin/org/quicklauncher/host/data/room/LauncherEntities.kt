package org.quicklauncher.host.data.room

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.ColumnInfo

@Entity(tableName = "destinations", indices = [Index(value = ["x", "y"], unique = true)])
internal data class DestinationEntity(
    @androidx.room3.PrimaryKey val id: String,
    val name: String,
    val x: Long,
    val y: Long,
)

@Entity(
    tableName = "configuration_documents",
    indices = [Index(value = ["configType", "schemaVersion"])],
)
internal data class ConfigurationDocumentEntity(
    @androidx.room3.PrimaryKey val id: String,
    val configType: String,
    val schemaVersion: Int,
    val encoded: String,
    val lastMigrationErrorCode: String?,
)

@Entity(
    tableName = "module_instances",
    foreignKeys = [
        ForeignKey(
            entity = ConfigurationDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["configurationDocumentId"],
        ),
    ],
    indices = [
        Index(value = ["configurationDocumentId"]),
        Index(value = ["contributionId"]),
    ],
)
internal data class ModuleInstanceEntity(
    @androidx.room3.PrimaryKey val id: String,
    val contributionId: String,
    val configurationDocumentId: String,
    val lifecycle: String,
    val quarantineReasonCode: String?,
    val quarantineReasonMessage: String?,
    @ColumnInfo(defaultValue = "'OTHER'") val quarantineOrigin: String = "OTHER",
)

@Entity(
    tableName = "destination_layouts",
    primaryKeys = ["destinationId", "layoutContributionId"],
    foreignKeys = [
        ForeignKey(
            entity = DestinationEntity::class,
            parentColumns = ["id"],
            childColumns = ["destinationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ModuleInstanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["rootInstanceId"],
        ),
    ],
    indices = [Index(value = ["rootInstanceId"], unique = true)],
)
internal data class DestinationLayoutEntity(
    val destinationId: String,
    val layoutContributionId: String,
    val rootInstanceId: String,
    val selected: Boolean,
)

@Entity(
    tableName = "placements",
    foreignKeys = [
        ForeignKey(
            entity = ModuleInstanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["treeRootInstanceId"],
        ),
        ForeignKey(
            entity = ModuleInstanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentInstanceId"],
        ),
        ForeignKey(
            entity = ModuleInstanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["childInstanceId"],
        ),
    ],
    indices = [
        Index(value = ["treeRootInstanceId"]),
        Index(value = ["parentInstanceId", "slotId", "position"], unique = true),
        Index(value = ["childInstanceId"], unique = true),
    ],
)
internal data class PlacementEntity(
    @androidx.room3.PrimaryKey val id: String,
    val treeRootInstanceId: String,
    val parentInstanceId: String,
    val slotId: String,
    val childInstanceId: String,
    val position: Int,
    val payloadSchemaVersion: Int,
    val encodedPlacement: String,
)

@Entity(tableName = "content_items", indices = [Index(value = ["kind"])])
internal data class ContentItemEntity(
    @androidx.room3.PrimaryKey val id: String,
    val kind: String,
    val profileSerial: Long?,
    val packageName: String?,
    val shortcutId: String?,
    val encodedSemanticData: String,
)

@Entity(
    tableName = "folder_members",
    primaryKeys = ["folderContentItemId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = ContentItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderContentItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContentItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["memberContentItemId"],
        ),
    ],
    indices = [
        Index(value = ["memberContentItemId"]),
        Index(value = ["folderContentItemId", "memberContentItemId"], unique = true),
    ],
)
internal data class FolderMemberEntity(
    val folderContentItemId: String,
    val memberContentItemId: String,
    val position: Int,
)

@Entity(
    tableName = "app_overrides",
    primaryKeys = ["profileSerial", "packageName", "activityName"],
)
internal data class AppOverrideEntity(
    val profileSerial: Long,
    val packageName: String,
    val activityName: String,
    val encodedOverride: String,
)

@Entity(
    tableName = "widget_placements",
    foreignKeys = [
        ForeignKey(
            entity = ModuleInstanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["moduleInstanceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class WidgetPlacementEntity(
    @androidx.room3.PrimaryKey val moduleInstanceId: String,
    val profileSerial: Long,
    val providerPackageName: String,
    val providerClassName: String,
    val appWidgetId: Int?,
    val bindingState: String,
    val encodedOptions: String,
)

@Entity(tableName = "theme_profiles")
internal data class ThemeProfileEntity(
    @androidx.room3.PrimaryKey val id: String,
    val name: String,
    val schemaVersion: Int,
    val encodedTheme: String,
)

@Entity(
    tableName = "destination_backgrounds",
    foreignKeys = [
        ForeignKey(
            entity = DestinationEntity::class,
            parentColumns = ["id"],
            childColumns = ["destinationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ThemeProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["themeProfileId"],
        ),
    ],
    indices = [Index(value = ["themeProfileId"])],
)
internal data class DestinationBackgroundEntity(
    @androidx.room3.PrimaryKey val destinationId: String,
    val themeProfileId: String,
    val schemaVersion: Int,
    val encodedBackground: String,
)

@Entity(
    tableName = "crash_markers",
    foreignKeys = [
        ForeignKey(
            entity = ModuleInstanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["moduleInstanceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["moduleInstanceId"], unique = true)],
)
internal data class CrashMarkerEntity(
    @androidx.room3.PrimaryKey val id: String,
    val moduleInstanceId: String,
    val appVersion: String,
    val startupAttempt: Int,
    val timestampEpochMillis: Long,
    val outcome: String?,
    val firstObservedAtEpochMillis: Long,
)

@Entity(
    tableName = "store_metadata",
    foreignKeys = [
        ForeignKey(
            entity = DestinationEntity::class,
            parentColumns = ["id"],
            childColumns = ["startDestinationId"],
        ),
    ],
    indices = [Index(value = ["startDestinationId"], unique = true)],
)
internal data class StoreMetadataEntity(
    @androidx.room3.PrimaryKey val singletonKey: Int = SINGLETON_KEY,
    val startDestinationId: String,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
) {
    companion object {
        const val SINGLETON_KEY: Int = 1
    }
}
