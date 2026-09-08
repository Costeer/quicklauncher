package org.quicklauncher.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.host.data.preferences.LauncherPreferencesStore
import org.quicklauncher.host.data.preferences.OnboardingState
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.platform.apps.AndroidAppPlatform
import org.quicklauncher.host.platform.diagnostics.AndroidDiagnosticLogFactory
import org.quicklauncher.host.platform.persistence.AndroidLauncherPreferencesFactory
import org.quicklauncher.host.platform.persistence.AndroidLauncherStoreFactory
import org.quicklauncher.host.platform.role.AndroidHomeRoleGateway
import org.quicklauncher.host.runtime.DefaultLauncherRuntime
import org.quicklauncher.host.runtime.LauncherEntry
import org.quicklauncher.host.runtime.LauncherRuntime
import org.quicklauncher.host.runtime.LauncherSurface
import org.quicklauncher.host.runtime.SafeLayoutSurface
import org.quicklauncher.host.runtime.catalog.DefaultAppCatalog
import org.quicklauncher.host.runtime.catalog.StoreAppCatalogOverrideSource
import org.quicklauncher.host.runtime.catalog.UnavailableAppPlatform
import org.quicklauncher.host.runtime.diagnostics.BoundedDiagnosticLog
import org.quicklauncher.host.runtime.diagnostics.DiagnosticRecord
import org.quicklauncher.host.runtime.diagnostics.DiagnosticStorage
import org.quicklauncher.host.runtime.permissions.HomeRoleRequestOutcome
import org.quicklauncher.host.runtime.permissions.HomeRoleRequestReason
import org.quicklauncher.host.runtime.permissions.HomeRoleState
import org.quicklauncher.host.settings.LauncherSettingsSurface
import org.quicklauncher.host.settings.AppRecoverySurface
import org.quicklauncher.host.settings.MapOverviewSurface
import org.quicklauncher.host.settings.RecoverySurface

class MainActivity : ComponentActivity() {
    private val activityJob = SupervisorJob()
    private val activityScope = CoroutineScope(activityJob + Dispatchers.Main.immediate)
    private val activityInstanceId = HomeEntryRecorder.newActivityInstance()
    private lateinit var launcherStore: LauncherStore
    private var preferences: LauncherPreferencesStore? = null
    private lateinit var runtime: LauncherRuntime
    private var startup: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val roleGateway = AndroidHomeRoleGateway(this)
        var durableStoreAvailable = true
        launcherStore = try {
            AndroidLauncherStoreFactory(this).open(DATABASE_NAME)
        } catch (_: Exception) {
            durableStoreAvailable = false
            InMemoryLauncherStore()
        }
        preferences = try {
            AndroidLauncherPreferencesFactory(this).open(PREFERENCES_NAME)
        } catch (_: Exception) {
            null
        }
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
        )

        val isHome = intent.isHomeEntry()
        recordEntry(isHome, fromNewIntent = false)
        consumeEntryIntent()
        val restoredDestination = savedInstanceState
            ?.getString(STATE_DESTINATION)
            ?.let { value -> runCatching { DestinationId.parse(value) }.getOrNull() }
        startup = activityScope.launch {
            runtime.start(
                HomeEntryPolicy.initial(
                    isHomeIntent = isHome,
                    hasSavedState = savedInstanceState != null,
                ),
                restoredDestination,
            )
            HomeEntryRecorder.recordRuntimeState(runtime.state.value)
        }

        setContent {
            MaterialTheme {
                QuicklauncherRoot(
                    runtime,
                    preferences,
                    activityScope,
                )
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
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        activityScope.cancel()
        if (::runtime.isInitialized) closeBestEffort(runtime)
        closeBestEffort(preferences)
        if (::launcherStore.isInitialized) closeBestEffort(launcherStore as? AutoCloseable)
        super.onDestroy()
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

    private companion object {
        const val DATABASE_NAME = "quicklauncher.db"
        const val PREFERENCES_NAME = "launcher-preferences.pb"
        const val STATE_DESTINATION = "launcher.current-destination"
        const val ACTION_CONSUMED_ENTRY = "org.quicklauncher.action.CONSUMED_ENTRY"
    }
}

@Composable
private fun QuicklauncherRoot(
    runtime: LauncherRuntime,
    preferences: LauncherPreferencesStore?,
    activityScope: CoroutineScope,
) {
    val state by runtime.state.collectAsState()
    LaunchedEffect(state) {
        HomeEntryRecorder.recordRuntimeState(state)
    }
    var fallbackRequired by remember { mutableStateOf(false) }
    PredictiveBackHandler(enabled = state.surface != LauncherSurface.SAFE_LAYOUT) { progress ->
        progress.collect()
        runtime.closeOverlay()
    }

    fun requestRole(reason: HomeRoleRequestReason) {
        activityScope.launch {
            if (reason == HomeRoleRequestReason.ONBOARDING_PREVIEW_CONFIRMED) {
                preferences.setOnboardingBestEffort(OnboardingState.IN_PROGRESS)
            }
            when (runtime.requestHomeRole(reason)) {
                HomeRoleRequestOutcome.Granted,
                HomeRoleRequestOutcome.AlreadyHeld,
                -> {
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
        LauncherSurface.MAP_OVERVIEW -> MapOverviewSurface(
            destinations = state.destinations,
            onSelectDestination = { destination ->
                activityScope.launch { runtime.selectDestination(destination) }
            },
            onClose = runtime::closeOverlay,
        )
        LauncherSurface.SETTINGS -> LauncherSettingsSurface(
            homeRoleState = state.homeRoleState,
            fallbackAvailable = fallbackRequired && state.homeRoleState != HomeRoleState.HELD,
            onRequestHomeRole = { requestRole(HomeRoleRequestReason.SETTINGS_USER_ACTION) },
            onOpenHomeSettings = { runtime.openHomeSettingsFallback() },
            onOpenAppRecovery = { runtime.open(LauncherSurface.APP_RECOVERY) },
            onClose = runtime::closeOverlay,
        )
        LauncherSurface.APP_RECOVERY -> AppRecoverySurface(
            apps = state.settingsApps,
            onLaunch = { identity -> activityScope.launch { runtime.launch(identity) } },
            onClose = runtime::closeOverlay,
        )
        LauncherSurface.RECOVERY -> RecoverySurface(
            instances = state.recoveryInstances,
            onRetry = { instance -> activityScope.launch { runtime.retryRenderer(instance) } },
            onClose = runtime::closeOverlay,
        )
    }
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
