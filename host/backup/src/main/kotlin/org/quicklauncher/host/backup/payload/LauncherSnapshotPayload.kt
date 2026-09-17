package org.quicklauncher.host.backup.payload

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ContributionId
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
import org.quicklauncher.host.data.store.ContentItemRecord
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
import org.quicklauncher.host.data.store.ShortcutTarget
import org.quicklauncher.host.data.store.StoredConfigurationDocument
import org.quicklauncher.host.data.store.ThemeProfileRecord
import org.quicklauncher.host.data.store.WidgetBindState
import org.quicklauncher.host.data.store.WidgetCleanupState
import org.quicklauncher.host.data.store.WidgetPlacementRecord
import org.quicklauncher.host.data.store.WidgetRestoreState

enum class LauncherPayloadProblem {
    OVERSIZED,
    MALFORMED,
    UNSUPPORTED_VERSION,
    INVALID_STATE,
}

sealed interface LauncherPayloadEncodeResult {
    class Encoded(bytes: ByteArray) : LauncherPayloadEncodeResult {
        private val bytes = bytes.copyOf()
        fun bytesCopy(): ByteArray = bytes.copyOf()
    }

    data class Rejected(val problem: LauncherPayloadProblem) : LauncherPayloadEncodeResult
}

sealed interface LauncherPayloadDecodeResult {
    class Decoded(
        val snapshot: LauncherSnapshot,
        opaqueExtensions: Map<String, ByteArray>,
    ) : LauncherPayloadDecodeResult {
        private val opaqueExtensions = opaqueExtensions.entries.associate { it.key to it.value.copyOf() }
        val extensionNames: Set<String> = opaqueExtensions.keys.toSet()
        fun extensionCopy(name: String): ByteArray? = opaqueExtensions[name]?.copyOf()
    }

    data class Rejected(val problem: LauncherPayloadProblem) : LauncherPayloadDecodeResult
}

/** Deterministic, version-tagged representation of the complete portable Room aggregate. */
object LauncherSnapshotPayloadCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
    const val MAX_RECORDS = 100_000

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(
        snapshot: LauncherSnapshot,
        opaqueExtensions: Map<String, ByteArray> = emptyMap(),
    ): LauncherPayloadEncodeResult {
        return try {
            if (recordCount(snapshot) > MAX_RECORDS || !validExtensions(opaqueExtensions)) {
                return LauncherPayloadEncodeResult.Rejected(LauncherPayloadProblem.OVERSIZED)
            }
            val payload = SnapshotPayload.from(snapshot, opaqueExtensions)
            val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_PAYLOAD_BYTES) {
                LauncherPayloadEncodeResult.Rejected(LauncherPayloadProblem.OVERSIZED)
            } else {
                LauncherPayloadEncodeResult.Encoded(bytes)
            }
        } catch (_: IllegalArgumentException) {
            LauncherPayloadEncodeResult.Rejected(LauncherPayloadProblem.INVALID_STATE)
        }
    }

    fun decode(bytes: ByteArray): LauncherPayloadDecodeResult {
        if (bytes.size > MAX_PAYLOAD_BYTES) {
            return LauncherPayloadDecodeResult.Rejected(LauncherPayloadProblem.OVERSIZED)
        }
        return try {
            val payload = json.decodeFromString<SnapshotPayload>(bytes.toString(Charsets.UTF_8))
            if (payload.schemaVersion != SCHEMA_VERSION) {
                return LauncherPayloadDecodeResult.Rejected(LauncherPayloadProblem.UNSUPPORTED_VERSION)
            }
            if (payload.recordCount() > MAX_RECORDS) {
                return LauncherPayloadDecodeResult.Rejected(LauncherPayloadProblem.OVERSIZED)
            }
            val extensions = payload.extensions.associate { extension ->
                require(extension.name.matches(Regex("[a-z][a-z0-9._-]{0,63}")))
                extension.name to Base64.getDecoder().decode(extension.base64)
            }
            if (extensions.size != payload.extensions.size || !validExtensions(extensions)) {
                return LauncherPayloadDecodeResult.Rejected(LauncherPayloadProblem.INVALID_STATE)
            }
            LauncherPayloadDecodeResult.Decoded(payload.toSnapshot(), immutableByteMap(extensions))
        } catch (_: SerializationException) {
            LauncherPayloadDecodeResult.Rejected(LauncherPayloadProblem.MALFORMED)
        } catch (_: IllegalArgumentException) {
            LauncherPayloadDecodeResult.Rejected(LauncherPayloadProblem.INVALID_STATE)
        }
    }

    private fun recordCount(snapshot: LauncherSnapshot): Int = listOf(
        snapshot.destinations,
        snapshot.destinationLayouts,
        snapshot.moduleInstances,
        snapshot.configurationDocuments,
        snapshot.placements,
        snapshot.contentItems,
        snapshot.folderMembers,
        snapshot.appOverrides,
        snapshot.widgetPlacements,
        snapshot.themeProfiles,
        snapshot.destinationBackgrounds,
    ).sumOf(List<*>::size)

    private fun validExtensions(values: Map<String, ByteArray>): Boolean =
        values.size <= 64 && values.entries.all { (name, bytes) ->
            name.matches(Regex("[a-z][a-z0-9._-]{0,63}")) && bytes.size <= 64 * 1024
        }

    private fun immutableByteMap(values: Map<String, ByteArray>): Map<String, ByteArray> =
        values.entries.associate { it.key to it.value.copyOf() }.toMap()
}

@Serializable
private data class SnapshotPayload(
    val schemaVersion: Int = LauncherSnapshotPayloadCodec.SCHEMA_VERSION,
    val startDestinationId: String?,
    val destinations: List<DestinationPayload>,
    val layouts: List<LayoutPayload>,
    val instances: List<InstancePayload>,
    val configurations: List<ConfigurationPayload>,
    val placements: List<PlacementPayload>,
    val content: List<ContentPayload>,
    val folderMembers: List<FolderMemberPayload>,
    val appOverrides: List<AppOverridePayload>,
    val widgets: List<WidgetPayload>,
    val themeProfiles: List<ThemeProfilePayload>,
    val backgrounds: List<BackgroundPayload>,
    val extensions: List<ExtensionPayload> = emptyList(),
) {
    fun recordCount(): Int = destinations.size + layouts.size + instances.size + configurations.size +
        placements.size + content.size + folderMembers.size + appOverrides.size + widgets.size +
        themeProfiles.size + backgrounds.size

    fun toSnapshot(): LauncherSnapshot = LauncherSnapshot.restored(
        startDestinationId = startDestinationId?.let(DestinationId::parse),
        destinations = destinations.map { it.toRecord() },
        destinationLayouts = layouts.map { it.toRecord() },
        moduleInstances = instances.map { it.toRecord() },
        configurationDocuments = configurations.map { it.toRecord() },
        placements = placements.map { it.toRecord() },
        contentItems = content.map { it.toRecord() },
        folderMembers = folderMembers.map { it.toRecord() },
        appOverrides = appOverrides.map { it.toRecord() },
        widgetPlacements = widgets.map { it.toRecord() },
        themeProfiles = themeProfiles.map { it.toRecord() },
        destinationBackgrounds = backgrounds.map { it.toRecord() },
    )

    companion object {
        fun from(snapshot: LauncherSnapshot, extensions: Map<String, ByteArray>) = SnapshotPayload(
            startDestinationId = snapshot.startDestinationId?.value,
            destinations = snapshot.destinations.map(DestinationPayload::from),
            layouts = snapshot.destinationLayouts.map(LayoutPayload::from),
            instances = snapshot.moduleInstances.map(InstancePayload::from),
            configurations = snapshot.configurationDocuments.map(ConfigurationPayload::from),
            placements = snapshot.placements.map(PlacementPayload::from),
            content = snapshot.contentItems.map(ContentPayload::from),
            folderMembers = snapshot.folderMembers.map(FolderMemberPayload::from),
            appOverrides = snapshot.appOverrides.map(AppOverridePayload::from),
            widgets = snapshot.widgetPlacements.map(WidgetPayload::from),
            themeProfiles = snapshot.themeProfiles.map(ThemeProfilePayload::from),
            backgrounds = snapshot.destinationBackgrounds.map(BackgroundPayload::from),
            extensions = extensions.toSortedMap().map { (name, bytes) ->
                ExtensionPayload(name, Base64.getEncoder().encodeToString(bytes))
            },
        )
    }
}

@Serializable
private data class DestinationPayload(val id: String, val name: String, val x: Long, val y: Long) {
    fun toRecord() = DestinationRecord(DestinationId.parse(id), name, DestinationCoordinate(x, y))
    companion object { fun from(value: DestinationRecord) = DestinationPayload(value.id.value, value.name, value.coordinate.x, value.coordinate.y) }
}

@Serializable
private data class LayoutPayload(val destinationId: String, val contributionId: String, val instanceId: String, val selected: Boolean) {
    fun toRecord() = DestinationLayoutRecord(DestinationId.parse(destinationId), ContributionId.parse(contributionId), ModuleInstanceId.parse(instanceId), selected)
    companion object { fun from(value: DestinationLayoutRecord) = LayoutPayload(value.destinationId.value, value.layoutContributionId.value, value.layoutInstanceId.value, value.selected) }
}

@Serializable
private data class InstancePayload(
    val id: String,
    val contributionId: String,
    val configurationId: String,
    val quarantineCode: String? = null,
    val quarantineMessage: String? = null,
    val quarantineOrigin: String? = null,
) {
    fun toRecord(): ModuleInstanceRecord {
        val status = if (quarantineCode == null && quarantineMessage == null && quarantineOrigin == null) {
            ModuleInstanceStatus.Active
        } else {
            ModuleInstanceStatus.Quarantined(
                requireNotNull(quarantineCode),
                requireNotNull(quarantineMessage),
                ModuleQuarantineOrigin.valueOf(requireNotNull(quarantineOrigin)),
            )
        }
        return ModuleInstanceRecord(ModuleInstanceId.parse(id), ContributionId.parse(contributionId), ConfigurationDocumentId.parse(configurationId), status)
    }
    companion object {
        fun from(value: ModuleInstanceRecord): InstancePayload {
            val quarantine = value.status as? ModuleInstanceStatus.Quarantined
            return InstancePayload(value.id.value, value.contributionId.value, value.configurationDocumentId.value, quarantine?.code, quarantine?.message, quarantine?.origin?.name)
        }
    }
}

@Serializable
private data class ConfigurationPayload(val id: String, val type: String, val version: Int, val encoded: String, val errorCode: String?) {
    fun toRecord() = StoredConfigurationDocument(
        ConfigurationDocumentId.parse(id),
        ConfigurationDocument(ConfigTypeId.parse(type), SchemaVersion.of(version), EncodedConfiguration.of(encoded)),
        errorCode,
    )
    companion object { fun from(value: StoredConfigurationDocument) = ConfigurationPayload(value.id.value, value.document.configType.value, value.document.schemaVersion.value, value.document.encoded.value, value.lastMigrationErrorCode) }
}

@Serializable
private data class PlacementPayload(
    val id: String,
    val layoutId: String,
    val parentId: String,
    val slotId: String,
    val childId: String,
    val index: Int,
    val data: String,
    val version: Int,
) {
    fun toRecord() = PlacementRecord(PlacementId.parse(id), ModuleInstanceId.parse(layoutId), ModuleInstanceId.parse(parentId), StableKey.parse(slotId), ModuleInstanceId.parse(childId), index, EncodedPlacementData.of(data), version)
    companion object { fun from(value: PlacementRecord) = PlacementPayload(value.id.value, value.layoutInstanceId.value, value.parentInstanceId.value, value.parentSlotId.value, value.childInstanceId.value, value.index, value.data.value, value.placementSchemaVersion) }
}

@Serializable
private data class ContentPayload(
    val id: String,
    val kind: String,
    val encoded: String,
    val profile: Long?,
    val packageName: String?,
    val shortcutId: String?,
) {
    fun toRecord(): ContentItemRecord {
        val parsedKind = ContentItemKind.valueOf(kind)
        return if (parsedKind == ContentItemKind.SHORTCUT) {
            ContentItemRecord.shortcut(
                ContentItemId.parse(id),
                encoded,
                ShortcutTarget(ProfileSerial.of(requireNotNull(profile)), PackageName.parse(requireNotNull(packageName)), ShortcutId.parse(requireNotNull(shortcutId))),
            )
        } else {
            ContentItemRecord(ContentItemId.parse(id), parsedKind, encoded, profile?.let(ProfileSerial::of), packageName?.let(PackageName::parse))
        }
    }
    companion object { fun from(value: ContentItemRecord) = ContentPayload(value.id.value, value.kind.name, value.encoded, value.profile?.value, value.packageName?.value, value.shortcutTarget?.shortcutId?.value) }
}

@Serializable
private data class FolderMemberPayload(val folderId: String, val memberId: String, val index: Int) {
    fun toRecord() = FolderMemberRecord(ContentItemId.parse(folderId), ContentItemId.parse(memberId), index)
    companion object { fun from(value: FolderMemberRecord) = FolderMemberPayload(value.folderId.value, value.memberId.value, value.index) }
}

@Serializable
private data class AppOverridePayload(val profile: Long, val packageName: String, val activityName: String, val customLabel: String?, val encodedIcon: String?, val favorite: Boolean, val collectionVisible: Boolean, val searchVisible: Boolean) {
    fun toRecord() = AppOverrideRecord(ProfileSerial.of(profile), PackageName.parse(packageName), activityName, customLabel, encodedIcon, favorite, collectionVisible, searchVisible)
    companion object { fun from(value: AppOverrideRecord) = AppOverridePayload(value.profile.value, value.packageName.value, value.activityName, value.customLabel, value.encodedIcon, value.favorite, value.collectionVisible, value.searchVisible) }
}

@Serializable
private data class WidgetPayload(val instanceId: String, val packageName: String, val className: String, val profile: Long, val width: Int, val height: Int) {
    fun toRecord() = WidgetPlacementRecord(ModuleInstanceId.parse(instanceId), null, PackageName.parse(packageName), className, ProfileSerial.of(profile), width, height, WidgetBindState.PENDING, WidgetRestoreState.REBIND_REQUIRED, WidgetCleanupState.NONE)
    companion object { fun from(value: WidgetPlacementRecord) = WidgetPayload(value.moduleInstanceId.value, value.providerPackage.value, value.providerClassName, value.profile.value, value.intendedWidthDp, value.intendedHeightDp) }
}

@Serializable
private data class ThemeProfilePayload(val id: String, val name: String, val encoded: String, val version: Int) {
    fun toRecord() = ThemeProfileRecord(ThemeProfileId.parse(id), name, encoded, version)
    companion object { fun from(value: ThemeProfileRecord) = ThemeProfilePayload(value.id.value, value.name, value.encoded, value.schemaVersion) }
}

@Serializable
private data class BackgroundPayload(val destinationId: String, val themeId: String, val encoded: String, val version: Int) {
    fun toRecord() = DestinationBackgroundRecord(DestinationId.parse(destinationId), ThemeProfileId.parse(themeId), encoded, version)
    companion object { fun from(value: DestinationBackgroundRecord) = BackgroundPayload(value.destinationId.value, value.themeProfileId.value, value.encoded, value.schemaVersion) }
}

@Serializable
private data class ExtensionPayload(val name: String, val base64: String)
