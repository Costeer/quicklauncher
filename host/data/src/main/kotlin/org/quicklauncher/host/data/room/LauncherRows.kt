package org.quicklauncher.host.data.room

internal data class LauncherRows(
    val destinations: List<DestinationEntity>,
    val destinationLayouts: List<DestinationLayoutEntity>,
    val moduleInstances: List<ModuleInstanceEntity>,
    val configurationDocuments: List<ConfigurationDocumentEntity>,
    val placements: List<PlacementEntity>,
    val contentItems: List<ContentItemEntity>,
    val folderMembers: List<FolderMemberEntity>,
    val appOverrides: List<AppOverrideEntity>,
    val widgetPlacements: List<WidgetPlacementEntity>,
    val themeProfiles: List<ThemeProfileEntity>,
    val destinationBackgrounds: List<DestinationBackgroundEntity>,
    val crashMarkers: List<CrashMarkerEntity>,
    val metadata: StoreMetadataEntity?,
)

internal suspend fun LauncherDao.readRows(): LauncherRows = LauncherRows(
    destinations = destinations(),
    destinationLayouts = destinationLayouts(),
    moduleInstances = moduleInstances(),
    configurationDocuments = configurationDocuments(),
    placements = placements(),
    contentItems = contentItems(),
    folderMembers = folderMembers(),
    appOverrides = appOverrides(),
    widgetPlacements = widgetPlacements(),
    themeProfiles = themeProfiles(),
    destinationBackgrounds = destinationBackgrounds(),
    crashMarkers = crashMarkers(),
    metadata = metadata(),
)

internal suspend fun LauncherDao.replaceRows(
    rows: LauncherRows,
    afterRowsCleared: suspend () -> Unit = {},
) {
    deleteCrashMarkers()
    deleteWidgetPlacements()
    deleteDestinationBackgrounds()
    deleteFolderMembers()
    deletePlacements()
    deleteDestinationLayouts()
    deleteAppOverrides()
    deleteContentItems()
    deleteMetadata()
    deleteDestinations()
    deleteThemeProfiles()
    deleteModuleInstances()
    deleteConfigurationDocuments()
    afterRowsCleared()

    insertIfNotEmpty(rows.configurationDocuments, ::insertConfigurationDocuments)
    insertIfNotEmpty(rows.moduleInstances, ::insertModuleInstances)
    insertIfNotEmpty(rows.destinations, ::insertDestinations)
    rows.metadata?.let { insertMetadata(it) }
    insertIfNotEmpty(rows.destinationLayouts, ::insertDestinationLayouts)
    insertIfNotEmpty(rows.placements, ::insertPlacements)
    insertIfNotEmpty(rows.contentItems, ::insertContentItems)
    insertIfNotEmpty(rows.folderMembers, ::insertFolderMembers)
    insertIfNotEmpty(rows.appOverrides, ::insertAppOverrides)
    insertIfNotEmpty(rows.widgetPlacements, ::insertWidgetPlacements)
    insertIfNotEmpty(rows.themeProfiles, ::insertThemeProfiles)
    insertIfNotEmpty(rows.destinationBackgrounds, ::insertDestinationBackgrounds)
    insertIfNotEmpty(rows.crashMarkers, ::insertCrashMarkers)
}

private suspend fun <T> insertIfNotEmpty(
    values: List<T>,
    insert: suspend (List<T>) -> Unit,
) {
    if (values.isNotEmpty()) insert(values)
}
