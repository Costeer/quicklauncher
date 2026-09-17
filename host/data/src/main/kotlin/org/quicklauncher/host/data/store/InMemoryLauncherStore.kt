package org.quicklauncher.host.data.store

import kotlin.coroutines.coroutineContext
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.quicklauncher.contracts.contribution.ConfigurationError
import org.quicklauncher.contracts.contribution.ConfigurationLoadResult
import org.quicklauncher.contracts.contribution.ContributionTypes
import org.quicklauncher.host.data.spatial.DestinationMap
import org.quicklauncher.host.data.spatial.DestinationMapEditResult
import org.quicklauncher.host.data.spatial.DestinationMapRejection
import org.quicklauncher.host.data.spatial.DestinationNode

class InMemoryLauncherStore(
    initialState: LauncherSnapshot = LauncherSnapshot.empty(),
    private val configurationResolver: ConfigurationResolver = EmptyConfigurationResolver,
    private val placementPolicy: PlacementPolicy = RejectUnresolvedPlacementPolicy,
) : LauncherStore {
    private val mutex = Mutex()
    private var state: LauncherSnapshot = initialState

    override suspend fun read(): LauncherSnapshot = mutex.withLock { state }

    override suspend fun readAppOverrides(): List<AppOverrideRecord> = mutex.withLock {
        state.appOverrides
    }

    override suspend fun commit(transaction: LauncherTransaction): CommitResult = mutex.withLock {
        coroutineContext.ensureActive()
        val before = state
        if (transaction.expectedRevision != before.revision) {
            return@withLock CommitResult.Rejected(
                StoreRejection(
                    StoreRejectionCode.STALE_REVISION,
                    "Expected revision ${transaction.expectedRevision.value}, current revision is " +
                        before.revision.value,
                ),
                before,
            )
        }

        val mutable = MutableStoreState(before, placementPolicy, configurationResolver)
        transaction.edits.forEach { edit ->
            coroutineContext.ensureActive()
            val rejection = mutable.apply(edit)
            if (rejection != null) return@withLock CommitResult.Rejected(rejection, before)
        }
        val rejection = mutable.validate()
        if (rejection != null) return@withLock CommitResult.Rejected(rejection, before)

        coroutineContext.ensureActive()
        val committed = mutable.snapshot(before.revision.next())
        state = committed
        CommitResult.Committed(committed)
    }

    override suspend fun reconcileConfigurations(): ConfigurationReconciliation = mutex.withLock {
        coroutineContext.ensureActive()
        val before = state
        val mutable = MutableStoreState(before, placementPolicy, configurationResolver)
        val migrated = mutableListOf<org.quicklauncher.contracts.domain.ConfigurationDocumentId>()
        val unknown = mutableListOf<org.quicklauncher.contracts.domain.ConfigurationDocumentId>()
        val failures = mutableListOf<ConfigurationFailure>()
        var changed = false

        mutable.configurationDocuments.values.sortedBy { it.id.value }.forEach { stored ->
            coroutineContext.ensureActive()
            val affected = mutable.moduleInstances.values
                .filter { it.configurationDocumentId == stored.id }
                .sortedBy { it.id.value }
            val resolved = affected.map { instance ->
                instance to configurationResolver.resolve(instance.contributionId)
            }
            if (resolved.all { it.second == null } || resolved.any { it.second == null }) {
                unknown += stored.id
                return@forEach
            }
            val loaders = resolved.map { checkNotNull(it.second) }
            val incompatibleSharedDocument = affected.map(ModuleInstanceRecord::contributionId).toSet().size > 1 ||
                loaders.map(ConfigurationLoader::configType).toSet().size > 1 ||
                loaders.map(ConfigurationLoader::currentSchemaVersion).toSet().size > 1
            if (incompatibleSharedDocument) {
                val error = ConfigurationError(
                    code = "configuration.shared-incompatible",
                    message = "Shared configuration references must resolve to one contribution and codec",
                )
                changed = mutable.recordConfigurationFailure(stored, affected, error) || changed
                failures += ConfigurationFailure(stored.id, affected.map(ModuleInstanceRecord::id), error)
                return@forEach
            }
            val loader = loaders.singleOrNull() ?: loaders.first()
            if (stored.document.configType != loader.configType) {
                val error = ConfigurationError(
                    code = "configuration.type-mismatch",
                    message = "Stored type '${stored.document.configType}' does not match registered type " +
                        "'${loader.configType}'",
                )
                changed = mutable.recordConfigurationFailure(stored, affected, error) || changed
                failures += ConfigurationFailure(stored.id, affected.map(ModuleInstanceRecord::id), error)
                return@forEach
            }
            val loaded = try {
                loader.load(stored.document)
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
            when (loaded) {
                is ConfigurationLoadResult.Loaded<*> -> {
                    val invalidLoadedDocument = when {
                        loaded.document.configType != loader.configType -> ConfigurationError(
                            "configuration.loader-type-mismatch",
                            "Configuration loader returned type '${loaded.document.configType}', expected " +
                                "'${loader.configType}'",
                        )
                        loaded.document.schemaVersion != loader.currentSchemaVersion -> ConfigurationError(
                            "configuration.loader-schema-mismatch",
                            "Configuration loader returned schema '${loaded.document.schemaVersion}', expected " +
                                "'${loader.currentSchemaVersion}'",
                        )
                        else -> null
                    }
                    if (invalidLoadedDocument != null) {
                        changed = mutable.recordConfigurationFailure(
                            stored,
                            affected,
                            invalidLoadedDocument,
                        ) || changed
                        failures += ConfigurationFailure(
                            stored.id,
                            affected.map(ModuleInstanceRecord::id),
                            invalidLoadedDocument,
                        )
                        return@forEach
                    }
                    val updated = stored.copy(
                        document = loaded.document,
                        lastMigrationErrorCode = null,
                    )
                    if (updated != stored) {
                        mutable.configurationDocuments[stored.id] = updated
                        if (loaded.document != stored.document) migrated += stored.id
                        changed = true
                    }
                    affected.forEach { instance ->
                        val quarantine = instance.status as? ModuleInstanceStatus.Quarantined
                        if (quarantine?.origin == ModuleQuarantineOrigin.CONFIGURATION) {
                            mutable.moduleInstances[instance.id] = instance.copy(
                                status = ModuleInstanceStatus.Active,
                            )
                            changed = true
                        }
                    }
                }
                is ConfigurationLoadResult.Failed -> {
                    val affectedIds = affected.map(ModuleInstanceRecord::id)
                    failures += ConfigurationFailure(stored.id, affectedIds, loaded.error)
                    changed = mutable.recordConfigurationFailure(stored, affected, loaded.error) || changed
                }
            }
        }
        coroutineContext.ensureActive()
        val reconciled = if (changed) mutable.snapshot(before.revision.next()) else before
        state = reconciled
        ConfigurationReconciliation(reconciled, migrated, unknown, failures)
    }
}

private class MutableStoreState(
    snapshot: LauncherSnapshot,
    private val placementPolicy: PlacementPolicy,
    private val configurationResolver: ConfigurationResolver,
) {
    var startDestinationId = snapshot.startDestinationId
    val destinations = snapshot.destinations.associateByTo(linkedMapOf(), DestinationRecord::id)
    val destinationLayouts = snapshot.destinationLayouts.toMutableList()
    val moduleInstances = snapshot.moduleInstances.associateByTo(linkedMapOf(), ModuleInstanceRecord::id)
    val configurationDocuments = snapshot.configurationDocuments.associateByTo(
        linkedMapOf(),
        StoredConfigurationDocument::id,
    )
    val placements = snapshot.placements.associateByTo(linkedMapOf(), PlacementRecord::id)

    private val contentItems = snapshot.contentItems.toMutableList()
    private val folderMembers = snapshot.folderMembers.toMutableList()
    private val appOverrides = snapshot.appOverrides.toMutableList()
    private val widgetPlacements = snapshot.widgetPlacements.toMutableList()
    private val themeProfiles = snapshot.themeProfiles.toMutableList()
    private val destinationBackgrounds = snapshot.destinationBackgrounds.toMutableList()
    private val crashMarkers = snapshot.crashMarkers.toMutableList()

    fun recordConfigurationFailure(
        stored: StoredConfigurationDocument,
        affected: Collection<ModuleInstanceRecord>,
        error: ConfigurationError,
    ): Boolean {
        var changed = false
        if (stored.lastMigrationErrorCode != error.code) {
            configurationDocuments[stored.id] = stored.copy(lastMigrationErrorCode = error.code)
            changed = true
        }
        affected.forEach { instance ->
            val existing = instance.status as? ModuleInstanceStatus.Quarantined
            if (existing == null || existing.origin == ModuleQuarantineOrigin.CONFIGURATION) {
                val status = ModuleInstanceStatus.Quarantined(
                    code = error.code,
                    message = error.message,
                    origin = ModuleQuarantineOrigin.CONFIGURATION,
                )
                if (instance.status != status) {
                    moduleInstances[instance.id] = instance.copy(status = status)
                    changed = true
                }
            }
        }
        return changed
    }

    fun apply(edit: LauncherEdit): StoreRejection? = when (edit) {
        is LauncherEdit.RestoreSnapshot -> restoreSnapshot(edit.snapshot, edit.rebindWidgets)
        is LauncherEdit.InstallLauncherPlan -> installLauncherPlan(edit.installation)
        is LauncherEdit.Bootstrap -> bootstrap(edit.install)
        is LauncherEdit.InstallDestination -> install(edit.install)
        is LauncherEdit.MoveDestinations -> moveDestinations(edit)
        is LauncherEdit.DeleteDestination -> deleteDestination(edit)
        is LauncherEdit.RenameDestination -> renameDestination(edit)
        is LauncherEdit.SetStartDestination -> setStartDestination(edit)
        is LauncherEdit.RetainLayout -> retainLayout(edit)
        is LauncherEdit.SelectLayout -> selectLayout(edit)
        is LauncherEdit.RemoveRetainedLayout -> removeRetainedLayout(edit)
        is LauncherEdit.CommitDrop -> commitDrop(edit)
        is LauncherEdit.MoveDrop -> moveDrop(edit)
        is LauncherEdit.CopySubtree -> copySubtree(edit)
        is LauncherEdit.ReplaceConfiguration -> replaceConfiguration(edit)
        is LauncherEdit.RemovePlacedSubtree -> removePlacedSubtree(edit)
        is LauncherEdit.BeginStartupRestore -> beginStartupRestore(edit)
        is LauncherEdit.CompleteStartupRestore -> completeStartupRestore(edit)
        is LauncherEdit.QuarantineRenderer -> quarantineRenderer(edit)
        is LauncherEdit.RetryRenderer -> retryRenderer(edit)
        is LauncherEdit.BeginWidgetBinding -> beginWidgetBinding(edit)
        is LauncherEdit.CompleteWidgetBinding -> completeWidgetBinding(edit)
        is LauncherEdit.CancelWidgetBinding -> cancelWidgetBinding(edit)
        is LauncherEdit.FailWidgetBinding -> failWidgetBinding(edit)
        is LauncherEdit.UpdateWidgetSize -> updateWidgetSize(edit)
        is LauncherEdit.ReplaceRestoredWidgetId -> replaceRestoredWidgetId(edit)
        is LauncherEdit.BeginWidgetDeletion -> beginWidgetDeletion(edit)
        is LauncherEdit.CompleteWidgetDeletion -> completeWidgetDeletion(edit)
        is LauncherEdit.BeginWidgetInvalidation -> beginWidgetInvalidation(edit)
        is LauncherEdit.CompleteWidgetInvalidation -> completeWidgetInvalidation(edit)
        is LauncherEdit.DeleteWidgetPlacement -> deleteWidgetPlacement(edit)
        is LauncherEdit.CreateShortcutPlacement -> createShortcutPlacement(edit)
        is LauncherEdit.RemoveShortcutPlacement -> removeShortcutPlacement(edit)
        is LauncherEdit.CreateFolder -> createFolder(edit)
        is LauncherEdit.RenameFolder -> renameFolder(edit)
        is LauncherEdit.SetFolderMembers -> setFolderMembers(edit)
        is LauncherEdit.SetFolderMembership -> setFolderMembership(edit)
        is LauncherEdit.DeleteFolder -> deleteFolder(edit)
        is LauncherEdit.PutAppOverride -> putAppOverride(edit)
        is LauncherEdit.RemoveAppOverride -> removeAppOverride(edit)
        is LauncherEdit.PutThemeProfile -> putThemeProfile(edit)
        is LauncherEdit.DeleteThemeProfile -> deleteThemeProfile(edit)
        is LauncherEdit.PutDestinationBackground -> putDestinationBackground(edit)
        is LauncherEdit.DeleteDestinationBackground -> deleteDestinationBackground(edit)
    }

    private fun restoreSnapshot(snapshot: LauncherSnapshot, rebindWidgets: Boolean): StoreRejection? {
        destinations.clear()
        destinationLayouts.clear()
        moduleInstances.clear()
        configurationDocuments.clear()
        placements.clear()
        contentItems.clear()
        folderMembers.clear()
        appOverrides.clear()
        widgetPlacements.clear()
        themeProfiles.clear()
        destinationBackgrounds.clear()
        crashMarkers.clear()

        startDestinationId = snapshot.startDestinationId
        snapshot.destinations.forEach { destinations[it.id] = it }
        destinationLayouts += snapshot.destinationLayouts
        snapshot.moduleInstances.forEach { moduleInstances[it.id] = it }
        snapshot.configurationDocuments.forEach { configurationDocuments[it.id] = it }
        snapshot.placements.forEach { placements[it.id] = it }
        contentItems += snapshot.contentItems
        folderMembers += snapshot.folderMembers
        appOverrides += snapshot.appOverrides
        widgetPlacements += snapshot.widgetPlacements.map { widget ->
            if (rebindWidgets) {
                widget.copy(
                    appWidgetId = null,
                    bindState = WidgetBindState.PENDING,
                    restoreState = WidgetRestoreState.REBIND_REQUIRED,
                    cleanupState = WidgetCleanupState.NONE,
                )
            } else {
                widget
            }
        }
        themeProfiles += snapshot.themeProfiles
        destinationBackgrounds += snapshot.destinationBackgrounds
        return null
    }

    private fun putThemeProfile(edit: LauncherEdit.PutThemeProfile): StoreRejection? {
        themeProfiles.removeAll { it.id == edit.profile.id }
        themeProfiles += edit.profile
        return null
    }

    private fun deleteThemeProfile(edit: LauncherEdit.DeleteThemeProfile): StoreRejection? {
        if (!edit.confirmed) return confirmationRequired("Theme profile removal requires confirmation")
        if (themeProfiles.none { it.id == edit.themeProfileId }) {
            return notFound("Theme profile does not exist")
        }
        if (destinationBackgrounds.any { it.themeProfileId == edit.themeProfileId }) {
            return recoveryStateMismatch("Theme profile still owns destination backgrounds")
        }
        themeProfiles.removeAll { it.id == edit.themeProfileId }
        return null
    }

    private fun putDestinationBackground(
        edit: LauncherEdit.PutDestinationBackground,
    ): StoreRejection? {
        val background = edit.background
        if (background.destinationId !in destinations) {
            return notFound("Destination background target does not exist")
        }
        if (themeProfiles.none { it.id == background.themeProfileId }) {
            return invalidReference("Destination background theme profile does not exist")
        }
        destinationBackgrounds.removeAll { it.destinationId == background.destinationId }
        destinationBackgrounds += background
        return null
    }

    private fun deleteDestinationBackground(
        edit: LauncherEdit.DeleteDestinationBackground,
    ): StoreRejection? {
        destinationBackgrounds.removeAll { it.destinationId == edit.destinationId }
        return null
    }

    fun validate(): StoreRejection? {
        if (destinations.isEmpty()) {
            return if (startDestinationId == null) null else StoreRejection(
                StoreRejectionCode.START_DESTINATION_REQUIRED,
                "An empty store cannot have a start destination",
            )
        }
        if (startDestinationId !in destinations) {
            return StoreRejection(
                StoreRejectionCode.START_DESTINATION_REQUIRED,
                "A populated store must reference one existing start destination",
            )
        }

        val occupied = destinations.values.groupBy(DestinationRecord::coordinate)
        if (occupied.values.any { it.size > 1 }) {
            return StoreRejection(
                StoreRejectionCode.COORDINATE_OCCUPIED,
                "Each destination must occupy a unique coordinate",
            )
        }
        try {
            destinationMap()
        } catch (_: IllegalArgumentException) {
            return StoreRejection(
                StoreRejectionCode.DISCONNECTED_MAP,
                "Every destination must be reachable through cardinal neighbors",
            )
        }

        for (destination in destinations.values) {
            val retained = destinationLayouts.filter { it.destinationId == destination.id }
            if (retained.count(DestinationLayoutRecord::selected) != 1) {
                return StoreRejection(
                    StoreRejectionCode.LAYOUT_SELECTION_REQUIRED,
                    "Destination '${destination.id}' must have exactly one selected layout",
                )
            }
            if (retained.map(DestinationLayoutRecord::layoutContributionId).toSet().size != retained.size) {
                return StoreRejection(
                    StoreRejectionCode.DUPLICATE_ID,
                    "Destination '${destination.id}' has the same layout contribution more than once",
                )
            }
        }
        if (destinationLayouts.map(DestinationLayoutRecord::layoutInstanceId).toSet().size !=
            destinationLayouts.size
        ) {
            return StoreRejection(
                StoreRejectionCode.DUPLICATE_ID,
                "A layout instance may be retained by only one destination",
            )
        }
        for (layout in destinationLayouts) {
            val root = moduleInstances[layout.layoutInstanceId]
            if (
                layout.destinationId !in destinations ||
                root == null ||
                root.contributionId != layout.layoutContributionId
            ) {
                return StoreRejection(
                    StoreRejectionCode.INVALID_REFERENCE,
                    "Retained layout '${layout.layoutInstanceId}' has an invalid destination or root",
                )
            }
        }

        for (instance in moduleInstances.values) {
            val configuration = configurationDocuments[instance.configurationDocumentId]
            if (configuration == null) {
                return StoreRejection(
                    StoreRejectionCode.INVALID_REFERENCE,
                    "Module instance '${instance.id}' has no configuration document",
                )
            }
            val registration = configurationResolver.resolve(instance.contributionId)
            if (registration != null && configuration.document.configType != registration.configType) {
                return StoreRejection(
                    StoreRejectionCode.CONFIGURATION_TYPE_MISMATCH,
                    "Module instance '${instance.id}' uses configuration type " +
                        "'${configuration.document.configType}', but '${instance.contributionId}' requires " +
                        "'${registration.configType}'",
                )
            }
        }
        val retainedRootIds = destinationLayouts.map(DestinationLayoutRecord::layoutInstanceId).toSet()
        val placedChildIds = placements.values.map(PlacementRecord::childInstanceId).toSet()
        moduleInstances.values.forEach { instance ->
            val registration = configurationResolver.resolve(instance.contributionId) ?: return@forEach
            val expectedCategory = when (instance.id) {
                in retainedRootIds -> ContributionTypes.LAYOUT
                in placedChildIds -> ContributionTypes.BLOCK
                else -> null
            }
            if (expectedCategory != null && registration.contributionTypeId != expectedCategory) {
                return StoreRejection(
                    StoreRejectionCode.CONTRIBUTION_CATEGORY_MISMATCH,
                    "Module instance '${instance.id}' is stored as '${expectedCategory.value}', but " +
                        "'${instance.contributionId}' is '${registration.contributionTypeId.value}'",
                )
            }
        }
        val referencedConfigurations = moduleInstances.values
            .map(ModuleInstanceRecord::configurationDocumentId)
            .toSet()
        if (configurationDocuments.keys.any { it !in referencedConfigurations }) {
            return StoreRejection(
                StoreRejectionCode.INVALID_REFERENCE,
                "Every configuration document must be referenced by a module instance",
            )
        }
        val sharedConfigurationMismatch = moduleInstances.values
            .groupBy(ModuleInstanceRecord::configurationDocumentId)
            .values
            .firstOrNull { instances ->
                instances.map(ModuleInstanceRecord::contributionId).toSet().size > 1
            }
        if (sharedConfigurationMismatch != null) {
            return StoreRejection(
                StoreRejectionCode.CONFIGURATION_TYPE_MISMATCH,
                "A shared configuration document may only be referenced by instances of one contribution",
            )
        }

        val placementRejection = validatePlacements()
        if (placementRejection != null) return placementRejection

        if (contentItems.map(ContentItemRecord::id).toSet().size != contentItems.size) {
            return duplicate("content item")
        }
        if (appOverrides.map { Triple(it.profile, it.packageName, it.activityName) }.toSet().size !=
            appOverrides.size
        ) {
            return duplicate("app override")
        }
        for (member in folderMembers) {
            if (
                contentItems.none { it.id == member.memberId } ||
                contentItems.none { it.id == member.folderId && it.kind == ContentItemKind.FOLDER }
            ) {
                return invalidReference("Folder membership refers to missing or non-folder content")
            }
        }
        if (folderMembers.groupBy { it.folderId to it.index }.values.any { it.size > 1 }) {
            return duplicate("folder position")
        }
        if (folderMembers.groupBy { it.folderId to it.memberId }.values.any { it.size > 1 }) {
            return duplicate("folder member")
        }
        if (widgetPlacements.any { it.moduleInstanceId !in moduleInstances }) {
            return invalidReference("Widget placement refers to a missing module instance")
        }
        val widgetIds = widgetPlacements.mapNotNull(WidgetPlacementRecord::appWidgetId)
        if (widgetIds.toSet().size != widgetIds.size) return duplicate("Android widget ID")
        if (widgetPlacements.map(WidgetPlacementRecord::moduleInstanceId).toSet().size !=
            widgetPlacements.size
        ) {
            return duplicate("widget module instance")
        }
        if (widgetPlacements.any {
                it.cleanupState != WidgetCleanupState.NONE && it.appWidgetId == null
            }
        ) {
            return invalidReference("Pending widget cleanup must retain its Android widget ID")
        }
        if (widgetPlacements.any { placement ->
                when (placement.bindState) {
                    WidgetBindState.BOUND ->
                        placement.appWidgetId == null || placement.restoreState != WidgetRestoreState.READY
                    WidgetBindState.FAILED ->
                        placement.appWidgetId != null || placement.cleanupState != WidgetCleanupState.NONE
                    WidgetBindState.PENDING ->
                        placement.appWidgetId == null &&
                            placement.restoreState != WidgetRestoreState.REBIND_REQUIRED
                } || (
                    placement.cleanupState == WidgetCleanupState.REBIND_PENDING &&
                        placement.bindState != WidgetBindState.BOUND
                    )
            }
        ) {
            return recoveryStateMismatch("Widget placement state is internally inconsistent")
        }
        if (themeProfiles.map(ThemeProfileRecord::id).toSet().size != themeProfiles.size) {
            return duplicate("theme profile")
        }
        if (themeProfiles.size > MAX_THEME_PROFILES) {
            return configurationInvalid("Theme profile count exceeds the durable bound")
        }
        if (
            destinationBackgrounds.map(DestinationBackgroundRecord::destinationId).toSet().size !=
            destinationBackgrounds.size
        ) {
            return duplicate("destination background")
        }
        if (destinationBackgrounds.size > MAX_DESTINATION_BACKGROUNDS) {
            return configurationInvalid("Destination background count exceeds the durable bound")
        }
        if (destinationBackgrounds.any {
                it.destinationId !in destinations ||
                    themeProfiles.none { theme -> theme.id == it.themeProfileId }
            }
        ) {
            return invalidReference("Destination background refers to a missing destination or theme")
        }
        if (crashMarkers.map(CrashMarkerRecord::id).toSet().size != crashMarkers.size) {
            return duplicate("crash marker")
        }
        if (
            crashMarkers
                .groupBy(CrashMarkerRecord::moduleInstanceId)
                .values
                .any { it.size > 1 }
        ) {
            return duplicate("startup marker for one module instance")
        }
        if (crashMarkers.any { it.moduleInstanceId !in moduleInstances }) {
            return invalidReference("Crash marker refers to a missing module instance")
        }
        return null
    }

    fun snapshot(revision: StoreRevision): LauncherSnapshot = LauncherSnapshot(
        revision = revision,
        startDestinationId = startDestinationId,
        destinations = destinations.values,
        destinationLayouts = destinationLayouts,
        moduleInstances = moduleInstances.values,
        configurationDocuments = configurationDocuments.values,
        placements = placements.values,
        contentItems = contentItems,
        folderMembers = folderMembers,
        appOverrides = appOverrides,
        widgetPlacements = widgetPlacements,
        themeProfiles = themeProfiles,
        destinationBackgrounds = destinationBackgrounds,
        crashMarkers = crashMarkers,
    )

    private fun bootstrap(install: DestinationInstall): StoreRejection? {
        if (destinations.isNotEmpty()) {
            return StoreRejection(
                StoreRejectionCode.ALREADY_BOOTSTRAPPED,
                "Bootstrap requires an empty store",
            )
        }
        val rejection = validateInstall(install)
        if (rejection != null) return rejection

        destinations[install.destination.id] = install.destination
        destinationLayouts += install.layout.copy(selected = true)
        moduleInstances[install.layoutInstance.id] = install.layoutInstance
        configurationDocuments[install.configuration.id] = install.configuration
        startDestinationId = install.destination.id
        return null
    }

    private fun installLauncherPlan(installation: LauncherPlanInstallation): StoreRejection? {
        val pristine = destinations.isEmpty() || (
            moduleInstances.values.all {
                it.contributionId.value == "org.quicklauncher.core/safe-layout"
            } && placements.isEmpty() && contentItems.isEmpty() && folderMembers.isEmpty() &&
                widgetPlacements.isEmpty() && themeProfiles.isEmpty() && destinationBackgrounds.isEmpty()
            )
        if (!pristine) {
            return StoreRejection(
                StoreRejectionCode.ALREADY_BOOTSTRAPPED,
                "Onboarding installation may only replace pristine safe-layout state",
            )
        }
        if (
            installation.destinations.map { it.id }.toSet().size != installation.destinations.size ||
            installation.layouts.map { it.layoutInstanceId }.toSet().size != installation.layouts.size ||
            installation.moduleInstances.map { it.id }.toSet().size != installation.moduleInstances.size ||
            installation.configurations.map { it.id }.toSet().size != installation.configurations.size ||
            installation.placements.map { it.id }.toSet().size != installation.placements.size
        ) {
            return duplicate("onboarding plan identity")
        }
        destinations.clear()
        destinationLayouts.clear()
        moduleInstances.clear()
        configurationDocuments.clear()
        placements.clear()
        crashMarkers.clear()
        installation.destinations.forEach { destinations[it.id] = it }
        destinationLayouts += installation.layouts
        installation.moduleInstances.forEach { moduleInstances[it.id] = it }
        installation.configurations.forEach { configurationDocuments[it.id] = it }
        installation.placements.forEach { placements[it.id] = it }
        startDestinationId = installation.startDestinationId
        return null
    }

    private fun install(install: DestinationInstall): StoreRejection? {
        if (destinations.isEmpty()) {
            return StoreRejection(
                StoreRejectionCode.STORE_EMPTY,
                "The first destination must use bootstrap",
            )
        }
        val rejection = validateInstall(install)
        if (rejection != null) return rejection
        if (
            install.destination.id in destinations ||
            install.layoutInstance.id in moduleInstances ||
            install.configuration.id in configurationDocuments
        ) {
            return duplicate("destination install identity")
        }
        destinations[install.destination.id] = install.destination
        destinationLayouts += install.layout.copy(selected = true)
        moduleInstances[install.layoutInstance.id] = install.layoutInstance
        configurationDocuments[install.configuration.id] = install.configuration
        return null
    }

    private fun moveDestinations(edit: LauncherEdit.MoveDestinations): StoreRejection? {
        if (destinations.isEmpty()) {
            return StoreRejection(
                StoreRejectionCode.STORE_EMPTY,
                "Destinations cannot move before the store is bootstrapped",
            )
        }
        val map = destinationMap()
        return when (val result = map.move(edit.destinationIds, edit.vector)) {
            is DestinationMapEditResult.Updated -> {
                result.map.destinations.forEach { moved ->
                    destinations[moved.id] = destinations.getValue(moved.id).copy(
                        coordinate = moved.coordinate,
                    )
                }
                null
            }
            is DestinationMapEditResult.Rejected -> StoreRejection(
                code = when (result.reason) {
                    DestinationMapRejection.COORDINATE_OCCUPIED -> StoreRejectionCode.COORDINATE_OCCUPIED
                    DestinationMapRejection.COORDINATE_OVERFLOW -> StoreRejectionCode.COORDINATE_OVERFLOW
                    DestinationMapRejection.GROUP_DISCONNECTED ->
                        StoreRejectionCode.DESTINATION_GROUP_DISCONNECTED
                    DestinationMapRejection.MAP_DISCONNECTED -> StoreRejectionCode.DISCONNECTED_MAP
                    DestinationMapRejection.UNKNOWN_DESTINATION -> StoreRejectionCode.NOT_FOUND
                    else -> StoreRejectionCode.INVALID_REFERENCE
                },
                message = "Destination group move was rejected: ${result.reason.name.lowercase()}",
            )
        }
    }

    private fun deleteDestination(edit: LauncherEdit.DeleteDestination): StoreRejection? {
        if (destinations.isEmpty()) {
            return StoreRejection(
                StoreRejectionCode.STORE_EMPTY,
                "Destinations cannot be removed before the store is bootstrapped",
            )
        }
        if (!edit.confirmed) return confirmationRequired("Destination removal requires confirmation")
        when (val result = destinationMap().delete(edit.destinationId)) {
            is DestinationMapEditResult.Updated -> Unit
            is DestinationMapEditResult.Rejected -> return when (result.reason) {
                DestinationMapRejection.UNKNOWN_DESTINATION ->
                    notFound("Destination '${edit.destinationId}' does not exist")
                DestinationMapRejection.CANNOT_DELETE_LAST_DESTINATION -> StoreRejection(
                    StoreRejectionCode.START_DESTINATION_REQUIRED,
                    "The final destination cannot be removed",
                )
                DestinationMapRejection.CANNOT_DELETE_START -> StoreRejection(
                    StoreRejectionCode.START_DESTINATION_REQUIRED,
                    "Select another start destination before removing this destination",
                )
                DestinationMapRejection.MAP_DISCONNECTED -> StoreRejection(
                    StoreRejectionCode.DISCONNECTED_MAP,
                    "Destination removal would disconnect the map",
                )
                else -> invalidReference("Destination removal was rejected: ${result.reason.name.lowercase()}")
            }
        }
        val roots = destinationLayouts
            .filter { it.destinationId == edit.destinationId }
            .map(DestinationLayoutRecord::layoutInstanceId)
            .toSet()
        val removedInstances = instancesInTrees(roots)
        if (widgetPlacements.any { it.moduleInstanceId in removedInstances && it.appWidgetId != null }) {
            return recoveryStateMismatch(
                "Destination removal requires allocated widget IDs to be cleaned up first",
            )
        }
        destinations.remove(edit.destinationId)
        destinationLayouts.removeAll { it.destinationId == edit.destinationId }
        placements.entries.removeAll {
            it.value.layoutInstanceId in roots || it.value.childInstanceId in removedInstances
        }
        removedInstances.forEach(moduleInstances::remove)
        widgetPlacements.removeAll { it.moduleInstanceId in removedInstances }
        crashMarkers.removeAll { it.moduleInstanceId in removedInstances }
        destinationBackgrounds.removeAll { it.destinationId == edit.destinationId }
        removeUnreferencedConfigurations()
        if (startDestinationId == edit.destinationId) startDestinationId = null
        return null
    }

    private fun renameDestination(edit: LauncherEdit.RenameDestination): StoreRejection? {
        val destination = destinations[edit.destinationId]
            ?: return notFound("Destination '${edit.destinationId}' does not exist")
        destinations[edit.destinationId] = destination.copy(name = edit.name)
        return null
    }

    private fun setStartDestination(edit: LauncherEdit.SetStartDestination): StoreRejection? {
        if (edit.destinationId !in destinations) {
            return notFound("Destination '${edit.destinationId}' does not exist")
        }
        startDestinationId = edit.destinationId
        return null
    }

    private fun retainLayout(edit: LauncherEdit.RetainLayout): StoreRejection? {
        val destination = destinations[edit.layout.destinationId]
            ?: return notFound("Destination '${edit.layout.destinationId}' does not exist")
        if (destination.id != edit.layout.destinationId) return invalidReference("Invalid destination")
        val install = DestinationInstall(
            destination = destination,
            layout = edit.layout,
            layoutInstance = edit.layoutInstance,
            configuration = edit.configuration,
        )
        val rejection = validateInstall(install)
        if (rejection != null) return rejection
        if (
            edit.layoutInstance.id in moduleInstances ||
            edit.configuration.id in configurationDocuments ||
            destinationLayouts.any {
                it.destinationId == edit.layout.destinationId &&
                    it.layoutContributionId == edit.layout.layoutContributionId
            }
        ) {
            return duplicate("retained layout identity")
        }
        destinationLayouts += edit.layout.copy(selected = false)
        moduleInstances[edit.layoutInstance.id] = edit.layoutInstance
        configurationDocuments[edit.configuration.id] = edit.configuration
        return null
    }

    private fun selectLayout(edit: LauncherEdit.SelectLayout): StoreRejection? {
        val target = destinationLayouts.firstOrNull {
            it.destinationId == edit.destinationId && it.layoutInstanceId == edit.layoutInstanceId
        } ?: return notFound("Retained layout '${edit.layoutInstanceId}' does not exist")
        destinationLayouts.replaceAll { layout ->
            if (layout.destinationId == target.destinationId) {
                layout.copy(selected = layout.layoutInstanceId == target.layoutInstanceId)
            } else {
                layout
            }
        }
        return null
    }

    private fun removeRetainedLayout(edit: LauncherEdit.RemoveRetainedLayout): StoreRejection? {
        val retained = destinationLayouts.singleOrNull {
            it.layoutInstanceId == edit.layoutInstanceId
        } ?: return notFound("Retained layout '${edit.layoutInstanceId}' does not exist")
        if (retained.selected) {
            return StoreRejection(
                StoreRejectionCode.LAYOUT_SELECTION_REQUIRED,
                "Select another layout before removing '${edit.layoutInstanceId}'",
            )
        }
        if (retained.layoutContributionId.value == "org.quicklauncher.core/safe-layout") {
            return invalidReference("The host-owned safe layout cannot be removed")
        }
        if (!edit.confirmed) return confirmationRequired("Retained layout removal requires confirmation")

        val removedInstances = instancesInTrees(setOf(retained.layoutInstanceId))
        if (widgetPlacements.any { it.moduleInstanceId in removedInstances && it.appWidgetId != null }) {
            return recoveryStateMismatch(
                "Retained layout removal requires allocated widget IDs to be cleaned up first",
            )
        }
        destinationLayouts.remove(retained)
        placements.entries.removeAll {
            it.value.layoutInstanceId == retained.layoutInstanceId ||
                it.value.childInstanceId in removedInstances
        }
        removedInstances.forEach(moduleInstances::remove)
        widgetPlacements.removeAll { it.moduleInstanceId in removedInstances }
        crashMarkers.removeAll { it.moduleInstanceId in removedInstances }
        removeUnreferencedConfigurations()
        return null
    }

    private fun commitDrop(edit: LauncherEdit.CommitDrop): StoreRejection? {
        val module = edit.module
        val placement = module.placement
        if (
            module.instance.id in moduleInstances ||
            module.configuration.id in configurationDocuments ||
            placement.id in placements
        ) {
            return duplicate("drop identity")
        }
        if (
            module.instance.configurationDocumentId != module.configuration.id ||
            placement.childInstanceId != module.instance.id
        ) {
            return invalidReference("Drop records do not reference each other")
        }
        val parent = moduleInstances[placement.parentInstanceId]
            ?: return notFound("Drop parent '${placement.parentInstanceId}' does not exist")
        if (!parentBelongsToTree(placement.parentInstanceId, placement.layoutInstanceId)) {
            return invalidReference("Drop parent does not belong to the target layout tree")
        }
        val siblings = siblingsOf(placement)
        if (placement.index > siblings.size) {
            return StoreRejection(
                StoreRejectionCode.PLACEMENT_INDEX_CONFLICT,
                "Drop index ${placement.index} exceeds sibling count ${siblings.size}",
            )
        }
        val policyRejection = placementPolicy.validate(
            PlacementRequest(
                parent = parent,
                child = module.instance,
                parentSlotId = placement.parentSlotId,
                childCountAfterDrop = siblings.size + 1,
            ),
        )
        if (policyRejection != null) return policyRejection

        shiftForInsertion(placement, excluding = null)
        configurationDocuments[module.configuration.id] = module.configuration
        moduleInstances[module.instance.id] = module.instance
        placements[placement.id] = placement
        return null
    }

    private fun moveDrop(edit: LauncherEdit.MoveDrop): StoreRejection? {
        val existing = placements[edit.placementId]
            ?: return notFound("Placement '${edit.placementId}' does not exist")
        val movedSubtreeInstances = subtreeInstances(existing.childInstanceId)
        val targetTree = treeForInstance(edit.parentInstanceId)
            ?: return invalidReference("Drop parent does not belong to a retained layout tree")
        val descendantPlacements = placements.values.filter {
            it.id != existing.id && it.childInstanceId in movedSubtreeInstances
        }
        val targetDocuments = edit.descendantPlacementDocuments.associateBy(
            PlacementDocumentUpdate::placementId,
        )
        if (targetTree == existing.layoutInstanceId && edit.descendantPlacementDocuments.isNotEmpty()) {
            return invalidReference("Same-layout moves must not replace descendant placement documents")
        }
        if (
            targetTree != existing.layoutInstanceId &&
            (
                targetDocuments.size != edit.descendantPlacementDocuments.size ||
                    targetDocuments.keys != descendantPlacements.map(PlacementRecord::id).toSet()
                )
        ) {
            return invalidReference(
                "Cross-layout subtree moves require one target placement document for every descendant",
            )
        }
        val child = moduleInstances.getValue(existing.childInstanceId)
        val parent = moduleInstances[edit.parentInstanceId]
            ?: return notFound("Drop parent '${edit.parentInstanceId}' does not exist")
        val moved = existing.copy(
            layoutInstanceId = targetTree,
            parentInstanceId = edit.parentInstanceId,
            parentSlotId = edit.parentSlotId,
            index = edit.index,
            data = edit.data,
            placementSchemaVersion = edit.placementSchemaVersion,
        )
        val targetSiblings = siblingsOf(moved, excluding = existing.id)
        if (edit.index > targetSiblings.size) {
            return StoreRejection(
                StoreRejectionCode.PLACEMENT_INDEX_CONFLICT,
                "Drop index ${edit.index} exceeds sibling count ${targetSiblings.size}",
            )
        }
        val policyRejection = placementPolicy.validate(
            PlacementRequest(parent, child, edit.parentSlotId, targetSiblings.size + 1),
        )
        if (policyRejection != null) return policyRejection

        placements.remove(existing.id)
        compactAfterRemoval(existing)
        placements.values
            .filter { it.childInstanceId in movedSubtreeInstances }
            .forEach { descendant ->
                val targetDocument = targetDocuments[descendant.id]
                placements[descendant.id] = descendant.copy(
                    layoutInstanceId = targetTree,
                    data = targetDocument?.data ?: descendant.data,
                    placementSchemaVersion = targetDocument?.placementSchemaVersion
                        ?: descendant.placementSchemaVersion,
                )
            }
        shiftForInsertion(moved, excluding = null)
        placements[moved.id] = moved
        return null
    }

    private fun copySubtree(edit: LauncherEdit.CopySubtree): StoreRejection? {
        val sourceRoot = placements[edit.sourcePlacementId]
            ?: return notFound("Placement '${edit.sourcePlacementId}' does not exist")
        val sourceInstances = subtreeInstances(sourceRoot.childInstanceId)
        val identitiesBySource = edit.identities.associateBy(ModuleCopyIdentity::sourceInstanceId)
        if (identitiesBySource.keys != sourceInstances) {
            return invalidReference("Copy identities must cover the source subtree exactly")
        }
        val identities = edit.identities
        if (
            identities.map(ModuleCopyIdentity::copiedInstanceId).toSet().size != identities.size ||
            identities.map(ModuleCopyIdentity::copiedConfigurationDocumentId).toSet().size != identities.size ||
            identities.map(ModuleCopyIdentity::copiedPlacementId).toSet().size != identities.size ||
            identities.any {
                it.copiedInstanceId in moduleInstances ||
                    it.copiedConfigurationDocumentId in configurationDocuments ||
                    it.copiedPlacementId in placements
            }
        ) {
            return duplicate("copy identity")
        }
        val targetTree = treeForInstance(edit.targetParentInstanceId)
            ?: return invalidReference("Copy target does not belong to a retained layout tree")

        val copiedInstances = linkedMapOf<org.quicklauncher.contracts.domain.ModuleInstanceId, ModuleInstanceRecord>()
        val copiedConfigurations = linkedMapOf<org.quicklauncher.contracts.domain.ConfigurationDocumentId, StoredConfigurationDocument>()
        identities.forEach { identity ->
            val source = moduleInstances.getValue(identity.sourceInstanceId)
            val sourceConfiguration = configurationDocuments.getValue(source.configurationDocumentId)
            copiedInstances[identity.copiedInstanceId] = source.copy(
                id = identity.copiedInstanceId,
                configurationDocumentId = identity.copiedConfigurationDocumentId,
                status = copiedConfigurationStatus(source, sourceConfiguration),
            )
            copiedConfigurations[identity.copiedConfigurationDocumentId] = sourceConfiguration.copy(
                id = identity.copiedConfigurationDocumentId,
            )
        }

        val sourcePlacements = placements.values.filter { it.childInstanceId in sourceInstances }
        val copiedPlacements = sourcePlacements.map { source ->
            val identity = identitiesBySource.getValue(source.childInstanceId)
            val isRoot = source.id == sourceRoot.id
            source.copy(
                id = identity.copiedPlacementId,
                layoutInstanceId = targetTree,
                parentInstanceId = if (isRoot) {
                    edit.targetParentInstanceId
                } else {
                    identitiesBySource.getValue(source.parentInstanceId).copiedInstanceId
                },
                parentSlotId = if (isRoot) edit.targetSlotId else source.parentSlotId,
                childInstanceId = identity.copiedInstanceId,
                index = if (isRoot) edit.targetIndex else source.index,
                data = identity.copiedPlacementData,
                placementSchemaVersion = identity.copiedPlacementSchemaVersion,
            )
        }
        val copiedRoot = copiedPlacements.single { it.id == identitiesBySource.getValue(sourceRoot.childInstanceId).copiedPlacementId }
        val targetSiblings = siblingsOf(copiedRoot)
        if (edit.targetIndex > targetSiblings.size) {
            return StoreRejection(
                StoreRejectionCode.PLACEMENT_INDEX_CONFLICT,
                "Copy index ${edit.targetIndex} exceeds sibling count ${targetSiblings.size}",
            )
        }
        for (placement in copiedPlacements.sortedBy { if (it == copiedRoot) 0 else 1 }) {
            val parent = copiedInstances[placement.parentInstanceId]
                ?: moduleInstances[placement.parentInstanceId]
                ?: return invalidReference("Copied placement parent is missing")
            val child = copiedInstances.getValue(placement.childInstanceId)
            val existingCount = if (placement == copiedRoot) {
                targetSiblings.size + 1
            } else {
                copiedPlacements.count {
                    it.parentInstanceId == placement.parentInstanceId &&
                        it.parentSlotId == placement.parentSlotId
                }
            }
            val rejection = placementPolicy.validate(
                PlacementRequest(parent, child, placement.parentSlotId, existingCount),
            )
            if (rejection != null) return rejection
        }

        shiftForInsertion(copiedRoot, excluding = null)
        configurationDocuments.putAll(copiedConfigurations)
        moduleInstances.putAll(copiedInstances)
        copiedPlacements.forEach { placements[it.id] = it }
        edit.identities.forEach { identity ->
            val sourceWidget = widgetPlacements.singleOrNull {
                it.moduleInstanceId == identity.sourceInstanceId
            } ?: return@forEach
            widgetPlacements += sourceWidget.copy(
                moduleInstanceId = identity.copiedInstanceId,
                appWidgetId = null,
                bindState = WidgetBindState.PENDING,
                restoreState = WidgetRestoreState.REBIND_REQUIRED,
                cleanupState = WidgetCleanupState.NONE,
            )
        }
        return null
    }

    private fun replaceConfiguration(edit: LauncherEdit.ReplaceConfiguration): StoreRejection? {
        val existing = configurationDocuments[edit.configurationDocumentId]
            ?: return notFound("Configuration '${edit.configurationDocumentId}' does not exist")
        if (existing.document.configType != edit.document.configType) {
            return StoreRejection(
                StoreRejectionCode.CONFIGURATION_TYPE_MISMATCH,
                "Replacement configuration type does not match the stored document",
            )
        }
        val affected = moduleInstances.values
            .filter { it.configurationDocumentId == edit.configurationDocumentId }
        if (affected.isEmpty()) {
            return invalidReference("Configuration '${edit.configurationDocumentId}' has no module instance")
        }
        val loaders = affected.map { instance ->
            configurationResolver.resolve(instance.contributionId)
                ?: return configurationInvalid(
                    "Configuration cannot be validated because contribution '${instance.contributionId}' is unavailable",
                )
        }
        val loader = loaders.first()
        if (
            loaders.any {
                it.configType != loader.configType ||
                    it.currentSchemaVersion != loader.currentSchemaVersion ||
                    it.contributionTypeId != loader.contributionTypeId
            }
        ) {
            return configurationInvalid("Configuration references do not resolve to one production codec")
        }
        if (edit.document.configType != loader.configType) {
            return StoreRejection(
                StoreRejectionCode.CONFIGURATION_TYPE_MISMATCH,
                "Replacement configuration type '${edit.document.configType}' does not match registered type " +
                    "'${loader.configType}'",
            )
        }
        val loaded = try {
            loader.load(edit.document)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return configurationInvalid(
                "Configuration validation threw ${failure::class.simpleName ?: "an exception"}",
            )
        }
        val validated = when (loaded) {
            is ConfigurationLoadResult.Failed -> return configurationInvalid(
                "${loaded.error.code}: ${loaded.error.message}",
            )
            is ConfigurationLoadResult.Loaded<*> -> loaded.document
        }
        if (
            validated.configType != loader.configType ||
            validated.schemaVersion != loader.currentSchemaVersion
        ) {
            return configurationInvalid("Configuration loader returned an incompatible document")
        }
        affected.forEach { instance ->
            val quarantine = instance.status as? ModuleInstanceStatus.Quarantined
            if (quarantine?.origin == ModuleQuarantineOrigin.CONFIGURATION) {
                moduleInstances[instance.id] = instance.copy(status = ModuleInstanceStatus.Active)
            }
        }
        configurationDocuments[edit.configurationDocumentId] = existing.copy(
            document = validated,
            lastMigrationErrorCode = null,
        )
        return null
    }

    private fun copiedConfigurationStatus(
        source: ModuleInstanceRecord,
        configuration: StoredConfigurationDocument,
    ): ModuleInstanceStatus {
        val migrationError = configuration.lastMigrationErrorCode
        if (migrationError != null) {
            val existing = source.status as? ModuleInstanceStatus.Quarantined
            if (existing?.origin == ModuleQuarantineOrigin.CONFIGURATION && existing.code == migrationError) {
                return existing
            }
            return ModuleInstanceStatus.Quarantined(
                code = migrationError,
                message = "Copied configuration requires successful validation before activation",
                origin = ModuleQuarantineOrigin.CONFIGURATION,
            )
        }
        val existing = source.status as? ModuleInstanceStatus.Quarantined
        return if (existing?.origin == ModuleQuarantineOrigin.CONFIGURATION) {
            existing
        } else {
            ModuleInstanceStatus.Active
        }
    }

    private fun removePlacedSubtree(edit: LauncherEdit.RemovePlacedSubtree): StoreRejection? {
        if (!edit.confirmed) return confirmationRequired("Configured block removal requires confirmation")
        val root = placements[edit.placementId]
            ?: return notFound("Placement '${edit.placementId}' does not exist")
        val removedInstances = subtreeInstances(root.childInstanceId)
        if (widgetPlacements.any { it.moduleInstanceId in removedInstances && it.appWidgetId != null }) {
            return recoveryStateMismatch(
                "Placement removal requires allocated widget IDs to be cleaned up first",
            )
        }
        val removedPlacements = placements.values.filter { it.childInstanceId in removedInstances }
        removedPlacements.forEach { placements.remove(it.id) }
        compactAfterRemoval(root)
        removedInstances.forEach(moduleInstances::remove)
        widgetPlacements.removeAll { it.moduleInstanceId in removedInstances }
        crashMarkers.removeAll { it.moduleInstanceId in removedInstances }
        removeUnreferencedConfigurations()
        return null
    }

    private fun beginStartupRestore(edit: LauncherEdit.BeginStartupRestore): StoreRejection? {
        val marker = edit.marker
        if (marker.moduleInstanceId !in moduleInstances) {
            return notFound("Restore instance '${marker.moduleInstanceId}' does not exist")
        }
        if (marker.outcome != null) {
            return invalidReference("A startup restore must begin with an unresolved marker")
        }
        if (crashMarkers.any { it.id == marker.id && it.moduleInstanceId != marker.moduleInstanceId }) {
            return duplicate("crash marker")
        }
        crashMarkers.removeAll { it.moduleInstanceId == marker.moduleInstanceId }
        crashMarkers += marker
        return null
    }

    private fun completeStartupRestore(edit: LauncherEdit.CompleteStartupRestore): StoreRejection? {
        if (edit.moduleInstanceId !in moduleInstances) {
            return notFound("Restore instance '${edit.moduleInstanceId}' does not exist")
        }
        val removed = crashMarkers.removeAll {
            it.moduleInstanceId == edit.moduleInstanceId && it.outcome == null
        }
        return if (removed) {
            null
        } else {
            notFound("Instance '${edit.moduleInstanceId}' has no unresolved startup marker")
        }
    }

    private fun quarantineRenderer(edit: LauncherEdit.QuarantineRenderer): StoreRejection? {
        val instance = moduleInstances[edit.moduleInstanceId]
            ?: return notFound("Restore instance '${edit.moduleInstanceId}' does not exist")
        val markerIndex = crashMarkers.indexOfFirst {
            it.moduleInstanceId == edit.moduleInstanceId && it.outcome == null
        }
        if (markerIndex < 0) {
            return notFound("Instance '${edit.moduleInstanceId}' has no unresolved startup marker")
        }
        val existing = instance.status as? ModuleInstanceStatus.Quarantined
        if (existing != null && existing.origin != ModuleQuarantineOrigin.RENDERER) {
            return invalidReference(
                "Renderer recovery cannot replace ${existing.origin.name.lowercase()} quarantine",
            )
        }
        moduleInstances[instance.id] = instance.copy(
            status = ModuleInstanceStatus.Quarantined(
                code = edit.code,
                message = edit.message,
                origin = ModuleQuarantineOrigin.RENDERER,
            ),
        )
        crashMarkers[markerIndex] = crashMarkers[markerIndex].copy(outcome = edit.outcome)
        return null
    }

    private fun retryRenderer(edit: LauncherEdit.RetryRenderer): StoreRejection? {
        val instance = moduleInstances[edit.moduleInstanceId]
            ?: return notFound("Recovery instance '${edit.moduleInstanceId}' does not exist")
        val quarantine = instance.status as? ModuleInstanceStatus.Quarantined
            ?: return recoveryStateMismatch(
                "Instance '${edit.moduleInstanceId}' is not quarantined",
            )
        if (quarantine.origin != ModuleQuarantineOrigin.RENDERER) {
            return recoveryStateMismatch(
                "Renderer retry cannot clear ${quarantine.origin.name.lowercase()} quarantine",
            )
        }
        moduleInstances[instance.id] = instance.copy(status = ModuleInstanceStatus.Active)
        crashMarkers.removeAll { it.moduleInstanceId == instance.id }
        return null
    }

    private fun beginWidgetBinding(edit: LauncherEdit.BeginWidgetBinding): StoreRejection? {
        val placement = edit.placement
        if (placement.moduleInstanceId !in moduleInstances) {
            return notFound("Widget instance '${placement.moduleInstanceId}' does not exist")
        }
        if (
            placement.appWidgetId == null ||
            placement.bindState != WidgetBindState.PENDING ||
            placement.restoreState != WidgetRestoreState.READY ||
            placement.cleanupState != WidgetCleanupState.NONE
        ) {
            return recoveryStateMismatch("A widget binding must begin with one allocated pending ID")
        }
        if (widgetPlacements.any {
                it.appWidgetId == placement.appWidgetId && it.moduleInstanceId != placement.moduleInstanceId
            }
        ) {
            return duplicate("Android widget ID")
        }
        val existing = widgetPlacements.indexOfFirst { it.moduleInstanceId == placement.moduleInstanceId }
        if (existing >= 0) {
            val current = widgetPlacements[existing]
            if (current.appWidgetId != null || current.bindState == WidgetBindState.BOUND) {
                return recoveryStateMismatch("Widget instance already owns an allocated ID")
            }
            widgetPlacements[existing] = placement
        } else {
            widgetPlacements += placement
        }
        return null
    }

    private fun completeWidgetBinding(edit: LauncherEdit.CompleteWidgetBinding): StoreRejection? {
        val index = widgetPlacements.indexOfFirst { it.moduleInstanceId == edit.moduleInstanceId }
        if (index < 0) return notFound("Widget placement '${edit.moduleInstanceId}' does not exist")
        val current = widgetPlacements[index]
        if (
            current.bindState != WidgetBindState.PENDING ||
            current.appWidgetId == null ||
            current.cleanupState != WidgetCleanupState.NONE
        ) {
            return recoveryStateMismatch("Only an allocated pending widget can complete binding")
        }
        widgetPlacements[index] = current.copy(
            bindState = WidgetBindState.BOUND,
            restoreState = WidgetRestoreState.READY,
        )
        return null
    }

    private fun cancelWidgetBinding(edit: LauncherEdit.CancelWidgetBinding): StoreRejection? {
        val current = widgetPlacements.singleOrNull { it.moduleInstanceId == edit.moduleInstanceId }
            ?: return notFound("Widget placement '${edit.moduleInstanceId}' does not exist")
        if (
            current.bindState != WidgetBindState.PENDING ||
            current.appWidgetId == null ||
            current.cleanupState != WidgetCleanupState.NONE
        ) {
            return recoveryStateMismatch("Only a pending widget binding can be cancelled")
        }
        widgetPlacements.remove(current)
        return null
    }

    private fun failWidgetBinding(edit: LauncherEdit.FailWidgetBinding): StoreRejection? {
        val index = widgetPlacements.indexOfFirst { it.moduleInstanceId == edit.moduleInstanceId }
        if (index < 0) return notFound("Widget placement '${edit.moduleInstanceId}' does not exist")
        val current = widgetPlacements[index]
        if (current.bindState != WidgetBindState.PENDING || current.appWidgetId == null) {
            return recoveryStateMismatch("Only a pending widget binding can fail")
        }
        widgetPlacements[index] = current.copy(
            appWidgetId = null,
            bindState = WidgetBindState.FAILED,
            cleanupState = WidgetCleanupState.NONE,
        )
        return null
    }

    private fun updateWidgetSize(edit: LauncherEdit.UpdateWidgetSize): StoreRejection? {
        val index = widgetPlacements.indexOfFirst { it.moduleInstanceId == edit.moduleInstanceId }
        if (index < 0) return notFound("Widget placement '${edit.moduleInstanceId}' does not exist")
        widgetPlacements[index] = widgetPlacements[index].copy(
            intendedWidthDp = edit.widthDp,
            intendedHeightDp = edit.heightDp,
        )
        return null
    }

    private fun replaceRestoredWidgetId(edit: LauncherEdit.ReplaceRestoredWidgetId): StoreRejection? {
        val index = widgetPlacements.indexOfFirst { it.moduleInstanceId == edit.moduleInstanceId }
        if (index < 0) return notFound("Restored widget placement '${edit.moduleInstanceId}' does not exist")
        val current = widgetPlacements[index]
        if (
            current.restoreState != WidgetRestoreState.REBIND_REQUIRED ||
            current.appWidgetId != null ||
            current.cleanupState != WidgetCleanupState.NONE
        ) {
            return recoveryStateMismatch("Only a restored pending placement can receive a replacement ID")
        }
        if (widgetPlacements.any { it.appWidgetId == edit.appWidgetId }) {
            return duplicate("Android widget ID")
        }
        widgetPlacements[index] = current.copy(
            appWidgetId = edit.appWidgetId,
            bindState = WidgetBindState.PENDING,
        )
        return null
    }

    private fun deleteWidgetPlacement(edit: LauncherEdit.DeleteWidgetPlacement): StoreRejection? {
        val current = widgetPlacements.singleOrNull { it.moduleInstanceId == edit.moduleInstanceId }
            ?: return notFound("Widget placement '${edit.moduleInstanceId}' does not exist")
        if (current.appWidgetId != null || current.cleanupState != WidgetCleanupState.NONE) {
            return recoveryStateMismatch("Allocated widget IDs must use coordinated framework cleanup")
        }
        widgetPlacements.remove(current)
        return null
    }

    private fun beginWidgetDeletion(edit: LauncherEdit.BeginWidgetDeletion): StoreRejection? {
        val index = widgetPlacements.indexOfFirst { it.moduleInstanceId == edit.moduleInstanceId }
        if (index < 0) return notFound("Widget placement '${edit.moduleInstanceId}' does not exist")
        val current = widgetPlacements[index]
        if (current.appWidgetId == null) {
            return recoveryStateMismatch("Only a widget with an allocated ID needs framework cleanup")
        }
        widgetPlacements[index] = current.copy(cleanupState = WidgetCleanupState.DELETE_PENDING)
        return null
    }

    private fun completeWidgetDeletion(edit: LauncherEdit.CompleteWidgetDeletion): StoreRejection? {
        val current = widgetPlacements.singleOrNull { it.moduleInstanceId == edit.moduleInstanceId }
            ?: return notFound("Widget placement '${edit.moduleInstanceId}' does not exist")
        if (current.cleanupState != WidgetCleanupState.DELETE_PENDING) {
            return recoveryStateMismatch("Widget framework cleanup has not begun")
        }
        widgetPlacements.remove(current)
        return null
    }

    private fun beginWidgetInvalidation(edit: LauncherEdit.BeginWidgetInvalidation): StoreRejection? {
        val index = widgetPlacements.indexOfFirst { it.moduleInstanceId == edit.moduleInstanceId }
        if (index < 0) return notFound("Widget placement '${edit.moduleInstanceId}' does not exist")
        val current = widgetPlacements[index]
        if (
            current.bindState != WidgetBindState.BOUND ||
            current.appWidgetId == null ||
            current.cleanupState != WidgetCleanupState.NONE
        ) {
            return recoveryStateMismatch("Only a bound widget can begin provider invalidation")
        }
        widgetPlacements[index] = current.copy(cleanupState = WidgetCleanupState.REBIND_PENDING)
        return null
    }

    private fun completeWidgetInvalidation(edit: LauncherEdit.CompleteWidgetInvalidation): StoreRejection? {
        val index = widgetPlacements.indexOfFirst { it.moduleInstanceId == edit.moduleInstanceId }
        if (index < 0) return notFound("Widget placement '${edit.moduleInstanceId}' does not exist")
        val current = widgetPlacements[index]
        if (current.cleanupState != WidgetCleanupState.REBIND_PENDING) {
            return recoveryStateMismatch("Widget invalidation cleanup has not begun")
        }
        widgetPlacements[index] = current.copy(
            appWidgetId = null,
            bindState = WidgetBindState.PENDING,
            restoreState = WidgetRestoreState.REBIND_REQUIRED,
            cleanupState = WidgetCleanupState.NONE,
        )
        return null
    }

    private fun createShortcutPlacement(edit: LauncherEdit.CreateShortcutPlacement): StoreRejection? {
        if (edit.item.kind != ContentItemKind.SHORTCUT || edit.item.shortcutTarget == null) {
            return invalidReference("Shortcut placement must contain a structural shortcut target")
        }
        if (contentItems.any { it.id == edit.item.id }) return duplicate("content item")
        contentItems += edit.item
        return null
    }

    private fun removeShortcutPlacement(edit: LauncherEdit.RemoveShortcutPlacement): StoreRejection? {
        val item = contentItems.singleOrNull { it.id == edit.contentItemId }
            ?: return notFound("Shortcut content '${edit.contentItemId}' does not exist")
        if (item.kind != ContentItemKind.SHORTCUT) {
            return invalidReference("Only shortcut content can use shortcut removal")
        }
        contentItems.remove(item)
        removeMembershipsAndCompact { it.memberId == item.id }
        return null
    }

    private fun createFolder(edit: LauncherEdit.CreateFolder): StoreRejection? {
        if (edit.folder.kind != ContentItemKind.FOLDER) {
            return invalidReference("Folder creation requires folder content")
        }
        if (contentItems.any { it.id == edit.folder.id }) return duplicate("content item")
        if (edit.members.toSet().size != edit.members.size) return duplicate("folder member")
        if (edit.members.any { member -> contentItems.none { it.id == member } }) {
            return invalidReference("Folder creation refers to missing content")
        }
        contentItems += edit.folder
        edit.members.forEachIndexed { index, member ->
            folderMembers += FolderMemberRecord(edit.folder.id, member, index)
        }
        return null
    }

    private fun renameFolder(edit: LauncherEdit.RenameFolder): StoreRejection? {
        if (edit.encoded.isBlank()) return invalidReference("Folder data must not be blank")
        val index = contentItems.indexOfFirst { it.id == edit.folderId && it.kind == ContentItemKind.FOLDER }
        if (index < 0) return notFound("Folder '${edit.folderId}' does not exist")
        contentItems[index] = ContentItemRecord(edit.folderId, ContentItemKind.FOLDER, edit.encoded)
        return null
    }

    private fun setFolderMembers(edit: LauncherEdit.SetFolderMembers): StoreRejection? {
        if (contentItems.none { it.id == edit.folderId && it.kind == ContentItemKind.FOLDER }) {
            return notFound("Folder '${edit.folderId}' does not exist")
        }
        if (edit.members.toSet().size != edit.members.size) return duplicate("folder member")
        if (edit.members.any { member -> contentItems.none { it.id == member } }) {
            return invalidReference("Folder membership refers to missing content")
        }
        folderMembers.removeAll { it.folderId == edit.folderId }
        edit.members.forEachIndexed { index, member ->
            folderMembers += FolderMemberRecord(edit.folderId, member, index)
        }
        return null
    }

    private fun setFolderMembership(edit: LauncherEdit.SetFolderMembership): StoreRejection? {
        if (contentItems.none { it.id == edit.folderId && it.kind == ContentItemKind.FOLDER }) {
            return notFound("Folder '${edit.folderId}' does not exist")
        }
        val existingItem = contentItems.singleOrNull { it.id == edit.member.id }
        if (existingItem != null && existingItem != edit.member) {
            return invalidReference("Content identity '${edit.member.id}' changed while editing a folder")
        }
        val existingMember = folderMembers.singleOrNull {
            it.folderId == edit.folderId && it.memberId == edit.member.id
        }
        if (edit.included) {
            if (existingMember != null) return duplicate("folder member")
            if (existingItem == null) contentItems += edit.member
            val index = folderMembers.count { it.folderId == edit.folderId }
            folderMembers += FolderMemberRecord(edit.folderId, edit.member.id, index)
        } else {
            if (existingMember == null) return notFound("Folder membership does not exist")
            folderMembers.remove(existingMember)
            val retained = folderMembers.filter { it.folderId == edit.folderId }.sortedBy { it.index }
            folderMembers.removeAll { it.folderId == edit.folderId }
            retained.forEachIndexed { index, member -> folderMembers += member.copy(index = index) }
        }
        return null
    }

    private fun deleteFolder(edit: LauncherEdit.DeleteFolder): StoreRejection? {
        if (!edit.confirmed) return confirmationRequired("Folder deletion requires confirmation")
        val folder = contentItems.singleOrNull { it.id == edit.folderId && it.kind == ContentItemKind.FOLDER }
            ?: return notFound("Folder '${edit.folderId}' does not exist")
        contentItems.remove(folder)
        folderMembers.removeAll { it.folderId == edit.folderId }
        removeMembershipsAndCompact { it.memberId == edit.folderId }
        return null
    }

    private fun removeMembershipsAndCompact(remove: (FolderMemberRecord) -> Boolean) {
        val affectedFolders = folderMembers.filter(remove).map(FolderMemberRecord::folderId).toSet()
        folderMembers.removeAll(remove)
        affectedFolders.forEach { folderId ->
            val retained = folderMembers.filter { it.folderId == folderId }.sortedBy { it.index }
            folderMembers.removeAll { it.folderId == folderId }
            retained.forEachIndexed { index, member -> folderMembers += member.copy(index = index) }
        }
    }

    private fun putAppOverride(edit: LauncherEdit.PutAppOverride): StoreRejection? {
        appOverrides.removeAll {
            it.profile == edit.override.profile &&
                it.packageName == edit.override.packageName &&
                it.activityName == edit.override.activityName
        }
        appOverrides += edit.override
        return null
    }

    private fun removeAppOverride(edit: LauncherEdit.RemoveAppOverride): StoreRejection? {
        val removed = appOverrides.removeAll {
            it.profile == edit.profile && it.packageName == edit.packageName &&
                it.activityName == edit.activityName
        }
        return if (removed) null else notFound("App override does not exist")
    }

    private fun validateInstall(install: DestinationInstall): StoreRejection? {
        if (
            install.layout.destinationId != install.destination.id ||
            install.layout.layoutInstanceId != install.layoutInstance.id ||
            install.layout.layoutContributionId != install.layoutInstance.contributionId ||
            install.layoutInstance.configurationDocumentId != install.configuration.id
        ) {
            return StoreRejection(
                StoreRejectionCode.INVALID_REFERENCE,
                "Destination install records do not reference each other",
            )
        }
        return null
    }

    private fun parentBelongsToTree(
        parentInstanceId: org.quicklauncher.contracts.domain.ModuleInstanceId,
        layoutInstanceId: org.quicklauncher.contracts.domain.ModuleInstanceId,
    ): Boolean = parentInstanceId == layoutInstanceId || placements.values.any {
        it.layoutInstanceId == layoutInstanceId && it.childInstanceId == parentInstanceId
    }

    private fun siblingsOf(
        placement: PlacementRecord,
        excluding: org.quicklauncher.contracts.domain.PlacementId? = null,
    ): List<PlacementRecord> = placements.values.filter {
        it.id != excluding &&
            it.layoutInstanceId == placement.layoutInstanceId &&
            it.parentInstanceId == placement.parentInstanceId &&
            it.parentSlotId == placement.parentSlotId
    }

    private fun shiftForInsertion(
        placement: PlacementRecord,
        excluding: org.quicklauncher.contracts.domain.PlacementId?,
    ) {
        siblingsOf(placement, excluding)
            .filter { it.index >= placement.index }
            .forEach { sibling ->
                placements[sibling.id] = sibling.copy(index = sibling.index + 1)
            }
    }

    private fun compactAfterRemoval(removed: PlacementRecord) {
        placements.values
            .filter {
                it.layoutInstanceId == removed.layoutInstanceId &&
                    it.parentInstanceId == removed.parentInstanceId &&
                    it.parentSlotId == removed.parentSlotId &&
                    it.index > removed.index
            }
            .forEach { sibling -> placements[sibling.id] = sibling.copy(index = sibling.index - 1) }
    }

    private fun treeForInstance(
        instanceId: org.quicklauncher.contracts.domain.ModuleInstanceId,
    ): org.quicklauncher.contracts.domain.ModuleInstanceId? =
        destinationLayouts.firstOrNull { it.layoutInstanceId == instanceId }?.layoutInstanceId
            ?: placements.values.firstOrNull { it.childInstanceId == instanceId }?.layoutInstanceId

    private fun subtreeInstances(
        root: org.quicklauncher.contracts.domain.ModuleInstanceId,
    ): Set<org.quicklauncher.contracts.domain.ModuleInstanceId> {
        val found = linkedSetOf(root)
        var changed: Boolean
        do {
            changed = false
            placements.values.forEach { placement ->
                if (placement.parentInstanceId in found && found.add(placement.childInstanceId)) {
                    changed = true
                }
            }
        } while (changed)
        return found
    }

    private fun destinationMap(): DestinationMap = DestinationMap.restore(
        destinations.values.map { destination ->
            DestinationNode(
                id = destination.id,
                name = destination.name,
                coordinate = destination.coordinate,
                isStart = destination.id == startDestinationId,
            )
        },
    )

    private fun validatePlacements(): StoreRejection? {
        val retainedRoots = destinationLayouts.map(DestinationLayoutRecord::layoutInstanceId).toSet()
        val placedChildren = placements.values.map(PlacementRecord::childInstanceId)
        if (placedChildren.toSet().size != placedChildren.size) {
            return duplicate("placed child instance")
        }
        if (placedChildren.any { it in retainedRoots }) {
            return StoreRejection(
                StoreRejectionCode.PLACEMENT_CYCLE,
                "A retained layout root cannot also be a placed child",
            )
        }
        for (placement in placements.values) {
            if (
                placement.layoutInstanceId !in retainedRoots ||
                placement.parentInstanceId !in moduleInstances ||
                placement.childInstanceId !in moduleInstances
            ) {
                return invalidReference("Placement '${placement.id}' has a missing tree or instance")
            }
        }
        val groups = placements.values.groupBy { Triple(it.layoutInstanceId, it.parentInstanceId, it.parentSlotId) }
        if (groups.values.any { group ->
                group.map(PlacementRecord::index).sorted() != group.indices.toList()
            }
        ) {
            return StoreRejection(
                StoreRejectionCode.PLACEMENT_INDEX_CONFLICT,
                "Sibling placement indexes must be unique and contiguous from zero",
            )
        }

        for (root in retainedRoots) {
            val treePlacements = placements.values.filter { it.layoutInstanceId == root }
            val reached = linkedSetOf(root)
            var changed: Boolean
            do {
                changed = false
                treePlacements.forEach { placement ->
                    if (placement.parentInstanceId in reached && reached.add(placement.childInstanceId)) {
                        changed = true
                    }
                }
            } while (changed)
            if (treePlacements.any { it.childInstanceId !in reached }) {
                return StoreRejection(
                    StoreRejectionCode.PLACEMENT_CYCLE,
                    "Every placement must form an acyclic tree below its retained layout root",
                )
            }
        }
        val rootOrPlaced = retainedRoots + placedChildren
        if (moduleInstances.keys.any { it !in rootOrPlaced }) {
            return invalidReference("Every module instance must belong to one retained layout tree")
        }
        for (placement in placements.values) {
            val parent = moduleInstances.getValue(placement.parentInstanceId)
            val child = moduleInstances.getValue(placement.childInstanceId)
            if (
                configurationResolver.resolve(parent.contributionId) == null ||
                configurationResolver.resolve(child.contributionId) == null
            ) {
                continue
            }
            val siblingCount = groups.getValue(
                Triple(placement.layoutInstanceId, placement.parentInstanceId, placement.parentSlotId),
            ).size
            val rejection = placementPolicy.validate(
                PlacementRequest(parent, child, placement.parentSlotId, siblingCount),
            )
            if (rejection != null) return rejection
        }
        return null
    }

    private fun instancesInTrees(roots: Set<org.quicklauncher.contracts.domain.ModuleInstanceId>): Set<org.quicklauncher.contracts.domain.ModuleInstanceId> =
        roots + placements.values
            .filter { it.layoutInstanceId in roots }
            .map(PlacementRecord::childInstanceId)

    private fun removeUnreferencedConfigurations() {
        val referenced = moduleInstances.values.map(ModuleInstanceRecord::configurationDocumentId).toSet()
        configurationDocuments.keys.removeAll { it !in referenced }
    }

    private fun duplicate(subject: String): StoreRejection = StoreRejection(
        StoreRejectionCode.DUPLICATE_ID,
        "Duplicate $subject",
    )

    private fun notFound(message: String): StoreRejection =
        StoreRejection(StoreRejectionCode.NOT_FOUND, message)

    private fun invalidReference(message: String): StoreRejection =
        StoreRejection(StoreRejectionCode.INVALID_REFERENCE, message)

    private fun confirmationRequired(message: String): StoreRejection =
        StoreRejection(StoreRejectionCode.CONFIRMATION_REQUIRED, message)

    private fun configurationInvalid(message: String): StoreRejection =
        StoreRejection(StoreRejectionCode.CONFIGURATION_INVALID, message)

    private fun recoveryStateMismatch(message: String): StoreRejection =
        StoreRejection(StoreRejectionCode.RECOVERY_STATE_MISMATCH, message)

    private companion object {
        const val MAX_THEME_PROFILES = 64
        const val MAX_DESTINATION_BACKGROUNDS = 256
    }
}
