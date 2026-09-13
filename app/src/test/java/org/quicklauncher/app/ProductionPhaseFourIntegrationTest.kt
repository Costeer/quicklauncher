package org.quicklauncher.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.ProfilePackageIdentity
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.BlockAction
import org.quicklauncher.contracts.ui.LayoutAction
import org.quicklauncher.contracts.ui.PreparedContentKind
import org.quicklauncher.contracts.ui.RenderStatus
import org.quicklauncher.contracts.ui.NotificationIndicator
import org.quicklauncher.contracts.ui.PreparedProfileKind
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.ui.BackgroundContrast
import org.quicklauncher.contracts.ui.EditorMode
import org.quicklauncher.contracts.ui.LauncherTheme
import org.quicklauncher.contracts.ui.ThemeMode
import org.quicklauncher.contracts.ui.WindowInfo
import org.quicklauncher.contracts.ui.WindowOrientation
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.ContentItemKind
import org.quicklauncher.host.data.store.ContentItemRecord
import org.quicklauncher.host.data.store.RegistryPlacementPolicy
import org.quicklauncher.host.data.store.WidgetBindState
import org.quicklauncher.host.data.store.WidgetPlacementRecord
import org.quicklauncher.host.data.store.WidgetRestoreState
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.registryConfigurationResolver
import org.quicklauncher.host.editor.EditorIdentitySource
import org.quicklauncher.host.editor.DefaultLauncherEditor
import org.quicklauncher.host.editor.EditorAction
import org.quicklauncher.host.editor.EditorResult
import org.quicklauncher.host.editor.OnboardingResult
import org.quicklauncher.host.editor.OnboardingSafeLayoutFactory
import org.quicklauncher.host.editor.SafeLayoutDraft
import org.quicklauncher.host.editor.TemplateOnboarding
import org.quicklauncher.host.runtime.SafeLayoutIds
import org.quicklauncher.host.runtime.LauncherApp
import org.quicklauncher.host.runtime.catalog.AppCatalogStatus
import org.quicklauncher.host.runtime.profile.PrivateSpaceState
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileCoordinatorState
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.profile.ProfileState
import org.quicklauncher.host.runtime.profile.ProfileTransition
import org.quicklauncher.host.runtime.shortcuts.ResolvedShortcutPlacement
import org.quicklauncher.host.runtime.shortcuts.ShortcutSnapshot
import org.quicklauncher.host.runtime.shortcuts.ShortcutTargetIdentity
import org.quicklauncher.host.runtime.widgets.WidgetRenderToken
import org.quicklauncher.host.runtime.widgets.WidgetBindingRequest
import org.quicklauncher.host.runtime.widgets.WidgetCoordinator
import org.quicklauncher.host.runtime.widgets.WidgetFlowResult
import org.quicklauncher.host.runtime.widgets.WidgetReconciliationResult
import org.quicklauncher.host.runtime.widgets.WidgetRemovalResult
import org.quicklauncher.host.runtime.widgets.WidgetResizeResult
import org.quicklauncher.host.runtime.widgets.WidgetSize
import org.quicklauncher.host.runtime.widgets.WidgetSurfaceResult
import org.quicklauncher.host.runtime.composition.CompositionEnvironment
import org.quicklauncher.host.runtime.composition.CompositionRequest
import org.quicklauncher.host.runtime.composition.DefaultCompositionEngine
import org.quicklauncher.registry.production.productionRegistry

class ProductionPhaseFourIntegrationTest {
    @Test
    fun confirmedEditorRemovalDeletesFrameworkWidgetBeforeItsPlacedSubtree() = runBlocking {
        val store = installedModularStore()
        val placedInstance = store.read().placements.first().childInstanceId
        val pending = store.read()
        store.commit(
            LauncherTransaction(
                pending.revision,
                listOf(
                    LauncherEdit.BeginWidgetBinding(
                        WidgetPlacementRecord(
                            moduleInstanceId = placedInstance,
                            appWidgetId = 91,
                            providerPackage = PackageName.parse("org.quicklauncher.fixture.widget"),
                            providerClassName = "org.quicklauncher.fixture.widget.Provider",
                            profile = ProfileSerial.of(0),
                            intendedWidthDp = 180,
                            intendedHeightDp = 120,
                            bindState = WidgetBindState.PENDING,
                            restoreState = WidgetRestoreState.READY,
                        ),
                    ),
                ),
            ),
        )
        val bound = store.read()
        store.commit(
            LauncherTransaction(
                bound.revision,
                listOf(LauncherEdit.CompleteWidgetBinding(placedInstance)),
            ),
        )
        val scope = CoroutineScope(SupervisorJob())
        val editor = DefaultLauncherEditor(store, scope)
        editor.start()
        val widgets = StoreDeletingWidgetCoordinator(store)
        val dispatcher = ProductionEditorDispatcher(editor, store, widgets)

        assertTrue(
            dispatcher.dispatch(
                EditorAction.RemovePlacedModule(placedInstance, confirmed = true),
            ) is EditorResult.Rejected,
        )
        assertTrue(widgets.removals.isEmpty())
        assertTrue(
            dispatcher.dispatch(
                EditorAction.RemovePlacedModule(placedInstance, confirmed = false),
            ) is EditorResult.Rejected,
        )
        assertEquals(
            EditorResult.Applied,
            dispatcher.dispatch(EditorAction.RemovePlacedModule(placedInstance, confirmed = true)),
        )

        assertEquals(listOf(placedInstance), widgets.removals)
        assertTrue(store.read().widgetPlacements.none { it.moduleInstanceId == placedInstance })
        assertTrue(store.read().moduleInstances.none { it.id == placedInstance })
        editor.close()
        scope.cancel()
    }

    @Test
    fun repeatedHomeEmitsOneTransientDismissalAndCancelsEveryDurablePendingBinding() = runBlocking {
        val store = installedModularStore()
        val snapshot = store.read()
        val instanceId = snapshot.moduleInstances.first().id
        store.commit(
            LauncherTransaction(
                snapshot.revision,
                listOf(
                    LauncherEdit.BeginWidgetBinding(
                        WidgetPlacementRecord(
                            moduleInstanceId = instanceId,
                            appWidgetId = 73,
                            providerPackage = PackageName.parse("org.quicklauncher.fixture.widget"),
                            providerClassName = "org.quicklauncher.fixture.widget.Provider",
                            profile = ProfileSerial.of(0),
                            intendedWidthDp = 180,
                            intendedHeightDp = 120,
                            bindState = WidgetBindState.PENDING,
                            restoreState = WidgetRestoreState.READY,
                        ),
                    ),
                ),
            ),
        )
        val controller = WidgetTransientFlowController()
        val coordinator = HomeDismissRecordingWidgetCoordinator()
        val event = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            controller.events.first()
        }

        val result = controller.dismissForRepeatedHome(store, coordinator)

        assertEquals(WidgetTransientEvent, event.await())
        assertEquals(setOf(instanceId), result.requested)
        assertTrue(result.incomplete.isEmpty())
        assertEquals(listOf(instanceId), coordinator.cancelled)
    }

    @Test
    fun freshCompositionReceivesNoSyntheticWidgetDismissal() {
        val controller = WidgetTransientFlowController()

        assertTrue(controller.events.replayCache.isEmpty())
    }

    @Test
    fun boundWidgetUsesOneOpaqueHostSurfaceAndUnavailableProfileExposesNoSurface() = runBlocking {
        val store = installedModularStore()
        val base = store.read()
        val instanceId = base.moduleInstances.first().id
        val placement = WidgetPlacementRecord(
            moduleInstanceId = instanceId,
            appWidgetId = 42,
            providerPackage = PackageName.parse("org.quicklauncher.fixture.widget"),
            providerClassName = "org.quicklauncher.fixture.widget.Provider",
            profile = ProfileSerial.of(0),
            intendedWidthDp = 180,
            intendedHeightDp = 120,
            bindState = WidgetBindState.PENDING,
            restoreState = WidgetRestoreState.READY,
        )
        store.commit(LauncherTransaction(base.revision, listOf(LauncherEdit.BeginWidgetBinding(placement))))
        val pending = store.read()
        store.commit(
            LauncherTransaction(
                pending.revision,
                listOf(LauncherEdit.CompleteWidgetBinding(instanceId)),
            ),
        )
        val snapshot = store.read()
        val token = WidgetRenderToken.of("widget-surface-fixture")

        val ready = widgetContent(snapshot, instanceId, mapOf(instanceId to WidgetSurfaceResult.Ready(token)))
        assertEquals(RenderStatus.Ready, ready.status)
        assertEquals(listOf("widget-surface-fixture"), ready.content.surfaces.map { it.key.value })
        assertTrue(ready.content.items.isEmpty())

        val locked = widgetContent(snapshot, instanceId, mapOf(instanceId to WidgetSurfaceResult.ProfileUnavailable))
        assertEquals(RenderStatus.ProfileLocked(ProfileSerial.of(0)), locked.status)
        assertTrue(locked.content.surfaces.isEmpty())
    }

    @Test
    fun shortcutsRequireValidatedProfilesAndExposeWorkBadging() = runBlocking {
        val snapshot = installedModularSnapshot()
        val work = ProfileSerial.of(10)
        val unresolved = ProfileSerial.of(20)
        val workId = ContentItemId.parse("org.quicklauncher.content/work-compose")
        val unresolvedId = ContentItemId.parse("org.quicklauncher.content/unresolved-compose")
        fun shortcut(id: ContentItemId, profile: ProfileSerial) = ResolvedShortcutPlacement.Available(
            id = id,
            target = ShortcutTargetIdentity(
                profile,
                PackageName.parse("org.quicklauncher.mail"),
                ShortcutId.parse("compose"),
            ),
            label = "Compose",
            icon = null,
            dynamic = true,
            pinned = false,
        )
        val source = productionContentSource(
            snapshot = snapshot,
            apps = emptyList(),
            catalogStatus = AppCatalogStatus.READY,
            catalogErrorCode = null,
            shortcutState = ShortcutSnapshot(
                listOf(shortcut(workId, work), shortcut(unresolvedId, unresolved)),
                emptyMap(),
            ),
            profileState = ProfileCoordinatorState(
                listOf(ProfileState(work, ProfileKind.WORK, ProfileAvailability.AVAILABLE, ProfileTransition.IDLE)),
                PrivateSpaceState(null, ProfileAvailability.UNRESOLVED, ProfileTransition.IDLE, emptyList()),
            ),
        )
        val search = source.contentFor(
            snapshot.moduleInstances.single { it.contributionId.value == "org.quicklauncher.block/search" }.id,
        )

        assertEquals(listOf(workId), search.content.items.map { it.id })
        assertEquals(PreparedProfileKind.WORK, search.content.items.single().profile)
        assertEquals("Work shortcut", search.content.items.single().supportingText)
    }

    @Test
    fun productionContentAndActionsAreTypedAndInstanceScoped() = runBlocking {
        val store = installedModularStore()
        val folderId = ContentItemId.parse("org.quicklauncher.content/travel-folder")
        val beforeFolder = store.read()
        store.commit(
            LauncherTransaction(
                beforeFolder.revision,
                listOf(
                    LauncherEdit.CreateFolder(
                        ContentItemRecord(folderId, ContentItemKind.FOLDER, "Travel"),
                    ),
                ),
            ),
        )
        val snapshot = store.read()
        val favorite = app("Favorite", favorite = true)
        val ordinary = app("Ordinary", favorite = false)
        val fixedTime = ZonedDateTime.of(2026, 9, 10, 14, 5, 0, 0, ZoneOffset.UTC)
        val source = productionContentSource(
            snapshot,
            listOf(favorite, ordinary),
            AppCatalogStatus.READY,
            catalogErrorCode = null,
            now = fixedTime,
            indicators = mapOf(
                ProfilePackageIdentity(favorite.identity.profile, favorite.identity.packageName) to
                    NotificationIndicator.Dot,
            ),
        )
        val byContribution = snapshot.moduleInstances.associateBy { it.contributionId.value }

        val clock = source.contentFor(byContribution.getValue("org.quicklauncher.block/clock-date").id)
        assertEquals(RenderStatus.Ready, clock.status)
        assertEquals(listOf(PreparedContentKind.TEXT, PreparedContentKind.TEXT), clock.content.items.map { it.kind })
        assertEquals(listOf("14:05", "Thursday, 10 September"), clock.content.items.map { it.label })
        val favorites = source.contentFor(byContribution.getValue("org.quicklauncher.block/favorites").id)
        assertEquals(listOf("Favorite"), favorites.content.items.map { it.label })
        assertEquals(NotificationIndicator.Dot, favorites.content.items.single().indicator)
        assertEquals(PreparedProfileKind.PERSONAL, favorites.content.items.single().profile)
        val search = source.contentFor(byContribution.getValue("org.quicklauncher.block/search").id)
        assertEquals(listOf("Favorite", "Ordinary"), search.content.items.map { it.label })
        val destinationId = checkNotNull(snapshot.startDestinationId)
        val selectedRoot = snapshot.destinationLayouts.single {
            it.destinationId == destinationId && it.selected
        }.layoutInstanceId
        val currentBlock = snapshot.placements.first { it.layoutInstanceId == selectedRoot }.childInstanceId
        val knownItem = ContentItemId.parse("org.quicklauncher.test/known-item")
        val launches = mutableListOf<AppActivityIdentity>()
        val openedFolders = mutableListOf<ContentItemId>()
        val opened = mutableListOf<Unit>()
        val rendererFailures = mutableListOf<Pair<ModuleInstanceId, RuntimeException>>()
        val policy = ProductionActionPolicy(
            snapshot,
            destinationId,
            productionRegistry,
            mapOf(knownItem to favorite.identity),
            launches::add,
            { opened += Unit },
            { instanceId, failure -> rendererFailures += instanceId to failure },
            onOpenFolder = openedFolders::add,
        )

        assertTrue(policy.blockActions.emit(BlockAction.ActivateItem(ContentItemId.parse("org.quicklauncher.test/missing"))) is ActionDispatchResult.Rejected)
        assertEquals(ActionDispatchResult.Accepted, policy.blockActions.emit(BlockAction.ActivateItem(knownItem)))
        assertEquals(ActionDispatchResult.Accepted, policy.blockActions.emit(BlockAction.ActivateItem(folderId)))
        assertTrue(policy.blockActions.emit(BlockAction.OpenSettings(ModuleInstanceId.parse("org.quicklauncher.test/foreign"))) is ActionDispatchResult.Rejected)
        assertEquals(ActionDispatchResult.Accepted, policy.blockActions.emit(BlockAction.OpenSettings(currentBlock)))
        assertTrue(policy.layoutActions.emit(LayoutAction.SelectSlot(StableKey.parse("missing"))) is ActionDispatchResult.Rejected)
        policy.rendererFailures.report(
            org.quicklauncher.host.runtime.composition.CompositionIssue(
                org.quicklauncher.host.runtime.composition.CompositionIssueKind.RENDER_FAILED,
                currentBlock,
                "failed",
            ),
            IllegalArgumentException("original renderer failure"),
        )
        assertEquals(listOf(favorite.identity), launches)
        assertEquals(listOf(folderId), openedFolders)
        assertEquals(1, opened.size)
        assertEquals(listOf(currentBlock), rendererFailures.map { it.first })
        assertTrue(rendererFailures.single().second is IllegalArgumentException)
    }

    @Test
    fun hiddenSiblingActivityNeverReceivesItsVisiblePackagesIndicator() = runBlocking {
        val snapshot = installedModularSnapshot()
        val visible = app("Shared", favorite = false)
        val hidden = LauncherApp(
            identity = AppActivityIdentity(
                visible.identity.profile,
                visible.identity.packageName,
                ActivityName.parse("org.quicklauncher.shared.HiddenActivity"),
            ),
            label = "Hidden shared",
            workProfile = false,
            collectionVisible = false,
        )
        val source = productionContentSource(
            snapshot = snapshot,
            apps = listOf(hidden, visible),
            catalogStatus = AppCatalogStatus.READY,
            catalogErrorCode = null,
            indicators = mapOf(
                ProfilePackageIdentity(visible.identity.profile, visible.identity.packageName) to
                    NotificationIndicator.Dot,
            ),
        )
        val searchInstance = snapshot.moduleInstances.single {
            it.contributionId.value == "org.quicklauncher.block/search"
        }

        val items = source.contentFor(searchInstance.id).content.items.associateBy { it.label }

        assertEquals(NotificationIndicator.None, items.getValue("Hidden shared").indicator)
        assertEquals(NotificationIndicator.Dot, items.getValue("Shared").indicator)
    }

    @Test
    fun modularTemplateInstallsAtomicallyAndComposesCurrentDestinationWithNeighbors() = runBlocking {
        val store = InMemoryLauncherStore(
            configurationResolver = registryConfigurationResolver(productionRegistry),
            placementPolicy = RegistryPlacementPolicy(productionRegistry),
        )
        var nextIdentity = 0
        val onboarding = TemplateOnboarding(
            productionRegistry,
            store,
            OnboardingSafeLayoutFactory { destinationId ->
                SafeLayoutIds.recordsFor(destinationId, selected = false).let { records ->
                    SafeLayoutDraft(records.layout, records.instance, records.configuration)
                }
            },
            EditorIdentitySource { "phase-four-${nextIdentity++}" },
        )
        val modularId = ContributionId.parse("org.quicklauncher.template/modular")
        val template = onboarding.availableTemplates().single { it.id == modularId }

        val previewed = onboarding.previewConfigured(modularId, template.defaultConfiguration)
            as OnboardingResult.Previewed
        assertTrue(previewed.preview.canInstall)
        assertEquals(
            listOf("Home", "Entry", "All apps"),
            previewed.preview.plan.destinations.map { it.draft.name.value },
        )
        assertEquals(OnboardingResult.Installed, onboarding.install(previewed.preview))

        val snapshot = store.read()
        assertEquals(3, snapshot.destinations.size)
        snapshot.destinations.forEach { destination ->
            val retained = snapshot.destinationLayouts.filter { it.destinationId == destination.id }
            assertEquals(2, retained.size)
            assertEquals(1, retained.count { it.selected })
            assertTrue(retained.any { it.layoutContributionId == SafeLayoutIds.CONTRIBUTION })
        }

        val engineScope = CoroutineScope(SupervisorJob())
        val engine = DefaultCompositionEngine(productionRegistry, engineScope)
        try {
            val startDestinationId = checkNotNull(snapshot.startDestinationId)
            val prepared = engine.prepare(
                CompositionRequest(snapshot, startDestinationId, environment()),
            )
            assertEquals(3, prepared.destinations.size)
            assertEquals(1, prepared.destinations.count { it.composition.isInteractive })
            assertEquals(startDestinationId, prepared.current.destinationId)
            assertTrue(prepared.destinations.all { it.issues.isEmpty() })
            val noninteractivePreview = engine.prepare(
                CompositionRequest(
                    snapshot,
                    startDestinationId,
                    environment(),
                    currentInteractive = false,
                ),
            )
            assertTrue(noninteractivePreview.destinations.none { it.composition.isInteractive })
        } finally {
            engine.close()
            engineScope.cancel()
        }
    }

    private fun environment() = CompositionEnvironment(
        theme = LauncherTheme(
            mode = ThemeMode.LIGHT,
            foreground = ArgbColor.of(0xff111111),
            background = ArgbColor.of(0xffffffff),
            accent = ArgbColor.of(0xff3355cc),
            textScale = 1f,
            reducedMotion = true,
        ),
        window = WindowInfo(360, 800, WindowOrientation.PORTRAIT),
        backgroundContrast = BackgroundContrast(
            textContrastRatio = 7f,
            nonTextContrastRatio = 3f,
            prefersLightForeground = false,
        ),
        editorMode = EditorMode.BROWSING,
    )

    private suspend fun installedModularSnapshot(): org.quicklauncher.host.data.store.LauncherSnapshot {
        return installedModularStore().read()
    }

    private suspend fun installedModularStore(): InMemoryLauncherStore {
        val store = InMemoryLauncherStore(
            configurationResolver = registryConfigurationResolver(productionRegistry),
            placementPolicy = RegistryPlacementPolicy(productionRegistry),
        )
        var nextIdentity = 0
        val onboarding = TemplateOnboarding(
            productionRegistry,
            store,
            OnboardingSafeLayoutFactory { destinationId ->
                SafeLayoutIds.recordsFor(destinationId, selected = false).let { records ->
                    SafeLayoutDraft(records.layout, records.instance, records.configuration)
                }
            },
            EditorIdentitySource { "content-actions-${nextIdentity++}" },
        )
        val template = onboarding.availableTemplates().single {
            it.id == ContributionId.parse("org.quicklauncher.template/modular")
        }
        val preview = onboarding.previewConfigured(template.id, template.defaultConfiguration)
            as OnboardingResult.Previewed
        assertEquals(OnboardingResult.Installed, onboarding.install(preview.preview))
        return store
    }

    private fun app(label: String, favorite: Boolean): LauncherApp {
        val local = label.lowercase()
        return LauncherApp(
            AppActivityIdentity(
                ProfileSerial.of(0),
                PackageName.parse("org.quicklauncher.$local"),
                ActivityName.parse("org.quicklauncher.$local.MainActivity"),
            ),
            label,
            workProfile = false,
            favorite = favorite,
        )
    }

    private class HomeDismissRecordingWidgetCoordinator : WidgetCoordinator {
        val cancelled = mutableListOf<ModuleInstanceId>()

        override suspend fun cancelBinding(moduleInstanceId: ModuleInstanceId): WidgetFlowResult {
            cancelled += moduleInstanceId
            return WidgetFlowResult.Cancelled
        }

        override suspend fun beginBinding(request: WidgetBindingRequest) = unused()
        override suspend fun beginRestoredBinding(moduleInstanceId: ModuleInstanceId) = unused()
        override suspend fun completePermission(moduleInstanceId: ModuleInstanceId, accepted: Boolean) = unused()
        override suspend fun completeConfiguration(moduleInstanceId: ModuleInstanceId, accepted: Boolean) = unused()
        override suspend fun show(moduleInstanceId: ModuleInstanceId): WidgetSurfaceResult = unused()
        override suspend fun hide(moduleInstanceId: ModuleInstanceId) = Unit
        override suspend fun resize(moduleInstanceId: ModuleInstanceId, size: WidgetSize): WidgetResizeResult = unused()
        override suspend fun remove(moduleInstanceId: ModuleInstanceId): WidgetRemovalResult = unused()
        override suspend fun reconcileAfterRecreation(): WidgetReconciliationResult = unused()
        override fun close() = Unit

        private fun unused(): Nothing = error("Not used by repeated-Home tests")
    }

    private class StoreDeletingWidgetCoordinator(
        private val store: InMemoryLauncherStore,
    ) : WidgetCoordinator {
        val removals = mutableListOf<ModuleInstanceId>()

        override suspend fun remove(moduleInstanceId: ModuleInstanceId): WidgetRemovalResult {
            removals += moduleInstanceId
            val before = store.read()
            store.commit(
                LauncherTransaction(
                    before.revision,
                    listOf(LauncherEdit.BeginWidgetDeletion(moduleInstanceId)),
                ),
            )
            val pending = store.read()
            store.commit(
                LauncherTransaction(
                    pending.revision,
                    listOf(LauncherEdit.CompleteWidgetDeletion(moduleInstanceId)),
                ),
            )
            return WidgetRemovalResult.Removed
        }

        override suspend fun beginBinding(request: WidgetBindingRequest) = unused()
        override suspend fun beginRestoredBinding(moduleInstanceId: ModuleInstanceId) = unused()
        override suspend fun completePermission(moduleInstanceId: ModuleInstanceId, accepted: Boolean) = unused()
        override suspend fun completeConfiguration(moduleInstanceId: ModuleInstanceId, accepted: Boolean) = unused()
        override suspend fun cancelBinding(moduleInstanceId: ModuleInstanceId) = unused()
        override suspend fun show(moduleInstanceId: ModuleInstanceId): WidgetSurfaceResult = unused()
        override suspend fun hide(moduleInstanceId: ModuleInstanceId) = Unit
        override suspend fun resize(moduleInstanceId: ModuleInstanceId, size: WidgetSize): WidgetResizeResult = unused()
        override suspend fun reconcileAfterRecreation(): WidgetReconciliationResult = unused()
        override fun close() = Unit

        private fun unused(): Nothing = error("Not used by editor widget-cleanup tests")
    }
}
