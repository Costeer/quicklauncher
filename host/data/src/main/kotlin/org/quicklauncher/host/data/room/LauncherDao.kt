package org.quicklauncher.host.data.room

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

@Dao
internal interface LauncherDao {
    @Query("SELECT * FROM destinations ORDER BY id")
    suspend fun destinations(): List<DestinationEntity>

    @Query("SELECT * FROM destination_layouts ORDER BY destinationId, layoutContributionId")
    suspend fun destinationLayouts(): List<DestinationLayoutEntity>

    @Query("SELECT * FROM module_instances ORDER BY id")
    suspend fun moduleInstances(): List<ModuleInstanceEntity>

    @Query("SELECT * FROM configuration_documents ORDER BY id")
    suspend fun configurationDocuments(): List<ConfigurationDocumentEntity>

    @Query("SELECT * FROM placements ORDER BY treeRootInstanceId, parentInstanceId, slotId, position")
    suspend fun placements(): List<PlacementEntity>

    @Query("SELECT * FROM content_items ORDER BY id")
    suspend fun contentItems(): List<ContentItemEntity>

    @Query("SELECT * FROM folder_members ORDER BY folderContentItemId, position")
    suspend fun folderMembers(): List<FolderMemberEntity>

    @Query("SELECT * FROM app_overrides ORDER BY profileSerial, packageName, activityName")
    suspend fun appOverrides(): List<AppOverrideEntity>

    @Query("SELECT * FROM widget_placements ORDER BY moduleInstanceId")
    suspend fun widgetPlacements(): List<WidgetPlacementEntity>

    @Query("SELECT * FROM theme_profiles ORDER BY id")
    suspend fun themeProfiles(): List<ThemeProfileEntity>

    @Query("SELECT * FROM destination_backgrounds ORDER BY destinationId")
    suspend fun destinationBackgrounds(): List<DestinationBackgroundEntity>

    @Query("SELECT * FROM crash_markers ORDER BY id")
    suspend fun crashMarkers(): List<CrashMarkerEntity>

    @Query("SELECT * FROM store_metadata WHERE singletonKey = 1")
    suspend fun metadata(): StoreMetadataEntity?

    @Insert
    suspend fun insertDestinations(values: List<DestinationEntity>)

    @Insert
    suspend fun insertDestinationLayouts(values: List<DestinationLayoutEntity>)

    @Insert
    suspend fun insertModuleInstances(values: List<ModuleInstanceEntity>)

    @Insert
    suspend fun insertConfigurationDocuments(values: List<ConfigurationDocumentEntity>)

    @Insert
    suspend fun insertPlacements(values: List<PlacementEntity>)

    @Insert
    suspend fun insertContentItems(values: List<ContentItemEntity>)

    @Insert
    suspend fun insertFolderMembers(values: List<FolderMemberEntity>)

    @Insert
    suspend fun insertAppOverrides(values: List<AppOverrideEntity>)

    @Insert
    suspend fun insertWidgetPlacements(values: List<WidgetPlacementEntity>)

    @Insert
    suspend fun insertThemeProfiles(values: List<ThemeProfileEntity>)

    @Insert
    suspend fun insertDestinationBackgrounds(values: List<DestinationBackgroundEntity>)

    @Insert
    suspend fun insertCrashMarkers(values: List<CrashMarkerEntity>)

    @Insert
    suspend fun insertMetadata(value: StoreMetadataEntity)

    @Query("DELETE FROM crash_markers")
    suspend fun deleteCrashMarkers()

    @Query("DELETE FROM widget_placements")
    suspend fun deleteWidgetPlacements()

    @Query("DELETE FROM destination_backgrounds")
    suspend fun deleteDestinationBackgrounds()

    @Query("DELETE FROM folder_members")
    suspend fun deleteFolderMembers()

    @Query("DELETE FROM placements")
    suspend fun deletePlacements()

    @Query("DELETE FROM destination_layouts")
    suspend fun deleteDestinationLayouts()

    @Query("DELETE FROM app_overrides")
    suspend fun deleteAppOverrides()

    @Query("DELETE FROM content_items")
    suspend fun deleteContentItems()

    @Query("DELETE FROM store_metadata")
    suspend fun deleteMetadata()

    @Query("DELETE FROM destinations")
    suspend fun deleteDestinations()

    @Query("DELETE FROM theme_profiles")
    suspend fun deleteThemeProfiles()

    @Query("DELETE FROM module_instances")
    suspend fun deleteModuleInstances()

    @Query("DELETE FROM configuration_documents")
    suspend fun deleteConfigurationDocuments()
}
