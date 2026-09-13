package org.quicklauncher.host.runtime.phasefive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.host.runtime.LauncherApp
import org.quicklauncher.host.runtime.contentItemId
import org.quicklauncher.host.runtime.catalog.AppIcon
import org.quicklauncher.host.runtime.notifications.IndicatorEligibleApp
import org.quicklauncher.host.runtime.notifications.NotificationAccessAction
import org.quicklauncher.host.runtime.notifications.NotificationIndicatorCoordinator
import org.quicklauncher.host.runtime.notifications.NotificationIndicatorState
import org.quicklauncher.host.runtime.actions.ItemActionCommand
import org.quicklauncher.host.runtime.actions.ItemActionOverlayController
import org.quicklauncher.host.runtime.actions.ItemActionOverlayState
import org.quicklauncher.host.runtime.actions.ItemActionResult
import org.quicklauncher.host.runtime.actions.ItemActionPlatform
import org.quicklauncher.host.runtime.actions.ItemActionEligibility
import org.quicklauncher.host.runtime.actions.ItemActionPermission
import org.quicklauncher.host.runtime.actions.ItemPlatformResult
import org.quicklauncher.host.runtime.actions.ProfileItemAction
import org.quicklauncher.host.runtime.folders.FolderOperationResult
import org.quicklauncher.host.runtime.folders.FolderOverlayController
import org.quicklauncher.host.runtime.folders.FolderOverlayState
import org.quicklauncher.host.runtime.profile.PrivateSpaceActionResult
import org.quicklauncher.host.runtime.profile.PrivateSpaceEntryPoint
import org.quicklauncher.host.runtime.profile.PrivateLaunchResult
import org.quicklauncher.host.runtime.profile.PrivateSpaceSettingsResult
import org.quicklauncher.host.runtime.profile.PrivateSpaceState
import org.quicklauncher.host.runtime.profile.PrivateProfileItem
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileCoordinator
import org.quicklauncher.host.runtime.profile.ProfileCoordinatorState
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.profile.ProfileState
import org.quicklauncher.host.runtime.profile.ProfileTransition
import org.quicklauncher.host.runtime.profile.WorkModeResult
import org.quicklauncher.host.runtime.shortcuts.ResolvedShortcutPlacement
import org.quicklauncher.host.runtime.shortcuts.DiscoveredShortcut
import org.quicklauncher.host.runtime.shortcuts.ShortcutCoordinator
import org.quicklauncher.host.runtime.shortcuts.ShortcutLaunchResult
import org.quicklauncher.host.runtime.shortcuts.ShortcutOwner
import org.quicklauncher.host.runtime.shortcuts.ShortcutPlacement
import org.quicklauncher.host.runtime.shortcuts.ShortcutPlacementCreation
import org.quicklauncher.host.runtime.shortcuts.ShortcutSnapshot
import org.quicklauncher.host.runtime.shortcuts.ShortcutTargetIdentity

@OptIn(ExperimentalCoroutinesApi::class)
class PhaseFiveHostTest {
    @Test
    fun folderCreationUsesDistinctDurableIdentitiesAndRefreshesProductionState() = runTest {
        val first = ContentItemId.parse("org.quicklauncher.content/folder-first")
        val second = ContentItemId.parse("org.quicklauncher.content/folder-second")
        val identities = ArrayDeque(listOf(first, second))
        val folders = FakeFolders()
        var refreshes = 0
        val host = PhaseFiveHost(
            FakeProfiles(),
            FakeNotifications(),
            backgroundScope,
            folders = folders,
            folderIdentities = FolderIdentitySource { identities.removeFirst() },
            afterDurableMutation = { refreshes += 1 },
        )

        assertEquals(FolderOperationResult.Applied, host.createFolder("Trips"))
        assertEquals(FolderOperationResult.Applied, host.createFolder("Receipts"))

        assertEquals(listOf(first to "Trips", second to "Receipts"), folders.creations)
        assertEquals(2, refreshes)
        host.close()
    }

    @Test
    fun savedFolderIdentityRestoresTheHostOwnedOverlay() = runTest {
        val folderId = ContentItemId.parse("org.quicklauncher.content/restored-folder")
        val folders = FakeFolders()
        val host = PhaseFiveHost(
            FakeProfiles(),
            FakeNotifications(),
            backgroundScope,
            folders = folders,
        )

        assertEquals(FolderOperationResult.Applied, host.restoreFolderOverlay(folderId))

        assertEquals(listOf(folderId), folders.restorations)
        assertEquals(PhaseFiveOverlay.FOLDER, host.overlay.value)
        host.close()
    }

    @Test
    fun appliedDurableItemMutationRefreshesProductionStateButPlatformActionDoesNot() = runTest {
        var refreshes = 0
        val actions = FakeItemActions()
        val host = PhaseFiveHost(
            FakeProfiles(),
            FakeNotifications(),
            backgroundScope,
            itemActions = actions,
            afterDurableMutation = { refreshes += 1 },
        )

        assertEquals(ItemActionResult.Applied, host.executeItemAction(ItemActionCommand.SetFavorite(true)))
        assertEquals(1, refreshes)

        assertEquals(ItemActionResult.Applied, host.executeItemAction(ItemActionCommand.OpenDetails))
        assertEquals(1, refreshes)
        host.close()
    }

    @Test
    fun appliedFolderMutationRefreshesProductionStateAndPreservesCancellation() = runTest {
        val folderId = ContentItemId.parse("org.quicklauncher.content/folder")
        val memberId = ContentItemId.parse("org.quicklauncher.content/member")
        var refreshes = 0
        val host = PhaseFiveHost(
            FakeProfiles(),
            FakeNotifications(),
            backgroundScope,
            folders = FakeFolders(),
            afterDurableMutation = {
                refreshes += 1
                throw CancellationException("cancelled refresh")
            },
        )

        try {
            host.removeFolderMember(folderId, memberId)
            fail("CancellationException must propagate")
        } catch (_: CancellationException) {
            // Expected: a committed mutation must not turn coroutine cancellation into a result.
        }
        assertEquals(1, refreshes)
        host.close()
    }

    @Test
    fun workModeItemActionsUseProfileCoordinatorInsteadOfPlatformDelegate() = runTest {
        val profile = ProfileSerial.of(10)
        val profiles = FakeProfiles()
        val delegate = FakeItemPlatform()
        val platform = profileCoordinatedItemPlatform(delegate, profiles)

        assertEquals(
            ItemPlatformResult.Completed,
            platform.changeProfileMode(profile, ProfileItemAction.PAUSE),
        )

        assertEquals(listOf(profile to false), profiles.workModeRequests)
        assertEquals(0, delegate.profileModeRequests)
    }

    @Test
    fun productionPolicyNominatesDestructiveRoutesForPlatformEligibilityProof() {
        val personal = ProfileSerial.of(0)
        val app = app(personal, "org.quicklauncher.personal", "MainActivity", work = false)

        val policy = productionAppPolicyItem(contentItemId(app), app)

        assertTrue(policy.detailsAllowed)
        assertTrue(policy.uninstallAllowed)
        assertTrue(policy.disableAllowed)
    }

    @Test
    fun settingsActionLookupKeepsAHiddenOrdinaryAppReachableByStableIdentity() {
        val personal = ProfileSerial.of(0)
        val hidden = app(personal, "org.quicklauncher.hidden", "MainActivity", work = false)

        assertEquals(hidden, settingsActionApp(listOf(hidden), contentItemId(hidden)))
        assertEquals(
            null,
            settingsActionApp(
                listOf(hidden),
                ContentItemId.parse("org.quicklauncher.app/item-missing"),
            ),
        )
    }

    @Test
    fun folderShortcutActivationUsesTheRevalidatingShortcutCoordinator() = runTest {
        val placementId = ContentItemId.parse("org.quicklauncher.content/compose")
        val shortcuts = FakeShortcuts(placementId)
        val host = PhaseFiveHost(
            FakeProfiles(),
            FakeNotifications(),
            backgroundScope,
            shortcuts = shortcuts,
        )

        assertEquals(FolderMemberActivationResult.SHORTCUT_LAUNCHED, host.activateFolderMember(placementId))
        assertEquals(listOf(placementId), shortcuts.launches)
        host.close()
    }

    @Test
    fun collectionHiddenSearchResultCannotBeActivatedAsAFolderMember() = runTest {
        val personal = ProfileSerial.of(0)
        val hidden = app(
            personal,
            "org.quicklauncher.hidden",
            "MainActivity",
            work = false,
            collectionVisible = false,
        )
        val launches = mutableListOf<AppActivityIdentity>()
        val host = PhaseFiveHost(
            FakeProfiles(
                ProfileCoordinatorState(
                    listOf(
                        ProfileState(
                            personal,
                            ProfileKind.PERSONAL,
                            ProfileAvailability.AVAILABLE,
                            ProfileTransition.IDLE,
                        ),
                    ),
                    PrivateSpaceState(
                        null,
                        ProfileAvailability.UNRESOLVED,
                        ProfileTransition.IDLE,
                        emptyList(),
                    ),
                ),
            ),
            FakeNotifications(),
            backgroundScope,
            appLauncher = { identity -> launches += identity; true },
        )
        host.refresh(listOf(hidden))

        assertEquals(
            FolderMemberActivationResult.UNAVAILABLE,
            host.activateFolderMember(contentItemId(hidden)),
        )
        assertTrue(launches.isEmpty())
        host.close()
    }

    @Test
    fun privateSpaceIsTransientAndHomeAlwaysDismissesIt() = runTest {
        val profiles = FakeProfiles()
        val notifications = FakeNotifications()
        val host = PhaseFiveHost(profiles, notifications, backgroundScope, StandardTestDispatcher(testScheduler))

        host.openPrivateSpace()
        assertEquals(PhaseFiveOverlay.PRIVATE_SPACE, host.overlay.value)

        host.onHome()
        assertEquals(PhaseFiveOverlay.NONE, host.overlay.value)

        host.close()
    }

    @Test
    fun systemHiddenLockedPrivateEntryPointStaysClosedButSettingsRecoveryRemainsTyped() = runTest {
        val private = ProfileSerial.of(42)
        val profiles = FakeProfiles(
            ProfileCoordinatorState(
                listOf(
                    ProfileState(
                        private,
                        ProfileKind.PRIVATE,
                        ProfileAvailability.LOCKED,
                        ProfileTransition.IDLE,
                        PrivateSpaceEntryPoint.HIDDEN,
                    ),
                ),
                PrivateSpaceState(
                    private,
                    ProfileAvailability.LOCKED,
                    ProfileTransition.IDLE,
                    emptyList(),
                ),
            ),
        )
        val host = PhaseFiveHost(profiles, FakeNotifications(), backgroundScope)

        host.openPrivateSpace()

        assertEquals(PhaseFiveOverlay.NONE, host.overlay.value)
        assertEquals(PrivateSpaceSettingsResult.Launched, host.openPrivateSpaceSettings())
        assertEquals(1, profiles.privateSpaceSettingsRequests)
        host.close()
    }

    @Test
    fun hiddenPrivateSpacePurgesExposedMetadataAndKeepsOverlayClosed() = runTest {
        val private = ProfileSerial.of(42)
        val identity = AppActivityIdentity(
            private,
            PackageName.parse("org.example.private"),
            ActivityName.parse("org.example.private.Main"),
        )
        val profiles = FakeProfiles(
            ProfileCoordinatorState(
                listOf(
                    ProfileState(
                        private,
                        ProfileKind.PRIVATE,
                        ProfileAvailability.AVAILABLE,
                        ProfileTransition.IDLE,
                    ),
                ),
                PrivateSpaceState(
                    private,
                    ProfileAvailability.AVAILABLE,
                    ProfileTransition.IDLE,
                    listOf(PrivateProfileItem(identity, "Secret label", AppIcon.of(byteArrayOf(1)))),
                ),
            ),
        )
        val host = PhaseFiveHost(profiles, FakeNotifications(), backgroundScope)

        host.setPrivateSpaceVisible(false)
        host.openPrivateSpace()

        assertEquals(PhaseFiveOverlay.NONE, host.overlay.value)
        assertTrue(host.profileState.value.profiles.none { it.kind == ProfileKind.PRIVATE })
        assertTrue(host.profileState.value.privateSpace.items.isEmpty())
        assertEquals(null, host.profileState.value.privateSpace.profile)
        assertEquals(PrivateLaunchResult.REJECTED, host.launchPrivate(identity))

        host.setPrivateSpaceVisible(true)
        assertEquals(listOf("Secret label"), host.profileState.value.privateSpace.items.map { it.label })
        host.close()
    }

    @Test
    fun notificationRefreshUsesValidatedProfileAvailabilityAndOneEntryPerPackage() = runTest {
        val personal = ProfileSerial.of(0)
        val work = ProfileSerial.of(10)
        val profiles = FakeProfiles(
            ProfileCoordinatorState(
                listOf(
                    ProfileState(personal, ProfileKind.PERSONAL, ProfileAvailability.AVAILABLE, ProfileTransition.IDLE),
                    ProfileState(work, ProfileKind.WORK, ProfileAvailability.QUIET, ProfileTransition.IDLE),
                ),
                PrivateSpaceState(null, ProfileAvailability.UNRESOLVED, ProfileTransition.IDLE, emptyList()),
            ),
        )
        val notifications = FakeNotifications()
        val host = PhaseFiveHost(profiles, notifications, backgroundScope, StandardTestDispatcher(testScheduler))
        val apps = listOf(
            app(
                personal,
                "org.quicklauncher.personal",
                "HiddenActivity",
                work = false,
                collectionVisible = false,
            ),
            app(personal, "org.quicklauncher.personal", "SecondActivity", work = false),
            app(work, "org.quicklauncher.work", "MainActivity", work = true),
            app(
                personal,
                "org.quicklauncher.searchonly",
                "MainActivity",
                work = false,
                collectionVisible = false,
            ),
        )

        host.refresh(apps)

        assertEquals(3, notifications.lastApps.size)
        assertTrue(
            notifications.lastApps.single {
                it.identity.packageName.value == "org.quicklauncher.personal"
            }.collectionVisible,
        )
        assertTrue(notifications.lastApps.single { !it.profileAvailable }.identity.profile == work)
        assertTrue(
            notifications.lastApps.single {
                it.identity.packageName.value == "org.quicklauncher.searchonly"
            }.collectionVisible.not(),
        )

        host.close()
    }

    @Test
    fun shortcutDiscoveryAdmitsOnlyVisibleAvailableNonPrivateOwners() = runTest {
        val personal = ProfileSerial.of(0)
        val work = ProfileSerial.of(10)
        val private = ProfileSerial.of(20)
        val shortcuts = FakeShortcuts()
        val host = PhaseFiveHost(
            FakeProfiles(
                ProfileCoordinatorState(
                    listOf(
                        ProfileState(personal, ProfileKind.PERSONAL, ProfileAvailability.AVAILABLE, ProfileTransition.IDLE),
                        ProfileState(work, ProfileKind.WORK, ProfileAvailability.QUIET, ProfileTransition.IDLE),
                        ProfileState(private, ProfileKind.PRIVATE, ProfileAvailability.AVAILABLE, ProfileTransition.IDLE),
                    ),
                    PrivateSpaceState(private, ProfileAvailability.AVAILABLE, ProfileTransition.IDLE, emptyList()),
                ),
            ),
            FakeNotifications(),
            backgroundScope,
            shortcuts = shortcuts,
        )

        host.refresh(
            listOf(
                app(personal, "org.quicklauncher.personal", "MainActivity", work = false),
                app(work, "org.quicklauncher.work", "MainActivity", work = true),
                app(private, "org.quicklauncher.private", "MainActivity", work = false),
            ),
        )

        assertEquals(
            listOf(ShortcutOwner(personal, PackageName.parse("org.quicklauncher.personal"))),
            shortcuts.eligibleOwners,
        )
        host.close()
    }

    @Test
    fun shortcutSelectionCreatesDistinctPlacementsAndRefreshesProductionStateEachTime() = runTest {
        val target = ShortcutTargetIdentity(
            ProfileSerial.of(0),
            PackageName.parse("org.quicklauncher.mail"),
            ShortcutId.parse("compose"),
        )
        val shortcuts = FakeShortcuts(discoveredTarget = target)
        var refreshes = 0
        val host = PhaseFiveHost(
            FakeProfiles(
                ProfileCoordinatorState(
                    listOf(
                        ProfileState(
                            target.profile,
                            ProfileKind.PERSONAL,
                            ProfileAvailability.AVAILABLE,
                            ProfileTransition.IDLE,
                        ),
                    ),
                    PrivateSpaceState(null, ProfileAvailability.UNRESOLVED, ProfileTransition.IDLE, emptyList()),
                ),
            ),
            FakeNotifications(),
            backgroundScope,
            shortcuts = shortcuts,
            afterDurableMutation = { refreshes += 1 },
        )
        host.refresh(listOf(app(target.profile, target.packageName.value, "MainActivity", work = false)))

        val first = host.createShortcutPlacement(target) as ShortcutPlacementCreation.Created
        val second = host.createShortcutPlacement(target) as ShortcutPlacementCreation.Created

        assertTrue(first.placement.id != second.placement.id)
        assertEquals(listOf(target, target), shortcuts.createdTargets)
        assertEquals(2, refreshes)
        host.close()
    }

    @Test
    fun closeAttemptsEveryOwnedCoordinatorWhenOneThrows() {
        val profiles = FakeProfiles(throwOnClose = true)
        val notifications = FakeNotifications()
        val scope = CoroutineScope(SupervisorJob())
        val host = PhaseFiveHost(profiles, notifications, scope)

        host.close()

        assertTrue(profiles.closeCalled)
        assertTrue(notifications.closeCalled)
        scope.cancel()
    }

    private fun app(
        profile: ProfileSerial,
        packageName: String,
        activity: String,
        work: Boolean,
        collectionVisible: Boolean = true,
    ) = LauncherApp(
        AppActivityIdentity(
            profile,
            PackageName.parse(packageName),
            ActivityName.parse("$packageName.$activity"),
        ),
        activity,
        workProfile = work,
        collectionVisible = collectionVisible,
    )

    private class FakeProfiles(
        initial: ProfileCoordinatorState = ProfileCoordinatorState.Empty,
        private val throwOnClose: Boolean = false,
    ) : ProfileCoordinator {
        override val state = MutableStateFlow(initial)
        var closeCalled = false
        var privateSpaceSettingsRequests = 0
        val workModeRequests = mutableListOf<Pair<ProfileSerial, Boolean>>()
        override suspend fun refresh() = Unit
        override suspend fun setWorkMode(profile: ProfileSerial, enabled: Boolean): WorkModeResult {
            workModeRequests += profile to enabled
            return WorkModeResult.Requested
        }
        override suspend fun setPrivateSpaceLocked(locked: Boolean) = PrivateSpaceActionResult.Requested
        override suspend fun launchPrivate(identity: AppActivityIdentity) = PrivateLaunchResult.LAUNCHED
        override suspend fun openPrivateSpaceSettings(): PrivateSpaceSettingsResult {
            privateSpaceSettingsRequests += 1
            return PrivateSpaceSettingsResult.Launched
        }
        override fun close() {
            closeCalled = true
            if (throwOnClose) error("profile close failed")
        }
    }

    private class FakeNotifications : NotificationIndicatorCoordinator {
        override val state: StateFlow<NotificationIndicatorState> =
            MutableStateFlow(NotificationIndicatorState.Initial)
        var lastApps: List<IndicatorEligibleApp> = emptyList()
        var closeCalled = false
        override suspend fun refresh(apps: Collection<IndicatorEligibleApp>) {
            lastApps = apps.toList()
        }
        override suspend fun refreshAfterAccessSettings(apps: Collection<IndicatorEligibleApp>) {
            lastApps = apps.toList()
        }
        override suspend fun requestAccess() = NotificationAccessAction.OpenedSettings
        override suspend fun openRestrictedSettingsRecovery() = NotificationAccessAction.OpenedSettings
        override fun close() {
            closeCalled = true
        }
    }

    private class FakeShortcuts(
        placementId: ContentItemId? = null,
        private val discoveredTarget: ShortcutTargetIdentity? = null,
    ) : ShortcutCoordinator {
        private val target = ShortcutTargetIdentity(
            ProfileSerial.of(0),
            PackageName.parse("org.quicklauncher.mail"),
            ShortcutId.parse("compose"),
        )
        override val state = MutableStateFlow(
            ShortcutSnapshot(
                placementId?.let {
                    listOf(
                    ResolvedShortcutPlacement.Available(
                        it,
                        target,
                        "Compose",
                        null,
                        dynamic = true,
                        pinned = true,
                    ),
                    )
                } ?: emptyList(),
                emptyMap(),
                availableShortcuts = discoveredTarget?.let {
                    listOf(DiscoveredShortcut(it, "Compose", null, dynamic = true, pinned = false, enabled = true))
                } ?: emptyList(),
            ),
        )
        val launches = mutableListOf<ContentItemId>()
        var eligibleOwners = emptyList<ShortcutOwner>()
        val createdTargets = mutableListOf<ShortcutTargetIdentity>()
        private var created = 0
        override suspend fun refresh() = Unit
        override suspend fun refresh(eligibleOwners: Collection<ShortcutOwner>) {
            this.eligibleOwners = eligibleOwners.toList()
        }
        override suspend fun createPlacement(target: ShortcutTargetIdentity): ShortcutPlacementCreation {
            createdTargets += target
            created += 1
            return ShortcutPlacementCreation.Created(
                ShortcutPlacement(
                    ContentItemId.parse("org.quicklauncher.content/created-$created"),
                    target,
                ),
            )
        }
        override suspend fun launch(placementId: ContentItemId): ShortcutLaunchResult {
            launches += placementId
            return ShortcutLaunchResult.Launched
        }
        override fun close() = Unit
    }

    private class FakeItemActions : ItemActionOverlayController {
        override val state = MutableStateFlow<ItemActionOverlayState>(ItemActionOverlayState.Closed)
        override suspend fun open(itemId: ContentItemId) = ItemActionResult.Applied
        override suspend fun execute(command: ItemActionCommand) = ItemActionResult.Applied
        override fun dismiss() = true
        override fun close() = Unit
    }

    private class FakeFolders : FolderOverlayController {
        val creations = mutableListOf<Pair<ContentItemId, String>>()
        val restorations = mutableListOf<ContentItemId?>()
        override val state = MutableStateFlow<FolderOverlayState>(FolderOverlayState.Closed)
        override suspend fun open(folderId: ContentItemId) = FolderOperationResult.Applied
        override suspend fun restore(folderId: ContentItemId?): FolderOperationResult {
            restorations += folderId
            return FolderOperationResult.Applied
        }
        override suspend fun create(folderId: ContentItemId, name: String): FolderOperationResult {
            creations += folderId to name
            return FolderOperationResult.Applied
        }
        override suspend fun rename(folderId: ContentItemId, name: String) = FolderOperationResult.Applied
        override suspend fun setMembers(folderId: ContentItemId, members: List<ContentItemId>) =
            FolderOperationResult.Applied
        override suspend fun addMember(folderId: ContentItemId, memberId: ContentItemId) =
            FolderOperationResult.Applied
        override suspend fun removeMember(folderId: ContentItemId, memberId: ContentItemId) =
            FolderOperationResult.Applied
        override suspend fun moveMember(folderId: ContentItemId, memberId: ContentItemId, targetIndex: Int) =
            FolderOperationResult.Applied
        override suspend fun delete(folderId: ContentItemId, confirmed: Boolean) =
            FolderOperationResult.Applied
        override fun dismiss() = true
        override fun close() = Unit
    }

    private class FakeItemPlatform : ItemActionPlatform {
        var profileModeRequests = 0
        override suspend fun eligibility(identity: AppActivityIdentity) = ItemActionEligibility(
            ItemActionPermission.ALLOWED,
            ItemActionPermission.ALLOWED,
        )
        override suspend fun openDetails(identity: AppActivityIdentity) = ItemPlatformResult.Completed
        override suspend fun uninstall(identity: AppActivityIdentity) = ItemPlatformResult.Completed
        override suspend fun disable(identity: AppActivityIdentity) = ItemPlatformResult.Completed
        override suspend fun changeProfileMode(profile: ProfileSerial, action: ProfileItemAction): ItemPlatformResult {
            profileModeRequests += 1
            return ItemPlatformResult.Completed
        }
    }
}
