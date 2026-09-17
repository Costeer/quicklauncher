package org.quicklauncher.host.data.store

import java.util.Collections
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.CrashMarkerId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.PlacementId
import org.quicklauncher.contracts.domain.ProfilePackageIdentity
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.host.data.spatial.DestinationCoordinate

@JvmInline
value class StoreRevision private constructor(val value: Long) {
    fun next(): StoreRevision {
        check(value < Long.MAX_VALUE) { "Store revision is exhausted" }
        return StoreRevision(value + 1L)
    }

    companion object {
        val ZERO: StoreRevision = StoreRevision(0L)

        fun of(value: Long): StoreRevision {
            require(value >= 0L) { "Store revision must not be negative; received $value" }
            return StoreRevision(value)
        }
    }
}

@JvmInline
value class EncodedPlacementData private constructor(val value: String) {
    companion object {
        fun of(value: String): EncodedPlacementData = EncodedPlacementData(value)
    }
}

data class DestinationRecord(
    val id: DestinationId,
    val name: String,
    val coordinate: DestinationCoordinate,
) {
    init {
        require(name.isNotBlank()) { "Destination name must not be blank" }
    }
}

data class DestinationLayoutRecord(
    val destinationId: DestinationId,
    val layoutContributionId: ContributionId,
    val layoutInstanceId: ModuleInstanceId,
    val selected: Boolean,
)

sealed interface ModuleInstanceStatus {
    data object Active : ModuleInstanceStatus

    data class Quarantined(
        val code: String,
        val message: String,
        val origin: ModuleQuarantineOrigin = ModuleQuarantineOrigin.OTHER,
    ) : ModuleInstanceStatus {
        init {
            require(code.isNotBlank()) { "Quarantine code must not be blank" }
            require(message.isNotBlank()) { "Quarantine message must not be blank" }
        }
    }
}

enum class ModuleQuarantineOrigin {
    CONFIGURATION,
    RENDERER,
    RESTORE,
    OTHER,
}

data class ModuleInstanceRecord(
    val id: ModuleInstanceId,
    val contributionId: ContributionId,
    val configurationDocumentId: ConfigurationDocumentId,
    val status: ModuleInstanceStatus = ModuleInstanceStatus.Active,
)

data class StoredConfigurationDocument(
    val id: ConfigurationDocumentId,
    val document: ConfigurationDocument,
    val lastMigrationErrorCode: String? = null,
) {
    init {
        require(lastMigrationErrorCode == null || lastMigrationErrorCode.isNotBlank()) {
            "Migration error code must be null or nonblank"
        }
    }
}

data class PlacementRecord(
    val id: PlacementId,
    val layoutInstanceId: ModuleInstanceId,
    val parentInstanceId: ModuleInstanceId,
    val parentSlotId: StableKey,
    val childInstanceId: ModuleInstanceId,
    val index: Int,
    val data: EncodedPlacementData = EncodedPlacementData.of("{}"),
    val placementSchemaVersion: Int = 1,
) {
    init {
        require(index >= 0) { "Placement index must not be negative" }
        require(placementSchemaVersion >= 1) { "Placement schema version must be positive" }
    }
}

enum class ContentItemKind {
    APP,
    FOLDER,
    FAVORITE,
    SHORTCUT,
    OTHER,
}

data class ShortcutTarget(
    val profile: ProfileSerial,
    val packageName: PackageName,
    val shortcutId: ShortcutId,
)

@ConsistentCopyVisibility
data class ContentItemRecord private constructor(
    val id: ContentItemId,
    val kind: ContentItemKind,
    val encoded: String,
    val packageIdentity: ProfilePackageIdentity?,
    val shortcutTarget: ShortcutTarget?,
) {
    constructor(
        id: ContentItemId,
        kind: ContentItemKind,
        encoded: String,
        profile: ProfileSerial? = null,
        packageName: PackageName? = null,
    ) : this(
        id = id,
        kind = kind,
        encoded = encoded,
        packageIdentity = when {
            profile != null && packageName != null -> ProfilePackageIdentity(profile, packageName)
            else -> null
        },
        shortcutTarget = null,
    ) {
        require((profile == null) == (packageName == null)) {
            "Content profile and package identity must either both be present or both be absent"
        }
    }

    val profile: ProfileSerial?
        get() = shortcutTarget?.profile ?: packageIdentity?.profile

    val packageName: PackageName?
        get() = shortcutTarget?.packageName ?: packageIdentity?.packageName

    init {
        require((kind == ContentItemKind.SHORTCUT) == (shortcutTarget != null)) {
            "Shortcut content must have a structural profile, package, and shortcut ID"
        }
        require(kind != ContentItemKind.SHORTCUT || packageIdentity == null) {
            "Shortcut content must use its structural shortcut target"
        }
    }

    companion object {
        fun shortcut(
            id: ContentItemId,
            encoded: String,
            target: ShortcutTarget,
        ): ContentItemRecord = ContentItemRecord(
            id = id,
            kind = ContentItemKind.SHORTCUT,
            encoded = encoded,
            packageIdentity = null,
            shortcutTarget = target,
        )
    }
}

internal fun restoreContentItem(
    id: ContentItemId,
    kind: ContentItemKind,
    encoded: String,
    profile: ProfileSerial?,
    packageName: PackageName?,
    shortcutId: ShortcutId?,
): ContentItemRecord = when (kind) {
    ContentItemKind.SHORTCUT -> {
        requireNotNull(profile) { "Stored shortcut content is missing its profile" }
        requireNotNull(packageName) { "Stored shortcut content is missing its package" }
        requireNotNull(shortcutId) { "Stored shortcut content is missing its shortcut ID" }
        ContentItemRecord.shortcut(
            id = id,
            encoded = encoded,
            target = ShortcutTarget(profile, packageName, shortcutId),
        )
    }
    else -> {
        require(shortcutId == null) {
            "Only shortcut content may have a stored shortcut ID"
        }
        ContentItemRecord(id, kind, encoded, profile, packageName)
    }
}

data class FolderMemberRecord(
    val folderId: ContentItemId,
    val memberId: ContentItemId,
    val index: Int,
) {
    init {
        require(index >= 0) { "Folder-member index must not be negative" }
    }
}

data class AppOverrideRecord(
    val profile: ProfileSerial,
    val packageName: PackageName,
    val activityName: String,
    val customLabel: String?,
    val encodedIcon: String?,
    val favorite: Boolean,
    val collectionVisible: Boolean,
    val searchVisible: Boolean,
) {
    init {
        require(activityName.isNotBlank()) { "Activity name must not be blank" }
        require(customLabel == null || customLabel.isNotBlank()) {
            "Custom app label must be null or nonblank"
        }
    }
}

enum class WidgetBindState {
    PENDING,
    BOUND,
    FAILED,
}

enum class WidgetRestoreState {
    READY,
    REBIND_REQUIRED,
}

enum class WidgetCleanupState {
    NONE,
    DELETE_PENDING,
    REBIND_PENDING,
}

data class WidgetPlacementRecord(
    val moduleInstanceId: ModuleInstanceId,
    val appWidgetId: Int?,
    val providerPackage: PackageName,
    val providerClassName: String,
    val profile: ProfileSerial,
    val intendedWidthDp: Int,
    val intendedHeightDp: Int,
    val bindState: WidgetBindState,
    val restoreState: WidgetRestoreState,
    val cleanupState: WidgetCleanupState = WidgetCleanupState.NONE,
) {
    init {
        require(appWidgetId == null || appWidgetId >= 0) { "Widget ID must be null or nonnegative" }
        require(providerClassName.isNotBlank()) { "Widget provider class name must not be blank" }
        require(intendedWidthDp > 0 && intendedHeightDp > 0) {
            "Widget intended size must be positive"
        }
    }
}

data class ThemeProfileRecord(
    val id: ThemeProfileId,
    val name: String,
    val encoded: String,
    val schemaVersion: Int = 1,
) {
    init {
        require(id.value.length <= 200) { "Theme profile identity exceeds the durable bound" }
        require(name.isNotBlank() && name.length <= 80) {
            "Theme profile name must contain 1 to 80 characters"
        }
        require(encoded.length <= 256 * 1024) { "Encoded theme profile exceeds the durable bound" }
        require(schemaVersion >= 1) { "Theme profile schema version must be positive" }
    }
}

data class DestinationBackgroundRecord(
    val destinationId: DestinationId,
    val themeProfileId: ThemeProfileId,
    val encoded: String,
    val schemaVersion: Int = 1,
) {
    init {
        require(destinationId.value.length <= 200 && themeProfileId.value.length <= 200) {
            "Destination-background identity exceeds the durable bound"
        }
        require(encoded.length <= 64 * 1024) {
            "Encoded destination background exceeds the durable bound"
        }
        require(schemaVersion >= 1) { "Destination-background schema version must be positive" }
    }
}

data class CrashMarkerRecord(
    val id: CrashMarkerId,
    val moduleInstanceId: ModuleInstanceId,
    val appVersion: String,
    val startupAttempt: Int,
    val timestampEpochMillis: Long,
    val outcome: StableKey?,
    val firstObservedAtEpochMillis: Long = timestampEpochMillis,
) {
    init {
        require(appVersion.isNotBlank()) { "Crash-marker app version must not be blank" }
        require(startupAttempt >= 1) { "Crash-marker startup attempt must be positive" }
        require(timestampEpochMillis >= 0L) { "Crash-marker timestamp must not be negative" }
        require(firstObservedAtEpochMillis in 0L..timestampEpochMillis) {
            "Crash-marker first observation must be nonnegative and not after its last observation"
        }
    }
}

class LauncherSnapshot internal constructor(
    val revision: StoreRevision,
    val startDestinationId: DestinationId?,
    destinations: Collection<DestinationRecord>,
    destinationLayouts: Collection<DestinationLayoutRecord>,
    moduleInstances: Collection<ModuleInstanceRecord>,
    configurationDocuments: Collection<StoredConfigurationDocument>,
    placements: Collection<PlacementRecord>,
    contentItems: Collection<ContentItemRecord> = emptyList(),
    folderMembers: Collection<FolderMemberRecord> = emptyList(),
    appOverrides: Collection<AppOverrideRecord> = emptyList(),
    widgetPlacements: Collection<WidgetPlacementRecord> = emptyList(),
    themeProfiles: Collection<ThemeProfileRecord> = emptyList(),
    destinationBackgrounds: Collection<DestinationBackgroundRecord> = emptyList(),
    crashMarkers: Collection<CrashMarkerRecord> = emptyList(),
) {
    val destinations: List<DestinationRecord> = immutableList(destinations.sortedBy { it.id.value })
    val destinationLayouts: List<DestinationLayoutRecord> = immutableList(
        destinationLayouts.sortedWith(
            compareBy({ it.destinationId.value }, { it.layoutContributionId.value }, { it.layoutInstanceId.value }),
        ),
    )
    val moduleInstances: List<ModuleInstanceRecord> = immutableList(
        moduleInstances.sortedBy { it.id.value },
    )
    val configurationDocuments: List<StoredConfigurationDocument> =
        immutableList(configurationDocuments.sortedBy { it.id.value })
    val placements: List<PlacementRecord> = immutableList(placements.sortedBy { it.id.value })
    val contentItems: List<ContentItemRecord> = immutableList(contentItems.sortedBy { it.id.value })
    val folderMembers: List<FolderMemberRecord> = immutableList(
        folderMembers.sortedWith(compareBy({ it.folderId.value }, { it.index }, { it.memberId.value })),
    )
    val appOverrides: List<AppOverrideRecord> = immutableList(
        appOverrides.sortedWith(compareBy({ it.profile.value }, { it.packageName.value }, { it.activityName })),
    )
    val widgetPlacements: List<WidgetPlacementRecord> = immutableList(
        widgetPlacements.sortedBy { it.moduleInstanceId.value },
    )
    val themeProfiles: List<ThemeProfileRecord> = immutableList(themeProfiles.sortedBy { it.id.value })
    val destinationBackgrounds: List<DestinationBackgroundRecord> =
        immutableList(destinationBackgrounds.sortedBy { it.destinationId.value })
    val crashMarkers: List<CrashMarkerRecord> = immutableList(crashMarkers.sortedBy { it.id.value })

    /**
     * Rebuilds the composition-owned portion of a durable snapshot after decoding persisted data.
     * The result remains immutable; validation belongs to the consuming recovery module.
     */
    fun withRestoredComposition(
        destinationLayouts: Collection<DestinationLayoutRecord> = this.destinationLayouts,
        configurationDocuments: Collection<StoredConfigurationDocument> = this.configurationDocuments,
        placements: Collection<PlacementRecord> = this.placements,
    ): LauncherSnapshot = LauncherSnapshot(
        revision = revision,
        startDestinationId = startDestinationId,
        destinations = destinations,
        destinationLayouts = destinationLayouts,
        moduleInstances = moduleInstances,
        configurationDocuments = configurationDocuments,
        placements = placements,
        contentItems = contentItems,
        folderMembers = folderMembers,
        appOverrides = appOverrides,
        widgetPlacements = widgetPlacements,
        themeProfiles = themeProfiles,
        destinationBackgrounds = destinationBackgrounds,
        crashMarkers = crashMarkers,
    )

    override fun equals(other: Any?): Boolean = other is LauncherSnapshot &&
        revision == other.revision &&
        startDestinationId == other.startDestinationId &&
        destinations == other.destinations &&
        destinationLayouts == other.destinationLayouts &&
        moduleInstances == other.moduleInstances &&
        configurationDocuments == other.configurationDocuments &&
        placements == other.placements &&
        contentItems == other.contentItems &&
        folderMembers == other.folderMembers &&
        appOverrides == other.appOverrides &&
        widgetPlacements == other.widgetPlacements &&
        themeProfiles == other.themeProfiles &&
        destinationBackgrounds == other.destinationBackgrounds &&
        crashMarkers == other.crashMarkers

    override fun hashCode(): Int = listOf(
        revision,
        startDestinationId,
        destinations,
        destinationLayouts,
        moduleInstances,
        configurationDocuments,
        placements,
        contentItems,
        folderMembers,
        appOverrides,
        widgetPlacements,
        themeProfiles,
        destinationBackgrounds,
        crashMarkers,
    ).hashCode()

    override fun toString(): String =
        "LauncherSnapshot(revision=$revision, startDestinationId=$startDestinationId, " +
            "destinations=$destinations, destinationLayouts=$destinationLayouts, " +
            "moduleInstances=$moduleInstances, configurationDocuments=$configurationDocuments, " +
            "placements=$placements, contentItems=$contentItems, folderMembers=$folderMembers, " +
            "appOverrides=$appOverrides, widgetPlacements=$widgetPlacements, " +
            "themeProfiles=$themeProfiles, destinationBackgrounds=$destinationBackgrounds, " +
            "crashMarkers=$crashMarkers)"

    companion object {
        fun empty(): LauncherSnapshot = LauncherSnapshot(
            revision = StoreRevision.ZERO,
            startDestinationId = null,
            destinations = emptyList(),
            destinationLayouts = emptyList(),
            moduleInstances = emptyList(),
            configurationDocuments = emptyList(),
            placements = emptyList(),
        )

        /** Builds the immutable composition snapshot shown before an atomic plan installation. */
        fun preview(installation: LauncherPlanInstallation): LauncherSnapshot = LauncherSnapshot(
            revision = StoreRevision.ZERO,
            startDestinationId = installation.startDestinationId,
            destinations = installation.destinations,
            destinationLayouts = installation.layouts,
            moduleInstances = installation.moduleInstances,
            configurationDocuments = installation.configurations,
            placements = installation.placements,
        )

        /**
         * Rebuilds a complete portable snapshot before the store validates it as one atomic edit.
         * The archived revision is deliberately ignored: a successful restore receives the next
         * local revision and can never move optimistic-concurrency state backwards.
         */
        fun restored(
            startDestinationId: DestinationId?,
            destinations: Collection<DestinationRecord>,
            destinationLayouts: Collection<DestinationLayoutRecord>,
            moduleInstances: Collection<ModuleInstanceRecord>,
            configurationDocuments: Collection<StoredConfigurationDocument>,
            placements: Collection<PlacementRecord>,
            contentItems: Collection<ContentItemRecord> = emptyList(),
            folderMembers: Collection<FolderMemberRecord> = emptyList(),
            appOverrides: Collection<AppOverrideRecord> = emptyList(),
            widgetPlacements: Collection<WidgetPlacementRecord> = emptyList(),
            themeProfiles: Collection<ThemeProfileRecord> = emptyList(),
            destinationBackgrounds: Collection<DestinationBackgroundRecord> = emptyList(),
        ): LauncherSnapshot = LauncherSnapshot(
            revision = StoreRevision.ZERO,
            startDestinationId = startDestinationId,
            destinations = destinations,
            destinationLayouts = destinationLayouts,
            moduleInstances = moduleInstances,
            configurationDocuments = configurationDocuments,
            placements = placements,
            contentItems = contentItems,
            folderMembers = folderMembers,
            appOverrides = appOverrides,
            widgetPlacements = widgetPlacements,
            themeProfiles = themeProfiles,
            destinationBackgrounds = destinationBackgrounds,
            crashMarkers = emptyList(),
        )
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
