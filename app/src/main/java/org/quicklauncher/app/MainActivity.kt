package org.quicklauncher.app

import android.content.Intent
import android.os.Bundle
import java.lang.ref.WeakReference
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.host.data.preferences.LauncherPreferencesStore
import org.quicklauncher.host.data.preferences.OnboardingState
import org.quicklauncher.host.data.preferences.PrivateSpaceVisibility
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.RegistryPlacementPolicy
import org.quicklauncher.host.data.store.registryConfigurationResolver
import org.quicklauncher.host.data.store.DestinationInstall
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.WidgetBindState
import org.quicklauncher.host.data.store.WidgetCleanupState
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.editor.DefaultLauncherEditor
import org.quicklauncher.host.editor.DestinationEditorSurface
import org.quicklauncher.host.editor.EditorAction
import org.quicklauncher.host.editor.EditorDestinationFactory
import org.quicklauncher.host.editor.EditorScreen
import org.quicklauncher.host.editor.LauncherEditor
import org.quicklauncher.host.editor.LauncherOnboardingSurface
import org.quicklauncher.host.editor.MapEditorSurface
import org.quicklauncher.host.editor.OnboardingResult
import org.quicklauncher.host.editor.OnboardingSafeLayoutFactory
import org.quicklauncher.host.editor.SafeLayoutDraft
import org.quicklauncher.host.editor.TemplateOnboarding
import org.quicklauncher.host.editor.registryEditorConfigurationDefaults
import org.quicklauncher.host.platform.apps.AndroidAppPlatform
import org.quicklauncher.host.platform.actions.AndroidItemActionPlatform
import org.quicklauncher.host.platform.notifications.AndroidNotificationPlatform
import org.quicklauncher.host.platform.profile.AndroidProfilePlatform
import org.quicklauncher.host.platform.profile.AndroidSecureOverlayProtection
import org.quicklauncher.host.platform.shortcuts.AndroidShortcutPlatform
import org.quicklauncher.host.platform.widgets.AndroidWidgetPlatform
import org.quicklauncher.host.platform.widgets.AndroidWidgetPreparedContentRenderer
import org.quicklauncher.host.platform.widgets.rememberAndroidWidgetUserActionLauncher
import org.quicklauncher.host.platform.diagnostics.AndroidDiagnosticLogFactory
import org.quicklauncher.host.platform.persistence.AndroidLauncherStoreFactory
import org.quicklauncher.host.platform.role.AndroidHomeRoleGateway
import org.quicklauncher.host.runtime.DefaultLauncherRuntime
import org.quicklauncher.host.runtime.LauncherEntry
import org.quicklauncher.host.runtime.LauncherRuntime
import org.quicklauncher.host.runtime.LauncherSurface
import org.quicklauncher.host.runtime.SafeLayoutSurface
import org.quicklauncher.host.runtime.SafeLayoutIds
import org.quicklauncher.host.runtime.catalog.DefaultAppCatalog
import org.quicklauncher.host.runtime.catalog.StoreAppCatalogOverrideSource
import org.quicklauncher.host.runtime.catalog.UnavailableAppPlatform
import org.quicklauncher.host.runtime.diagnostics.BoundedDiagnosticLog
import org.quicklauncher.host.runtime.diagnostics.DiagnosticRecord
import org.quicklauncher.host.runtime.diagnostics.DiagnosticStorage
import org.quicklauncher.host.runtime.notifications.DefaultNotificationIndicatorCoordinator
import org.quicklauncher.host.runtime.phasefive.PhaseFiveHost
import org.quicklauncher.host.runtime.phasefive.PhaseFiveOverlay
import org.quicklauncher.host.runtime.phasefive.productionPhaseFiveHost
import org.quicklauncher.host.runtime.phasefive.unavailableNotificationPlatform
import org.quicklauncher.host.runtime.phasefive.unavailableProfilePlatform
import org.quicklauncher.host.runtime.phasefive.unavailableShortcutPlatform
import org.quicklauncher.host.runtime.profile.DefaultProfileCoordinator
import org.quicklauncher.host.runtime.profile.PrivateSpaceEntryPoint
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.profile.SecureOverlayProtection
import org.quicklauncher.host.runtime.shortcuts.DefaultShortcutCoordinator
import org.quicklauncher.host.runtime.shortcuts.StoreShortcutPlacementSource
import org.quicklauncher.host.runtime.widgets.DefaultWidgetCoordinator
import org.quicklauncher.host.runtime.widgets.WidgetCoordinator
import org.quicklauncher.host.runtime.widgets.WidgetFlowResult
import org.quicklauncher.host.runtime.widgets.WidgetActionLaunchResult
import org.quicklauncher.host.runtime.widgets.WidgetPendingUserAction
import org.quicklauncher.host.runtime.widgets.WidgetReconciliationResult
import org.quicklauncher.host.runtime.widgets.WidgetUserAction
import org.quicklauncher.host.runtime.permissions.HomeRoleRequestOutcome
import org.quicklauncher.host.runtime.permissions.HomeRoleRequestReason
import org.quicklauncher.host.runtime.permissions.HomeRoleState
import org.quicklauncher.host.runtime.composition.CompositionRequest
import org.quicklauncher.host.runtime.composition.CompositionRestoreRequestSource
import org.quicklauncher.host.runtime.composition.DefaultCompositionEngine
import org.quicklauncher.host.settings.LauncherSettingsSurface
import org.quicklauncher.host.settings.MapOverviewSurface
import org.quicklauncher.host.settings.RecoverySurface
import org.quicklauncher.host.settings.PrivateSpaceOverlay
import org.quicklauncher.host.settings.IndicatorStyleOption
import org.quicklauncher.host.settings.PrivateSpaceVisibilityOption
import org.quicklauncher.host.settings.ShortcutSelectionItem
import org.quicklauncher.host.settings.FolderOverlaySurface
import org.quicklauncher.host.settings.ItemActionOverlaySurface
import org.quicklauncher.host.runtime.folders.FolderOverlayState
import org.quicklauncher.host.runtime.actions.ItemActionOverlayState
import org.quicklauncher.registry.production.productionRegistry

class MainActivity : ComponentActivity() {
    private val activityJob = SupervisorJob()
    private val activityScope = CoroutineScope(activityJob + Dispatchers.Main.immediate)
    private val activityInstanceId = HomeEntryRecorder.newActivityInstance()
    private lateinit var launcherStore: LauncherStore
    private var preferences: LauncherPreferencesStore? = null
    private lateinit var runtime: LauncherRuntime
    private lateinit var compositionEngine: DefaultCompositionEngine
    private lateinit var editor: LauncherEditor
    private lateinit var onboarding: TemplateOnboarding
    private lateinit var phaseFiveHost: PhaseFiveHost
    private lateinit var secureOverlayProtection: SecureOverlayProtection
    private var widgetPlatform: AndroidWidgetPlatform? = null
    private var widgetCoordinator: WidgetCoordinator? = null
    private val widgetTransientFlows = WidgetTransientFlowController()
    private var startup: Job? = null
    private var resourcesClosed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isHome = intent.isHomeEntry()
        LauncherActivityOwner.claim(this)
            ?.takeIf { previous -> isHome && previous.taskId != taskId }
            ?.retireForReplacement()

        val roleGateway = AndroidHomeRoleGateway(this)
        val configurationResolver = registryConfigurationResolver(productionRegistry)
        val placementPolicy = RegistryPlacementPolicy(productionRegistry)
        var durableStoreAvailable = true
        launcherStore = try {
            AndroidLauncherStoreFactory(this).open(DATABASE_NAME, configurationResolver, placementPolicy)
        } catch (_: Exception) {
            durableStoreAvailable = false
            InMemoryLauncherStore(
                configurationResolver = configurationResolver,
                placementPolicy = placementPolicy,
            )
        }
        preferences = (application as QuicklauncherApplication).launcherPreferences
        val profilePlatform = try {
            AndroidProfilePlatform(this)
        } catch (_: Exception) {
            unavailableProfilePlatform()
        }
        val notificationPlatform = try {
            AndroidNotificationPlatform(this)
        } catch (_: Exception) {
            unavailableNotificationPlatform()
        }
        val profileCoordinator = DefaultProfileCoordinator(profilePlatform, activityScope)
        val notificationCoordinator = DefaultNotificationIndicatorCoordinator(
            platform = notificationPlatform,
            notificationStyle = (preferences?.state ?: flowOf(
                org.quicklauncher.host.data.preferences.LauncherPreferences.Default,
            )).map { it.notificationStyle }.distinctUntilChanged(),
            parentScope = activityScope,
        )
        secureOverlayProtection = AndroidSecureOverlayProtection(this)
        val platform = try {
            AndroidAppPlatform(this)
        } catch (_: Exception) {
            UnavailableAppPlatform()
        }
        val catalog = DefaultAppCatalog(
            platform = platform,
            overrideSource = if (durableStoreAvailable) {
                StoreAppCatalogOverrideSource(launcherStore)
            } else {
                org.quicklauncher.host.runtime.catalog.AppCatalogOverrideSource {
                    error("Durable app visibility policy is unavailable")
                }
            },
            parentScope = activityScope,
        )
        editor = DefaultLauncherEditor(
            store = launcherStore,
            parentScope = activityScope,
            configurationDefaults = registryEditorConfigurationDefaults(productionRegistry),
            destinationFactory = EditorDestinationFactory { destinationId, name, x, y ->
                val safe = SafeLayoutIds.recordsFor(destinationId, selected = true)
                DestinationInstall(
                    DestinationRecord(destinationId, name, DestinationCoordinate(x, y)),
                    safe.layout,
                    safe.instance,
                    safe.configuration,
                )
            },
            contributionRegistry = productionRegistry,
        )
        onboarding = TemplateOnboarding(
            productionRegistry,
            launcherStore,
            OnboardingSafeLayoutFactory { destinationId ->
                val safe = SafeLayoutIds.recordsFor(destinationId, selected = false)
                SafeLayoutDraft(safe.layout, safe.instance, safe.configuration)
            },
        )
        compositionEngine = DefaultCompositionEngine(
            productionRegistry,
            activityScope,
            CompositionRestoreRequestSource { selectedLayoutInstanceId ->
                val snapshot = launcherStore.read()
                val destinationId = snapshot.destinationLayouts.single {
                    it.layoutInstanceId == selectedLayoutInstanceId
                }.destinationId
                CompositionRequest(snapshot, destinationId, restoreEnvironment(snapshot, destinationId))
            },
        )
        runtime = DefaultLauncherRuntime(
            store = launcherStore,
            catalog = catalog,
            roleGateway = roleGateway,
            diagnostics = try {
                AndroidDiagnosticLogFactory(this).open()
            } catch (_: Exception) {
                inMemoryDiagnosticLog()
            },
            appVersion = BuildConfig.VERSION_NAME,
            parentScope = activityScope,
            selectedLayoutRestorer = compositionEngine,
        )
        val shortcutPlatform = try {
            AndroidShortcutPlatform(this)
        } catch (_: Exception) {
            unavailableShortcutPlatform()
        }
        val shortcutCoordinator = DefaultShortcutCoordinator(
            shortcutPlatform,
            StoreShortcutPlacementSource(launcherStore),
            activityScope,
        )
        widgetPlatform = try {
            AndroidWidgetPlatform(this)
        } catch (_: Exception) {
            null
        }
        widgetCoordinator = widgetPlatform?.let { DefaultWidgetCoordinator(launcherStore, it) }
        phaseFiveHost = productionPhaseFiveHost(
            launcherStore,
            runtime,
            profileCoordinator,
            notificationCoordinator,
            AndroidItemActionPlatform(this),
            shortcutCoordinator,
            activityScope,
        )

        recordEntry(isHome, fromNewIntent = false)
        consumeEntryIntent()
        val restoredDestination = savedInstanceState
            ?.getString(STATE_DESTINATION)
            ?.let { value -> runCatching { DestinationId.parse(value) }.getOrNull() }
        val restoredFolder = savedInstanceState
            ?.getString(STATE_FOLDER_OVERLAY)
            ?.let { value -> runCatching { ContentItemId.parse(value) }.getOrNull() }
        startup = activityScope.launch {
            editor.start()
            runtime.start(
                HomeEntryPolicy.initial(
                    isHomeIntent = isHome,
                    hasSavedState = savedInstanceState != null,
                ),
                restoredDestination,
            )
            if (restoredFolder != null) {
                phaseFiveHost.restoreFolderOverlay(restoredFolder)
            }
            HomeEntryRecorder.recordRuntimeState(runtime.state.value)
        }

        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(Modifier.fillMaxSize()) {
                    QuicklauncherRoot(
                        runtime,
                        launcherStore,
                        compositionEngine,
                        editor,
                        onboarding,
                        preferences,
                        phaseFiveHost,
                        secureOverlayProtection,
                        widgetPlatform,
                        widgetCoordinator,
                        widgetTransientFlows.events,
                        activityScope,
                        ::finishAndRemoveTask,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val isHome = intent.isHomeEntry()
        recordEntry(isHome, fromNewIntent = true)
        consumeEntryIntent()
        if (isHome) {
            activityScope.launch {
                startup?.join()
                widgetTransientFlows.dismissForRepeatedHome(launcherStore, widgetCoordinator)
                phaseFiveHost.onHome()
                editor.dispatch(EditorAction.Close)
                runtime.onHomeIntent()
                HomeEntryRecorder.recordRuntimeState(runtime.state.value)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::runtime.isInitialized) {
            activityScope.launch {
                startup?.join()
                runtime.onResume()
                phaseFiveHost.refreshAfterExternalSettings(runtime.state.value.visibleApps)
                HomeEntryRecorder.recordRuntimeState(runtime.state.value)
                if (runtime.state.value.homeRoleState == HomeRoleState.HELD) {
                    preferences.setOnboardingBestEffort(OnboardingState.COMPLETED)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        runtime.state.value.currentDestinationId?.let { destination ->
            outState.putString(STATE_DESTINATION, destination.value)
        }
        if (::phaseFiveHost.isInitialized && phaseFiveHost.overlay.value == PhaseFiveOverlay.FOLDER) {
            (phaseFiveHost.folderState.value as? FolderOverlayState.Open)?.id?.let { folderId ->
                outState.putString(STATE_FOLDER_OVERLAY, folderId.value)
            }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        LauncherActivityOwner.release(this)
        super.onDestroy()
        closeOwnedResources()
    }

    private fun retireForReplacement() {
        setContent(content = {})
        closeOwnedResources()
        finishAndRemoveTask()
    }

    private fun closeOwnedResources() {
        if (resourcesClosed) return
        resourcesClosed = true
        if (::editor.isInitialized) closeBestEffort(editor)
        if (::phaseFiveHost.isInitialized) closeBestEffort(phaseFiveHost)
        closeBestEffort(widgetCoordinator)
        if (::compositionEngine.isInitialized) closeBestEffort(compositionEngine)
        if (::runtime.isInitialized) closeBestEffort(runtime)
        if (::launcherStore.isInitialized) closeBestEffort(launcherStore as? AutoCloseable)
        activityScope.cancel()
    }

    private fun restoreEnvironment(
        snapshot: org.quicklauncher.host.data.store.LauncherSnapshot,
        destinationId: DestinationId,
    ) = productionEnvironment(
        apps = if (::runtime.isInitialized) runtime.state.value.visibleApps else emptyList(),
        catalogStatus = if (::runtime.isInitialized) {
            runtime.state.value.catalogStatus
        } else {
            org.quicklauncher.host.runtime.catalog.AppCatalogStatus.LOADING
        },
        catalogErrorCode = if (::runtime.isInitialized) runtime.state.value.errorCode else null,
        notificationState = if (::phaseFiveHost.isInitialized) {
            phaseFiveHost.notificationState.value
        } else {
            org.quicklauncher.host.runtime.notifications.NotificationIndicatorState.Initial
        },
        shortcutState = if (::phaseFiveHost.isInitialized) {
            phaseFiveHost.shortcutState.value
        } else {
            org.quicklauncher.host.runtime.shortcuts.ShortcutSnapshot.Empty
        },
        profileState = if (::phaseFiveHost.isInitialized) {
            phaseFiveHost.profileState.value
        } else {
            org.quicklauncher.host.runtime.profile.ProfileCoordinatorState.Empty
        },
        snapshot = snapshot,
        dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES,
        reducedMotion = !android.animation.ValueAnimator.areAnimatorsEnabled(),
        textScale = resources.configuration.fontScale,
        widthDp = resources.configuration.screenWidthDp,
        heightDp = resources.configuration.screenHeightDp,
        destinationId = destinationId,
        runtime = runtime,
        editor = editor,
        onOpenFolder = { id ->
            if (::phaseFiveHost.isInitialized) activityScope.launch { phaseFiveHost.openFolder(id) }
        },
        onOpenItemActions = { id ->
            if (::phaseFiveHost.isInitialized) activityScope.launch { phaseFiveHost.openItemActions(id) }
        },
        onLaunchShortcut = { id ->
            if (::phaseFiveHost.isInitialized) activityScope.launch { phaseFiveHost.launchShortcut(id) }
        },
        scope = activityScope,
    )

    private fun recordEntry(isHome: Boolean, fromNewIntent: Boolean) {
        if (isHome) {
            HomeEntryRecorder.recordHome(activityInstanceId, fromNewIntent)
        } else if (intent?.action == Intent.ACTION_MAIN) {
            HomeEntryRecorder.recordAppIcon(activityInstanceId)
        }
    }

    private fun Intent?.isHomeEntry(): Boolean =
        this?.action == Intent.ACTION_MAIN && hasCategory(Intent.CATEGORY_HOME)

    private fun consumeEntryIntent() {
        setIntent(Intent(intent).apply {
            action = ACTION_CONSUMED_ENTRY
            categories?.toList()?.forEach(::removeCategory)
        })
    }

    internal companion object {
        const val DATABASE_NAME = "quicklauncher.db"
        const val PREFERENCES_NAME = "launcher-preferences.pb"
        const val STATE_DESTINATION = "launcher.current-destination"
        const val STATE_FOLDER_OVERLAY = "launcher.folder-overlay"
        const val ACTION_CONSUMED_ENTRY = "org.quicklauncher.action.CONSUMED_ENTRY"
    }
}

private object LauncherActivityOwner {
    private var current = WeakReference<MainActivity>(null)

    @Synchronized
    fun claim(activity: MainActivity): MainActivity? {
        val previous = current.get()?.takeUnless { it === activity }
        current = WeakReference(activity)
        return previous
    }

    @Synchronized
    fun release(activity: MainActivity) {
        if (current.get() === activity) current.clear()
    }
}

@Composable
private fun QuicklauncherRoot(
    runtime: LauncherRuntime,
    store: LauncherStore,
    compositionEngine: DefaultCompositionEngine,
    editor: LauncherEditor,
    onboarding: TemplateOnboarding,
    preferences: LauncherPreferencesStore?,
    phaseFiveHost: PhaseFiveHost,
    secureOverlayProtection: SecureOverlayProtection,
    widgetPlatform: AndroidWidgetPlatform?,
    widgetCoordinator: WidgetCoordinator?,
    widgetTransientEvents: SharedFlow<WidgetTransientEvent>,
    activityScope: CoroutineScope,
    onHomeRoleGranted: () -> Unit,
) {
    val editorDispatcher = remember(editor, store, widgetCoordinator) {
        ProductionEditorDispatcher(editor, store, widgetCoordinator)
    }
    val state by runtime.state.collectAsState()
    val editorState by editor.state.collectAsState()
    val preferenceState by (
        preferences?.state ?: kotlinx.coroutines.flow.flowOf(
            org.quicklauncher.host.data.preferences.LauncherPreferences.Default,
        )
    ).collectAsState(initial = org.quicklauncher.host.data.preferences.LauncherPreferences.Default)
    val phaseFiveOverlay by phaseFiveHost.overlay.collectAsState()
    val profileState by phaseFiveHost.profileState.collectAsState()
    val notificationState by phaseFiveHost.notificationState.collectAsState()
    val folderState by phaseFiveHost.folderState.collectAsState()
    val itemActionState by phaseFiveHost.itemActionState.collectAsState()
    val shortcutState by phaseFiveHost.shortcutState.collectAsState()
    LaunchedEffect(preferenceState.privateSpaceVisibility) {
        phaseFiveHost.setPrivateSpaceVisible(
            preferenceState.privateSpaceVisibility == PrivateSpaceVisibility.VISIBLE,
        )
    }
    WidgetRecreationActions(
        widgetCoordinator,
        store,
        activityScope,
        changeKey = state.storeRevision to editorState.revision,
        transientEvents = widgetTransientEvents,
    )
    LaunchedEffect(state.visibleApps, state.storeRevision) {
        phaseFiveHost.refresh(state.visibleApps)
    }
    LaunchedEffect(state) {
        HomeEntryRecorder.recordRuntimeState(state)
    }
    var fallbackRequired by remember { mutableStateOf(false) }
    var onboardingPreview by remember { mutableStateOf<org.quicklauncher.host.editor.OnboardingPreview?>(null) }
    var onboardingInstalled by remember {
        mutableStateOf(preferenceState.onboardingState == OnboardingState.IN_PROGRESS)
    }
    var onboardingMessage by remember { mutableStateOf<String?>(null) }
    PredictiveBackHandler(
        enabled = editorState.screen != EditorScreen.CLOSED ||
            phaseFiveOverlay != PhaseFiveOverlay.NONE ||
            (state.surface != LauncherSurface.SAFE_LAYOUT && state.surface != LauncherSurface.SELECTED_LAYOUT),
    ) { progress ->
        progress.collect()
        if (phaseFiveOverlay != PhaseFiveOverlay.NONE) {
            phaseFiveHost.closeOverlay()
        } else if (editorState.screen != EditorScreen.CLOSED) {
            editor.dispatch(EditorAction.Close)
        } else {
            runtime.closeOverlay()
        }
    }

    if (phaseFiveOverlay == PhaseFiveOverlay.PRIVATE_SPACE) {
        PrivateSpaceOverlay(
            state = profileState.privateSpace,
            protection = secureOverlayProtection,
            onLock = { activityScope.launch { phaseFiveHost.setPrivateSpaceLocked(locked = true) } },
            onUnlock = { activityScope.launch { phaseFiveHost.setPrivateSpaceLocked(locked = false) } },
            onLaunch = { identity -> activityScope.launch { phaseFiveHost.launchPrivate(identity) } },
            onOpenSettings = { activityScope.launch { phaseFiveHost.openPrivateSpaceSettings() } },
            onClose = phaseFiveHost::closeOverlay,
        )
        return
    }
    if (phaseFiveOverlay == PhaseFiveOverlay.FOLDER) {
        val folder = folderState as? FolderOverlayState.Open
        if (folder != null) {
            FolderOverlaySurface(
                state = folder,
                onActivateMember = { id ->
                    activityScope.launch { phaseFiveHost.activateFolderMember(id) }
                },
                onMoveMember = { memberId, index ->
                    activityScope.launch { phaseFiveHost.moveFolderMember(folder.id, memberId, index) }
                },
                onRemoveMember = { memberId ->
                    activityScope.launch { phaseFiveHost.removeFolderMember(folder.id, memberId) }
                },
                onRename = { name ->
                    activityScope.launch { phaseFiveHost.renameFolder(folder.id, name) }
                },
                onDelete = {
                    activityScope.launch {
                        phaseFiveHost.deleteFolder(folder.id)
                        phaseFiveHost.closeOverlay()
                    }
                },
                onClose = phaseFiveHost::closeOverlay,
            )
            return
        }
    }
    if (phaseFiveOverlay == PhaseFiveOverlay.ITEM_ACTIONS) {
        val actions = itemActionState as? ItemActionOverlayState.Open
        if (actions != null) {
            ItemActionOverlaySurface(
                state = actions,
                onCommand = { command ->
                    activityScope.launch { phaseFiveHost.executeItemAction(command) }
                },
                onClose = phaseFiveHost::closeOverlay,
            )
            return
        }
    }

    fun requestRole(reason: HomeRoleRequestReason) {
        activityScope.launch {
            if (reason == HomeRoleRequestReason.ONBOARDING_PREVIEW_CONFIRMED) {
                preferences.setOnboardingBestEffort(OnboardingState.IN_PROGRESS)
            }
            when (runtime.requestHomeRole(reason)) {
                HomeRoleRequestOutcome.Granted -> {
                    fallbackRequired = false
                    preferences.setOnboardingBestEffort(OnboardingState.COMPLETED)
                    onHomeRoleGranted()
                }
                HomeRoleRequestOutcome.AlreadyHeld -> {
                    fallbackRequired = false
                    preferences.setOnboardingBestEffort(OnboardingState.COMPLETED)
                }
                HomeRoleRequestOutcome.FallbackRequired -> {
                    fallbackRequired = true
                    runtime.open(LauncherSurface.SETTINGS)
                }
                HomeRoleRequestOutcome.Unavailable -> fallbackRequired = false
            }
        }
    }

    if (preferenceState.onboardingState != OnboardingState.COMPLETED &&
        state.homeRoleState != HomeRoleState.HELD &&
        !fallbackRequired
    ) {
        LauncherOnboardingSurface(
            templates = onboarding.availableTemplates(),
            preview = onboardingPreview,
            installed = onboardingInstalled || state.surface == LauncherSurface.SELECTED_LAYOUT,
            message = onboardingMessage,
            onPreview = { template, configuration ->
                when (val result = onboarding.previewConfigured(template.id, configuration)) {
                    is OnboardingResult.Previewed -> {
                        onboardingPreview = result.preview
                        onboardingMessage = null
                    }
                    is OnboardingResult.Invalid -> onboardingMessage = result.message
                    else -> Unit
                }
            },
            onInstall = { preview ->
                activityScope.launch {
                    when (val result = onboarding.install(preview)) {
                        OnboardingResult.Installed -> {
                            onboardingInstalled = true
                            preferences.setOnboardingBestEffort(OnboardingState.IN_PROGRESS)
                            runtime.start(LauncherEntry.HOME)
                        }
                        is OnboardingResult.Rejected -> onboardingMessage = result.message
                        else -> Unit
                    }
                }
            },
            previewContent = { selected ->
                ProductionOnboardingPreview(
                    selected,
                    state.visibleApps,
                    state.catalogStatus,
                    state.errorCode,
                    activityScope,
                )
            },
            onRequestHomeRole = {
                requestRole(HomeRoleRequestReason.ONBOARDING_PREVIEW_CONFIRMED)
            },
        )
        return
    }

    if (editorState.screen == EditorScreen.MAP) {
        MapEditorSurface(
            editorState,
            onAction = { action -> activityScope.launch { editorDispatcher.dispatch(action) } },
        )
        return
    }
    if (editorState.screen == EditorScreen.DESTINATION) {
        DestinationEditorSurface(
            editorState,
            onAction = { action -> activityScope.launch { editorDispatcher.dispatch(action) } },
        )
        return
    }

    when (state.surface) {
        LauncherSurface.SAFE_LAYOUT -> SafeLayoutSurface(
            state = state,
            onQueryChanged = runtime::setLocalQuery,
            onLaunch = { identity -> activityScope.launch { runtime.launch(identity) } },
            onOpenMap = { runtime.open(LauncherSurface.MAP_OVERVIEW) },
            onOpenSettings = { runtime.open(LauncherSurface.SETTINGS) },
            onOpenRecovery = { runtime.open(LauncherSurface.RECOVERY) },
            onRequestHomeRole = {
                requestRole(HomeRoleRequestReason.ONBOARDING_PREVIEW_CONFIRMED)
            },
        )
        LauncherSurface.SELECTED_LAYOUT -> ProductionCompositionSurface(
            store,
            runtime,
            compositionEngine,
            editor,
            preferences,
            notificationState,
            shortcutState,
            profileState,
            widgetPlatform = widgetPlatform,
            widgetCoordinator = widgetCoordinator,
            widgetTransientEvents = widgetTransientEvents,
            contentRenderer = remember(widgetPlatform) {
                widgetPlatform?.let {
                    AndroidWidgetPreparedContentRenderer(it, ProductionPreparedContentRenderer)
                } ?: ProductionPreparedContentRenderer
            },
            onOpenFolder = { id -> activityScope.launch { phaseFiveHost.openFolder(id) } },
            onOpenItemActions = { id -> activityScope.launch { phaseFiveHost.openItemActions(id) } },
            onLaunchShortcut = { id -> activityScope.launch { phaseFiveHost.launchShortcut(id) } },
            scope = activityScope,
        )
        LauncherSurface.MAP_OVERVIEW -> MapOverviewSurface(
            destinations = state.destinations,
            onSelectDestination = { destination ->
                activityScope.launch { runtime.selectDestination(destination) }
            },
            privateSpaceVisible = preferenceState.privateSpaceVisibility == PrivateSpaceVisibility.VISIBLE,
            privateSpaceEntryPointAvailable = profileState.profiles
                .singleOrNull { it.kind == ProfileKind.PRIVATE }
                ?.let {
                    it.availability != ProfileAvailability.LOCKED ||
                        it.privateSpaceEntryPoint != PrivateSpaceEntryPoint.HIDDEN
                } ?: true,
            onOpenPrivateSpace = {
                if (preferenceState.privateSpaceVisibility == PrivateSpaceVisibility.VISIBLE) {
                    phaseFiveHost.openPrivateSpace()
                } else {
                    activityScope.launch {
                        preferences?.setPrivateSpaceVisibility(PrivateSpaceVisibility.VISIBLE)
                    }
                }
            },
            onCreateFolder = { name ->
                activityScope.launch { phaseFiveHost.createFolder(name) }
            },
            onEditMap = { activityScope.launch { editor.dispatch(EditorAction.OpenMap) } },
            onClose = runtime::closeOverlay,
        )
        LauncherSurface.SETTINGS -> LauncherSettingsSurface(
            homeRoleState = state.homeRoleState,
            fallbackAvailable = fallbackRequired && state.homeRoleState != HomeRoleState.HELD,
            onRequestHomeRole = { requestRole(HomeRoleRequestReason.SETTINGS_USER_ACTION) },
            onOpenHomeSettings = { runtime.openHomeSettingsFallback() },
            onOpenAppRecovery = { runtime.open(LauncherSurface.APP_RECOVERY) },
            notificationStyle = preferenceState.notificationStyle.toSettingsOption(),
            notificationAccess = notificationState.access,
            onNotificationStyle = { option ->
                activityScope.launch { preferences?.setNotificationStyle(option.toPreference()) }
            },
            onRequestNotificationAccess = {
                activityScope.launch { phaseFiveHost.requestNotificationAccess() }
            },
            onOpenNotificationRecovery = {
                activityScope.launch { phaseFiveHost.openNotificationRecovery() }
            },
            privateSpaceVisibility = preferenceState.privateSpaceVisibility.toSettingsOption(),
            onPrivateSpaceVisibility = { option ->
                activityScope.launch {
                    preferences?.setPrivateSpaceVisibility(option.toPreference())
                }
            },
            onOpenPrivateSpaceSettings = {
                activityScope.launch { phaseFiveHost.openPrivateSpaceSettings() }
            },
            workProfiles = profileState.profiles.filter {
                it.kind == org.quicklauncher.host.runtime.profile.ProfileKind.WORK
            },
            onSetWorkMode = { profile, enabled ->
                activityScope.launch { phaseFiveHost.setWorkMode(profile, enabled) }
            },
            shortcuts = shortcutState.availableShortcuts.mapNotNull { shortcut ->
                val profile = profileState.profile(shortcut.target.profile) ?: return@mapNotNull null
                if (!shortcut.enabled ||
                    (!shortcut.dynamic && !shortcut.pinned) ||
                    profile.kind == org.quicklauncher.host.runtime.profile.ProfileKind.PRIVATE ||
                    profile.availability != org.quicklauncher.host.runtime.profile.ProfileAvailability.AVAILABLE
                ) return@mapNotNull null
                ShortcutSelectionItem(
                    target = shortcut.target,
                    label = shortcut.label,
                    workProfile = profile.kind == org.quicklauncher.host.runtime.profile.ProfileKind.WORK,
                    dynamic = shortcut.dynamic,
                    pinned = shortcut.pinned,
                )
            },
            onAddShortcut = { target ->
                activityScope.launch { phaseFiveHost.createShortcutPlacement(target) }
            },
            onClose = runtime::closeOverlay,
        )
        LauncherSurface.APP_RECOVERY -> ProductionAppRecoverySurface(
            apps = state.settingsApps,
            onLaunch = { identity -> activityScope.launch { runtime.launch(identity) } },
            onOpenActions = { id -> activityScope.launch { phaseFiveHost.openItemActions(id) } },
            onClose = runtime::closeOverlay,
        )
        LauncherSurface.RECOVERY -> RecoverySurface(
            instances = state.recoveryInstances,
            onRetry = { instance -> activityScope.launch { runtime.retryRenderer(instance) } },
            onClose = runtime::closeOverlay,
        )
    }
}

@Composable
private fun WidgetRecreationActions(
    coordinator: WidgetCoordinator?,
    store: LauncherStore,
    scope: CoroutineScope,
    changeKey: Any,
    transientEvents: SharedFlow<WidgetTransientEvent>,
) {
    if (coordinator == null) return
    var pending by remember(coordinator) { mutableStateOf<List<WidgetPendingUserAction>>(emptyList()) }
    var activeJob by remember(coordinator) { mutableStateOf<Job?>(null) }
    val launcher = rememberAndroidWidgetUserActionLauncher { result ->
        activeJob?.cancel()
        activeJob = scope.launch {
            val placement = store.read().widgetPlacements.singleOrNull {
                it.appWidgetId == result.action.appWidgetId
            }
            val completed = placement?.let {
                when (result.action) {
                    is WidgetUserAction.BindPermission ->
                        coordinator.completePermission(it.moduleInstanceId, result.accepted)
                    is WidgetUserAction.Configure ->
                        coordinator.completeConfiguration(it.moduleInstanceId, result.accepted)
                }
            }
            val followUp = when (completed) {
                is WidgetFlowResult.AwaitingPermission -> WidgetPendingUserAction(
                    requireNotNull(placement).moduleInstanceId,
                    completed.action,
                )
                is WidgetFlowResult.AwaitingConfiguration -> WidgetPendingUserAction(
                    requireNotNull(placement).moduleInstanceId,
                    completed.action,
                )
                else -> null
            }
            pending = pending.drop(1) + listOfNotNull(followUp)
        }
    }
    LaunchedEffect(coordinator, changeKey) {
        pending = when (val result = coordinator.reconcileAfterRecreation()) {
            is WidgetReconciliationResult.UserActionsRequired -> result.actions
            else -> emptyList()
        }
    }
    LaunchedEffect(transientEvents) {
        transientEvents.collect {
            activeJob?.cancel()
            activeJob = null
            pending = emptyList()
        }
    }
    val next = pending.firstOrNull()
    LaunchedEffect(next) {
        if (next == null) return@LaunchedEffect
        when (launcher.launch(next.action)) {
            WidgetActionLaunchResult.Launched,
            WidgetActionLaunchResult.Busy,
            -> Unit
            else -> {
                coordinator.cancelBinding(next.moduleInstanceId)
                pending = pending.drop(1)
            }
        }
    }
}

internal data object WidgetTransientEvent

internal data class WidgetHomeDismissalResult(
    val requested: Set<ModuleInstanceId>,
    val incomplete: Set<ModuleInstanceId>,
)

internal class WidgetTransientFlowController {
    private val mutableEvents = MutableSharedFlow<WidgetTransientEvent>()
    val events: SharedFlow<WidgetTransientEvent> = mutableEvents

    suspend fun dismissForRepeatedHome(
        store: LauncherStore,
        coordinator: WidgetCoordinator?,
    ): WidgetHomeDismissalResult {
        mutableEvents.emit(WidgetTransientEvent)
        if (coordinator == null) return WidgetHomeDismissalResult(emptySet(), emptySet())
        val pending = try {
            store.read().widgetPlacements.filter {
                it.bindState == WidgetBindState.PENDING &&
                    it.cleanupState == WidgetCleanupState.NONE &&
                    it.appWidgetId != null
            }.map { it.moduleInstanceId }
        } catch (_: RuntimeException) {
            return WidgetHomeDismissalResult(emptySet(), emptySet())
        }
        val incomplete = linkedSetOf<ModuleInstanceId>()
        pending.forEach { moduleInstanceId ->
            val result = try {
                coordinator.cancelBinding(moduleInstanceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                incomplete += moduleInstanceId
                return@forEach
            }
            if (result != WidgetFlowResult.Cancelled) incomplete += moduleInstanceId
        }
        return WidgetHomeDismissalResult(pending.toSet(), incomplete)
    }
}

private fun org.quicklauncher.host.data.preferences.NotificationStyle.toSettingsOption() = when (this) {
    org.quicklauncher.host.data.preferences.NotificationStyle.HIDDEN -> IndicatorStyleOption.HIDDEN
    org.quicklauncher.host.data.preferences.NotificationStyle.DOT -> IndicatorStyleOption.DOT
    org.quicklauncher.host.data.preferences.NotificationStyle.APPROXIMATE_COUNT ->
        IndicatorStyleOption.APPROXIMATE_COUNT
}

private fun IndicatorStyleOption.toPreference() = when (this) {
    IndicatorStyleOption.HIDDEN -> org.quicklauncher.host.data.preferences.NotificationStyle.HIDDEN
    IndicatorStyleOption.DOT -> org.quicklauncher.host.data.preferences.NotificationStyle.DOT
    IndicatorStyleOption.APPROXIMATE_COUNT ->
        org.quicklauncher.host.data.preferences.NotificationStyle.APPROXIMATE_COUNT
}

private fun PrivateSpaceVisibility.toSettingsOption() = when (this) {
    PrivateSpaceVisibility.VISIBLE -> PrivateSpaceVisibilityOption.VISIBLE
    PrivateSpaceVisibility.HIDDEN -> PrivateSpaceVisibilityOption.HIDDEN
}

private fun PrivateSpaceVisibilityOption.toPreference() = when (this) {
    PrivateSpaceVisibilityOption.VISIBLE -> PrivateSpaceVisibility.VISIBLE
    PrivateSpaceVisibilityOption.HIDDEN -> PrivateSpaceVisibility.HIDDEN
}

private suspend fun LauncherPreferencesStore?.setOnboardingBestEffort(state: OnboardingState) {
    val store = this ?: return
    try {
        store.setOnboardingState(state)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Home-role acquisition and launcher recovery must survive preference I/O failure.
    }
}

private fun inMemoryDiagnosticLog() = BoundedDiagnosticLog(
    object : DiagnosticStorage {
        private var records: List<DiagnosticRecord> = emptyList()

        override suspend fun read(): List<DiagnosticRecord> = records

        override suspend fun replace(records: List<DiagnosticRecord>) {
            this.records = records
        }
    },
)

private fun closeBestEffort(closeable: AutoCloseable?) {
    try {
        closeable?.close()
    } catch (_: Exception) {
        // One failing adapter must not prevent the remaining launcher resources from closing.
    }
}
