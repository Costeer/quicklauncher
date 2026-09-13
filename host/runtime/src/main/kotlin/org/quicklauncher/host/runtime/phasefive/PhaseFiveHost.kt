package org.quicklauncher.host.runtime.phasefive

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.domain.ProfilePackageIdentity
import org.quicklauncher.host.runtime.LauncherApp
import org.quicklauncher.host.runtime.contentItemId
import org.quicklauncher.host.runtime.actions.ItemActionCommand
import org.quicklauncher.host.runtime.actions.ActionableItemKind
import org.quicklauncher.host.runtime.actions.DefaultItemActionOverlayController
import org.quicklauncher.host.runtime.actions.ItemMutationTargetResolver
import org.quicklauncher.host.runtime.actions.ItemActionPlatform
import org.quicklauncher.host.runtime.actions.ItemActionPolicyItem
import org.quicklauncher.host.runtime.actions.ItemActionPolicySource
import org.quicklauncher.host.runtime.actions.ItemAvailability
import org.quicklauncher.host.runtime.actions.LauncherStoreItemActionMutation
import org.quicklauncher.host.runtime.actions.LauncherStoreItemActionResolver
import org.quicklauncher.host.runtime.actions.ProfileItemAction
import org.quicklauncher.host.runtime.actions.ItemActionOverlayController
import org.quicklauncher.host.runtime.actions.ItemActionOverlayState
import org.quicklauncher.host.runtime.actions.ItemActionResult
import org.quicklauncher.host.runtime.folders.FolderOperationResult
import org.quicklauncher.host.runtime.folders.DefaultFolderOverlayController
import org.quicklauncher.host.runtime.folders.FolderMemberKind
import org.quicklauncher.host.runtime.folders.FolderMemberPresentation
import org.quicklauncher.host.runtime.folders.FolderMemberResolver
import org.quicklauncher.host.runtime.folders.LauncherStoreFolderRepository
import org.quicklauncher.host.runtime.folders.FolderOverlayController
import org.quicklauncher.host.runtime.folders.FolderOverlayState
import org.quicklauncher.host.runtime.notifications.IndicatorEligibleApp
import org.quicklauncher.host.runtime.notifications.IndicatorProfileKind
import org.quicklauncher.host.runtime.notifications.NotificationAccessAction
import org.quicklauncher.host.runtime.notifications.NotificationIndicatorCoordinator
import org.quicklauncher.host.runtime.notifications.NotificationIndicatorState
import org.quicklauncher.host.runtime.notifications.NotificationCountSnapshot
import org.quicklauncher.host.runtime.notifications.NotificationListenerStatus
import org.quicklauncher.host.runtime.notifications.NotificationPlatform
import org.quicklauncher.host.runtime.notifications.NotificationPlatformActionResult
import org.quicklauncher.host.runtime.profile.PrivateSpaceActionResult
import org.quicklauncher.host.runtime.profile.PrivateSpaceEntryPoint
import org.quicklauncher.host.runtime.profile.PrivateSpaceSettingsResult
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileCoordinator
import org.quicklauncher.host.runtime.profile.ProfileCoordinatorState
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.profile.PrivateItemsResult
import org.quicklauncher.host.runtime.profile.PrivateLaunchResult
import org.quicklauncher.host.runtime.profile.ProfileInvalidation
import org.quicklauncher.host.runtime.profile.ProfilePlatform
import org.quicklauncher.host.runtime.profile.ProfileRecord
import org.quicklauncher.host.runtime.profile.WorkModeRequestResult
import org.quicklauncher.host.runtime.profile.WorkModeResult
import org.quicklauncher.host.runtime.shortcuts.ResolvedShortcutPlacement
import org.quicklauncher.host.runtime.shortcuts.ShortcutCoordinator
import org.quicklauncher.host.runtime.shortcuts.ShortcutLaunchResult
import org.quicklauncher.host.runtime.shortcuts.ShortcutSnapshot
import org.quicklauncher.host.runtime.shortcuts.ShortcutInvalidation
import org.quicklauncher.host.runtime.shortcuts.ShortcutOwner
import org.quicklauncher.host.runtime.shortcuts.ShortcutPinRequest
import org.quicklauncher.host.runtime.shortcuts.ShortcutPinResult
import org.quicklauncher.host.runtime.shortcuts.ShortcutPlacementCreation
import org.quicklauncher.host.runtime.shortcuts.ShortcutPlatform
import org.quicklauncher.host.runtime.shortcuts.ShortcutQueryResult
import org.quicklauncher.host.runtime.shortcuts.ShortcutTargetIdentity
import org.quicklauncher.host.runtime.shortcuts.ShortcutUnavailableReason
import org.quicklauncher.host.data.store.ContentItemKind
import org.quicklauncher.host.data.store.LauncherStore

enum class PhaseFiveOverlay { NONE, PRIVATE_SPACE, FOLDER, ITEM_ACTIONS }
enum class FolderMemberActivationResult { APP_LAUNCHED, SHORTCUT_LAUNCHED, FOLDER_OPENED, UNAVAILABLE }

internal fun interface FolderIdentitySource {
    fun next(): org.quicklauncher.contracts.domain.ContentItemId
}

private val RandomFolderIdentitySource = FolderIdentitySource {
    org.quicklauncher.contracts.domain.ContentItemId.parse(
        "org.quicklauncher.content/folder-${UUID.randomUUID().toString().lowercase()}",
    )
}

/** Owns Phase 5 profile policy, notification eligibility, and the secure overlay lifecycle. */
class PhaseFiveHost internal constructor(
    private val profiles: ProfileCoordinator,
    private val notifications: NotificationIndicatorCoordinator,
    parentScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val folders: FolderOverlayController? = null,
    private val itemActions: ItemActionOverlayController? = null,
    private val shortcuts: ShortcutCoordinator? = null,
    private val appLauncher: (suspend (org.quicklauncher.contracts.domain.AppActivityIdentity) -> Boolean)? = null,
    private val folderIdentities: FolderIdentitySource = RandomFolderIdentitySource,
    private val afterDurableMutation: suspend () -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val privateSpaceVisible = AtomicBoolean(true)
    private val refreshMutex = Mutex()
    private val ownedJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownedJob)
    private val mutableOverlay = MutableStateFlow(PhaseFiveOverlay.NONE)
    private val mutableProfileState = MutableStateFlow(profiles.state.value.sanitizedForPrivateVisibility(true))
    private var currentApps: List<LauncherApp> = emptyList()

    val overlay: StateFlow<PhaseFiveOverlay> = mutableOverlay.asStateFlow()
    val profileState: StateFlow<ProfileCoordinatorState> = mutableProfileState.asStateFlow()
    val notificationState: StateFlow<NotificationIndicatorState> = notifications.state
    val folderState: StateFlow<FolderOverlayState> =
        folders?.state ?: MutableStateFlow(FolderOverlayState.Closed)
    val itemActionState: StateFlow<ItemActionOverlayState> =
        itemActions?.state ?: MutableStateFlow(ItemActionOverlayState.Closed)
    val shortcutState: StateFlow<ShortcutSnapshot> =
        shortcuts?.state ?: MutableStateFlow(ShortcutSnapshot.Empty)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            profiles.state.drop(1).collect {
                mutableProfileState.value = it.sanitizedForPrivateVisibility(privateSpaceVisible.get())
                if (
                    mutableOverlay.value == PhaseFiveOverlay.PRIVATE_SPACE &&
                    !canOpenPrivateSpace(mutableProfileState.value)
                ) {
                    mutableOverlay.value = PhaseFiveOverlay.NONE
                }
                refreshShortcuts()
                refreshIndicators(returnedFromSettings = false)
            }
        }
    }

    suspend fun refresh(apps: Collection<LauncherApp>) {
        if (closed.get()) return
        currentApps = apps.toList()
        profiles.refresh()
        refreshShortcuts()
        refreshIndicators(returnedFromSettings = false)
    }

    suspend fun refreshAfterExternalSettings(apps: Collection<LauncherApp>) {
        if (closed.get()) return
        currentApps = apps.toList()
        profiles.refresh()
        refreshShortcuts()
        refreshIndicators(returnedFromSettings = true)
    }

    fun openPrivateSpace() {
        if (
            !closed.get() &&
            privateSpaceVisible.get() &&
            canOpenPrivateSpace(mutableProfileState.value)
        ) {
            mutableOverlay.value = PhaseFiveOverlay.PRIVATE_SPACE
        }
    }

    suspend fun openPrivateSpaceSettings(): PrivateSpaceSettingsResult =
        if (closed.get()) PrivateSpaceSettingsResult.Closed else profiles.openPrivateSpaceSettings()

    fun setPrivateSpaceVisible(visible: Boolean) {
        if (closed.get()) return
        privateSpaceVisible.set(visible)
        mutableProfileState.value = profiles.state.value.sanitizedForPrivateVisibility(visible)
        if (!visible && mutableOverlay.value == PhaseFiveOverlay.PRIVATE_SPACE) {
            mutableOverlay.value = PhaseFiveOverlay.NONE
        }
    }

    fun closeOverlay() {
        folders?.dismiss()
        itemActions?.dismiss()
        mutableOverlay.value = PhaseFiveOverlay.NONE
    }

    fun onHome() = closeOverlay()

    suspend fun setPrivateSpaceLocked(locked: Boolean): PrivateSpaceActionResult =
        if (privateSpaceVisible.get()) {
            profiles.setPrivateSpaceLocked(locked)
        } else {
            PrivateSpaceActionResult.Unavailable
        }

    suspend fun setWorkMode(profile: org.quicklauncher.contracts.domain.ProfileSerial, enabled: Boolean) =
        profiles.setWorkMode(profile, enabled)

    suspend fun launchPrivate(identity: org.quicklauncher.contracts.domain.AppActivityIdentity): PrivateLaunchResult =
        if (privateSpaceVisible.get()) profiles.launchPrivate(identity) else PrivateLaunchResult.REJECTED

    suspend fun openFolder(id: org.quicklauncher.contracts.domain.ContentItemId): FolderOperationResult {
        val controller = folders
            ?: return FolderOperationResult.Rejected(
                org.quicklauncher.host.runtime.folders.FolderRejection.PERSISTENCE_FAILURE,
            )
        val result = controller.open(id)
        if (result == FolderOperationResult.Applied) mutableOverlay.value = PhaseFiveOverlay.FOLDER
        return result
    }

    suspend fun restoreFolderOverlay(
        id: org.quicklauncher.contracts.domain.ContentItemId?,
    ): FolderOperationResult {
        val controller = folders
            ?: return FolderOperationResult.Rejected(
                org.quicklauncher.host.runtime.folders.FolderRejection.PERSISTENCE_FAILURE,
            )
        val result = controller.restore(id)
        if (result == FolderOperationResult.Applied) {
            mutableOverlay.value = if (id == null) PhaseFiveOverlay.NONE else PhaseFiveOverlay.FOLDER
        }
        return result
    }

    suspend fun createFolder(name: String): FolderOperationResult {
        val controller = folders
            ?: return FolderOperationResult.Rejected(
                org.quicklauncher.host.runtime.folders.FolderRejection.PERSISTENCE_FAILURE,
            )
        return refreshAfterApplied(controller.create(folderIdentities.next(), name.trim()))
            ?: FolderOperationResult.Rejected(
                org.quicklauncher.host.runtime.folders.FolderRejection.PERSISTENCE_FAILURE,
            )
    }

    suspend fun openItemActions(id: org.quicklauncher.contracts.domain.ContentItemId): ItemActionResult {
        val controller = itemActions
            ?: return ItemActionResult.Rejected(
                org.quicklauncher.host.runtime.actions.ItemActionRejection.UNAVAILABLE,
            )
        val result = controller.open(id)
        if (result == ItemActionResult.Applied) mutableOverlay.value = PhaseFiveOverlay.ITEM_ACTIONS
        return result
    }

    suspend fun executeItemAction(command: ItemActionCommand): ItemActionResult {
        val result = itemActions?.execute(command) ?: ItemActionResult.Rejected(
            org.quicklauncher.host.runtime.actions.ItemActionRejection.UNAVAILABLE,
        )
        if (result == ItemActionResult.Applied && command.changesDurableState()) {
            afterDurableMutation()
        }
        return result
    }

    suspend fun launchShortcut(id: org.quicklauncher.contracts.domain.ContentItemId): ShortcutLaunchResult =
        shortcuts?.launch(id) ?: ShortcutLaunchResult.Unavailable(
            org.quicklauncher.host.runtime.shortcuts.ShortcutUnavailableReason.PLATFORM_FAILURE,
        )

    suspend fun createShortcutPlacement(target: ShortcutTargetIdentity): ShortcutPlacementCreation {
        val coordinator = shortcuts
            ?: return ShortcutPlacementCreation.Unavailable(ShortcutUnavailableReason.PLATFORM_FAILURE)
        val result = coordinator.createPlacement(target)
        if (result is ShortcutPlacementCreation.Created) {
            afterDurableMutation()
            refreshShortcuts()
        }
        return result
    }

    suspend fun activateFolderMember(
        id: org.quicklauncher.contracts.domain.ContentItemId,
    ): FolderMemberActivationResult {
        val app = currentApps.singleOrNull { contentItemId(it) == id && it.collectionVisible }
        if (app != null) {
            return if (appLauncher?.invoke(app.identity) == true) {
                FolderMemberActivationResult.APP_LAUNCHED
            } else {
                FolderMemberActivationResult.UNAVAILABLE
            }
        }
        if (shortcuts?.state?.value?.placements?.any { it.id == id } == true) {
            return if (shortcuts.launch(id) == ShortcutLaunchResult.Launched) {
                FolderMemberActivationResult.SHORTCUT_LAUNCHED
            } else {
                FolderMemberActivationResult.UNAVAILABLE
            }
        }
        return if (folders?.open(id) == FolderOperationResult.Applied) {
            mutableOverlay.value = PhaseFiveOverlay.FOLDER
            FolderMemberActivationResult.FOLDER_OPENED
        } else {
            FolderMemberActivationResult.UNAVAILABLE
        }
    }

    suspend fun moveFolderMember(
        folderId: org.quicklauncher.contracts.domain.ContentItemId,
        memberId: org.quicklauncher.contracts.domain.ContentItemId,
        targetIndex: Int,
    ) = refreshAfterApplied(folders?.moveMember(folderId, memberId, targetIndex))

    suspend fun removeFolderMember(
        folderId: org.quicklauncher.contracts.domain.ContentItemId,
        memberId: org.quicklauncher.contracts.domain.ContentItemId,
    ) = refreshAfterApplied(folders?.removeMember(folderId, memberId))

    suspend fun renameFolder(id: org.quicklauncher.contracts.domain.ContentItemId, name: String) =
        refreshAfterApplied(folders?.rename(id, name))

    suspend fun deleteFolder(id: org.quicklauncher.contracts.domain.ContentItemId) =
        refreshAfterApplied(folders?.delete(id, confirmed = true))

    suspend fun requestNotificationAccess(): NotificationAccessAction = notifications.requestAccess()

    suspend fun openNotificationRecovery(): NotificationAccessAction =
        notifications.openRestrictedSettingsRecovery()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mutableOverlay.value = PhaseFiveOverlay.NONE
        try {
            profiles.close()
        } catch (_: Exception) {
            // Continue closing resources owned by the other adapter.
        }
        try {
            notifications.close()
        } catch (_: Exception) {
            // Closing one adapter must not keep the activity-owned job alive.
        }
        try {
            folders?.close()
        } catch (_: Exception) {
            // Continue closing sibling controllers.
        }
        try {
            itemActions?.close()
        } catch (_: Exception) {
            // Continue cancelling the host scope.
        }
        try {
            shortcuts?.close()
        } catch (_: Exception) {
            // Continue cancelling the host scope.
        }
        scope.cancel()
    }

    private suspend fun refreshIndicators(returnedFromSettings: Boolean) {
        refreshMutex.withLock {
            val eligible = withContext(dispatcher) { eligibleApps(currentApps, profiles.state.value) }
            try {
                if (returnedFromSettings) {
                    notifications.refreshAfterAccessSettings(eligible)
                } else {
                    notifications.refresh(eligible)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                // Profile and launcher content remain usable when notification access fails.
            }
        }
    }

    private suspend fun refreshShortcuts() {
        val coordinator = shortcuts ?: return
        val owners = eligibleShortcutOwners(currentApps, profiles.state.value)
        try {
            coordinator.refresh(owners)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            // Apps remain usable when shortcut-host permission or a profile is unavailable.
        }
    }

    private suspend fun refreshAfterApplied(
        result: FolderOperationResult?,
    ): FolderOperationResult? {
        if (result == FolderOperationResult.Applied) afterDurableMutation()
        return result
    }
}

private fun canOpenPrivateSpace(state: ProfileCoordinatorState): Boolean {
    val privateProfile = state.profiles.singleOrNull { it.kind == ProfileKind.PRIVATE } ?: return true
    return privateProfile.availability != ProfileAvailability.LOCKED ||
        privateProfile.privateSpaceEntryPoint != PrivateSpaceEntryPoint.HIDDEN
}

private fun ItemActionCommand.changesDurableState(): Boolean = when (this) {
    is ItemActionCommand.SetFavorite,
    is ItemActionCommand.Hide,
    ItemActionCommand.Unhide,
    is ItemActionCommand.Rename,
    is ItemActionCommand.AddToFolder,
    is ItemActionCommand.RemoveFromFolder,
    is ItemActionCommand.RemoveShortcut,
    -> true
    ItemActionCommand.OpenDetails,
    is ItemActionCommand.Uninstall,
    is ItemActionCommand.Disable,
    ItemActionCommand.PauseProfile,
    ItemActionCommand.ResumeProfile,
    -> false
}

private fun ProfileCoordinatorState.sanitizedForPrivateVisibility(
    visible: Boolean,
): ProfileCoordinatorState = if (visible) {
    this
} else {
    ProfileCoordinatorState(
        profiles.filterNot { it.kind == ProfileKind.PRIVATE },
        org.quicklauncher.host.runtime.profile.PrivateSpaceState(
            profile = null,
            availability = ProfileAvailability.UNRESOLVED,
            transition = org.quicklauncher.host.runtime.profile.ProfileTransition.IDLE,
            items = emptyList(),
        ),
    )
}

private fun eligibleApps(
    apps: Collection<LauncherApp>,
    state: ProfileCoordinatorState,
): List<IndicatorEligibleApp> = apps
    .groupBy { ProfilePackageIdentity(it.identity.profile, it.identity.packageName) }
    .mapNotNull { (identity, activities) ->
        val representative = activities.first()
        val profile = state.profile(identity.profile) ?: return@mapNotNull null
        val kind = when {
            representative.workProfile && profile.kind == ProfileKind.WORK -> IndicatorProfileKind.WORK
            !representative.workProfile && profile.kind == ProfileKind.PERSONAL -> IndicatorProfileKind.PERSONAL
            else -> return@mapNotNull null
        }
        IndicatorEligibleApp(
            identity = identity,
            profileKind = kind,
            profileAvailable = profile.availability == ProfileAvailability.AVAILABLE,
            collectionVisible = activities.any(LauncherApp::collectionVisible),
            indicatorEligible = true,
        )
    }

private fun eligibleShortcutOwners(
    apps: Collection<LauncherApp>,
    state: ProfileCoordinatorState,
): List<ShortcutOwner> = apps.mapNotNull { app ->
    val profile = state.profile(app.identity.profile) ?: return@mapNotNull null
    val kindMatches = when (profile.kind) {
        ProfileKind.PERSONAL -> !app.workProfile
        ProfileKind.WORK -> app.workProfile
        ProfileKind.PRIVATE -> false
    }
    if (!kindMatches || profile.availability != ProfileAvailability.AVAILABLE) return@mapNotNull null
    ShortcutOwner(app.identity.profile, app.identity.packageName)
}.distinct()

private class UnavailableProfilePlatform : ProfilePlatform {
    override val invalidations = emptyFlow<ProfileInvalidation>()
    override suspend fun profiles(): List<ProfileRecord> = emptyList()
    override suspend fun privateItems(profile: org.quicklauncher.contracts.domain.ProfileSerial) =
        PrivateItemsResult.Unavailable
    override suspend fun setQuietMode(
        profile: org.quicklauncher.contracts.domain.ProfileSerial,
        quiet: Boolean,
    ) = WorkModeRequestResult.REJECTED
    override suspend fun launchPrivate(identity: org.quicklauncher.contracts.domain.AppActivityIdentity) =
        PrivateLaunchResult.REJECTED
    override fun close() = Unit
}

private class UnavailableNotificationPlatform : NotificationPlatform {
    override val invalidations = emptyFlow<Unit>()
    override suspend fun status() = NotificationListenerStatus.UNAVAILABLE
    override suspend fun counts() = NotificationCountSnapshot.Empty
    override suspend fun requestAccess() = NotificationPlatformActionResult.UNAVAILABLE
    override suspend fun openRestrictedSettingsRecovery() = NotificationPlatformActionResult.UNAVAILABLE
    override fun close() = Unit
}

private class UnavailableShortcutPlatform : ShortcutPlatform {
    override val invalidations = emptyFlow<ShortcutInvalidation>()
    override suspend fun query(owner: ShortcutOwner) =
        ShortcutQueryResult.Unavailable(ShortcutUnavailableReason.PLATFORM_FAILURE)
    override suspend fun pin(request: ShortcutPinRequest) =
        ShortcutPinResult.Unavailable(ShortcutUnavailableReason.PLATFORM_FAILURE)
    override suspend fun launch(target: ShortcutTargetIdentity) =
        ShortcutLaunchResult.Unavailable(ShortcutUnavailableReason.PLATFORM_FAILURE)
    override fun close() = Unit
}

fun unavailableProfilePlatform(): ProfilePlatform = UnavailableProfilePlatform()

fun unavailableNotificationPlatform(): NotificationPlatform = UnavailableNotificationPlatform()

fun unavailableShortcutPlatform(): ShortcutPlatform = UnavailableShortcutPlatform()

fun productionPhaseFiveHost(
    store: LauncherStore,
    runtime: org.quicklauncher.host.runtime.LauncherRuntime,
    profiles: ProfileCoordinator,
    notifications: NotificationIndicatorCoordinator,
    itemPlatform: ItemActionPlatform,
    shortcuts: ShortcutCoordinator,
    scope: CoroutineScope,
): PhaseFiveHost {
    suspend fun currentVisibleApp(id: org.quicklauncher.contracts.domain.ContentItemId): LauncherApp? =
        runtime.state.value.visibleApps.singleOrNull {
            contentItemId(it) == id && it.collectionVisible
        }

    suspend fun currentSettingsApp(id: org.quicklauncher.contracts.domain.ContentItemId): LauncherApp? =
        settingsActionApp(runtime.state.value.settingsApps, id)

    val policy = ItemActionPolicySource { id ->
        // Settings keeps hidden apps in this already-sanitized collection so UNHIDE remains
        // reachable. Locked work and all private apps are absent before this seam.
        val app = currentSettingsApp(id)
        if (app != null) {
            productionAppPolicyItem(id, app)
        } else {
            val shortcut = shortcuts.state.value.placements.singleOrNull { it.id == id }
                ?: return@ItemActionPolicySource null
            val available = shortcut as? ResolvedShortcutPlacement.Available
                ?: return@ItemActionPolicySource null
            ItemActionPolicyItem(
                id = id,
                label = available.label,
                kind = ActionableItemKind.SHORTCUT,
                availability = ItemAvailability.AVAILABLE,
                appIdentity = null,
                renameAllowed = false,
                detailsAllowed = false,
                uninstallAllowed = false,
                disableAllowed = false,
                profileAction = ProfileItemAction.NONE,
            )
        }
    }
    val targetResolver = ItemMutationTargetResolver { id ->
        currentSettingsApp(id)?.let {
            org.quicklauncher.host.runtime.actions.ItemMutationTarget.App(it.identity)
        }
            ?: store.read().contentItems.singleOrNull {
                it.id == id && it.kind == ContentItemKind.SHORTCUT
            }?.let { org.quicklauncher.host.runtime.actions.ItemMutationTarget.Shortcut }
    }
    val itemActions = DefaultItemActionOverlayController(
        LauncherStoreItemActionResolver(store, policy),
        LauncherStoreItemActionMutation(store, targetResolver),
        profileCoordinatedItemPlatform(itemPlatform, profiles),
    )
    val memberResolver = object : FolderMemberResolver {
        override val invalidations = merge(
            runtime.state.map { Unit },
            profiles.state.map { Unit },
        )

        override suspend fun resolve(
            id: org.quicklauncher.contracts.domain.ContentItemId,
        ): FolderMemberPresentation? {
            val app = currentVisibleApp(id)
            if (app != null) {
                if (app.workProfile) {
                    val work = profiles.state.value.profile(app.identity.profile)
                    if (work?.kind != ProfileKind.WORK ||
                        work.availability != ProfileAvailability.AVAILABLE
                    ) return null
                }
                return FolderMemberPresentation(
                    id,
                    app.label,
                    FolderMemberKind.APP,
                    workBadge = app.workProfile,
                    available = true,
                )
            }
            val folder = store.read().contentItems.singleOrNull {
                it.id == id && it.kind == ContentItemKind.FOLDER
            }
            if (folder == null) {
                val shortcut = shortcuts.state.value.placements.singleOrNull { it.id == id }
                    as? ResolvedShortcutPlacement.Available ?: return null
                val shortcutProfile = profiles.state.value.profile(shortcut.target.profile) ?: return null
                if (shortcutProfile.kind == ProfileKind.PRIVATE ||
                    shortcutProfile.availability != ProfileAvailability.AVAILABLE
                ) return null
                return FolderMemberPresentation(
                    shortcut.id,
                    shortcut.label,
                    FolderMemberKind.SHORTCUT,
                    workBadge = shortcutProfile.kind == ProfileKind.WORK,
                    available = true,
                )
            }
            return FolderMemberPresentation(
                folder.id,
                folder.encoded,
                FolderMemberKind.FOLDER,
                workBadge = false,
                available = true,
            )
        }
    }
    val folders = DefaultFolderOverlayController(
        LauncherStoreFolderRepository(store),
        memberResolver,
        scope,
    )
    return PhaseFiveHost(
        profiles,
        notifications,
        scope,
        folders = folders,
        itemActions = itemActions,
        shortcuts = shortcuts,
        appLauncher = { identity ->
            runtime.launch(identity) == org.quicklauncher.host.runtime.catalog.AppLaunchResult.Launched
        },
        afterDurableMutation = {
            try {
                runtime.onResume()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                // The durable commit stands. Still reconcile shortcut pinning from the store.
            }
            try {
                shortcuts.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                // The coordinator retains explicit unavailable reconciliation state where possible.
            }
        },
    )
}

internal fun settingsActionApp(
    apps: Collection<LauncherApp>,
    id: org.quicklauncher.contracts.domain.ContentItemId,
): LauncherApp? = apps.singleOrNull { contentItemId(it) == id }

internal fun productionAppPolicyItem(
    id: org.quicklauncher.contracts.domain.ContentItemId,
    app: LauncherApp,
): ItemActionPolicyItem = ItemActionPolicyItem(
    id = id,
    label = app.label,
    kind = ActionableItemKind.APP,
    availability = ItemAvailability.AVAILABLE,
    appIdentity = app.identity,
    renameAllowed = true,
    detailsAllowed = true,
    // The platform adapter intersects these candidates with current package and device policy.
    uninstallAllowed = true,
    disableAllowed = true,
    profileAction = if (app.workProfile) ProfileItemAction.PAUSE else ProfileItemAction.NONE,
)

internal fun profileCoordinatedItemPlatform(
    delegate: ItemActionPlatform,
    profiles: ProfileCoordinator,
): ItemActionPlatform = object : ItemActionPlatform {
    override suspend fun eligibility(identity: org.quicklauncher.contracts.domain.AppActivityIdentity) =
        delegate.eligibility(identity)

    override suspend fun openDetails(identity: org.quicklauncher.contracts.domain.AppActivityIdentity) =
        delegate.openDetails(identity)

    override suspend fun uninstall(identity: org.quicklauncher.contracts.domain.AppActivityIdentity) =
        delegate.uninstall(identity)

    override suspend fun disable(identity: org.quicklauncher.contracts.domain.AppActivityIdentity) =
        delegate.disable(identity)

    override suspend fun changeProfileMode(
        profile: org.quicklauncher.contracts.domain.ProfileSerial,
        action: ProfileItemAction,
    ): org.quicklauncher.host.runtime.actions.ItemPlatformResult {
        if (action == ProfileItemAction.NONE) {
            return org.quicklauncher.host.runtime.actions.ItemPlatformResult.Unavailable
        }
        return when (profiles.setWorkMode(profile, enabled = action == ProfileItemAction.RESUME)) {
            WorkModeResult.Requested,
            WorkModeResult.AlreadyInRequestedMode,
            WorkModeResult.TransitionInProgress,
            -> org.quicklauncher.host.runtime.actions.ItemPlatformResult.Completed
            WorkModeResult.Denied -> org.quicklauncher.host.runtime.actions.ItemPlatformResult.Denied
            WorkModeResult.Rejected -> org.quicklauncher.host.runtime.actions.ItemPlatformResult.Failed
            WorkModeResult.MissingProfile,
            WorkModeResult.NotWorkProfile,
            WorkModeResult.Closed,
            -> org.quicklauncher.host.runtime.actions.ItemPlatformResult.Unavailable
        }
    }
}
