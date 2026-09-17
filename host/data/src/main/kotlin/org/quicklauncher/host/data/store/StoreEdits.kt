package org.quicklauncher.host.data.store

import java.util.Collections
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PlacementId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.host.data.spatial.DestinationVector

class LauncherTransaction(
    val expectedRevision: StoreRevision,
    edits: Collection<LauncherEdit>,
) {
    val edits: List<LauncherEdit> = Collections.unmodifiableList(ArrayList(edits))

    init {
        require(this.edits.isNotEmpty()) { "Launcher transaction must contain at least one edit" }
    }
}

data class DestinationInstall(
    val destination: DestinationRecord,
    val layout: DestinationLayoutRecord,
    val layoutInstance: ModuleInstanceRecord,
    val configuration: StoredConfigurationDocument,
)

data class NewPlacedModule(
    val instance: ModuleInstanceRecord,
    val configuration: StoredConfigurationDocument,
    val placement: PlacementRecord,
)

class LauncherPlanInstallation(
    val startDestinationId: DestinationId,
    destinations: Collection<DestinationRecord>,
    layouts: Collection<DestinationLayoutRecord>,
    moduleInstances: Collection<ModuleInstanceRecord>,
    configurations: Collection<StoredConfigurationDocument>,
    placements: Collection<PlacementRecord>,
) {
    val destinations = Collections.unmodifiableList(ArrayList(destinations))
    val layouts = Collections.unmodifiableList(ArrayList(layouts))
    val moduleInstances = Collections.unmodifiableList(ArrayList(moduleInstances))
    val configurations = Collections.unmodifiableList(ArrayList(configurations))
    val placements = Collections.unmodifiableList(ArrayList(placements))

    init {
        require(this.destinations.isNotEmpty()) { "Launcher plan must contain a destination" }
    }
}

data class PlacementDocumentUpdate(
    val placementId: PlacementId,
    val data: EncodedPlacementData,
    val placementSchemaVersion: Int,
) {
    init {
        require(placementSchemaVersion >= 1) { "Placement schema version must be positive" }
    }
}

data class ModuleCopyIdentity(
    val sourceInstanceId: ModuleInstanceId,
    val copiedInstanceId: ModuleInstanceId,
    val copiedConfigurationDocumentId: ConfigurationDocumentId,
    val copiedPlacementId: PlacementId,
    val copiedPlacementData: EncodedPlacementData,
    val copiedPlacementSchemaVersion: Int,
) {
    init {
        require(copiedPlacementSchemaVersion >= 1) {
            "Copied placement schema version must be positive"
        }
    }
}

sealed interface LauncherEdit {
    /** Replaces all portable launcher state after archive decoding and compatibility preparation. */
    data class RestoreSnapshot(
        val snapshot: LauncherSnapshot,
        val rebindWidgets: Boolean = true,
    ) : LauncherEdit

    /** Atomically replaces the pristine first-run safe destination with a validated onboarding plan. */
    data class InstallLauncherPlan(val installation: LauncherPlanInstallation) : LauncherEdit

    data class Bootstrap(val install: DestinationInstall) : LauncherEdit

    data class InstallDestination(val install: DestinationInstall) : LauncherEdit

    class MoveDestinations(
        destinationIds: Collection<DestinationId>,
        val vector: DestinationVector,
    ) : LauncherEdit {
        val destinationIds: Set<DestinationId> =
            Collections.unmodifiableSet(LinkedHashSet(destinationIds))

        init {
            require(this.destinationIds.isNotEmpty()) { "Destination move must not be empty" }
        }
    }

    data class DeleteDestination(
        val destinationId: DestinationId,
        val confirmed: Boolean,
    ) : LauncherEdit

    data class RenameDestination(
        val destinationId: DestinationId,
        val name: String,
    ) : LauncherEdit {
        init {
            require(name.isNotBlank()) { "Destination name must not be blank" }
        }
    }

    data class SetStartDestination(val destinationId: DestinationId) : LauncherEdit

    data class RetainLayout(
        val layout: DestinationLayoutRecord,
        val layoutInstance: ModuleInstanceRecord,
        val configuration: StoredConfigurationDocument,
    ) : LauncherEdit

    data class SelectLayout(
        val destinationId: DestinationId,
        val layoutInstanceId: ModuleInstanceId,
    ) : LauncherEdit

    data class RemoveRetainedLayout(
        val layoutInstanceId: ModuleInstanceId,
        val confirmed: Boolean,
    ) : LauncherEdit

    data class CommitDrop(val module: NewPlacedModule) : LauncherEdit

    class MoveDrop(
        val placementId: PlacementId,
        val parentInstanceId: ModuleInstanceId,
        val parentSlotId: StableKey,
        val index: Int,
        val data: EncodedPlacementData,
        val placementSchemaVersion: Int,
        descendantPlacementDocuments: Collection<PlacementDocumentUpdate> = emptyList(),
    ) : LauncherEdit {
        val descendantPlacementDocuments: List<PlacementDocumentUpdate> =
            Collections.unmodifiableList(ArrayList(descendantPlacementDocuments))

        init {
            require(index >= 0) { "Drop index must not be negative" }
            require(placementSchemaVersion >= 1) { "Placement schema version must be positive" }
        }
    }

    class CopySubtree(
        val sourcePlacementId: PlacementId,
        val targetParentInstanceId: ModuleInstanceId,
        val targetSlotId: StableKey,
        val targetIndex: Int,
        identities: Collection<ModuleCopyIdentity>,
    ) : LauncherEdit {
        val identities: List<ModuleCopyIdentity> =
            Collections.unmodifiableList(ArrayList(identities))

        init {
            require(targetIndex >= 0) { "Copy target index must not be negative" }
            require(this.identities.isNotEmpty()) { "Subtree copy identities must not be empty" }
        }
    }

    data class ReplaceConfiguration(
        val configurationDocumentId: ConfigurationDocumentId,
        val document: ConfigurationDocument,
    ) : LauncherEdit

    data class RemovePlacedSubtree(
        val placementId: PlacementId,
        val confirmed: Boolean,
    ) : LauncherEdit

    /** Records the durable marker that makes an interrupted startup restore detectable. */
    data class BeginStartupRestore(val marker: CrashMarkerRecord) : LauncherEdit

    /** Marks a startup restore successful by removing its no-longer-needed crash marker. */
    data class CompleteStartupRestore(val moduleInstanceId: ModuleInstanceId) : LauncherEdit

    /** Isolates one repeatedly failing renderer and records how its startup attempt ended. */
    data class QuarantineRenderer(
        val moduleInstanceId: ModuleInstanceId,
        val code: String,
        val message: String,
        val outcome: StableKey,
    ) : LauncherEdit {
        init {
            require(code.isNotBlank()) { "Renderer quarantine code must not be blank" }
            require(message.isNotBlank()) { "Renderer quarantine message must not be blank" }
        }
    }

    /** Lets the user explicitly reactivate an instance isolated for renderer failure. */
    data class RetryRenderer(val moduleInstanceId: ModuleInstanceId) : LauncherEdit

    /** Records an allocated framework ID before any bind or configuration UI is launched. */
    data class BeginWidgetBinding(val placement: WidgetPlacementRecord) : LauncherEdit

    data class CompleteWidgetBinding(val moduleInstanceId: ModuleInstanceId) : LauncherEdit

    /** Removes a pending allocation after the framework ID has been deleted. */
    data class CancelWidgetBinding(val moduleInstanceId: ModuleInstanceId) : LauncherEdit

    /** Keeps a retryable placement without retaining the failed framework ID. */
    data class FailWidgetBinding(val moduleInstanceId: ModuleInstanceId) : LauncherEdit

    data class UpdateWidgetSize(
        val moduleInstanceId: ModuleInstanceId,
        val widthDp: Int,
        val heightDp: Int,
    ) : LauncherEdit {
        init {
            require(widthDp > 0 && heightDp > 0) { "Widget dimensions must be positive" }
        }
    }

    data class ReplaceRestoredWidgetId(
        val moduleInstanceId: ModuleInstanceId,
        val appWidgetId: Int,
    ) : LauncherEdit {
        init {
            require(appWidgetId >= 0) { "Widget ID must not be negative" }
        }
    }

    /** Makes framework deletion retryable across process death and adapter failure. */
    data class BeginWidgetDeletion(val moduleInstanceId: ModuleInstanceId) : LauncherEdit

    /** Removes the durable cleanup marker only after the framework confirms deletion. */
    data class CompleteWidgetDeletion(val moduleInstanceId: ModuleInstanceId) : LauncherEdit

    /** Records that an invalid framework binding must be deleted before this placement can rebind. */
    data class BeginWidgetInvalidation(val moduleInstanceId: ModuleInstanceId) : LauncherEdit

    /** Retains the semantic placement and marks it pending rebind after framework cleanup. */
    data class CompleteWidgetInvalidation(val moduleInstanceId: ModuleInstanceId) : LauncherEdit

    data class DeleteWidgetPlacement(val moduleInstanceId: ModuleInstanceId) : LauncherEdit

    data class CreateShortcutPlacement(val item: ContentItemRecord) : LauncherEdit

    data class RemoveShortcutPlacement(val contentItemId: ContentItemId) : LauncherEdit

    class CreateFolder(
        val folder: ContentItemRecord,
        members: Collection<ContentItemId> = emptyList(),
    ) : LauncherEdit {
        val members: List<ContentItemId> = Collections.unmodifiableList(ArrayList(members))
    }

    data class RenameFolder(val folderId: ContentItemId, val encoded: String) : LauncherEdit

    class SetFolderMembers(
        val folderId: ContentItemId,
        members: Collection<ContentItemId>,
    ) : LauncherEdit {
        val members: List<ContentItemId> = Collections.unmodifiableList(ArrayList(members))
    }

    /** Adds or removes one shared content reference while preserving explicit folder order. */
    data class SetFolderMembership(
        val folderId: ContentItemId,
        val member: ContentItemRecord,
        val included: Boolean,
    ) : LauncherEdit

    data class DeleteFolder(val folderId: ContentItemId, val confirmed: Boolean) : LauncherEdit

    data class PutAppOverride(val override: AppOverrideRecord) : LauncherEdit

    data class RemoveAppOverride(
        val profile: org.quicklauncher.contracts.domain.ProfileSerial,
        val packageName: org.quicklauncher.contracts.domain.PackageName,
        val activityName: String,
    ) : LauncherEdit

    /** Replaces one complete validated opaque profile record in the Room transaction domain. */
    data class PutThemeProfile(val profile: ThemeProfileRecord) : LauncherEdit

    data class DeleteThemeProfile(
        val themeProfileId: org.quicklauncher.contracts.domain.ThemeProfileId,
        val confirmed: Boolean,
    ) : LauncherEdit

    /** Replaces the complete destination override so readers never observe partial background state. */
    data class PutDestinationBackground(val background: DestinationBackgroundRecord) : LauncherEdit

    data class DeleteDestinationBackground(val destinationId: DestinationId) : LauncherEdit
}
