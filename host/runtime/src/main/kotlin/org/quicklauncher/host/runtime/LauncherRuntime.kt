package org.quicklauncher.host.runtime

import java.util.Collections
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceStatus
import org.quicklauncher.host.data.store.ModuleQuarantineOrigin
import org.quicklauncher.host.data.store.StoreRejection
import org.quicklauncher.host.data.store.StoreRevision
import org.quicklauncher.host.runtime.catalog.AppCatalog
import org.quicklauncher.host.runtime.catalog.AppCatalogSnapshot
import org.quicklauncher.host.runtime.catalog.AppCatalogStatus
import org.quicklauncher.host.runtime.catalog.AppLaunchResult
import org.quicklauncher.host.runtime.catalog.AppProfileKind
import org.quicklauncher.host.runtime.diagnostics.DiagnosticEvent
import org.quicklauncher.host.runtime.diagnostics.DiagnosticEventCode
import org.quicklauncher.host.runtime.diagnostics.DiagnosticLog
import org.quicklauncher.host.runtime.permissions.ContextualHomeRoleRequest
import org.quicklauncher.host.runtime.permissions.HomeRoleCoordinator
import org.quicklauncher.host.runtime.permissions.HomeRoleGateway
import org.quicklauncher.host.runtime.permissions.HomeRoleRequestOutcome
import org.quicklauncher.host.runtime.permissions.HomeRoleRequestReason
import org.quicklauncher.host.runtime.permissions.HomeRoleState
import org.quicklauncher.host.runtime.permissions.HomeSettingsOpenResult

enum class LauncherEntry {
    HOME,
    APP_ICON,
    RESTORE,
}

enum class LauncherRuntimeStatus {
    LOADING,
    READY,
    ERROR,
}

enum class LauncherSurface {
    SAFE_LAYOUT,
    SELECTED_LAYOUT,
    MAP_OVERVIEW,
    SETTINGS,
    APP_RECOVERY,
    RECOVERY,
}

data class LauncherDestination(
    val id: DestinationId,
    val name: String,
    val x: Long,
    val y: Long,
    val start: Boolean,
    val current: Boolean,
)

data class RecoveryInstance(
    val id: ModuleInstanceId,
    val contributionLabel: String,
    val code: String,
    val retryAllowed: Boolean,
)

data class LauncherApp(
    val identity: AppActivityIdentity,
    val label: String,
    val workProfile: Boolean,
    val favorite: Boolean = false,
    val collectionVisible: Boolean = true,
)

class LauncherRuntimeState(
    val status: LauncherRuntimeStatus,
    val currentDestinationId: DestinationId?,
    val surface: LauncherSurface,
    val localQuery: String,
    val homeRoleState: HomeRoleState,
    val catalogStatus: AppCatalogStatus,
    visibleApps: Collection<LauncherApp>,
    settingsApps: Collection<LauncherApp> = visibleApps,
    destinations: Collection<LauncherDestination>,
    recoveryInstances: Collection<RecoveryInstance>,
    val errorCode: String? = null,
    val storeRevision: StoreRevision = StoreRevision.ZERO,
) {
    val visibleApps: List<LauncherApp> = immutableList(visibleApps)
    val settingsApps: List<LauncherApp> = immutableList(settingsApps)
    val destinations: List<LauncherDestination> = immutableList(destinations)
    val recoveryInstances: List<RecoveryInstance> = immutableList(recoveryInstances)

    init {
        require((status == LauncherRuntimeStatus.ERROR) == (errorCode != null)) {
            "Only an error runtime state may carry an error code"
        }
        require(errorCode == null || errorCode.isNotBlank()) {
            "Launcher runtime error code must be null or nonblank"
        }
    }

    companion object {
        val Loading: LauncherRuntimeState = LauncherRuntimeState(
            status = LauncherRuntimeStatus.LOADING,
            currentDestinationId = null,
            surface = LauncherSurface.SAFE_LAYOUT,
            localQuery = "",
            homeRoleState = HomeRoleState.UNAVAILABLE,
            catalogStatus = AppCatalogStatus.LOADING,
            visibleApps = emptyList(),
            settingsApps = emptyList(),
            destinations = emptyList(),
            recoveryInstances = emptyList(),
        )
    }
}

interface LauncherRuntime : AutoCloseable {
    val state: StateFlow<LauncherRuntimeState>

    suspend fun start(entry: LauncherEntry, restoredDestinationId: DestinationId? = null)

    suspend fun onHomeIntent()

    suspend fun onResume()

    fun open(surface: LauncherSurface)

    fun closeOverlay()

    fun setLocalQuery(query: String)

    suspend fun selectDestination(destinationId: DestinationId): Boolean

    suspend fun launch(identity: AppActivityIdentity): AppLaunchResult

    suspend fun requestHomeRole(reason: HomeRoleRequestReason): HomeRoleRequestOutcome

    fun openHomeSettingsFallback(): HomeSettingsOpenResult

    suspend fun retryRenderer(instanceId: ModuleInstanceId): StoreRejection?

    suspend fun restoreSelectedLayout(
        instanceId: ModuleInstanceId,
        restore: suspend () -> Unit,
    ): RendererRestoreResult

    /** Durably isolates the exact layout or block renderer that failed in a live composition. */
    suspend fun reportRendererFailure(
        instanceId: ModuleInstanceId,
        failure: RuntimeException,
    ): StoreRejection?
}

fun interface SelectedLayoutRestorer {
    suspend fun restore(instanceId: ModuleInstanceId)
}

/** Owns launcher startup, Home re-entry, safe navigation, and recovery policy. */
class DefaultLauncherRuntime(
    private val store: LauncherStore,
    private val catalog: AppCatalog,
    roleGateway: HomeRoleGateway,
    private val diagnostics: DiagnosticLog,
    appVersion: String,
    parentScope: CoroutineScope,
    private val selectedLayoutRestorer: SelectedLayoutRestorer? = null,
) : LauncherRuntime {
    private val closed = AtomicBoolean(false)
    private val transitionMutex = Mutex()
    private val roleCoordinator = HomeRoleCoordinator(roleGateway)
    private val provisioner = SafeLayoutProvisioner(store)
    private val recovery = RecoveryController(store, diagnostics, appVersion)
    private val ownedJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownedJob)
    private val mutableState = MutableStateFlow(LauncherRuntimeState.Loading)
    private var lastSnapshot: LauncherSnapshot? = null
    private var latestCatalog: AppCatalogSnapshot = AppCatalogSnapshot.Loading

    override val state: StateFlow<LauncherRuntimeState> = mutableState.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            catalog.state.collect { catalogState ->
                latestCatalog = catalogState
                val snapshot = lastSnapshot
                if (snapshot != null && mutableState.value.status == LauncherRuntimeStatus.READY) {
                    publish(snapshot, mutableState.value.currentDestinationId)
                }
            }
        }
    }

    override suspend fun start(entry: LauncherEntry, restoredDestinationId: DestinationId?) {
        checkOpen()
        transitionMutex.withLock {
            mutableState.value = LauncherRuntimeState.Loading
            refreshCatalogForSafeSurface()
            try {
                provisioner.ensureAvailable()
                store.reconcileConfigurations()
                var snapshot = store.read()
                val requested = when (entry) {
                    LauncherEntry.HOME -> snapshot.startDestinationId
                    LauncherEntry.APP_ICON,
                    LauncherEntry.RESTORE,
                    -> restoredDestinationId?.takeIf { candidate ->
                        snapshot.destinations.any { it.id == candidate }
                    } ?: snapshot.startDestinationId
                }
                snapshot = restoreSelectedOnStartup(snapshot, requested)
                lastSnapshot = snapshot
                val current = when (entry) {
                    LauncherEntry.HOME -> snapshot.startDestinationId
                    LauncherEntry.APP_ICON,
                    LauncherEntry.RESTORE,
                    -> restoredDestinationId?.takeIf { candidate ->
                        snapshot.destinations.any { it.id == candidate }
                    } ?: snapshot.startDestinationId
                }
                publish(snapshot, current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                mutableState.value = errorState(STARTUP_FAILED)
            }
        }
    }

    private suspend fun restoreSelectedOnStartup(
        snapshot: LauncherSnapshot,
        destinationId: DestinationId?,
    ): LauncherSnapshot {
        val restorer = selectedLayoutRestorer ?: return snapshot
        val selected = snapshot.destinationLayouts.singleOrNull { layout ->
            layout.destinationId == destinationId && layout.selected
        } ?: return snapshot
        if (selected.layoutContributionId == SafeLayoutIds.CONTRIBUTION) return snapshot
        recovery.restoreSelectedLayout(selected.layoutInstanceId) {
            restorer.restore(selected.layoutInstanceId)
        }
        return store.read()
    }

    override suspend fun onHomeIntent() {
        checkOpen()
        transitionMutex.withLock {
            try {
                val snapshot = store.read()
                lastSnapshot = snapshot
                mutableState.update { current ->
                    buildState(
                        snapshot = snapshot,
                        currentDestinationId = snapshot.startDestinationId,
                        surface = homeSurface(snapshot, snapshot.startDestinationId),
                        query = "",
                        roleState = roleCoordinator.refresh(),
                        catalogState = latestCatalog,
                        priorError = current.errorCode,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                mutableState.value = errorState(
                    code = STARTUP_FAILED,
                    surface = LauncherSurface.SAFE_LAYOUT,
                    query = "",
                )
            }
        }
    }

    override suspend fun onResume() {
        checkOpen()
        transitionMutex.withLock {
            refreshCatalogForSafeSurface()
            try {
                val snapshot = store.read()
                lastSnapshot = snapshot
                publish(snapshot, mutableState.value.currentDestinationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                val current = mutableState.value
                mutableState.value = errorState(
                    code = STARTUP_FAILED,
                    surface = current.surface,
                    query = current.localQuery,
                )
            }
        }
    }

    override fun open(surface: LauncherSurface) {
        checkOpen()
        mutableState.update { current ->
            if (current.status == LauncherRuntimeStatus.LOADING) current else copyState(current, surface = surface)
        }
    }

    override fun closeOverlay() {
        val current = mutableState.value.currentDestinationId
        open(lastSnapshot?.let { homeSurface(it, current) } ?: LauncherSurface.SAFE_LAYOUT)
    }

    override fun setLocalQuery(query: String) {
        checkOpen()
        val bounded = query.take(MAXIMUM_LOCAL_QUERY_LENGTH)
        mutableState.update { current ->
            if (current.status == LauncherRuntimeStatus.LOADING) {
                current
            } else {
                copyState(current, query = bounded)
            }
        }
    }

    override suspend fun selectDestination(destinationId: DestinationId): Boolean {
        checkOpen()
        return transitionMutex.withLock {
            val current = mutableState.value
            var usedRetainedSnapshot = false
            val snapshot = try {
                store.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                usedRetainedSnapshot = true
                lastSnapshot ?: return@withLock false
            }
            if (snapshot.destinations.none { it.id == destinationId }) {
                false
            } else {
                lastSnapshot = snapshot
                mutableState.value = buildState(
                    snapshot = snapshot,
                    currentDestinationId = destinationId,
                    surface = homeSurface(snapshot, destinationId),
                    query = "",
                    roleState = roleCoordinator.refresh(),
                    catalogState = latestCatalog,
                    priorError = if (usedRetainedSnapshot) {
                        current.errorCode ?: STARTUP_FAILED
                    } else {
                        null
                    },
                )
                true
            }
        }
    }

    override suspend fun launch(identity: AppActivityIdentity): AppLaunchResult {
        checkOpen()
        return catalog.launch(identity)
    }

    override suspend fun requestHomeRole(
        reason: HomeRoleRequestReason,
    ): HomeRoleRequestOutcome {
        checkOpen()
        safeDiagnostic(DiagnosticEvent(DiagnosticEventCode.HOME_ROLE_REQUESTED))
        val outcome = roleCoordinator.request(ContextualHomeRoleRequest(reason))
        refreshRoleState()
        return outcome
    }

    override fun openHomeSettingsFallback(): HomeSettingsOpenResult {
        checkOpen()
        val result = roleCoordinator.openHomeSettingsFallback()
        if (result == HomeSettingsOpenResult.Opened) {
            scope.launch {
                safeDiagnostic(DiagnosticEvent(DiagnosticEventCode.HOME_ROLE_FALLBACK_OPENED))
            }
        }
        return result
    }

    override suspend fun retryRenderer(instanceId: ModuleInstanceId): StoreRejection? {
        checkOpen()
        return transitionMutex.withLock {
            val restorer = selectedLayoutRestorer ?: return@withLock StoreRejection(
                org.quicklauncher.host.data.store.StoreRejectionCode.RECOVERY_STATE_MISMATCH,
                "Renderer retry is unavailable until the contribution renderer is installed",
            )
            val snapshot = store.read()
            val retained = snapshot.destinationLayouts.singleOrNull {
                it.layoutInstanceId == instanceId
            } ?: return@withLock StoreRejection(
                org.quicklauncher.host.data.store.StoreRejectionCode.RECOVERY_STATE_MISMATCH,
                "Renderer retry requires a retained layout root",
            )
            when (
                val result = store.commit(
                    LauncherTransaction(
                        snapshot.revision,
                        listOf(
                            LauncherEdit.RetryRenderer(instanceId),
                            LauncherEdit.SelectLayout(retained.destinationId, instanceId),
                        ),
                    ),
                )
            ) {
                is CommitResult.Committed -> {
                    val restoreResult = recovery.restoreSelectedLayout(instanceId) {
                        restorer.restore(instanceId)
                    }
                    val after = store.read()
                    lastSnapshot = after
                    publish(after, retained.destinationId)
                    when (restoreResult) {
                        RendererRestoreResult.Restored -> {
                            safeDiagnostic(DiagnosticEvent(DiagnosticEventCode.INSTANCE_RESET, instanceId))
                            null
                        }
                        is RendererRestoreResult.SafeFallback -> null
                        is RendererRestoreResult.Rejected -> restoreResult.rejection
                    }
                }
                is CommitResult.Rejected -> result.reason
            }
        }
    }

    override suspend fun restoreSelectedLayout(
        instanceId: ModuleInstanceId,
        restore: suspend () -> Unit,
    ): RendererRestoreResult {
        checkOpen()
        val result = recovery.restoreSelectedLayout(instanceId, restore)
        val snapshot = store.read()
        lastSnapshot = snapshot
        publish(snapshot, mutableState.value.currentDestinationId)
        return result
    }

    override suspend fun reportRendererFailure(
        instanceId: ModuleInstanceId,
        failure: RuntimeException,
    ): StoreRejection? {
        checkOpen()
        return transitionMutex.withLock {
            val rejection = recovery.reportRendererFailure(instanceId, failure)
            val snapshot = store.read()
            lastSnapshot = snapshot
            publish(snapshot, mutableState.value.currentDestinationId)
            rejection
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        catalog.close()
    }

    private fun publish(snapshot: LauncherSnapshot, requestedDestinationId: DestinationId?) {
        val current = requestedDestinationId?.takeIf { id -> snapshot.destinations.any { it.id == id } }
            ?: snapshot.startDestinationId
        mutableState.update { prior ->
            buildState(
                snapshot = snapshot,
                currentDestinationId = current,
                surface = if (
                    prior.status == LauncherRuntimeStatus.LOADING ||
                    prior.surface == LauncherSurface.SAFE_LAYOUT ||
                    prior.surface == LauncherSurface.SELECTED_LAYOUT
                ) homeSurface(snapshot, current) else prior.surface,
                query = if (prior.status == LauncherRuntimeStatus.LOADING) "" else prior.localQuery,
                roleState = roleCoordinator.refresh(),
                catalogState = latestCatalog,
                priorError = null,
            )
        }
    }

    private fun homeSurface(
        snapshot: LauncherSnapshot,
        destinationId: DestinationId?,
    ): LauncherSurface {
        if (selectedLayoutRestorer == null) return LauncherSurface.SAFE_LAYOUT
        val selected = snapshot.destinationLayouts.singleOrNull {
            it.destinationId == destinationId && it.selected
        } ?: return LauncherSurface.SAFE_LAYOUT
        if (selected.layoutContributionId == SafeLayoutIds.CONTRIBUTION) {
            return LauncherSurface.SAFE_LAYOUT
        }
        val instance = snapshot.moduleInstances.singleOrNull { it.id == selected.layoutInstanceId }
        return if (instance?.status == ModuleInstanceStatus.Active) {
            LauncherSurface.SELECTED_LAYOUT
        } else {
            LauncherSurface.SAFE_LAYOUT
        }
    }

    private fun buildState(
        snapshot: LauncherSnapshot,
        currentDestinationId: DestinationId?,
        surface: LauncherSurface,
        query: String,
        roleState: HomeRoleState,
        catalogState: AppCatalogSnapshot,
        priorError: String?,
    ): LauncherRuntimeState {
        val destinations = snapshot.destinations.map { destination ->
            LauncherDestination(
                id = destination.id,
                name = destination.name,
                x = destination.coordinate.x,
                y = destination.coordinate.y,
                start = destination.id == snapshot.startDestinationId,
                current = destination.id == currentDestinationId,
            )
        }
        val recoveries = snapshot.moduleInstances.mapNotNull { instance ->
            val quarantine = instance.status as? ModuleInstanceStatus.Quarantined ?: return@mapNotNull null
            if (instance.contributionId == SafeLayoutIds.CONTRIBUTION) return@mapNotNull null
            RecoveryInstance(
                id = instance.id,
                contributionLabel = instance.contributionId.value,
                code = quarantine.code,
                retryAllowed = quarantine.origin == ModuleQuarantineOrigin.RENDERER &&
                    selectedLayoutRestorer != null,
            )
        }
        val status = if (priorError == null) LauncherRuntimeStatus.READY else LauncherRuntimeStatus.ERROR
        return LauncherRuntimeState(
            status = status,
            currentDestinationId = currentDestinationId,
            surface = surface,
            localQuery = query,
            homeRoleState = roleState,
            catalogStatus = catalogState.status,
            visibleApps = projectApps(catalogState, query, ordinaryCollection = true),
            settingsApps = projectApps(catalogState, query = "", ordinaryCollection = false),
            destinations = destinations,
            recoveryInstances = recoveries,
            errorCode = priorError,
            storeRevision = snapshot.revision,
        )
    }

    private fun copyState(
        current: LauncherRuntimeState,
        surface: LauncherSurface = current.surface,
        query: String = current.localQuery,
    ): LauncherRuntimeState = LauncherRuntimeState(
        status = current.status,
        currentDestinationId = current.currentDestinationId,
        surface = surface,
        localQuery = query,
        homeRoleState = current.homeRoleState,
        catalogStatus = latestCatalog.status,
        visibleApps = if (query == current.localQuery) {
            current.visibleApps
        } else {
            projectApps(latestCatalog, query, ordinaryCollection = true)
        },
        settingsApps = current.settingsApps,
        destinations = current.destinations,
        recoveryInstances = current.recoveryInstances,
        errorCode = current.errorCode,
        storeRevision = current.storeRevision,
    )

    private fun projectApps(
        catalogState: AppCatalogSnapshot,
        query: String,
        ordinaryCollection: Boolean,
    ): List<LauncherApp> {
        val normalizedQuery = query.trim()
        return catalogState.profiles
            .asSequence()
            .filter { it.available }
            .flatMap { profile ->
                profile.apps.asSequence()
                    .filter { app ->
                        !ordinaryCollection || app.collectionVisible ||
                            (normalizedQuery.isNotEmpty() && app.searchVisible)
                    }
                    .map { app ->
                        LauncherApp(
                            identity = app.identity,
                            label = app.label,
                            workProfile = profile.kind == AppProfileKind.WORK,
                            favorite = app.favorite,
                            collectionVisible = app.collectionVisible,
                        )
                    }
            }
            .filter { app ->
                normalizedQuery.isEmpty() || app.label.contains(normalizedQuery, ignoreCase = true)
            }
            .sortedWith(APP_ORDER)
            .toList()
    }

    private fun refreshRoleState() {
        mutableState.update { current ->
            LauncherRuntimeState(
                status = current.status,
                currentDestinationId = current.currentDestinationId,
                surface = current.surface,
                localQuery = current.localQuery,
                homeRoleState = roleCoordinator.refresh(),
                catalogStatus = current.catalogStatus,
                visibleApps = current.visibleApps,
                settingsApps = current.settingsApps,
                destinations = current.destinations,
                recoveryInstances = current.recoveryInstances,
                errorCode = current.errorCode,
                storeRevision = current.storeRevision,
            )
        }
    }

    private suspend fun safeDiagnostic(event: DiagnosticEvent) {
        try {
            diagnostics.record(event)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            // Diagnostics cannot block launcher navigation or a user-requested platform action.
        }
    }

    private suspend fun refreshCatalogForSafeSurface() {
        try {
            catalog.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            // A catalog adapter reports its own degraded state. Store recovery must still proceed.
        }
        latestCatalog = catalog.state.value
    }

    private fun errorState(
        code: String,
        surface: LauncherSurface = LauncherSurface.SAFE_LAYOUT,
        query: String = "",
    ): LauncherRuntimeState {
        val snapshot = lastSnapshot ?: LauncherSnapshot.empty()
        return buildState(
            snapshot = snapshot,
            currentDestinationId = snapshot.startDestinationId,
            surface = surface,
            query = query,
            roleState = roleCoordinator.refresh(),
            catalogState = latestCatalog,
            priorError = code,
        )
    }

    private fun checkOpen() {
        check(!closed.get()) { "Launcher runtime is closed" }
    }

    private companion object {
        const val STARTUP_FAILED = "launcher_startup_failed"
        const val MAXIMUM_LOCAL_QUERY_LENGTH = 200
        val APP_ORDER = compareBy<LauncherApp>(
            { it.label.lowercase(Locale.ROOT) },
            { it.label },
            { it.identity.packageName.value },
            { it.identity.activityName.value },
            { it.identity.profile.value },
        )
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
