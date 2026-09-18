package org.quicklauncher.app

import android.app.Activity
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.lang.ref.WeakReference
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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
import org.quicklauncher.host.backup.android.AndroidBackupAuthorizationStore
import org.quicklauncher.host.backup.android.AndroidChargingBackupScheduler
import org.quicklauncher.host.backup.android.AndroidSafBackupFolder
import org.quicklauncher.host.backup.library.BackupInventory
import org.quicklauncher.host.backup.library.BackupOperationResult
import org.quicklauncher.host.backup.library.PortableBackupLibrary
import org.quicklauncher.host.backup.library.RestoreSelection
import org.quicklauncher.host.backup.library.StagedRestore
import org.quicklauncher.host.backup.archive.PortableBackupArchiveCodec
import org.quicklauncher.host.backup.support.BackupDiagnosticCode
import org.quicklauncher.host.backup.support.BackupDiagnosticEvent
import org.quicklauncher.host.backup.support.RedactedSupportBundle
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
import org.quicklauncher.host.platform.motion.AndroidMotionPolicy
import org.quicklauncher.host.platform.profile.AndroidProfilePlatform
import org.quicklauncher.host.platform.profile.AndroidSecureOverlayProtection
import org.quicklauncher.host.platform.shortcuts.AndroidShortcutPlatform
import org.quicklauncher.host.platform.theme.AndroidIconPackResolver
import org.quicklauncher.host.platform.theme.AndroidThemeAssetStore
import org.quicklauncher.host.platform.theme.AvailableIconPack
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
import org.quicklauncher.host.runtime.catalog.AppCatalog
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
import org.quicklauncher.host.runtime.profile.ProfileCoordinator
import org.quicklauncher.host.runtime.profile.PrivateSpaceEntryPoint
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.profile.SecureOverlayProtection
import org.quicklauncher.host.runtime.shortcuts.DefaultShortcutCoordinator
import org.quicklauncher.host.runtime.shortcuts.ShortcutCoordinator
import org.quicklauncher.host.runtime.shortcuts.StoreShortcutPlacementSource
import org.quicklauncher.host.runtime.theme.BuiltInLauncherThemeResolver
import org.quicklauncher.host.runtime.theme.LauncherMaterialTheme
import org.quicklauncher.host.runtime.theme.ResolvedLauncherTheme
import org.quicklauncher.host.runtime.theme.ThemeResolutionRequest
import org.quicklauncher.host.runtime.theme.BackgroundDefinition
import org.quicklauncher.host.runtime.theme.BuiltInThemeProfiles
import org.quicklauncher.host.runtime.theme.FontImportResult
import org.quicklauncher.host.runtime.theme.FontSelection
import org.quicklauncher.host.runtime.theme.IconPackSelection
import org.quicklauncher.host.runtime.theme.ImageDerivedTheme
import org.quicklauncher.host.runtime.theme.ImageImportResult
import org.quicklauncher.host.runtime.theme.ModuleThemeOverride
import org.quicklauncher.host.runtime.theme.PaletteDefinition
import org.quicklauncher.host.runtime.theme.ThemeController
import org.quicklauncher.host.runtime.theme.ThemeControllerState
import org.quicklauncher.host.runtime.theme.ThemeFontRole
import org.quicklauncher.host.runtime.theme.ThemeModePolicy
import org.quicklauncher.host.runtime.theme.ThemeProfile
import org.quicklauncher.host.runtime.theme.WallpaperCommand
import org.quicklauncher.host.runtime.theme.WallpaperCoordinator
import org.quicklauncher.host.runtime.theme.WallpaperCrop
import org.quicklauncher.host.runtime.theme.WallpaperPreview
import org.quicklauncher.host.runtime.theme.WallpaperResult
import org.quicklauncher.host.runtime.theme.WallpaperTarget
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
import org.quicklauncher.host.settings.SearchProviderSetting
import org.quicklauncher.host.settings.ThemeProfileSetting
import org.quicklauncher.host.settings.ThemeProfileCreation
import org.quicklauncher.host.settings.ManualPaletteSetting
import org.quicklauncher.host.settings.ManualPaletteValues
import org.quicklauncher.host.settings.ThemeOverrideSetting
import org.quicklauncher.host.settings.IconPackSetting
import org.quicklauncher.host.settings.BackupRestoreReviewSetting
import org.quicklauncher.host.settings.BackupRestoreSelectionSetting
import org.quicklauncher.host.settings.BackupRecoverySetting
import org.quicklauncher.host.settings.BackupSnapshotSetting
import org.quicklauncher.host.settings.FolderOverlaySurface
import org.quicklauncher.host.settings.ItemActionOverlaySurface
import org.quicklauncher.host.runtime.folders.FolderOverlayState
import org.quicklauncher.host.runtime.actions.ItemActionOverlayState
import org.quicklauncher.registry.production.productionRegistry
import org.quicklauncher.contracts.contribution.CommandContext
import org.quicklauncher.contracts.contribution.RegisteredLauncherCommand
import org.quicklauncher.contracts.contribution.RegisteredSearchProvider
import org.quicklauncher.contracts.contribution.LauncherCommandAction
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.host.data.preferences.HistoryPolicy
import org.quicklauncher.host.data.store.SearchLaunchHistoryStore
import org.quicklauncher.host.platform.search.AndroidContactSearchAdapter
import org.quicklauncher.host.platform.search.AndroidFileSearchAdapter
import org.quicklauncher.host.platform.search.AndroidSearchTargetPlatform
import org.quicklauncher.host.platform.search.AndroidSettingsRouteLauncher
import org.quicklauncher.host.platform.search.ProductionSearchCatalog
import org.quicklauncher.host.platform.search.SearchRecoveryLauncher
import org.quicklauncher.host.platform.search.SearchProviderAvailabilityCoordinator
import org.quicklauncher.host.platform.search.SearchProviderIdentitySet
import org.quicklauncher.host.runtime.search.CommandCapabilityChecker
import org.quicklauncher.host.runtime.search.ComposeSearchPresentationSource
import org.quicklauncher.host.runtime.search.DefaultSearchActionExecutor
import org.quicklauncher.host.runtime.search.DefaultSearchEngine
import org.quicklauncher.host.runtime.search.DisabledSearchHistory
import org.quicklauncher.host.runtime.search.HostCommandActionExecutor
import org.quicklauncher.host.runtime.search.PreferenceControlledSearchHistory
import org.quicklauncher.host.runtime.search.RegisteredCommandExecutor
import org.quicklauncher.host.runtime.search.RegisteredSearchProviderBinding
import org.quicklauncher.host.runtime.search.SearchActionExecutor
import org.quicklauncher.host.runtime.search.SearchCandidateSource
import org.quicklauncher.host.runtime.search.SearchCommandCatalog
import org.quicklauncher.host.runtime.search.SearchEngine
import org.quicklauncher.host.runtime.search.SearchExecutionResult
import org.quicklauncher.host.runtime.search.SearchExecutionTarget
import org.quicklauncher.host.runtime.search.SearchProviderBinding
import org.quicklauncher.host.runtime.search.SearchRanker
import org.quicklauncher.host.runtime.search.SearchRecoveryAction
import org.quicklauncher.host.runtime.search.SearchSettingsRoute
import org.quicklauncher.host.runtime.search.StoreSearchHistory
import org.quicklauncher.modules.search.core.CoreSearchIds

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
    private lateinit var appCatalog: AppCatalog
    private lateinit var profileCoordinator: ProfileCoordinator
    private lateinit var shortcutCoordinator: ShortcutCoordinator
    private lateinit var searchEngine: DefaultSearchEngine
    private lateinit var searchCatalog: ProductionSearchCatalog
    private lateinit var searchAvailability: SearchProviderAvailabilityCoordinator
    private lateinit var searchPresentations: org.quicklauncher.host.runtime.composition.SearchPresentationSource
    private lateinit var themeAssets: AndroidThemeAssetStore
    private lateinit var iconPackResolver: AndroidIconPackResolver
    private lateinit var wallpaperCoordinator: WallpaperCoordinator
    private lateinit var backupAuthorization: AndroidBackupAuthorizationStore
    private var backupLibrary: PortableBackupLibrary? = null
    private var stagedRestore: StagedRestore? = null
    private val backupUiState = MutableStateFlow(BackupUiState())
    private val backupDiagnosticLog by lazy {
        PrivateBackupDiagnosticLog(File(noBackupFilesDir, PrivateBackupDiagnosticLog.FILE_NAME))
    }
    private var pendingSupportBundle: PendingSupportBundle? = null
    private var themeController: ThemeController? = null
    private val availableIconPacks = MutableStateFlow<List<AvailableIconPack>>(emptyList())
    private val themeMessage = MutableStateFlow<String?>(null)
    private val wallpaperPreview = MutableStateFlow<WallpaperPreview?>(null)
    private val wallpaperResult = MutableStateFlow<WallpaperResult?>(null)
    private val wallpaperActionMutex = Mutex()
    private var pendingWallpaperAssets: Set<org.quicklauncher.contracts.domain.StableKey> = emptySet()
    private var pendingImageAction = ThemeImageAction.LAUNCHER_BACKGROUND
    private var pendingFontRole = ThemeFontRole.BODY
    private val themeCapabilities = productionRegistry.entries.associate { entry ->
        entry.descriptor.metadata.id to entry.descriptor.metadata.providedCapabilities
    }
    private lateinit var secureOverlayProtection: SecureOverlayProtection
    private var widgetPlatform: AndroidWidgetPlatform? = null
    private var widgetCoordinator: WidgetCoordinator? = null
    private val widgetTransientFlows = WidgetTransientFlowController()
    private var startup: Job? = null
    private var resourcesClosed = false

    private val contactsPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (::searchAvailability.isInitialized) {
            activityScope.launch { searchAvailability.completeContactsRequest(granted) }
        }
    }
    private val fileAuthorizationRequest = registerForActivityResult(
        FileAuthorizationContract(),
    ) { authorization ->
        val granted = authorization?.let { result ->
            if (result.uri.scheme != "content" ||
                result.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0
            ) {
                false
            } else {
                try {
                    contentResolver.takePersistableUriPermission(
                        result.uri,
                        result.flags and (
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            ),
                    )
                    contentResolver.persistedUriPermissions.any {
                        it.uri == result.uri && it.isReadPermission
                    }
                } catch (_: SecurityException) {
                    false
                } catch (_: RuntimeException) {
                    false
                }
            }
        } ?: false
        if (::searchAvailability.isInitialized) {
            activityScope.launch { searchAvailability.completeFileRequest(granted) }
        }
    }
    private val fontImportRequest = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        activityScope.launch { handleFontImport(uri) }
    }
    private val themeImageRequest = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        activityScope.launch { handleImageImport(uri, pendingImageAction) }
    }
    private val backupFolderRequest = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null && ::backupAuthorization.isInitialized) {
            try {
                val authorization = backupAuthorization.authorize(uri)
                configureBackup(authorization.treeUri, authorization.automaticEnabled)
                recordBackupEvent(BackupDiagnosticCode.FOLDER_AUTHORIZED)
                refreshBackupInventory()
            } catch (_: SecurityException) {
                backupUiState.value = backupUiState.value.copy(
                    message = "The selected folder did not grant lasting read and write access.",
                )
            } catch (_: RuntimeException) {
                backupUiState.value = backupUiState.value.copy(
                    message = "The selected folder is unavailable.",
                )
            }
        }
    }
    private val supportBundleRequest = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val pending = pendingSupportBundle
        pendingSupportBundle = null
        if (pending != null) {
            activityScope.launch(Dispatchers.IO) {
                var exported = false
                try {
                    if (uri != null) {
                        exported = contentResolver.openOutputStream(uri, "w")?.use { output ->
                            output.write(pending.bytes)
                            true
                        } == true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    exported = false
                } finally {
                    pending.bytes.fill(0)
                }
                if (exported) {
                    backupDiagnosticLog.clearExported(pending.diagnosticSnapshot)
                } else if (uri != null) {
                    recordBackupEvent(BackupDiagnosticCode.EXPORT_FAILED)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val backupReviewWasPending = savedInstanceState?.getBoolean(STATE_BACKUP_RESTORE_REVIEW) == true
        ThemeImportRequestState.restore(
            savedInstanceState?.getString(STATE_THEME_IMAGE_ACTION),
            savedInstanceState?.getString(STATE_THEME_FONT_ROLE),
        ).also { restored ->
            pendingImageAction = restored.imageAction
            pendingFontRole = restored.fontRole
        }
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
        backupAuthorization = AndroidBackupAuthorizationStore(this)
        preferences = (application as QuicklauncherApplication).launcherPreferences
        themeAssets = AndroidThemeAssetStore(this)
        backupAuthorization.current()?.let { authorization ->
            configureBackup(authorization.treeUri, authorization.automaticEnabled)
        }
        BackupRecoveryLifecyclePolicy.restore(
            savedInstanceState?.getBoolean(STATE_BACKUP_RECOVERY) == true,
            savedInstanceState?.getInt(STATE_BACKUP_RECOVERY_WIDGETS) ?: 0,
            savedInstanceState?.getInt(STATE_BACKUP_RECOVERY_QUARANTINE) ?: 0,
            savedInstanceState?.getInt(STATE_BACKUP_RECOVERY_PROFILES) ?: 0,
            savedInstanceState?.getLongArray(STATE_BACKUP_RECOVERY_PROFILE_SERIALS)?.toList().orEmpty(),
        )?.let { recovery ->
            backupUiState.value = backupUiState.value.copy(
                recovery = recovery,
            )
        }
        backupUiState.value = BackupRestoreLifecyclePolicy.afterRecreation(
            backupUiState.value,
            backupReviewWasPending,
        )
        iconPackResolver = AndroidIconPackResolver(this)
        wallpaperCoordinator = WallpaperCoordinator(themeAssets)
        themeController = preferences?.let { themePreferences ->
            ThemeController(
                launcherStore,
                themePreferences,
                themeCapabilities,
                themeAssets::fontAssets,
                themeAssets::imageAssets,
                themeAssets::deleteUnreferenced,
                activityScope,
            )
        }
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
        profileCoordinator = DefaultProfileCoordinator(profilePlatform, activityScope)
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
        appCatalog = DefaultAppCatalog(
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
            catalog = appCatalog,
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
        shortcutCoordinator = DefaultShortcutCoordinator(
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
        createSearch(appCatalog, profileCoordinator, shortcutCoordinator)

        recordEntry(isHome, fromNewIntent = false)
        consumeEntryIntent()
        val restoredDestination = savedInstanceState
            ?.getString(STATE_DESTINATION)
            ?.let { value -> runCatching { DestinationId.parse(value) }.getOrNull() }
        val restoredFolder = savedInstanceState
            ?.getString(STATE_FOLDER_OVERLAY)
            ?.let { value -> runCatching { ContentItemId.parse(value) }.getOrNull() }
        startup = activityScope.launch {
            themeController?.start()
            availableIconPacks.value = runCatching { iconPackResolver.discover() }.getOrDefault(emptyList())
            if (::searchAvailability.isInitialized) searchAvailability.bootstrapSafeProviders()
            refreshBackupInventory()
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
            val systemDark = isSystemInDarkTheme()
            val textScale = LocalDensity.current.fontScale
            val reducedMotion = AndroidMotionPolicy.reducedMotion()
            val themeState by (themeController?.state ?: flowOf(defaultThemeControllerState()))
                .collectAsState(initial = defaultThemeControllerState())
            val launcherState by runtime.state.collectAsState()
            val destinationBackground = launcherState.currentDestinationId?.let(themeState.backgrounds::get)
            val launcherResolvedTheme = remember(
                systemDark,
                textScale,
                reducedMotion,
                themeState.generation,
            ) {
                BuiltInLauncherThemeResolver.resolve(
                    ThemeResolutionRequest(
                        systemDark,
                        textScale,
                        reducedMotion,
                        themeState.profileForPresentation(),
                        themeCapabilities,
                        themeState.fontAssets,
                        null,
                        themeState.imageAssets,
                    ),
                )
            }
            val resolvedTheme = remember(launcherResolvedTheme, destinationBackground) {
                destinationBackground?.let { background ->
                    BuiltInLauncherThemeResolver.resolve(
                        ThemeResolutionRequest(
                            systemDark,
                            textScale,
                            reducedMotion,
                            themeState.profileForPresentation(),
                            themeCapabilities,
                            themeState.fontAssets,
                            background,
                            themeState.imageAssets,
                        ),
                    )
                } ?: launcherResolvedTheme
            }
            val destinationThemes = remember(
                systemDark,
                textScale,
                reducedMotion,
                themeState.generation,
            ) {
                themeState.backgrounds.entries.associate { (destinationId, background) ->
                    destinationId to BuiltInLauncherThemeResolver.resolve(
                        ThemeResolutionRequest(
                            systemDark,
                            textScale,
                            reducedMotion,
                            themeState.profileForPresentation(),
                            themeCapabilities,
                            themeState.fontAssets,
                            background,
                            themeState.imageAssets,
                        ),
                    )
                }
            }
            val resolvedTypography = rememberThemeTypography(resolvedTheme, themeAssets)
            LauncherMaterialTheme(resolvedTheme, resolvedTypography) {
                ResolvedThemeBackground(resolvedTheme, themeAssets) {
                    Surface(
                        Modifier.fillMaxSize(),
                        color = Color.Transparent,
                        contentColor = Color(resolvedTheme.theme.foreground.value),
                    ) {
                        QuicklauncherRoot(
                        runtime,
                        launcherStore,
                        compositionEngine,
                        editor,
                        onboarding,
                        preferences,
                        resolvedTheme,
                        launcherResolvedTheme,
                        destinationThemes,
                        iconPackResolver,
                        themeAssets,
                        phaseFiveHost,
                        secureOverlayProtection,
                        widgetPlatform,
                        widgetCoordinator,
                        widgetTransientFlows.events,
                        activityScope,
                        searchPresentations,
                        ::updateSearchProvider,
                        ::updateLocalSearchHistory,
                        themeState,
                        availableIconPacks,
                        themeMessage,
                        wallpaperPreview,
                        wallpaperResult,
                        ::selectThemeProfile,
                        ::previewThemeProfile,
                        ::deleteThemeProfile,
                        ::createThemeProfile,
                        ::saveManualPalette,
                        ::saveThemeOverride,
                        ::requestFontImport,
                        ::selectIconPack,
                        ::requestThemeImage,
                        ::setPresetBackground,
                        ::confirmWallpaper,
                        ::updateWallpaperCrop,
                        ::cancelWallpaper,
                        backupUiState,
                        ::chooseBackupFolder,
                        ::setAutomaticBackups,
                        ::exportBackup,
                        ::previewBackup,
                        ::restoreBackup,
                        ::cancelBackupRestore,
                        ::exportSupportBundle,
                        ::reauthorizeRestoredDocuments,
                        ::openAppPermissionSettings,
                        ::finishAndRemoveTask,
                        )
                    }
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
                cancelWallpaperNow()
                cancelBackupRestore()
                searchPresentations.dismissAll()
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
                if (::searchAvailability.isInitialized) searchAvailability.disableRevokedProviders()
                HomeEntryRecorder.recordRuntimeState(runtime.state.value)
                if (runtime.state.value.homeRoleState == HomeRoleState.HELD) {
                    preferences.setOnboardingBestEffort(OnboardingState.COMPLETED)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::searchPresentations.isInitialized) searchPresentations.setHostActive(true)
    }

    override fun onStop() {
        if (::searchPresentations.isInitialized) searchPresentations.setHostActive(false)
        super.onStop()
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
        outState.putString(STATE_THEME_IMAGE_ACTION, pendingImageAction.name)
        outState.putString(STATE_THEME_FONT_ROLE, pendingFontRole.name)
        outState.putBoolean(
            STATE_BACKUP_RESTORE_REVIEW,
            stagedRestore != null || backupUiState.value.review != null,
        )
        backupUiState.value.recovery?.let { recovery ->
            outState.putBoolean(STATE_BACKUP_RECOVERY, true)
            outState.putInt(STATE_BACKUP_RECOVERY_WIDGETS, recovery.widgetsToRebind)
            outState.putInt(STATE_BACKUP_RECOVERY_QUARANTINE, recovery.quarantined)
            outState.putInt(STATE_BACKUP_RECOVERY_PROFILES, recovery.profilesToReview)
            outState.putLongArray(
                STATE_BACKUP_RECOVERY_PROFILE_SERIALS,
                recovery.profileSerials.toLongArray(),
            )
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
        if (::searchEngine.isInitialized) closeBestEffort(searchEngine)
        if (::searchCatalog.isInitialized) closeBestEffort(searchCatalog)
        if (::searchAvailability.isInitialized) closeBestEffort(searchAvailability)
        closeBestEffort(themeController)
        if (::wallpaperCoordinator.isInitialized) wallpaperCoordinator.close()
        if (::iconPackResolver.isInitialized) closeBestEffort(iconPackResolver)
        if (::themeAssets.isInitialized && pendingWallpaperAssets.isNotEmpty()) {
            runBlocking(Dispatchers.IO) { themeAssets.deleteAssets(pendingWallpaperAssets) }
            pendingWallpaperAssets = emptySet()
        }
        pendingSupportBundle?.bytes?.fill(0)
        pendingSupportBundle = null
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
        resolvedTheme = resolvedThemeFor(destinationId),
        fallbackResolvedTheme = resolvedThemeFor(null),
        destinationThemes = (themeController?.state?.value ?: defaultThemeControllerState())
            .backgrounds.keys.associateWith(::resolvedThemeFor),
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
        searchPresentations = if (::searchPresentations.isInitialized) searchPresentations else null,
    )

    private fun resolvedThemeFor(destinationId: DestinationId?): ResolvedLauncherTheme {
        val state = themeController?.state?.value ?: defaultThemeControllerState()
        return BuiltInLauncherThemeResolver.resolve(
            ThemeResolutionRequest(
                systemDark = (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES,
                textScale = resources.configuration.fontScale,
                reducedMotion = AndroidMotionPolicy.reducedMotion(),
                profile = state.activeProfile,
                registeredCapabilities = themeCapabilities,
                availableFontAssets = state.fontAssets,
                destinationBackground = destinationId?.let(state.backgrounds::get),
                availableImageAssets = state.imageAssets,
            ),
        )
    }

    private fun createSearch(
        catalog: AppCatalog,
        profiles: ProfileCoordinator,
        shortcuts: ShortcutCoordinator,
    ) {
        val searchPreferences = preferences ?: run {
            searchPresentations = org.quicklauncher.host.runtime.composition.SearchPresentationSource { null }
            return
        }
        val searchEntries = productionRegistry.entries.filterIsInstance<RegisteredSearchProvider<*>>()
        val commandEntries = productionRegistry.entries.filterIsInstance<RegisteredLauncherCommand<*>>()
        val providerIdentities = SearchProviderIdentitySet(
            contacts = ContributionId.parse(CoreSearchIds.CONTACTS),
            files = ContributionId.parse(CoreSearchIds.FILES),
            graphene = ContributionId.parse(CoreSearchIds.GRAPHENE_SETTINGS),
            safeDefaults = setOf(
                ContributionId.parse(CoreSearchIds.APPS),
                ContributionId.parse(CoreSearchIds.SHORTCUTS),
                ContributionId.parse(CoreSearchIds.COMMANDS),
                ContributionId.parse(CoreSearchIds.SETTINGS),
                ContributionId.parse(CoreSearchIds.WEB),
                ContributionId.parse(CoreSearchIds.INFORMATION),
            ),
        )
        searchAvailability = SearchProviderAvailabilityCoordinator(
            this,
            searchPreferences,
            searchEntries.map { it.descriptor.metadata.id },
            providerIdentities,
            activityScope,
        )
        val settingsLauncher = AndroidSettingsRouteLauncher(this)
        searchCatalog = ProductionSearchCatalog(
            catalog,
            profiles,
            shortcuts,
            commandEntries,
            settingsLauncher,
            providerIdentities.files,
            activityScope,
        )
        val contactAdapter = AndroidContactSearchAdapter(this, profiles)
        val fileAdapter = AndroidFileSearchAdapter(this)
        val sources = mapOf(
            ContributionId.parse(CoreSearchIds.APPS) to
                searchCatalog.apps(ContributionId.parse(CoreSearchIds.APPS)),
            ContributionId.parse(CoreSearchIds.SHORTCUTS) to
                searchCatalog.shortcuts(ContributionId.parse(CoreSearchIds.SHORTCUTS)),
            ContributionId.parse(CoreSearchIds.COMMANDS) to
                searchCatalog.commands(ContributionId.parse(CoreSearchIds.COMMANDS)),
            ContributionId.parse(CoreSearchIds.CONTACTS) to
                searchCatalog.contacts(
                    ContributionId.parse(CoreSearchIds.CONTACTS),
                    contactAdapter,
                ) { searchAvailability.reportAccessLost(ContributionId.parse(CoreSearchIds.CONTACTS)) },
            ContributionId.parse(CoreSearchIds.FILES) to
                searchCatalog.files(
                    ContributionId.parse(CoreSearchIds.FILES),
                    fileAdapter,
                ) { searchAvailability.reportAccessLost(ContributionId.parse(CoreSearchIds.FILES)) },
            ContributionId.parse(CoreSearchIds.SETTINGS) to
                searchCatalog.publicSettings(ContributionId.parse(CoreSearchIds.SETTINGS)),
            ContributionId.parse(CoreSearchIds.GRAPHENE_SETTINGS) to
                searchCatalog.grapheneSettings(ContributionId.parse(CoreSearchIds.GRAPHENE_SETTINGS)),
            ContributionId.parse(CoreSearchIds.WEB) to
                searchCatalog.web(ContributionId.parse(CoreSearchIds.WEB)),
            ContributionId.parse(CoreSearchIds.INFORMATION) to
                searchCatalog.information(ContributionId.parse(CoreSearchIds.INFORMATION)),
        )
        val bindings = searchEntries.map { entry ->
            bindSearchProvider(
                entry,
                searchAvailability.state(entry.descriptor.metadata.id),
                requireNotNull(sources[entry.descriptor.metadata.id]),
            )
        }
        val commandExecutor = RegisteredCommandExecutor(
            commandEntries,
            // No Phase 6 command is granted a host capability. Future capability-bearing
            // commands remain unavailable until the composition root supplies an explicit grant.
            CommandCapabilityChecker { false },
            HostCommandActionExecutor { action -> executeCommandAction(action) },
        )
        val targetPlatform = AndroidSearchTargetPlatform(
            this,
            catalog,
            shortcuts,
            contactAdapter,
            fileAdapter,
            SearchRecoveryLauncher { action -> executeSearchRecovery(action, settingsLauncher) },
        )
        val actionExecutor: SearchActionExecutor = DefaultSearchActionExecutor(
            searchCatalog,
            targetPlatform,
            commandExecutor,
        )
        val history = (launcherStore as? SearchLaunchHistoryStore)?.let { store ->
            PreferenceControlledSearchHistory(searchPreferences, StoreSearchHistory(store))
        } ?: DisabledSearchHistory
        searchEngine = DefaultSearchEngine(
            providers = bindings,
            targets = searchCatalog,
            commands = SearchCommandCatalog { id -> commandExecutor.contains(id, CommandContext.SEARCH) },
            executor = actionExecutor,
            ranker = SearchRanker(history),
            parentScope = activityScope,
        )
        searchPresentations = ComposeSearchPresentationSource(searchEngine) {
            activityScope.launch { runtime.onHomeIntent() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun bindSearchProvider(
        entry: RegisteredSearchProvider<*>,
        availability: kotlinx.coroutines.flow.StateFlow<org.quicklauncher.host.runtime.search.SearchProviderAvailability>,
        source: SearchCandidateSource,
    ): SearchProviderBinding = RegisteredSearchProviderBinding(
        entry as RegisteredSearchProvider<Any>,
        availability,
        source,
    )

    private suspend fun executeCommandAction(action: LauncherCommandAction): SearchExecutionResult = when (action) {
        LauncherCommandAction.OpenLauncherSettings -> {
            runtime.open(LauncherSurface.SETTINGS)
            SearchExecutionResult.Succeeded
        }
        LauncherCommandAction.OpenSearch -> if (searchPresentations.requestFocus()) {
            SearchExecutionResult.Succeeded
        } else {
            SearchExecutionResult.Unavailable
        }
        is LauncherCommandAction.NavigateTo -> {
            if (runtime.selectDestination(action.destinationId)) {
                SearchExecutionResult.Succeeded
            } else {
                SearchExecutionResult.Unavailable
            }
        }
        is LauncherCommandAction.OpenItemActions -> {
            if (
                phaseFiveHost.openItemActions(action.itemId) ==
                org.quicklauncher.host.runtime.actions.ItemActionResult.Applied
            ) {
                SearchExecutionResult.Succeeded
            } else {
                SearchExecutionResult.Rejected
            }
        }
    }

    private suspend fun executeSearchRecovery(
        action: SearchRecoveryAction,
        settingsLauncher: AndroidSettingsRouteLauncher,
    ): SearchExecutionResult = when (action) {
        SearchRecoveryAction.ENABLE_CONTACTS -> {
            searchAvailability.beginContactsRequest()
            contactsPermissionRequest.launch(android.Manifest.permission.READ_CONTACTS)
            SearchExecutionResult.Succeeded
        }
        SearchRecoveryAction.AUTHORIZE_FILES -> {
            searchAvailability.beginFileRequest()
            fileAuthorizationRequest.launch(Unit)
            SearchExecutionResult.Succeeded
        }
        SearchRecoveryAction.OPEN_LAUNCHER_SETTINGS -> {
            runtime.open(LauncherSurface.SETTINGS)
            SearchExecutionResult.Succeeded
        }
        SearchRecoveryAction.OPEN_APP_DETAILS -> settingsLauncher.launch(
            SearchExecutionTarget.Setting(
                SearchSettingsRoute.APPLICATION_DETAILS,
                PackageName.parse(packageName),
            ),
        )
    }

    private fun updateSearchProvider(id: ContributionId, enabled: Boolean) {
        if (!::searchAvailability.isInitialized) return
        activityScope.launch {
            when {
                id == ContributionId.parse(CoreSearchIds.CONTACTS) && enabled ->
                    executeSearchRecovery(
                        SearchRecoveryAction.ENABLE_CONTACTS,
                        AndroidSettingsRouteLauncher(this@MainActivity),
                    )
                id == ContributionId.parse(CoreSearchIds.FILES) && enabled ->
                    executeSearchRecovery(
                        SearchRecoveryAction.AUTHORIZE_FILES,
                        AndroidSettingsRouteLauncher(this@MainActivity),
                    )
                id == ContributionId.parse(CoreSearchIds.GRAPHENE_SETTINGS) ->
                    searchAvailability.setGrapheneEnabled(enabled)
                else -> searchAvailability.setEnabled(id, enabled)
            }
        }
    }

    private fun updateLocalSearchHistory(enabled: Boolean) {
        activityScope.launch {
            preferences?.setHistoryPolicy(if (enabled) HistoryPolicy.LOCAL else HistoryPolicy.DISABLED)
        }
    }

    private fun selectThemeProfile(id: ThemeProfileId) {
        activityScope.launch {
            themeMessage.value = when (themeController?.select(id)) {
                is org.quicklauncher.host.runtime.theme.ThemeMutationResult.Applied -> null
                else -> "Theme profile could not be selected."
            }
            iconPackResolver.invalidateProfileAndConfiguration()
        }
    }

    private fun previewThemeProfile(id: ThemeProfileId?) {
        activityScope.launch {
            val profile = id?.let { selected ->
                themeController?.state?.value?.profiles?.singleOrNull { it.id == selected }
            }
            themeMessage.value = when (themeController?.preview(profile)) {
                is org.quicklauncher.host.runtime.theme.ThemeMutationResult.Applied -> null
                else -> "Theme preview is unavailable."
            }
            iconPackResolver.invalidateProfileAndConfiguration()
        }
    }

    private fun createThemeProfile(kind: ThemeProfileCreation) {
        activityScope.launch {
            val state = themeController?.state?.value ?: return@launch
            val active = state.activeProfile
            val generated = when (kind) {
                ThemeProfileCreation.MATERIAL_YOU -> active.copy(
                    id = newThemeProfileId(),
                    name = "Material You",
                    palette = PaletteDefinition.MaterialYou(ArgbColor.of(0xff6750a4L)),
                    imageDerived = null,
                )
                ThemeProfileCreation.MATERIAL_EXPRESSIVE -> active.copy(
                    id = newThemeProfileId(),
                    name = "Material Expressive",
                    palette = PaletteDefinition.MaterialExpressive(ArgbColor.of(0xff006875L)),
                    imageDerived = null,
                )
                ThemeProfileCreation.MANUAL -> {
                    val dark = (resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    val colors = BuiltInLauncherThemeResolver.resolve(
                        ThemeResolutionRequest(
                            dark,
                            resources.configuration.fontScale,
                            false,
                            active,
                            themeCapabilities,
                            state.fontAssets,
                            availableImageAssets = state.imageAssets,
                        ),
                    ).theme.colors
                    active.copy(
                        id = newThemeProfileId(),
                        name = "Manual palette",
                        palette = PaletteDefinition.Manual(colors),
                        imageDerived = null,
                    )
                }
            }
            saveAndSelectTheme(generated)
        }
    }

    private fun deleteThemeProfile(id: ThemeProfileId) {
        activityScope.launch {
            themeMessage.value = mutationMessage(themeController?.delete(id))
            iconPackResolver.invalidateProfileAndConfiguration()
        }
    }

    private fun saveManualPalette(values: ManualPaletteValues) {
        activityScope.launch {
            val state = themeController?.state?.value ?: return@launch
            val active = state.activeProfile
            val base = BuiltInLauncherThemeResolver.resolve(
                ThemeResolutionRequest(
                    systemDark = (resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES,
                    textScale = resources.configuration.fontScale,
                    reducedMotion = false,
                    profile = active,
                    registeredCapabilities = themeCapabilities,
                    availableFontAssets = state.fontAssets,
                    availableImageAssets = state.imageAssets,
                ),
            ).theme.colors
            saveAndSelectTheme(
                active.copy(
                    id = newThemeProfileId(),
                    name = "Manual palette",
                    palette = PaletteDefinition.Manual(
                        base.copy(
                            primary = values.primary,
                            onPrimary = values.onPrimary,
                            surface = values.surface,
                            onSurface = values.onSurface,
                        ),
                    ),
                    imageDerived = null,
                ),
                invalidMessage = "Palette colors must be opaque and meet text and control contrast requirements.",
            )
        }
    }

    private fun saveThemeOverride(contributionId: ContributionId, accent: ArgbColor?) {
        activityScope.launch {
            val capability = CapabilityId.parse(THEME_ACCENT_CAPABILITY)
            if (capability !in themeCapabilities[contributionId].orEmpty()) {
                themeMessage.value = "This module does not allow a theme override."
                return@launch
            }
            val active = themeController?.state?.value?.activeProfile ?: return@launch
            val retained = active.overrides.filterNot { it.contributionId == contributionId }
            val overrides = if (accent == null) retained else retained + ModuleThemeOverride(
                contributionId = contributionId,
                requiredCapability = capability,
                accent = accent,
                font = null,
            )
            saveAndSelectTheme(
                active.copy(
                    id = newThemeProfileId(),
                    name = "Module accent",
                    overrides = overrides,
                ),
                invalidMessage = "The module accent must meet contrast requirements.",
            )
        }
    }

    private fun requestFontImport(role: ThemeFontRole) {
        pendingFontRole = role
        fontImportRequest.launch(
            arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-opentype"),
        )
    }

    private suspend fun handleFontImport(uri: Uri?) {
        when (val result = themeAssets.importFont(uri)) {
            is FontImportResult.Imported -> {
                val active = themeController?.state?.value?.activeProfile
                if (active == null) {
                    themeAssets.deleteAssets(setOf(result.metadata.assetId))
                    themeMessage.value = "Font could not be saved."
                    return
                }
                val selection = FontSelection(
                    result.metadata.assetId,
                    result.metadata.weight,
                    result.metadata.italic,
                )
                val saved = saveAndSelectTheme(
                    active.copy(
                        id = newThemeProfileId(),
                        name = "Imported font",
                        fonts = active.fonts + (pendingFontRole to selection),
                    ),
                )
                if (!saved) themeAssets.deleteAssets(setOf(result.metadata.assetId))
            }
            FontImportResult.Cancelled -> themeMessage.value = null
            FontImportResult.Denied -> themeMessage.value = "Font access was denied."
            FontImportResult.Oversized -> themeMessage.value = "Font exceeds the import limit."
            FontImportResult.TimedOut -> themeMessage.value = "Font validation timed out."
            else -> themeMessage.value = "Font is malformed or unsupported."
        }
    }

    private fun selectIconPack(selection: IconPackSelection?) {
        activityScope.launch {
            val active = themeController?.state?.value?.activeProfile ?: return@launch
            saveAndSelectTheme(
                active.copy(
                    id = newThemeProfileId(),
                    name = if (selection == null) "Application icons" else "Icon pack",
                    iconPack = selection,
                ),
            )
            iconPackResolver.invalidateProfileAndConfiguration()
        }
    }

    private fun requestThemeImage(action: ThemeImageAction) {
        pendingImageAction = action
        themeImageRequest.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
    }

    private suspend fun handleImageImport(uri: Uri?, action: ThemeImageAction) {
        val retainSource = action != ThemeImageAction.DERIVED_THEME
        when (val result = themeAssets.importImage(uri, retainSource)) {
            is ImageImportResult.Imported -> when (action) {
                ThemeImageAction.DERIVED_THEME -> {
                    val active = themeController?.state?.value?.activeProfile
                    if (active == null) {
                        themeAssets.deleteAssets(setOf(result.metadata.previewId))
                        themeMessage.value = "Image-derived theme could not be saved."
                        return
                    }
                    val saved = saveAndSelectTheme(
                        active.copy(
                            id = newThemeProfileId(),
                            name = "Image-derived theme",
                            palette = PaletteDefinition.MaterialYou(result.metadata.seed),
                            imageDerived = ImageDerivedTheme(
                                result.metadata.seed,
                                result.metadata.previewId,
                            ),
                            background = null,
                        ),
                    )
                    if (!saved) themeAssets.deleteAssets(setOf(result.metadata.previewId))
                }
                ThemeImageAction.LAUNCHER_BACKGROUND -> setImageBackground(result.metadata, false)
                ThemeImageAction.DESTINATION_BACKGROUND -> setImageBackground(result.metadata, true)
                ThemeImageAction.WALLPAPER -> {
                    val preview = wallpaperCoordinator.preview(
                        result.metadata.assetId,
                        WallpaperCrop(0f, 0f, 1f, 1f),
                    )
                    if (preview == null) {
                        themeAssets.deleteAssets(setOf(result.metadata.assetId, result.metadata.previewId))
                        themeMessage.value = "Wallpaper preview could not be generated."
                    } else {
                        pendingWallpaperAssets = setOf(
                            result.metadata.assetId,
                            result.metadata.previewId,
                            preview.previewId,
                        )
                        wallpaperPreview.value = preview
                        wallpaperResult.value = null
                        themeMessage.value = null
                    }
                }
            }
            ImageImportResult.Cancelled -> themeMessage.value = null
            ImageImportResult.Denied -> themeMessage.value = "Image access was denied."
            ImageImportResult.Oversized -> themeMessage.value = "Image exceeds the import limits."
            ImageImportResult.TimedOut -> themeMessage.value = "Image validation timed out."
            else -> themeMessage.value = "Image is malformed or unsupported."
        }
    }

    private suspend fun setImageBackground(
        metadata: org.quicklauncher.host.runtime.theme.ImportedImageMetadata,
        destination: Boolean,
    ) {
        val background = BackgroundDefinition.Image(
            metadata.assetId,
            metadata.previewId,
            metadata.representativeColor,
            ArgbColor.of(0xff000000L),
            0.25f,
        )
        if (destination) {
            val destinationId = runtime.state.value.currentDestinationId
            if (destinationId == null) {
                themeAssets.deleteAssets(setOf(metadata.assetId, metadata.previewId))
                themeMessage.value = "No current destination is available."
                return
            }
            val result = themeController?.setDestinationBackground(
                destinationId,
                background,
                materializedProfileId = newThemeProfileId(),
            )
            themeMessage.value = mutationMessage(result)
            if (result !is org.quicklauncher.host.runtime.theme.ThemeMutationResult.Applied) {
                themeAssets.deleteAssets(setOf(metadata.assetId, metadata.previewId))
            }
        } else {
            val active = themeController?.state?.value?.activeProfile
            if (active == null) {
                themeAssets.deleteAssets(setOf(metadata.assetId, metadata.previewId))
                themeMessage.value = "Image background could not be saved."
                return
            }
            val saved = saveAndSelectTheme(
                active.copy(
                    id = newThemeProfileId(),
                    name = "Image background",
                    background = background,
                ),
            )
            if (!saved) themeAssets.deleteAssets(setOf(metadata.assetId, metadata.previewId))
        }
    }

    private fun setPresetBackground(destination: Boolean, preset: String) {
        activityScope.launch {
            val active = themeController?.state?.value?.activeProfile ?: return@launch
            val dark = active.mode == ThemeModePolicy.DARK
            val background = when (preset) {
                "inherit" -> null
                "solid" -> BackgroundDefinition.Solid(
                    ArgbColor.of(if (dark) 0xff161616L else 0xfff5f5f5L),
                )
                "gradient" -> BackgroundDefinition.Gradient(
                    listOf(ArgbColor.of(0xff1b1b1fL), ArgbColor.of(0xff2949a3L)),
                    135f,
                )
                else -> return@launch
            }
            if (destination) {
                val id = runtime.state.value.currentDestinationId ?: return@launch
                themeMessage.value = mutationMessage(
                    themeController?.setDestinationBackground(
                        id,
                        background,
                        materializedProfileId = if (background == null) null else newThemeProfileId(),
                    ),
                )
            } else {
                saveAndSelectTheme(
                    active.copy(
                        id = newThemeProfileId(),
                        name = if (background is BackgroundDefinition.Gradient) {
                            "Gradient background"
                        } else {
                            "Solid background"
                        },
                        background = background,
                    ),
                )
            }
        }
    }

    private fun confirmWallpaper(target: WallpaperTarget) {
        activityScope.launch {
            wallpaperActionMutex.withLock {
                val preview = wallpaperPreview.value ?: return@withLock
                val result = wallpaperCoordinator.execute(
                    WallpaperCommand(preview.previewId, preview.generation, target, warningAcknowledged = true),
                )
                wallpaperResult.value = result
                wallpaperPreview.value = null
                themeAssets.deleteAssets(pendingWallpaperAssets)
                pendingWallpaperAssets = emptySet()
            }
        }
    }

    private fun updateWallpaperCrop(crop: WallpaperCrop) {
        activityScope.launch {
            wallpaperActionMutex.withLock {
                val current = wallpaperPreview.value ?: return@withLock
                if (current.crop == crop) return@withLock
                val replacement = wallpaperCoordinator.preview(current.assetId, crop)
                if (replacement == null) {
                    themeMessage.value = "Wallpaper crop preview could not be generated."
                    return@withLock
                }
                wallpaperCoordinator.cancel(current.previewId)
                themeAssets.deleteAssets(setOf(current.previewId))
                pendingWallpaperAssets = pendingWallpaperAssets - current.previewId + replacement.previewId
                wallpaperPreview.value = replacement
                wallpaperResult.value = null
                themeMessage.value = null
            }
        }
    }

    private fun cancelWallpaper() {
        activityScope.launch {
            cancelWallpaperNow()
        }
    }

    private suspend fun cancelWallpaperNow() = wallpaperActionMutex.withLock {
        val preview = wallpaperPreview.value ?: return@withLock
        wallpaperCoordinator.cancel(preview.previewId)
        wallpaperPreview.value = null
        wallpaperResult.value = WallpaperResult.Cancelled
        themeAssets.deleteAssets(pendingWallpaperAssets)
        pendingWallpaperAssets = emptySet()
    }

    private suspend fun saveAndSelectTheme(
        profile: ThemeProfile,
        invalidMessage: String? = null,
    ): Boolean {
        val result = themeController?.save(profile, select = true)
        themeMessage.value = if (
            invalidMessage != null &&
            result is org.quicklauncher.host.runtime.theme.ThemeMutationResult.Invalid
        ) invalidMessage else mutationMessage(result)
        if (result is org.quicklauncher.host.runtime.theme.ThemeMutationResult.Applied) {
            iconPackResolver.invalidateProfileAndConfiguration()
        }
        return result is org.quicklauncher.host.runtime.theme.ThemeMutationResult.Applied
    }

    private fun mutationMessage(result: org.quicklauncher.host.runtime.theme.ThemeMutationResult?): String? =
        if (result is org.quicklauncher.host.runtime.theme.ThemeMutationResult.Applied) null
        else "Theme change could not be saved."

    private fun newThemeProfileId(): ThemeProfileId = ThemeProfileId.parse(
        "org.quicklauncher.theme/custom-${java.util.UUID.randomUUID().toString().replace("-", "")}",
    )

    private fun configureBackup(treeUri: Uri, automaticEnabled: Boolean) {
        val webAdapters = PersistedWebAdapterBackupPort(this)
        backupLibrary = PortableBackupLibrary(
            folder = AndroidSafBackupFolder(contentResolver, treeUri),
            store = launcherStore,
            knownContribution = { id ->
                id == SafeLayoutIds.CONTRIBUTION ||
                    productionRegistry.entries.any { entry -> entry.descriptor.metadata.id == id }
            },
            sectionSource = CompositeBackupSectionSource(
                listOf(ThemeAssetBackupSectionSource(themeAssets), webAdapters),
            ),
            launcherExtensionSource = webAdapters,
            auxiliaryPort = CompositeRestoreAuxiliaryPort(
                listOf(ThemeAssetRestorePort(themeAssets), webAdapters),
            ),
        )
        val reconciledAutomaticEnabled = reconcileAutomaticBackup(automaticEnabled)
        backupUiState.value = backupUiState.value.copy(
            folderSelected = true,
            automaticEnabled = reconciledAutomaticEnabled,
            message = if (automaticEnabled && !reconciledAutomaticEnabled) {
                "Android could not schedule automatic backups."
            } else {
                null
            },
        )
    }

    private fun recordBackupEvent(code: BackupDiagnosticCode, detail: Int? = null) {
        backupDiagnosticLog.record(
            BackupDiagnosticEvent(System.currentTimeMillis().coerceAtLeast(0L), code, detail),
        )
    }

    private fun chooseBackupFolder() {
        backupFolderRequest.launch(null)
    }

    private fun refreshBackupInventory() {
        val library = backupLibrary ?: return
        activityScope.launch {
            val result = withContext(Dispatchers.IO) { library.inventory() }
            backupUiState.value = when (result) {
                is BackupOperationResult.Completed -> backupUiState.value.withInventory(result.value)
                is BackupOperationResult.Failed -> backupUiState.value.copy(
                    message = "The backup folder could not be read.",
                )
            }
        }
    }

    private fun setAutomaticBackups(enabled: Boolean) {
        if (!::backupAuthorization.isInitialized || backupLibrary == null) return
        val scheduler = automaticBackupScheduler()
        val effectiveEnabled = if (enabled) {
            AutomaticBackupPreferencePolicy.afterEnableRequest {
                runCatching { scheduler.schedule() }.getOrDefault(false)
            }
        } else {
            scheduler.cancel()
            false
        }
        backupAuthorization.setAutomaticEnabled(effectiveEnabled)
        backupUiState.value = backupUiState.value.copy(
            automaticEnabled = effectiveEnabled,
            message = if (enabled && !effectiveEnabled) "Android could not schedule automatic backups." else null,
        )
    }

    private fun reconcileAutomaticBackup(persistedEnabled: Boolean): Boolean {
        val jobs = getSystemService(JobScheduler::class.java)
        val service = ComponentName(this, AutomaticBackupJobService::class.java)
        val scheduler = AndroidChargingBackupScheduler(jobs, service)
        val hasScheduledJob = runCatching {
            jobs.getPendingJob(AndroidChargingBackupScheduler.JOB_ID)?.service == service
        }.getOrDefault(false)
        val effectiveEnabled = AutomaticBackupPreferencePolicy.reconcile(
            persistedEnabled,
            hasScheduledJob,
        ) {
            runCatching { scheduler.schedule() }.getOrDefault(false)
        }
        if (effectiveEnabled != persistedEnabled) {
            backupAuthorization.setAutomaticEnabled(effectiveEnabled)
        }
        return effectiveEnabled
    }

    private fun automaticBackupScheduler(): AndroidChargingBackupScheduler =
        AndroidChargingBackupScheduler(
            getSystemService(JobScheduler::class.java),
            ComponentName(this, AutomaticBackupJobService::class.java),
        )

    private fun exportBackup(passphrase: CharArray?) {
        val library = backupLibrary ?: return
        val secret = passphrase?.copyOf()
        activityScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { library.createManual(secret) }
            } finally {
                secret?.fill('\u0000')
            }
            backupUiState.value = backupUiState.value.copy(
                message = if (result is BackupOperationResult.Completed) {
                    recordBackupEvent(BackupDiagnosticCode.MANUAL_EXPORT_SUCCEEDED)
                    "Backup exported."
                } else {
                    recordBackupEvent(BackupDiagnosticCode.EXPORT_FAILED)
                    "Backup export failed without changing launcher state."
                },
            )
            refreshBackupInventory()
        }
    }

    private fun previewBackup(documentId: String, passphrase: CharArray?) {
        val library = backupLibrary ?: return
        val secret = passphrase?.copyOf()
        activityScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { library.preview(documentId, secret) }
            } finally {
                secret?.fill('\u0000')
            }
            if (result is BackupOperationResult.Completed) {
                stagedRestore = result.value
                val review = result.value.review
                backupUiState.value = backupUiState.value.copy(
                    review = BackupRestoreReviewSetting(
                        review.destinationCount,
                        review.moduleCount,
                        review.widgetRebindCount,
                        review.quarantinedInstanceCount,
                        review.profilesToReview,
                        review.profileSerials.map { it.value },
                    ),
                    recovery = null,
                    message = "Review the restore before applying it.",
                )
            } else {
                recordBackupEvent(BackupDiagnosticCode.PREVIEW_REJECTED)
                stagedRestore = null
                backupUiState.value = backupUiState.value.copy(
                    review = null,
                    message = "The backup could not be previewed. Check its passphrase and integrity.",
                )
            }
        }
    }

    private fun restoreBackup(selection: BackupRestoreSelectionSetting, passphrase: CharArray?) {
        val library = backupLibrary ?: return
        val staged = stagedRestore ?: return
        val secret = passphrase?.copyOf()
        activityScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    library.restore(
                        staged,
                        RestoreSelection(
                            launcherMap = selection.launcherMap,
                            themes = selection.themesAndAssets,
                            webAdapters = selection.webAdapters,
                            assets = selection.themesAndAssets,
                        ),
                        secret,
                    )
                }
            } finally {
                secret?.fill('\u0000')
            }
            if (result is BackupOperationResult.Completed) {
                recordBackupEvent(BackupDiagnosticCode.RESTORE_SUCCEEDED)
                stagedRestore = null
                backupUiState.value = backupUiState.value.copy(
                    review = null,
                    recovery = if (selection.launcherMap) {
                        BackupRecoverySetting(
                            result.value.widgetRebindCount,
                            result.value.quarantinedInstanceCount,
                            staged.review.profilesToReview,
                            staged.review.profileSerials.map { it.value },
                        )
                    } else {
                        null
                    },
                    message = if (selection.launcherMap) {
                        "Restore completed. Finish the recovery actions below."
                    } else {
                        "Selected backup categories restored."
                    },
                )
                runtime.onResume()
                themeController?.start()
            } else {
                recordBackupEvent(BackupDiagnosticCode.RESTORE_REJECTED)
                backupUiState.value = backupUiState.value.copy(
                    message = "Restore was rejected; the current launcher state is unchanged.",
                )
            }
            refreshBackupInventory()
        }
    }

    private fun reauthorizeRestoredDocuments() {
        activityScope.launch {
            searchAvailability.beginFileRequest()
            fileAuthorizationRequest.launch(Unit)
        }
    }

    private fun openAppPermissionSettings() {
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun cancelBackupRestore() {
        val stagedWasPresent = stagedRestore != null
        stagedRestore = null
        backupUiState.value = BackupRestoreLifecyclePolicy.cancel(
            stagedWasPresent,
            backupUiState.value,
        )
    }

    private fun exportSupportBundle() {
        pendingSupportBundle?.bytes?.fill(0)
        val snapshot = backupDiagnosticLog.snapshotForExport()
        pendingSupportBundle = PendingSupportBundle(
            bytes = RedactedSupportBundle.encode(
                generatedAtEpochMillis = System.currentTimeMillis().coerceAtLeast(0L),
                appVersion = BuildConfig.VERSION_NAME,
                archiveFormatMajor = PortableBackupArchiveCodec.FORMAT_MAJOR,
                events = snapshot.events,
            ),
            diagnosticSnapshot = snapshot,
        )
        supportBundleRequest.launch("quicklauncher-support.txt")
    }

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
        const val STATE_THEME_IMAGE_ACTION = "launcher.theme-image-action"
        const val STATE_THEME_FONT_ROLE = "launcher.theme-font-role"
        const val STATE_BACKUP_RESTORE_REVIEW = "launcher.backup-restore-review"
        const val STATE_BACKUP_RECOVERY = "launcher.backup-recovery"
        const val STATE_BACKUP_RECOVERY_WIDGETS = "launcher.backup-recovery-widgets"
        const val STATE_BACKUP_RECOVERY_QUARANTINE = "launcher.backup-recovery-quarantine"
        const val STATE_BACKUP_RECOVERY_PROFILES = "launcher.backup-recovery-profiles"
        const val STATE_BACKUP_RECOVERY_PROFILE_SERIALS = "launcher.backup-recovery-profile-serials"
        const val ACTION_CONSUMED_ENTRY = "org.quicklauncher.action.CONSUMED_ENTRY"
        const val THEME_ACCENT_CAPABILITY = "org.quicklauncher.capability/theme-accent"
    }
}

internal enum class ThemeImageAction {
    LAUNCHER_BACKGROUND,
    DESTINATION_BACKGROUND,
    DERIVED_THEME,
    WALLPAPER,
}

internal data class BackupUiState(
    val folderSelected: Boolean = false,
    val automaticEnabled: Boolean = false,
    val manual: List<BackupSnapshotSetting> = emptyList(),
    val automatic: List<BackupSnapshotSetting> = emptyList(),
    val preRestore: List<BackupSnapshotSetting> = emptyList(),
    val review: BackupRestoreReviewSetting? = null,
    val recovery: BackupRecoverySetting? = null,
    val message: String? = null,
) {
    fun withInventory(inventory: BackupInventory): BackupUiState = copy(
        manual = inventory.manual.map { document ->
            BackupSnapshotSetting(document.id, document.displayName, document.sizeLabel())
        },
        automatic = inventory.automatic.map { document ->
            BackupSnapshotSetting(document.id, document.displayName, document.sizeLabel())
        },
        preRestore = inventory.preRestore.map { document ->
            BackupSnapshotSetting(document.id, document.displayName, document.sizeLabel())
        },
    )
}

private fun org.quicklauncher.host.backup.library.BackupDocument.sizeLabel(): String =
    sizeBytes?.let { "$it bytes" } ?: "Size unavailable"

internal object BackupRestoreLifecyclePolicy {
    fun afterRecreation(state: BackupUiState, reviewWasPending: Boolean): BackupUiState =
        if (reviewWasPending) {
            state.copy(
                review = null,
                message = "Restore review expired after recreation. Preview the backup again.",
            )
        } else {
            state
        }

    fun cancel(stagedWasPresent: Boolean, state: BackupUiState): BackupUiState =
        if (stagedWasPresent || state.review != null) {
            state.copy(
                review = null,
                message = "Restore cancelled; no launcher state changed.",
            )
        } else {
            state
        }
}

internal object BackupRecoveryLifecyclePolicy {
    fun restore(
        present: Boolean,
        widgetsToRebind: Int,
        quarantined: Int,
        profilesToReview: Int,
        profileSerials: List<Long> = emptyList(),
    ): BackupRecoverySetting? = if (present) {
        BackupRecoverySetting(
            widgetsToRebind.coerceAtLeast(0),
            quarantined.coerceAtLeast(0),
            profilesToReview.coerceAtLeast(0),
            profileSerials.take(profilesToReview.coerceAtLeast(0)),
        )
    } else {
        null
    }
}

internal data class ThemeImportRequestState(
    val imageAction: ThemeImageAction,
    val fontRole: ThemeFontRole,
) {
    companion object {
        fun restore(imageAction: String?, fontRole: String?): ThemeImportRequestState =
            ThemeImportRequestState(
                ThemeImageAction.entries.singleOrNull { it.name == imageAction }
                    ?: ThemeImageAction.LAUNCHER_BACKGROUND,
                ThemeFontRole.entries.singleOrNull { it.name == fontRole }
                    ?: ThemeFontRole.BODY,
            )
    }
}

private fun defaultThemeControllerState(): ThemeControllerState = ThemeControllerState(
    profiles = BuiltInThemeProfiles.All,
    activeProfile = BuiltInThemeProfiles.System,
    previewProfile = null,
    backgrounds = emptyMap(),
    fontAssets = emptySet(),
    imageAssets = emptySet(),
    recoveryReason = org.quicklauncher.host.runtime.theme.ThemeRecoveryReason.NONE,
    generation = 0L,
)

private fun WallpaperResult.toUserMessage(): String = when (this) {
    WallpaperResult.Success -> "Wallpaper changed."
    WallpaperResult.Cancelled -> "Wallpaper change cancelled."
    WallpaperResult.Unavailable -> "Wallpaper changes are unavailable."
    WallpaperResult.Stale -> "Wallpaper preview expired. Choose the image again."
    WallpaperResult.Denied -> "Wallpaper access was denied."
    WallpaperResult.RecoverableFailure -> "Wallpaper was not changed. Try again."
}

@Composable
private fun WallpaperCropPreview(
    previewId: org.quicklauncher.contracts.domain.StableKey,
    assets: AndroidThemeAssetStore,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, previewId, assets) {
        value = assets.loadPreview(previewId)
    }
    DisposableEffect(bitmap) {
        onDispose { bitmap?.recycle() }
    }
    bitmap?.let { loaded ->
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = "Wallpaper crop preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
        )
    }
}

@Composable
internal fun ResolvedThemeBackground(
    resolved: ResolvedLauncherTheme,
    assets: AndroidThemeAssetStore,
    content: @Composable () -> Unit,
) {
    when (val definition = resolved.background.definition) {
        is BackgroundDefinition.Solid -> Box(
            Modifier.fillMaxSize().background(Color(definition.color.value)),
        ) { content() }
        is BackgroundDefinition.Gradient -> {
            val colors = remember(definition) { definition.colors.map { Color(it.value) } }
            val radians = remember(definition.angleDegrees) {
                Math.toRadians(definition.angleDegrees.toDouble())
            }
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val x = cos(radians).toFloat()
                    val y = sin(radians).toFloat()
                    val halfSpan = (abs(size.width * x) + abs(size.height * y)) / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawRect(
                        brush = Brush.linearGradient(
                            colors,
                            start = center - Offset(x * halfSpan, y * halfSpan),
                            end = center + Offset(x * halfSpan, y * halfSpan),
                        ),
                    )
                },
            ) { content() }
        }
        is BackgroundDefinition.Image -> {
            val bitmap by produceState<android.graphics.Bitmap?>(
                initialValue = null,
                key1 = definition.previewId,
                key2 = assets,
            ) {
                value = assets.loadPreview(definition.previewId)
            }
            DisposableEffect(bitmap) {
                onDispose { bitmap?.recycle() }
            }
            Box(Modifier.fillMaxSize().background(Color(definition.representativeColor.value))) {
                bitmap?.let { loaded ->
                    Image(
                        bitmap = loaded.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Color(definition.scrim.value).copy(alpha = definition.scrimOpacity),
                    ),
                )
                content()
            }
        }
    }
}

@Composable
internal fun rememberThemeTypography(
    resolved: ResolvedLauncherTheme,
    assets: AndroidThemeAssetStore,
): Typography {
    val roles = resolved.theme.fonts
    val imported by produceState<Map<org.quicklauncher.contracts.domain.StableKey, android.graphics.Typeface>>(
        initialValue = emptyMap(),
        key1 = roles,
        key2 = assets,
    ) {
        val selections = listOf(roles.display, roles.headline, roles.title, roles.body, roles.label)
            .mapNotNull { role -> role.assetToken?.let { token -> token to role } }
            .distinctBy { it.first }
            .take(ThemeFontRole.entries.size)
        value = selections.mapNotNull { (token, role) ->
            assets.loadTypeface(token, role.weight, role.italic)?.let { token to it }
        }.toMap()
    }
    fun family(role: org.quicklauncher.contracts.ui.ResolvedFontRole): FontFamily? =
        role.assetToken?.let(imported::get)?.let(::FontFamily)
    val base = remember { Typography() }
    return remember(roles, imported) {
        val display = family(roles.display)
        val headline = family(roles.headline)
        val title = family(roles.title)
        val body = family(roles.body)
        val label = family(roles.label)
        base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = display),
            displayMedium = base.displayMedium.copy(fontFamily = display),
            displaySmall = base.displaySmall.copy(fontFamily = display),
            headlineLarge = base.headlineLarge.copy(fontFamily = headline),
            headlineMedium = base.headlineMedium.copy(fontFamily = headline),
            headlineSmall = base.headlineSmall.copy(fontFamily = headline),
            titleLarge = base.titleLarge.copy(fontFamily = title),
            titleMedium = base.titleMedium.copy(fontFamily = title),
            titleSmall = base.titleSmall.copy(fontFamily = title),
            bodyLarge = base.bodyLarge.copy(fontFamily = body),
            bodyMedium = base.bodyMedium.copy(fontFamily = body),
            bodySmall = base.bodySmall.copy(fontFamily = body),
            labelLarge = base.labelLarge.copy(fontFamily = label),
            labelMedium = base.labelMedium.copy(fontFamily = label),
            labelSmall = base.labelSmall.copy(fontFamily = label),
        )
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

internal data class FileAuthorizationResult(val uri: Uri, val flags: Int)

internal class FileAuthorizationContract : ActivityResultContract<Unit, FileAuthorizationResult?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }

    override fun parseResult(resultCode: Int, intent: Intent?): FileAuthorizationResult? {
        if (resultCode != Activity.RESULT_OK) return null
        val uri = intent?.data?.takeIf { it.scheme == "content" } ?: return null
        val flags = intent.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return null
        return FileAuthorizationResult(uri, flags)
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
    resolvedTheme: ResolvedLauncherTheme,
    launcherResolvedTheme: ResolvedLauncherTheme,
    destinationThemes: Map<DestinationId, ResolvedLauncherTheme>,
    iconPackResolver: AndroidIconPackResolver,
    themeAssets: AndroidThemeAssetStore,
    phaseFiveHost: PhaseFiveHost,
    secureOverlayProtection: SecureOverlayProtection,
    widgetPlatform: AndroidWidgetPlatform?,
    widgetCoordinator: WidgetCoordinator?,
    widgetTransientEvents: SharedFlow<WidgetTransientEvent>,
    activityScope: CoroutineScope,
    searchPresentations: org.quicklauncher.host.runtime.composition.SearchPresentationSource,
    onSearchProviderEnabled: (ContributionId, Boolean) -> Unit,
    onLocalSearchHistory: (Boolean) -> Unit,
    themeState: ThemeControllerState,
    availableIconPacks: kotlinx.coroutines.flow.StateFlow<List<AvailableIconPack>>,
    themeMessage: kotlinx.coroutines.flow.StateFlow<String?>,
    wallpaperPreview: kotlinx.coroutines.flow.StateFlow<WallpaperPreview?>,
    wallpaperResult: kotlinx.coroutines.flow.StateFlow<WallpaperResult?>,
    onSelectThemeProfile: (ThemeProfileId) -> Unit,
    onPreviewThemeProfile: (ThemeProfileId?) -> Unit,
    onDeleteThemeProfile: (ThemeProfileId) -> Unit,
    onCreateThemeProfile: (ThemeProfileCreation) -> Unit,
    onSaveManualPalette: (ManualPaletteValues) -> Unit,
    onSaveThemeOverride: (ContributionId, ArgbColor?) -> Unit,
    onImportFont: (ThemeFontRole) -> Unit,
    onSelectIconPack: (IconPackSelection?) -> Unit,
    onRequestThemeImage: (ThemeImageAction) -> Unit,
    onSetPresetBackground: (Boolean, String) -> Unit,
    onConfirmWallpaper: (WallpaperTarget) -> Unit,
    onWallpaperCrop: (WallpaperCrop) -> Unit,
    onCancelWallpaper: () -> Unit,
    backupUiState: kotlinx.coroutines.flow.StateFlow<BackupUiState>,
    onChooseBackupFolder: () -> Unit,
    onAutomaticBackups: (Boolean) -> Unit,
    onExportBackup: (CharArray?) -> Unit,
    onPreviewBackup: (String, CharArray?) -> Unit,
    onRestoreBackup: (BackupRestoreSelectionSetting, CharArray?) -> Unit,
    onCancelBackupRestore: () -> Unit,
    onExportSupportBundle: () -> Unit,
    onReauthorizeDocuments: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
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
    val searchActive by searchPresentations.hasActiveQuery.collectAsState()
    val iconPacks by availableIconPacks.collectAsState()
    val currentThemeMessage by themeMessage.collectAsState()
    val currentWallpaperPreview by wallpaperPreview.collectAsState()
    val currentWallpaperResult by wallpaperResult.collectAsState()
    val currentBackupUiState by backupUiState.collectAsState()
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
    var onboardingInstalled by remember(preferenceState.onboardingState) {
        mutableStateOf(preferenceState.onboardingState == OnboardingState.IN_PROGRESS)
    }
    var onboardingMessage by remember { mutableStateOf<String?>(null) }
    PredictiveBackHandler(
        enabled = editorState.screen != EditorScreen.CLOSED ||
            phaseFiveOverlay != PhaseFiveOverlay.NONE ||
            searchActive ||
            currentWallpaperPreview != null ||
            currentBackupUiState.review != null ||
            (state.surface != LauncherSurface.SAFE_LAYOUT && state.surface != LauncherSurface.SELECTED_LAYOUT),
    ) { progress ->
        org.quicklauncher.host.runtime.navigation.PredictiveBackCommitPolicy.collect(progress) {
            if (currentWallpaperPreview != null) {
                onCancelWallpaper()
            } else if (currentBackupUiState.review != null) {
                onCancelBackupRestore()
            } else if (searchActive) {
                searchPresentations.dismissAll()
            } else if (phaseFiveOverlay != PhaseFiveOverlay.NONE) {
                phaseFiveHost.closeOverlay()
            } else if (editorState.screen != EditorScreen.CLOSED) {
                editor.dispatch(EditorAction.Close)
            } else {
                runtime.closeOverlay()
            }
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
                    resolvedTheme,
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
            resolvedTheme,
            launcherResolvedTheme,
            destinationThemes,
            iconPackResolver,
            themeAssets,
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
            searchPresentations = searchPresentations,
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
            searchProviders = productionRegistry.entries
                .filterIsInstance<RegisteredSearchProvider<*>>()
                .map { entry ->
                    SearchProviderSetting(
                        entry.descriptor.metadata.id,
                        entry.descriptor.metadata.displayName.value,
                        entry.descriptor.metadata.id in preferenceState.enabledSearchProviders,
                    )
                },
            localSearchHistory = preferenceState.historyPolicy == HistoryPolicy.LOCAL,
            onSearchProviderEnabled = onSearchProviderEnabled,
            onLocalSearchHistory = onLocalSearchHistory,
            themeProfiles = themeState.profiles.map { profile ->
                ThemeProfileSetting(
                    profile.id,
                    profile.name,
                    profile.id == themeState.activeProfile.id,
                    profile.id == themeState.previewProfile?.id,
                    BuiltInThemeProfiles.find(profile.id) == null,
                )
            },
            themeMessage = currentThemeMessage ?: if (
                themeState.recoveryReason != org.quicklauncher.host.runtime.theme.ThemeRecoveryReason.NONE
            ) "Stored theme was invalid; the system profile is active." else null,
            onSelectThemeProfile = onSelectThemeProfile,
            onPreviewThemeProfile = onPreviewThemeProfile,
            onDeleteThemeProfile = onDeleteThemeProfile,
            onCreateThemeProfile = onCreateThemeProfile,
            manualPalette = ManualPaletteSetting(
                themeState.activeProfile.id,
                resolvedTheme.theme.colors.primary,
                resolvedTheme.theme.colors.onPrimary,
                resolvedTheme.theme.colors.surface,
                resolvedTheme.theme.colors.onSurface,
            ),
            onSaveManualPalette = onSaveManualPalette,
            themeOverrides = productionRegistry.entries.asSequence()
                .filter { entry ->
                    CapabilityId.parse(MainActivity.THEME_ACCENT_CAPABILITY) in
                        entry.descriptor.metadata.providedCapabilities
                }
                .map { entry ->
                    ThemeOverrideSetting(
                        contributionId = entry.descriptor.metadata.id,
                        label = entry.descriptor.metadata.displayName.value,
                        accent = themeState.activeProfile.overrides
                            .singleOrNull { it.contributionId == entry.descriptor.metadata.id }
                            ?.accent,
                    )
                }
                .sortedBy(ThemeOverrideSetting::label)
                .take(ThemeProfile.MAX_MODULE_OVERRIDES)
                .toList(),
            onSaveThemeOverride = onSaveThemeOverride,
            onImportFont = onImportFont,
            iconPacks = iconPacks.mapIndexed { index, pack ->
                IconPackSetting(
                    IconPackSelection(pack.packageName, pack.dialect),
                    "Installed ${pack.dialect.name.lowercase()} icon pack ${index + 1}",
                )
            },
            onSelectIconPack = onSelectIconPack,
            onLauncherBackground = { preset -> onSetPresetBackground(false, preset) },
            onDestinationBackground = { preset -> onSetPresetBackground(true, preset) },
            onImportLauncherBackground = {
                onRequestThemeImage(ThemeImageAction.LAUNCHER_BACKGROUND)
            },
            onImportDestinationBackground = {
                onRequestThemeImage(ThemeImageAction.DESTINATION_BACKGROUND)
            },
            onDeriveThemeFromImage = { onRequestThemeImage(ThemeImageAction.DERIVED_THEME) },
            wallpaperConfirmationVisible = currentWallpaperPreview != null,
            wallpaperPreviewContent = currentWallpaperPreview?.let { preview ->
                { WallpaperCropPreview(preview.previewId, themeAssets) }
            },
            wallpaperCrop = currentWallpaperPreview?.crop
                ?: org.quicklauncher.host.settings.FullWallpaperCrop,
            wallpaperResult = currentWallpaperResult?.toUserMessage(),
            onChooseWallpaper = { onRequestThemeImage(ThemeImageAction.WALLPAPER) },
            onWallpaperCrop = onWallpaperCrop,
            onConfirmWallpaper = onConfirmWallpaper,
            onCancelWallpaper = onCancelWallpaper,
            backupSupported = true,
            backupFolderSelected = currentBackupUiState.folderSelected,
            automaticBackupsEnabled = currentBackupUiState.automaticEnabled,
            manualBackups = currentBackupUiState.manual,
            automaticBackups = currentBackupUiState.automatic,
            preRestoreBackups = currentBackupUiState.preRestore,
            backupMessage = currentBackupUiState.message,
            backupRestoreReview = currentBackupUiState.review,
            backupRecovery = currentBackupUiState.recovery,
            onChooseBackupFolder = onChooseBackupFolder,
            onAutomaticBackups = onAutomaticBackups,
            onExportBackup = onExportBackup,
            onPreviewBackup = onPreviewBackup,
            onRestoreSelection = onRestoreBackup,
            onCancelRestore = onCancelBackupRestore,
            onExportSupportBundle = onExportSupportBundle,
            onReauthorizeDocuments = onReauthorizeDocuments,
            onOpenPermissionSettings = onOpenPermissionSettings,
            onContinueWidgetRebinding = runtime::closeOverlay,
            onReviewQuarantine = { runtime.open(LauncherSurface.RECOVERY) },
            onReviewProfiles = { runtime.open(LauncherSurface.APP_RECOVERY) },
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
