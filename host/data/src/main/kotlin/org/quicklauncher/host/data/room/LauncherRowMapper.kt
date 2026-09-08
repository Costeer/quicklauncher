package org.quicklauncher.host.data.room

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.CrashMarkerId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.PlacementId
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.AppOverrideRecord
import org.quicklauncher.host.data.store.ContentItemKind
import org.quicklauncher.host.data.store.CrashMarkerRecord
import org.quicklauncher.host.data.store.DestinationBackgroundRecord
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.EncodedPlacementData
import org.quicklauncher.host.data.store.FolderMemberRecord
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.ModuleInstanceStatus
import org.quicklauncher.host.data.store.ModuleQuarantineOrigin
import org.quicklauncher.host.data.store.PlacementRecord
import org.quicklauncher.host.data.store.StoreRevision
import org.quicklauncher.host.data.store.StoredConfigurationDocument
import org.quicklauncher.host.data.store.ThemeProfileRecord
import org.quicklauncher.host.data.store.WidgetBindState
import org.quicklauncher.host.data.store.WidgetPlacementRecord
import org.quicklauncher.host.data.store.WidgetRestoreState
import org.quicklauncher.host.data.store.restoreContentItem

private val payloadJson = Json {
    encodeDefaults = true
}

@Serializable
private data class AppOverridePayload(
    val activityName: String,
    val customLabel: String?,
    val encodedIcon: String?,
    val favorite: Boolean,
    val collectionVisible: Boolean,
    val searchVisible: Boolean,
)

@Serializable
private data class WidgetPlacementPayload(
    val intendedWidthDp: Int,
    val intendedHeightDp: Int,
    val bindState: String,
    val restoreState: String,
)

internal fun LauncherRows.toLauncherSnapshot(): LauncherSnapshot {
    if (destinations.isEmpty()) {
        check(
            metadata == null &&
                destinationLayouts.isEmpty() &&
                moduleInstances.isEmpty() &&
                configurationDocuments.isEmpty() &&
                placements.isEmpty() &&
                contentItems.isEmpty() &&
                folderMembers.isEmpty() &&
                appOverrides.isEmpty() &&
                widgetPlacements.isEmpty() &&
                themeProfiles.isEmpty() &&
                destinationBackgrounds.isEmpty() &&
                crashMarkers.isEmpty(),
        ) { "An empty launcher database cannot contain other durable rows" }
        return LauncherSnapshot.empty()
    }
    val storedMetadata = checkNotNull(metadata) {
        "A populated launcher database must contain store metadata"
    }
    return LauncherSnapshot(
        revision = StoreRevision.of(storedMetadata.revision),
        startDestinationId = DestinationId.parse(storedMetadata.startDestinationId),
        destinations = destinations.map { entity ->
            DestinationRecord(
                id = DestinationId.parse(entity.id),
                name = entity.name,
                coordinate = DestinationCoordinate(entity.x, entity.y),
            )
        },
        destinationLayouts = destinationLayouts.map { entity ->
            DestinationLayoutRecord(
                destinationId = DestinationId.parse(entity.destinationId),
                layoutContributionId = ContributionId.parse(entity.layoutContributionId),
                layoutInstanceId = ModuleInstanceId.parse(entity.rootInstanceId),
                selected = entity.selected,
            )
        },
        moduleInstances = moduleInstances.map(ModuleInstanceEntity::toRecord),
        configurationDocuments = configurationDocuments.map { entity ->
            StoredConfigurationDocument(
                id = ConfigurationDocumentId.parse(entity.id),
                document = ConfigurationDocument(
                    configType = ConfigTypeId.parse(entity.configType),
                    schemaVersion = SchemaVersion.of(entity.schemaVersion),
                    encoded = EncodedConfiguration.of(entity.encoded),
                ),
                lastMigrationErrorCode = entity.lastMigrationErrorCode,
            )
        },
        placements = placements.map { entity ->
            PlacementRecord(
                id = PlacementId.parse(entity.id),
                layoutInstanceId = ModuleInstanceId.parse(entity.treeRootInstanceId),
                parentInstanceId = ModuleInstanceId.parse(entity.parentInstanceId),
                parentSlotId = StableKey.parse(entity.slotId),
                childInstanceId = ModuleInstanceId.parse(entity.childInstanceId),
                index = entity.position,
                data = EncodedPlacementData.of(entity.encodedPlacement),
                placementSchemaVersion = entity.payloadSchemaVersion,
            )
        },
        contentItems = contentItems.map { entity ->
            restoreContentItem(
                id = ContentItemId.parse(entity.id),
                kind = ContentItemKind.valueOf(entity.kind),
                encoded = entity.encodedSemanticData,
                profile = entity.profileSerial?.let(ProfileSerial::of),
                packageName = entity.packageName?.let(PackageName::parse),
                shortcutId = entity.shortcutId?.let(ShortcutId::parse),
            )
        },
        folderMembers = folderMembers.map { entity ->
            FolderMemberRecord(
                folderId = ContentItemId.parse(entity.folderContentItemId),
                memberId = ContentItemId.parse(entity.memberContentItemId),
                index = entity.position,
            )
        },
        appOverrides = appOverrides.map(AppOverrideEntity::toRecord),
        widgetPlacements = widgetPlacements.map(WidgetPlacementEntity::toRecord),
        themeProfiles = themeProfiles.map { entity ->
            ThemeProfileRecord(
                id = ThemeProfileId.parse(entity.id),
                name = entity.name,
                encoded = entity.encodedTheme,
                schemaVersion = entity.schemaVersion,
            )
        },
        destinationBackgrounds = destinationBackgrounds.map { entity ->
            DestinationBackgroundRecord(
                destinationId = DestinationId.parse(entity.destinationId),
                themeProfileId = ThemeProfileId.parse(entity.themeProfileId),
                encoded = entity.encodedBackground,
                schemaVersion = entity.schemaVersion,
            )
        },
        crashMarkers = crashMarkers.map(CrashMarkerEntity::toRecord),
    )
}

internal fun LauncherSnapshot.toLauncherRows(): LauncherRows = LauncherRows(
    destinations = destinations.map { record ->
        DestinationEntity(record.id.value, record.name, record.coordinate.x, record.coordinate.y)
    },
    destinationLayouts = destinationLayouts.map { record ->
        DestinationLayoutEntity(
            destinationId = record.destinationId.value,
            layoutContributionId = record.layoutContributionId.value,
            rootInstanceId = record.layoutInstanceId.value,
            selected = record.selected,
        )
    },
    moduleInstances = moduleInstances.map(ModuleInstanceRecord::toEntity),
    configurationDocuments = configurationDocuments.map { record ->
        ConfigurationDocumentEntity(
            id = record.id.value,
            configType = record.document.configType.value,
            schemaVersion = record.document.schemaVersion.value,
            encoded = record.document.encoded.value,
            lastMigrationErrorCode = record.lastMigrationErrorCode,
        )
    },
    placements = placements.map { record ->
        PlacementEntity(
            id = record.id.value,
            treeRootInstanceId = record.layoutInstanceId.value,
            parentInstanceId = record.parentInstanceId.value,
            slotId = record.parentSlotId.value,
            childInstanceId = record.childInstanceId.value,
            position = record.index,
            payloadSchemaVersion = record.placementSchemaVersion,
            encodedPlacement = record.data.value,
        )
    },
    contentItems = contentItems.map { record ->
        ContentItemEntity(
            id = record.id.value,
            kind = record.kind.name,
            profileSerial = record.profile?.value,
            packageName = record.packageName?.value,
            shortcutId = record.shortcutTarget?.shortcutId?.value,
            encodedSemanticData = record.encoded,
        )
    },
    folderMembers = folderMembers.map { record ->
        FolderMemberEntity(record.folderId.value, record.memberId.value, record.index)
    },
    appOverrides = appOverrides.map(AppOverrideRecord::toEntity),
    widgetPlacements = widgetPlacements.map(WidgetPlacementRecord::toEntity),
    themeProfiles = themeProfiles.map { record ->
        ThemeProfileEntity(record.id.value, record.name, record.schemaVersion, record.encoded)
    },
    destinationBackgrounds = destinationBackgrounds.map { record ->
        DestinationBackgroundEntity(
            destinationId = record.destinationId.value,
            themeProfileId = record.themeProfileId.value,
            schemaVersion = record.schemaVersion,
            encodedBackground = record.encoded,
        )
    },
    crashMarkers = crashMarkers.map(CrashMarkerRecord::toEntity),
    metadata = startDestinationId?.let { start ->
        StoreMetadataEntity(startDestinationId = start.value, revision = revision.value)
    },
)

private fun ModuleInstanceEntity.toRecord(): ModuleInstanceRecord = ModuleInstanceRecord(
    id = ModuleInstanceId.parse(id),
    contributionId = ContributionId.parse(contributionId),
    configurationDocumentId = ConfigurationDocumentId.parse(configurationDocumentId),
    status = when (lifecycle) {
        "active" -> ModuleInstanceStatus.Active
        "quarantined" -> ModuleInstanceStatus.Quarantined(
            code = checkNotNull(quarantineReasonCode),
            message = checkNotNull(quarantineReasonMessage),
            origin = ModuleQuarantineOrigin.valueOf(quarantineOrigin),
        )
        else -> error("Unknown module-instance lifecycle '$lifecycle'")
    },
)

private fun ModuleInstanceRecord.toEntity(): ModuleInstanceEntity {
    val quarantine = status as? ModuleInstanceStatus.Quarantined
    return ModuleInstanceEntity(
        id = id.value,
        contributionId = contributionId.value,
        configurationDocumentId = configurationDocumentId.value,
        lifecycle = if (quarantine == null) "active" else "quarantined",
        quarantineReasonCode = quarantine?.code,
        quarantineReasonMessage = quarantine?.message,
        quarantineOrigin = quarantine?.origin?.name ?: ModuleQuarantineOrigin.OTHER.name,
    )
}

internal fun AppOverrideEntity.toRecord(): AppOverrideRecord {
    val payload = payloadJson.decodeFromString<AppOverridePayload>(encodedOverride)
    return AppOverrideRecord(
        profile = ProfileSerial.of(profileSerial),
        packageName = PackageName.parse(packageName),
        activityName = activityName.also {
            check(it == payload.activityName) {
                "App override activity identity does not match its encoded payload"
            }
        },
        customLabel = payload.customLabel,
        encodedIcon = payload.encodedIcon,
        favorite = payload.favorite,
        collectionVisible = payload.collectionVisible,
        searchVisible = payload.searchVisible,
    )
}

private fun AppOverrideRecord.toEntity(): AppOverrideEntity = AppOverrideEntity(
    profileSerial = profile.value,
    packageName = packageName.value,
    activityName = activityName,
    encodedOverride = payloadJson.encodeToString(
        AppOverridePayload(
            activityName,
            customLabel,
            encodedIcon,
            favorite,
            collectionVisible,
            searchVisible,
        ),
    ),
)

private fun WidgetPlacementEntity.toRecord(): WidgetPlacementRecord {
    val payload = payloadJson.decodeFromString<WidgetPlacementPayload>(encodedOptions)
    return WidgetPlacementRecord(
        moduleInstanceId = ModuleInstanceId.parse(moduleInstanceId),
        appWidgetId = appWidgetId,
        providerPackage = PackageName.parse(providerPackageName),
        providerClassName = providerClassName,
        profile = ProfileSerial.of(profileSerial),
        intendedWidthDp = payload.intendedWidthDp,
        intendedHeightDp = payload.intendedHeightDp,
        bindState = WidgetBindState.valueOf(bindingState).also {
            check(it.name == payload.bindState) {
                "Widget binding state does not match its encoded options"
            }
        },
        restoreState = WidgetRestoreState.valueOf(payload.restoreState),
    )
}

private fun WidgetPlacementRecord.toEntity(): WidgetPlacementEntity = WidgetPlacementEntity(
    moduleInstanceId = moduleInstanceId.value,
    profileSerial = profile.value,
    providerPackageName = providerPackage.value,
    providerClassName = providerClassName,
    appWidgetId = appWidgetId,
    bindingState = bindState.name,
    encodedOptions = payloadJson.encodeToString(
        WidgetPlacementPayload(
            intendedWidthDp,
            intendedHeightDp,
            bindState.name,
            restoreState.name,
        ),
    ),
)

private fun CrashMarkerEntity.toRecord(): CrashMarkerRecord {
    return CrashMarkerRecord(
        id = CrashMarkerId.parse(id),
        moduleInstanceId = ModuleInstanceId.parse(moduleInstanceId),
        appVersion = appVersion,
        startupAttempt = startupAttempt,
        timestampEpochMillis = timestampEpochMillis,
        outcome = outcome?.let(StableKey::parse),
        firstObservedAtEpochMillis = firstObservedAtEpochMillis,
    )
}

private fun CrashMarkerRecord.toEntity(): CrashMarkerEntity = CrashMarkerEntity(
    id = id.value,
    moduleInstanceId = moduleInstanceId.value,
    appVersion = appVersion,
    startupAttempt = startupAttempt,
    timestampEpochMillis = timestampEpochMillis,
    outcome = outcome?.value,
    firstObservedAtEpochMillis = firstObservedAtEpochMillis,
)
