package org.quicklauncher.app

import android.graphics.BitmapFactory
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.quicklauncher.contracts.contribution.ContributionRegistry
import org.quicklauncher.contracts.contribution.RegisteredLayout
import org.quicklauncher.contracts.contribution.find
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.ProfilePackageIdentity
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.ActionSink
import org.quicklauncher.contracts.ui.BlockAction
import org.quicklauncher.contracts.ui.EditorMode
import org.quicklauncher.contracts.ui.LayoutAction
import org.quicklauncher.contracts.ui.PreparedContentItem
import org.quicklauncher.contracts.ui.PreparedIcon
import org.quicklauncher.contracts.ui.PreparedIconSource
import org.quicklauncher.contracts.ui.PreparedContentKind
import org.quicklauncher.contracts.ui.PreparedProfileKind
import org.quicklauncher.contracts.ui.NotificationIndicator
import org.quicklauncher.contracts.ui.PreparedContentRenderer
import org.quicklauncher.contracts.ui.PreparedHostContent
import org.quicklauncher.contracts.ui.PreparedHostSurface
import org.quicklauncher.contracts.ui.RenderStatus
import org.quicklauncher.contracts.ui.WindowInfo
import org.quicklauncher.contracts.ui.WindowOrientation
import org.quicklauncher.host.data.preferences.LauncherPreferences
import org.quicklauncher.host.data.preferences.LauncherPreferencesStore
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.WidgetBindState
import org.quicklauncher.host.data.store.WidgetPlacementRecord
import org.quicklauncher.host.data.store.WidgetRestoreState
import org.quicklauncher.host.editor.EditorAction
import org.quicklauncher.host.editor.LauncherEditor
import org.quicklauncher.host.runtime.LauncherApp
import org.quicklauncher.host.runtime.LauncherRuntime
import org.quicklauncher.host.runtime.contentItemId
import org.quicklauncher.host.runtime.composition.CompositionEnvironment
import org.quicklauncher.host.runtime.composition.CompositionIssue
import org.quicklauncher.host.runtime.composition.CompositionRequest
import org.quicklauncher.host.runtime.composition.DefaultCompositionEngine
import org.quicklauncher.host.runtime.composition.PreparedComposition
import org.quicklauncher.host.runtime.composition.PreparedBlockContent
import org.quicklauncher.host.runtime.composition.PreparedContentSource
import org.quicklauncher.host.runtime.composition.SearchPresentationSource
import org.quicklauncher.host.runtime.catalog.AppCatalogStatus
import org.quicklauncher.host.runtime.navigation.SpatialNavigationSurface
import org.quicklauncher.host.runtime.navigation.rememberSpatialNavigationState
import org.quicklauncher.host.runtime.notifications.NotificationIndicatorState
import org.quicklauncher.host.runtime.shortcuts.ResolvedShortcutPlacement
import org.quicklauncher.host.runtime.shortcuts.ShortcutSnapshot
import org.quicklauncher.host.runtime.theme.ResolvedLauncherTheme
import org.quicklauncher.host.runtime.theme.LauncherMaterialTheme
import org.quicklauncher.host.runtime.profile.ProfileCoordinatorState
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.widgets.WidgetCoordinator
import org.quicklauncher.host.runtime.widgets.WidgetActionLaunchResult
import org.quicklauncher.host.runtime.widgets.WidgetBindingRequest
import org.quicklauncher.host.runtime.widgets.WidgetFlowResult
import org.quicklauncher.host.runtime.widgets.WidgetProviderDescriptor
import org.quicklauncher.host.runtime.widgets.WidgetProviderDiscoveryResult
import org.quicklauncher.host.runtime.widgets.WidgetResizeResult
import org.quicklauncher.host.runtime.widgets.WidgetSize
import org.quicklauncher.host.runtime.widgets.WidgetSurfaceResult
import org.quicklauncher.host.runtime.widgets.WidgetUserAction
import org.quicklauncher.host.platform.widgets.AndroidWidgetPlatform
import org.quicklauncher.host.platform.widgets.rememberAndroidWidgetUserActionLauncher
import org.quicklauncher.host.platform.theme.AndroidIconPackResolver
import org.quicklauncher.host.platform.theme.AndroidThemeAssetStore

@Composable
internal fun ProductionCompositionSurface(
    store: LauncherStore,
    runtime: LauncherRuntime,
    engine: DefaultCompositionEngine,
    editor: LauncherEditor,
    preferences: LauncherPreferencesStore?,
    notificationState: NotificationIndicatorState,
    shortcutState: ShortcutSnapshot,
    profileState: ProfileCoordinatorState,
    resolvedTheme: ResolvedLauncherTheme,
    fallbackResolvedTheme: ResolvedLauncherTheme,
    destinationThemes: Map<DestinationId, ResolvedLauncherTheme> = emptyMap(),
    iconPackResolver: AndroidIconPackResolver? = null,
    themeAssets: AndroidThemeAssetStore,
    widgetPlatform: AndroidWidgetPlatform? = null,
    widgetCoordinator: WidgetCoordinator? = null,
    widgetTransientEvents: Flow<WidgetTransientEvent> = emptyFlow(),
    contentRenderer: PreparedContentRenderer = ProductionPreparedContentRenderer,
    onOpenFolder: (ContentItemId) -> Unit,
    onOpenItemActions: (ContentItemId) -> Unit,
    onLaunchShortcut: (ContentItemId) -> Unit,
    searchPresentations: SearchPresentationSource? = null,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val runtimeState by runtime.state.collectAsState()
    val configuration = LocalConfiguration.current
    val preferencesState by (preferences?.state ?: kotlinx.coroutines.flow.flowOf(LauncherPreferences.Default))
        .collectAsState(initial = LauncherPreferences.Default)
    val editorState by editor.state.collectAsState()
    var snapshot by remember { mutableStateOf<LauncherSnapshot?>(null) }
    var prepared by remember { mutableStateOf<PreparedComposition?>(null) }
    val widgetLifecycle = remember(widgetCoordinator) {
        widgetCoordinator?.let(::WidgetSurfaceLifecycle)
    }
    var widgetSurfaces by remember(widgetCoordinator) {
        mutableStateOf<Map<ModuleInstanceId, WidgetSurfaceResult>>(emptyMap())
    }
    var widgetEpoch by remember { mutableStateOf(0) }
    var preparedIcons by remember { mutableStateOf<Map<ContentItemId, PreparedIcon>>(emptyMap()) }
    val current = runtimeState.currentDestinationId
    val disposed = remember(engine) { AtomicBoolean(false) }
    DisposableEffect(engine) {
        disposed.set(false)
        onDispose {
            disposed.set(true)
            engine.release()
        }
    }
    LaunchedEffect(
        runtimeState.visibleApps,
        resolvedTheme.profileId,
        resolvedTheme.iconPack,
        iconPackResolver,
        configuration,
    ) {
        val next = linkedMapOf<ContentItemId, PreparedIcon>()
        for (app in runtimeState.visibleApps.take(MAX_PREPARED_APPLICATION_ICONS)) {
            val fallback = app.icon ?: continue
            val resolved = iconPackResolver?.resolve(
                resolvedTheme.iconPack,
                resolvedTheme.profileId,
                app.identity,
                fallback,
            )
                ?: fallback
            val source = if (resolved == fallback) {
                PreparedIconSource.APPLICATION
            } else {
                PreparedIconSource.ICON_PACK
            }
            runCatching { PreparedIcon.of(resolved.bytes(), source) }.getOrNull()?.let { icon ->
                next[contentItemId(app)] = icon
            }
        }
        preparedIcons = next
    }

    LaunchedEffect(current, runtimeState.storeRevision, editorState.revision, widgetEpoch) {
        if (current != null) snapshot = store.read()
    }
    LaunchedEffect(widgetLifecycle) {
        val lifecycle = widgetLifecycle ?: return@LaunchedEffect
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { runCatching { lifecycle.release() } }
        }
    }
    val latest = snapshot
    LaunchedEffect(latest, current, widgetLifecycle, widgetEpoch) {
        val lifecycle = widgetLifecycle ?: run {
            widgetSurfaces = emptyMap()
            return@LaunchedEffect
        }
        val currentSnapshot = latest ?: return@LaunchedEffect
        val visible = visibleWidgetInstances(currentSnapshot, current)
        widgetSurfaces = lifecycle.update(visible)
    }
    val environment = remember(
        runtimeState.visibleApps,
        runtimeState.catalogStatus,
        runtimeState.errorCode,
        current,
        latest,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        resolvedTheme,
        destinationThemes,
        preparedIcons,
        notificationState,
        shortcutState,
        profileState,
        widgetSurfaces,
        searchPresentations,
    ) {
        if (current == null || latest == null) null else productionEnvironment(
            apps = runtimeState.visibleApps,
            catalogStatus = runtimeState.catalogStatus,
            catalogErrorCode = runtimeState.errorCode,
            notificationState = notificationState,
            shortcutState = shortcutState,
            profileState = profileState,
            widgetSurfaces = widgetSurfaces,
            contentRenderer = contentRenderer,
            snapshot = latest,
            resolvedTheme = resolvedTheme,
            fallbackResolvedTheme = fallbackResolvedTheme,
            destinationThemes = destinationThemes,
            preparedIcons = preparedIcons,
            widthDp = configuration.screenWidthDp,
            heightDp = configuration.screenHeightDp,
            destinationId = current,
            runtime = runtime,
            editor = editor,
            onOpenFolder = onOpenFolder,
            onOpenItemActions = onOpenItemActions,
            onLaunchShortcut = onLaunchShortcut,
            searchPresentations = searchPresentations,
            scope = scope,
        )
    }
    LaunchedEffect(current, environment, latest) {
        val destinationId = current ?: return@LaunchedEffect
        val requestEnvironment = environment ?: return@LaunchedEffect
        val requestSnapshot = latest ?: return@LaunchedEffect
        try {
            prepared = engine.prepare(
                CompositionRequest(requestSnapshot, destinationId, requestEnvironment),
            )
        } catch (failure: IllegalStateException) {
            // Activity recreation may close the old engine while its cancelled Compose effect is
            // leaving the dispatcher. Only suppress that disposed-owner race; active failures
            // still surface normally.
            if (!disposed.get()) throw failure
        }
    }

    val composition = prepared
    if (latest == null || composition == null || current == null) {
        Box(modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("Loading destination")
        }
        return
    }
    val navigation = rememberSpatialNavigationState(latest.destinations, current) { destinationId ->
        scope.launch { runtime.selectDestination(destinationId) }
    }
    PredictiveBackHandler(enabled = navigation.frame.drag != null) { progress ->
        progress.collect()
        navigation.cancelPreview()
    }
    Box(modifier.fillMaxSize()) {
        SpatialNavigationSurface(
            state = navigation,
            mode = preferencesState.gestureMode,
            reducedMotion = resolvedTheme.theme.reducedMotion,
            modifier = Modifier.fillMaxSize(),
        ) { destinationId ->
            val destinationTheme = destinationThemes[destinationId] ?: fallbackResolvedTheme
            LauncherMaterialTheme(destinationTheme, rememberThemeTypography(destinationTheme, themeAssets)) {
                ResolvedThemeBackground(destinationTheme, themeAssets) {
                    composition.destinations.singleOrNull { it.destinationId == destinationId }?.Render(
                        Modifier.fillMaxSize(),
                    ) ?: Box(
                        Modifier.fillMaxSize().semantics {
                            contentDescription = "Unavailable destination"
                        },
                    )
                }
            }
        }
        val mapDescription = stringResource(org.quicklauncher.host.runtime.R.string.map_overview)
        val settingsDescription = stringResource(org.quicklauncher.host.runtime.R.string.launcher_settings)
        Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
            TextButton(
                onClick = { runtime.open(org.quicklauncher.host.runtime.LauncherSurface.MAP_OVERVIEW) },
                modifier = Modifier.semantics { contentDescription = mapDescription },
            ) { Text(mapDescription) }
            TextButton(
                onClick = { runtime.open(org.quicklauncher.host.runtime.LauncherSurface.SETTINGS) },
                modifier = Modifier.semantics { contentDescription = settingsDescription },
            ) { Text(settingsDescription) }
        }
        WidgetBindingControls(
            snapshot = latest,
            current = current,
            profiles = profileState,
            platform = widgetPlatform,
            coordinator = widgetCoordinator,
            transientEvents = widgetTransientEvents,
            scope = scope,
            onChanged = { widgetEpoch++ },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
        WidgetResizeControls(
            snapshot = latest,
            current = current,
            coordinator = widgetCoordinator,
            transientEvents = widgetTransientEvents,
            maximumSize = WidgetSize(
                configuration.screenWidthDp.coerceAtLeast(MINIMUM_WIDGET_SIZE_DP),
                configuration.screenHeightDp.coerceAtLeast(MINIMUM_WIDGET_SIZE_DP),
            ),
            scope = scope,
            onChanged = { widgetEpoch++ },
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        )
    }
}

/** Owns widget surfaces for one selected-layout composition. */
internal class WidgetSurfaceLifecycle(
    private val coordinator: WidgetCoordinator,
) {
    private val mutex = Mutex()
    private val surfaces = linkedMapOf<ModuleInstanceId, WidgetSurfaceResult>()
    private var released = false

    suspend fun update(visible: Set<ModuleInstanceId>): Map<ModuleInstanceId, WidgetSurfaceResult> = try {
        mutex.withLock {
            check(!released) { "Widget surface lifecycle is released" }
            releaseLocked(surfaces.keys - visible)
            visible.forEach { instanceId ->
                if (surfaces[instanceId] !is WidgetSurfaceResult.Ready) {
                    surfaces[instanceId] = coordinator.show(instanceId)
                }
            }
            visible.associateWithTo(linkedMapOf()) { instanceId ->
                checkNotNull(surfaces[instanceId])
            }
        }
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            try {
                mutex.withLock { releaseLocked(surfaces.keys.toSet()) }
            } catch (cleanupFailure: Throwable) {
                cancelled.addSuppressed(cleanupFailure)
            }
        }
        throw cancelled
    }

    suspend fun release() {
        mutex.withLock {
            if (released) return
            released = true
            releaseLocked(surfaces.keys.toSet())
        }
    }

    private suspend fun releaseLocked(instanceIds: Set<ModuleInstanceId>) {
        var firstFailure: Throwable? = null
        instanceIds.forEach { instanceId ->
            val surface = surfaces.remove(instanceId)
            if (surface is WidgetSurfaceResult.Ready) {
                try {
                    coordinator.hide(instanceId)
                } catch (failure: Throwable) {
                    if (firstFailure == null) firstFailure = failure else firstFailure?.addSuppressed(failure)
                }
            }
        }
        firstFailure?.let { throw it }
    }
}

internal enum class WidgetResizeCommand {
    NARROWER,
    WIDER,
    SHORTER,
    TALLER,
}

internal suspend fun resizeWidget(
    coordinator: WidgetCoordinator,
    placement: WidgetPlacementRecord,
    command: WidgetResizeCommand,
    maximumSize: WidgetSize = WidgetSize(Int.MAX_VALUE, Int.MAX_VALUE),
): WidgetResizeResult {
    val width = when (command) {
        WidgetResizeCommand.NARROWER ->
            (placement.intendedWidthDp - WIDGET_RESIZE_STEP_DP).coerceAtLeast(MINIMUM_WIDGET_SIZE_DP)
        WidgetResizeCommand.WIDER ->
            (placement.intendedWidthDp + WIDGET_RESIZE_STEP_DP)
                .coerceAtMost(maximumSize.widthDp.coerceAtLeast(placement.intendedWidthDp))
        else -> placement.intendedWidthDp
    }
    val height = when (command) {
        WidgetResizeCommand.SHORTER ->
            (placement.intendedHeightDp - WIDGET_RESIZE_STEP_DP).coerceAtLeast(MINIMUM_WIDGET_SIZE_DP)
        WidgetResizeCommand.TALLER ->
            (placement.intendedHeightDp + WIDGET_RESIZE_STEP_DP)
                .coerceAtMost(maximumSize.heightDp.coerceAtLeast(placement.intendedHeightDp))
        else -> placement.intendedHeightDp
    }
    return coordinator.resize(placement.moduleInstanceId, WidgetSize(width, height))
}

@Composable
private fun WidgetResizeControls(
    snapshot: LauncherSnapshot,
    current: DestinationId,
    coordinator: WidgetCoordinator?,
    transientEvents: Flow<WidgetTransientEvent>,
    maximumSize: WidgetSize,
    scope: CoroutineScope,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (coordinator == null) return
    val visible = visibleWidgetInstances(snapshot, current)
    val placements = snapshot.widgetPlacements.filter {
        it.moduleInstanceId in visible &&
            it.appWidgetId != null &&
            it.bindState == WidgetBindState.BOUND &&
            it.restoreState == WidgetRestoreState.READY
    }
    if (placements.isEmpty()) return

    var open by remember(placements.map { it.moduleInstanceId }) { mutableStateOf(false) }
    var localSizes by remember(
        placements.map { Triple(it.moduleInstanceId, it.intendedWidthDp, it.intendedHeightDp) },
    ) {
        mutableStateOf(
            placements.associate { it.moduleInstanceId to WidgetSize(it.intendedWidthDp, it.intendedHeightDp) },
        )
    }
    var message by remember { mutableStateOf<String?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    fun launchTask(block: suspend () -> Unit) {
        activeJob?.cancel()
        activeJob = scope.launch { block() }
    }

    LaunchedEffect(transientEvents) {
        transientEvents.collect {
            activeJob?.cancel()
            activeJob = null
            open = false
            message = null
        }
    }

    Button(onClick = { open = true }, modifier = modifier) { Text("Resize widgets") }
    if (!open) return

    AlertDialog(
        onDismissRequest = {
            activeJob?.cancel()
            activeJob = null
            open = false
        },
        title = { Text("Resize widgets") },
        text = {
            LazyColumn {
                items(placements, key = { it.moduleInstanceId.value }) { durable ->
                    val size = localSizes[durable.moduleInstanceId]
                        ?: WidgetSize(durable.intendedWidthDp, durable.intendedHeightDp)
                    val currentPlacement = durable.copy(
                        intendedWidthDp = size.widthDp,
                        intendedHeightDp = size.heightDp,
                    )
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("Widget ${placements.indexOf(durable) + 1}: ${size.widthDp} by ${size.heightDp} dp")
                        Row {
                            WidgetResizeButton("Decrease width") {
                                launchTask {
                                    val result = resizeWidget(
                                        coordinator,
                                        currentPlacement,
                                        WidgetResizeCommand.NARROWER,
                                        maximumSize,
                                    )
                                    if (result == WidgetResizeResult.Resized) {
                                        localSizes = localSizes + (
                                            durable.moduleInstanceId to WidgetSize(
                                                (size.widthDp - WIDGET_RESIZE_STEP_DP)
                                                    .coerceAtLeast(MINIMUM_WIDGET_SIZE_DP),
                                                size.heightDp,
                                            )
                                        )
                                        onChanged()
                                    } else {
                                        message = widgetResizeMessage(result)
                                    }
                                }
                            }
                            WidgetResizeButton("Increase width") {
                                launchTask {
                                    val result = resizeWidget(
                                        coordinator,
                                        currentPlacement,
                                        WidgetResizeCommand.WIDER,
                                        maximumSize,
                                    )
                                    if (result == WidgetResizeResult.Resized) {
                                        localSizes = localSizes + (
                                            durable.moduleInstanceId to WidgetSize(
                                                (size.widthDp + WIDGET_RESIZE_STEP_DP)
                                                    .coerceAtMost(
                                                        maximumSize.widthDp.coerceAtLeast(size.widthDp),
                                                    ),
                                                size.heightDp,
                                            )
                                        )
                                        onChanged()
                                    } else {
                                        message = widgetResizeMessage(result)
                                    }
                                }
                            }
                        }
                        Row {
                            WidgetResizeButton("Decrease height") {
                                launchTask {
                                    val result = resizeWidget(
                                        coordinator,
                                        currentPlacement,
                                        WidgetResizeCommand.SHORTER,
                                        maximumSize,
                                    )
                                    if (result == WidgetResizeResult.Resized) {
                                        localSizes = localSizes + (
                                            durable.moduleInstanceId to WidgetSize(
                                                size.widthDp,
                                                (size.heightDp - WIDGET_RESIZE_STEP_DP)
                                                    .coerceAtLeast(MINIMUM_WIDGET_SIZE_DP),
                                            )
                                        )
                                        onChanged()
                                    } else {
                                        message = widgetResizeMessage(result)
                                    }
                                }
                            }
                            WidgetResizeButton("Increase height") {
                                launchTask {
                                    val result = resizeWidget(
                                        coordinator,
                                        currentPlacement,
                                        WidgetResizeCommand.TALLER,
                                        maximumSize,
                                    )
                                    if (result == WidgetResizeResult.Resized) {
                                        localSizes = localSizes + (
                                            durable.moduleInstanceId to WidgetSize(
                                                size.widthDp,
                                                (size.heightDp + WIDGET_RESIZE_STEP_DP)
                                                    .coerceAtMost(
                                                        maximumSize.heightDp.coerceAtLeast(size.heightDp),
                                                    ),
                                            )
                                        )
                                        onChanged()
                                    } else {
                                        message = widgetResizeMessage(result)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } },
        dismissButton = { message?.let { Text(it) } },
    )
}

@Composable
private fun WidgetResizeButton(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        Text(label)
    }
}

private fun widgetResizeMessage(result: WidgetResizeResult): String = when (result) {
    WidgetResizeResult.Resized -> "Widget resized"
    is WidgetResizeResult.Rejected -> "Widget resize was rejected"
    is WidgetResizeResult.Unavailable -> "Widget resize is unavailable"
}

private const val WIDGET_RESIZE_STEP_DP = 40
private const val MINIMUM_WIDGET_SIZE_DP = 40

@Composable
private fun WidgetBindingControls(
    snapshot: LauncherSnapshot,
    current: DestinationId,
    profiles: ProfileCoordinatorState,
    platform: AndroidWidgetPlatform?,
    coordinator: WidgetCoordinator?,
    transientEvents: Flow<WidgetTransientEvent>,
    scope: CoroutineScope,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (platform == null || coordinator == null) return
    val candidate = visibleWidgetInstances(snapshot, current).firstOrNull { instanceId ->
        snapshot.widgetPlacements.none { it.moduleInstanceId == instanceId } ||
            snapshot.widgetPlacements.any {
                it.moduleInstanceId == instanceId && it.restoreState == WidgetRestoreState.REBIND_REQUIRED
            }
    } ?: return
    val restored = snapshot.widgetPlacements.singleOrNull { it.moduleInstanceId == candidate }
        ?.restoreState == WidgetRestoreState.REBIND_REQUIRED
    var choosing by remember(candidate) { mutableStateOf(false) }
    var providers by remember(candidate) { mutableStateOf<List<WidgetProviderDescriptor>>(emptyList()) }
    var message by remember(candidate) { mutableStateOf<String?>(null) }
    var pending by remember(candidate) { mutableStateOf<Pair<ModuleInstanceId, WidgetUserAction>?>(null) }
    var activeJob by remember(candidate) { mutableStateOf<Job?>(null) }
    fun launchTask(block: suspend () -> Unit) {
        activeJob?.cancel()
        activeJob = scope.launch { block() }
    }

    LaunchedEffect(transientEvents, candidate) {
        transientEvents.collect {
            activeJob?.cancel()
            activeJob = null
            choosing = false
            providers = emptyList()
            message = null
            pending = null
        }
    }

    fun handle(result: WidgetFlowResult) {
        when (result) {
            is WidgetFlowResult.AwaitingPermission -> pending = candidate to result.action
            is WidgetFlowResult.AwaitingConfiguration -> pending = candidate to result.action
            WidgetFlowResult.Bound, WidgetFlowResult.Cancelled -> {
                pending = null
                choosing = false
                onChanged()
            }
            is WidgetFlowResult.CleanupRequired -> message = "Widget cleanup will be retried"
            is WidgetFlowResult.Rejected -> message = "Widget setup was rejected"
            is WidgetFlowResult.Unavailable -> message = "Widget is unavailable"
        }
    }
    val actionLauncher = rememberAndroidWidgetUserActionLauncher { result ->
        val active = pending
        if (active == null || active.second.appWidgetId != result.action.appWidgetId) return@rememberAndroidWidgetUserActionLauncher
        launchTask {
            val completed = when (result.action) {
                is WidgetUserAction.BindPermission ->
                    coordinator.completePermission(active.first, result.accepted)
                is WidgetUserAction.Configure ->
                    coordinator.completeConfiguration(active.first, result.accepted)
            }
            handle(completed)
        }
    }
    LaunchedEffect(pending) {
        val active = pending ?: return@LaunchedEffect
        when (actionLauncher.launch(active.second)) {
            WidgetActionLaunchResult.Launched, WidgetActionLaunchResult.Busy -> Unit
            else -> handle(coordinator.cancelBinding(active.first))
        }
    }

    Button(
        onClick = {
            if (restored) {
                launchTask { handle(coordinator.beginRestoredBinding(candidate)) }
            } else {
                choosing = true
                launchTask {
                    message = null
                    providers = buildList {
                        profiles.profiles.filter {
                            it.kind != ProfileKind.PRIVATE &&
                                it.availability == org.quicklauncher.host.runtime.profile.ProfileAvailability.AVAILABLE
                        }.forEach { profile ->
                            val discovered = platform.discoverProviders(profile.serial)
                            if (discovered is WidgetProviderDiscoveryResult.Available) {
                                addAll(discovered.providers)
                            }
                        }
                    }
                    if (providers.isEmpty()) message = "No available widgets"
                }
            }
        },
        modifier = modifier,
    ) {
        Text(if (restored) "Reconnect widget" else "Set up widget")
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = {
                activeJob?.cancel()
                activeJob = null
                choosing = false
            },
            title = { Text("Choose a widget") },
            text = {
                if (providers.isEmpty()) {
                    Text(message ?: "Loading widgets")
                } else {
                    LazyColumn {
                        items(providers, key = { "${it.identity.profile.value}:${it.identity.packageName.value}:${it.identity.className}" }) { provider ->
                            TextButton(
                                onClick = {
                                    launchTask {
                                        handle(
                                            coordinator.beginBinding(
                                                WidgetBindingRequest(candidate, provider.identity, provider.minimumSize),
                                            ),
                                        )
                                    }
                                },
                            ) { Text(provider.label) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    activeJob?.cancel()
                    activeJob = null
                    choosing = false
                }) { Text("Cancel") }
            },
        )
    }
    message?.takeIf { !choosing }?.let { Text(it, modifier = modifier.padding(top = 56.dp)) }
}

internal fun productionEnvironment(
    apps: List<LauncherApp>,
    catalogStatus: AppCatalogStatus,
    catalogErrorCode: String?,
    notificationState: NotificationIndicatorState = NotificationIndicatorState.Initial,
    shortcutState: ShortcutSnapshot = ShortcutSnapshot.Empty,
    profileState: ProfileCoordinatorState = ProfileCoordinatorState.Empty,
    widgetSurfaces: Map<ModuleInstanceId, WidgetSurfaceResult> = emptyMap(),
    contentRenderer: PreparedContentRenderer = ProductionPreparedContentRenderer,
    snapshot: LauncherSnapshot,
    resolvedTheme: ResolvedLauncherTheme,
    fallbackResolvedTheme: ResolvedLauncherTheme = resolvedTheme,
    destinationThemes: Map<DestinationId, ResolvedLauncherTheme> = emptyMap(),
    preparedIcons: Map<ContentItemId, PreparedIcon> = emptyMap(),
    widthDp: Int,
    heightDp: Int,
    destinationId: DestinationId,
    runtime: LauncherRuntime,
    editor: LauncherEditor,
    onOpenFolder: (ContentItemId) -> Unit = {},
    onOpenItemActions: (ContentItemId) -> Unit = {},
    onLaunchShortcut: (ContentItemId) -> Unit = {},
    searchPresentations: SearchPresentationSource? = null,
    scope: CoroutineScope,
): CompositionEnvironment {
    val identities = apps.associate { app -> contentItemId(app) to app.identity }
    fun openEditor() {
        scope.launch { editor.dispatch(EditorAction.OpenDestination(destinationId)) }
    }
    val actionPolicy = ProductionActionPolicy(
        snapshot = snapshot,
        destinationId = destinationId,
        registry = org.quicklauncher.registry.production.productionRegistry,
        identities = identities,
        shortcutIds = shortcutState.placements.mapTo(linkedSetOf()) { it.id },
        onLaunch = { identity -> scope.launch { runtime.launch(identity) } },
        onOpenFolder = onOpenFolder,
        onOpenItemActions = onOpenItemActions,
        onLaunchShortcut = onLaunchShortcut,
        onOpenEditor = ::openEditor,
        onRendererFailure = { instanceId, failure ->
            scope.launch { runtime.reportRendererFailure(instanceId, failure) }
        },
    )
    return CompositionEnvironment(
        resolvedTheme = resolvedTheme,
        destinationThemes = destinationThemes,
        fallbackResolvedTheme = fallbackResolvedTheme,
        window = WindowInfo(
            widthDp.coerceAtLeast(1),
            heightDp.coerceAtLeast(1),
            if (widthDp >= heightDp) WindowOrientation.LANDSCAPE else WindowOrientation.PORTRAIT,
        ),
        editorMode = EditorMode.BROWSING,
        contentSource = productionContentSource(
            snapshot,
            apps,
            catalogStatus,
            catalogErrorCode,
            shortcutState = shortcutState,
            profileState = profileState,
            widgetSurfaces = widgetSurfaces,
            indicators = notificationState.asMap(),
            preparedIcons = preparedIcons,
        ),
        contentRenderer = contentRenderer,
        placeholderRenderer = { issue, renderModifier -> CompositionPlaceholder(issue, renderModifier) },
        layoutActions = actionPolicy.layoutActions,
        blockActions = actionPolicy.blockActions,
        searchPresentations = searchPresentations ?: SearchPresentationSource { null },
        rendererFailures = actionPolicy.rendererFailures,
    )
}

internal class ProductionActionPolicy(
    snapshot: LauncherSnapshot,
    destinationId: DestinationId,
    registry: ContributionRegistry,
    private val identities: Map<ContentItemId, org.quicklauncher.contracts.domain.AppActivityIdentity>,
    private val onLaunch: (org.quicklauncher.contracts.domain.AppActivityIdentity) -> Unit,
    private val onOpenEditor: () -> Unit,
    private val onRendererFailure: (org.quicklauncher.contracts.domain.ModuleInstanceId, RuntimeException) -> Unit,
    private val shortcutIds: Set<ContentItemId> = emptySet(),
    private val onOpenFolder: (ContentItemId) -> Unit = {},
    private val onOpenItemActions: (ContentItemId) -> Unit = {},
    private val onLaunchShortcut: (ContentItemId) -> Unit = {},
) {
    private val folderIds = snapshot.contentItems
        .filter { it.kind == org.quicklauncher.host.data.store.ContentItemKind.FOLDER }
        .mapTo(linkedSetOf()) { it.id }
    private val selectedLayout = snapshot.destinationLayouts.singleOrNull {
        it.destinationId == destinationId && it.selected
    }
    private val interactiveInstances = selectedLayout?.let { selected ->
        val reachable = linkedSetOf(selected.layoutInstanceId)
        val pending = ArrayDeque(reachable)
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            snapshot.placements.filter { it.parentInstanceId == parent }.forEach { placement ->
                if (reachable.add(placement.childInstanceId)) pending.addLast(placement.childInstanceId)
            }
        }
        reachable
    }.orEmpty()
    private val layoutSlots = selectedLayout?.let { selected ->
        (registry.find(selected.layoutContributionId) as? RegisteredLayout<*>)
            ?.descriptor?.slots?.mapTo(linkedSetOf()) { it.id }
    }.orEmpty()

    val layoutActions = ActionSink<LayoutAction> { action ->
        when (action) {
            is LayoutAction.OpenSettings -> if (action.instanceId == selectedLayout?.layoutInstanceId) {
                onOpenEditor()
                ActionDispatchResult.Accepted
            } else {
                rejected("layout-action-invalid", "Layout action does not target the current selected layout")
            }
            is LayoutAction.SelectSlot -> if (action.slotId in layoutSlots) {
                ActionDispatchResult.Accepted
            } else {
                rejected("layout-slot-invalid", "Layout action targets an unknown slot")
            }
        }
    }
    val blockActions = ActionSink<BlockAction> { action ->
        when (action) {
            is BlockAction.ActivateItem -> when {
                action.itemId in identities -> {
                    onLaunch(identities.getValue(action.itemId))
                    ActionDispatchResult.Accepted
                }
                action.itemId in shortcutIds -> {
                    onLaunchShortcut(action.itemId)
                    ActionDispatchResult.Accepted
                }
                action.itemId in folderIds -> {
                    onOpenFolder(action.itemId)
                    ActionDispatchResult.Accepted
                }
                else -> rejected("content-item-invalid", "Content item is no longer available")
            }
            is BlockAction.OpenSettings -> if (action.instanceId in interactiveInstances &&
                action.instanceId != selectedLayout?.layoutInstanceId
            ) {
                onOpenEditor()
                ActionDispatchResult.Accepted
            } else {
                rejected("block-action-invalid", "Block action does not target the current composition")
            }
            is BlockAction.OpenItemActions -> if (
                action.itemId in identities || action.itemId in shortcutIds
            ) {
                onOpenItemActions(action.itemId)
                ActionDispatchResult.Accepted
            } else {
                rejected("item-actions-invalid", "Item is no longer available")
            }
        }
    }
    val rendererFailures = org.quicklauncher.host.runtime.composition.RendererFailureSink { issue, failure ->
        issue.instanceId?.let { instanceId ->
            if (instanceId in snapshot.moduleInstances.map { it.id }) {
                onRendererFailure(instanceId, failure)
            }
        }
    }

    private fun rejected(code: String, message: String) = ActionDispatchResult.Rejected(
        org.quicklauncher.contracts.domain.StableKey.parse(code),
        message,
    )
}

internal fun productionContentSource(
    snapshot: LauncherSnapshot,
    apps: List<LauncherApp>,
    catalogStatus: AppCatalogStatus,
    catalogErrorCode: String?,
    shortcutState: ShortcutSnapshot = ShortcutSnapshot.Empty,
    profileState: ProfileCoordinatorState = ProfileCoordinatorState.Empty,
    widgetSurfaces: Map<ModuleInstanceId, WidgetSurfaceResult> = emptyMap(),
    now: ZonedDateTime = ZonedDateTime.now(),
    indicators: Map<ProfilePackageIdentity, NotificationIndicator> = emptyMap(),
    preparedIcons: Map<ContentItemId, PreparedIcon> = emptyMap(),
): PreparedContentSource {
    val shortcutItems = shortcutState.placements.mapNotNull { placement ->
        val available = placement as? ResolvedShortcutPlacement.Available ?: return@mapNotNull null
        val profile = profileState.profile(available.target.profile) ?: return@mapNotNull null
        if (profile.kind == ProfileKind.PRIVATE ||
            profile.availability != org.quicklauncher.host.runtime.profile.ProfileAvailability.AVAILABLE
        ) return@mapNotNull null
        val work = profile.kind == ProfileKind.WORK
        PreparedContentItem(
            available.id,
            available.label,
            if (work) "Work shortcut" else "Shortcut",
            PreparedContentKind.SHORTCUT,
            enabled = true,
            profile = if (work) PreparedProfileKind.WORK else PreparedProfileKind.PERSONAL,
        )
    }
    val appItems = apps.map { app ->
        PreparedContentItem(
            contentItemId(app),
            app.label,
            if (app.workProfile) "Work" else null,
            PreparedContentKind.APP,
            enabled = true,
            profile = if (app.workProfile) PreparedProfileKind.WORK else PreparedProfileKind.PERSONAL,
            indicator = app.eligibleIndicator(indicators),
            icon = preparedIcons[contentItemId(app)],
        )
    }
    val favoriteItems = apps.filter(LauncherApp::favorite).map { app ->
        PreparedContentItem(
            contentItemId(app),
            app.label,
            if (app.workProfile) "Work" else null,
            PreparedContentKind.APP,
            enabled = true,
            profile = if (app.workProfile) PreparedProfileKind.WORK else PreparedProfileKind.PERSONAL,
            indicator = app.eligibleIndicator(indicators),
            icon = preparedIcons[contentItemId(app)],
        )
    }
    val folderItems = snapshot.contentItems
        .asSequence()
        .filter { it.kind == org.quicklauncher.host.data.store.ContentItemKind.FOLDER }
        .sortedBy { it.id.value }
        .map { folder ->
            PreparedContentItem(
                id = folder.id,
                label = folder.encoded,
                supportingText = null,
                kind = PreparedContentKind.FOLDER,
                enabled = true,
            )
        }
        .toList()
    val clockItems = listOf(
        PreparedContentItem(
            ContentItemId.parse("org.quicklauncher.app/clock-time"),
            now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())),
            null,
            PreparedContentKind.TEXT,
            enabled = false,
        ),
        PreparedContentItem(
            ContentItemId.parse("org.quicklauncher.app/clock-date"),
            now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
            null,
            PreparedContentKind.TEXT,
            enabled = false,
        ),
    )
    fun catalogContent(items: List<PreparedContentItem>): PreparedBlockContent = when (catalogStatus) {
        AppCatalogStatus.LOADING -> PreparedBlockContent(RenderStatus.Loading, PreparedHostContent.Empty)
        AppCatalogStatus.ERROR -> PreparedBlockContent(
            RenderStatus.Error(
                org.quicklauncher.contracts.domain.StableKey.parse(catalogErrorCode ?: "catalog-error"),
                "Applications are unavailable",
            ),
            PreparedHostContent.Empty,
        )
        AppCatalogStatus.READY -> if (items.isEmpty()) {
            PreparedBlockContent.Empty
        } else {
            PreparedBlockContent.ready(PreparedHostContent(items, emptyList()))
        }
    }
    val contributionsByInstance = snapshot.moduleInstances.associate { it.id to it.contributionId }
    return PreparedContentSource { instanceId ->
        when (contributionsByInstance[instanceId]) {
            FAVORITES_BLOCK_ID -> catalogContent(favoriteItems)
            FOLDER_BLOCK_ID -> if (folderItems.isEmpty()) {
                PreparedBlockContent.Empty
            } else {
                PreparedBlockContent.ready(PreparedHostContent(folderItems, emptyList()))
            }
            CLOCK_BLOCK_ID ->
                PreparedBlockContent.ready(PreparedHostContent(clockItems, emptyList()))
            WIDGET_BLOCK_ID -> widgetContent(snapshot, instanceId, widgetSurfaces)
            else -> catalogContent(appItems + shortcutItems)
        }
    }
}

private fun LauncherApp.eligibleIndicator(
    indicators: Map<ProfilePackageIdentity, NotificationIndicator>,
): NotificationIndicator {
    if (!collectionVisible) return NotificationIndicator.None
    return indicators[ProfilePackageIdentity(identity.profile, identity.packageName)]
        ?: NotificationIndicator.None
}

internal fun widgetContent(
    snapshot: LauncherSnapshot,
    instanceId: ModuleInstanceId,
    surfaces: Map<ModuleInstanceId, WidgetSurfaceResult>,
): PreparedBlockContent {
    val placement = snapshot.widgetPlacements.singleOrNull { it.moduleInstanceId == instanceId }
        ?: return PreparedBlockContent.Empty
    return when (val surface = surfaces[instanceId]) {
        is WidgetSurfaceResult.Ready -> PreparedBlockContent.ready(
            PreparedHostContent(
                emptyList(),
                listOf(
                    PreparedHostSurface(
                        StableKey.parse(surface.token.value),
                        org.quicklauncher.contracts.ui.PreparedSurfaceKind.WIDGET,
                        RenderStatus.Ready,
                    ),
                ),
            ),
        )
        WidgetSurfaceResult.ProfileUnavailable -> PreparedBlockContent(
            RenderStatus.ProfileLocked(placement.profile),
            PreparedHostContent.Empty,
        )
        WidgetSurfaceResult.ProviderUnavailable -> PreparedBlockContent(
            RenderStatus.Error(StableKey.parse("widget-provider-unavailable"), "Widget provider unavailable"),
            PreparedHostContent.Empty,
        )
        is WidgetSurfaceResult.Failed -> PreparedBlockContent(
            RenderStatus.Error(StableKey.parse(surface.code), "Widget unavailable"),
            PreparedHostContent.Empty,
        )
        WidgetSurfaceResult.NotBound, null -> PreparedBlockContent(RenderStatus.Loading, PreparedHostContent.Empty)
    }
}

internal fun visibleWidgetInstances(
    snapshot: LauncherSnapshot,
    current: DestinationId?,
): Set<ModuleInstanceId> {
    val currentDestination = snapshot.destinations.singleOrNull { it.id == current } ?: return emptySet()
    val visibleDestinations = snapshot.destinations.asSequence()
        .filter {
            kotlin.math.abs(it.coordinate.x - currentDestination.coordinate.x) +
                kotlin.math.abs(it.coordinate.y - currentDestination.coordinate.y) <= 1
        }
        .map { it.id }
        .toSet()
    val roots = snapshot.destinationLayouts.asSequence()
        .filter { it.selected && it.destinationId in visibleDestinations }
        .map { it.layoutInstanceId }
        .toSet()
    val reachable = LinkedHashSet(roots)
    val pending = ArrayDeque(roots)
    while (pending.isNotEmpty()) {
        val parent = pending.removeFirst()
        snapshot.placements.asSequence()
            .filter { it.parentInstanceId == parent }
            .map { it.childInstanceId }
            .forEach { if (reachable.add(it)) pending.addLast(it) }
    }
    val widgetIds = snapshot.moduleInstances.asSequence()
        .filter { it.contributionId == WIDGET_BLOCK_ID }
        .map { it.id }
        .toSet()
    return reachable.intersect(widgetIds)
}

private val FAVORITES_BLOCK_ID = ContributionId.parse("org.quicklauncher.block/favorites")
private val FOLDER_BLOCK_ID = ContributionId.parse("org.quicklauncher.block/folder")
private val CLOCK_BLOCK_ID = ContributionId.parse("org.quicklauncher.block/clock-date")
private val WIDGET_BLOCK_ID = ContributionId.parse("org.quicklauncher.block/widget")
private const val MAX_PREPARED_APPLICATION_ICONS = 512
private const val MAX_PREPARED_ICON_DIMENSION = 512
private const val MAX_PREPARED_ICON_PIXELS = 512L * 512L

internal object ProductionPreparedContentRenderer : PreparedContentRenderer {
    @Composable
    override fun RenderItem(item: PreparedContentItem, modifier: Modifier) {
        val indicator = when (val value = item.indicator) {
            NotificationIndicator.None -> ""
            NotificationIndicator.Dot -> " •"
            is NotificationIndicator.ApproximateCount -> " ${value.bucket}+"
        }
        val bitmap = remember(item.icon) { item.icon?.let(::decodePreparedIcon) }
        DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            bitmap?.let { loaded ->
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).padding(end = 8.dp),
                )
            }
            Text(item.label + indicator)
        }
    }

    @Composable
    override fun RenderSurface(surface: PreparedHostSurface, modifier: Modifier) {
        Text("${surface.kind.name.lowercase()} unavailable", modifier = modifier)
    }
}

private fun decodePreparedIcon(icon: PreparedIcon): android.graphics.Bitmap? {
    val bytes = icon.bytes()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..MAX_PREPARED_ICON_DIMENSION ||
        bounds.outHeight !in 1..MAX_PREPARED_ICON_DIMENSION ||
        bounds.outWidth.toLong() * bounds.outHeight > MAX_PREPARED_ICON_PIXELS
    ) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

@Composable
private fun CompositionPlaceholder(issue: CompositionIssue, modifier: Modifier) {
    Box(
        modifier.fillMaxSize().semantics { contentDescription = "Unavailable module: ${issue.kind.name}" },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text("This module is unavailable", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun ProductionOnboardingPreview(
    preview: org.quicklauncher.host.editor.OnboardingPreview,
    apps: List<LauncherApp>,
    catalogStatus: AppCatalogStatus,
    catalogErrorCode: String?,
    resolvedTheme: ResolvedLauncherTheme,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val snapshot = preview.snapshot
    if (snapshot == null) {
        Text(preview.issues.joinToString())
        return
    }
    val configuration = LocalConfiguration.current
    val previewEngine = remember(snapshot) {
        DefaultCompositionEngine(org.quicklauncher.registry.production.productionRegistry, scope)
    }
    DisposableEffect(previewEngine) {
        onDispose { previewEngine.close() }
    }
    val environment = remember(
        snapshot,
        apps,
        catalogStatus,
        catalogErrorCode,
        resolvedTheme,
        configuration.screenWidthDp,
    ) {
        CompositionEnvironment(
            resolvedTheme = resolvedTheme,
            window = WindowInfo(
                configuration.screenWidthDp.coerceAtLeast(1),
                320,
                WindowOrientation.PORTRAIT,
            ),
            editorMode = EditorMode.BROWSING,
            contentSource = productionContentSource(snapshot, apps, catalogStatus, catalogErrorCode),
            contentRenderer = ProductionPreparedContentRenderer,
            placeholderRenderer = { issue, renderModifier -> CompositionPlaceholder(issue, renderModifier) },
        )
    }
    val prepared = remember(previewEngine, environment) {
        previewEngine.prepare(
            CompositionRequest(
                snapshot,
                checkNotNull(snapshot.startDestinationId),
                environment,
                currentInteractive = false,
            ),
        )
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(320.dp)
            .semantics {
                contentDescription = "Noninteractive preview of ${preview.plan.startDestination.draft.name.value}"
            },
    ) {
        prepared.current.Render(Modifier.fillMaxSize())
    }
}
