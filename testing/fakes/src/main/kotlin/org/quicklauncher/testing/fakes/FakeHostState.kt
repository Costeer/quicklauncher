package org.quicklauncher.testing.fakes

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.quicklauncher.contracts.contribution.CancellationSignal
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.SlotTypeId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.ActionSink
import org.quicklauncher.contracts.ui.BackgroundContrast
import org.quicklauncher.contracts.ui.BlockRenderState
import org.quicklauncher.contracts.ui.CompositionRole
import org.quicklauncher.contracts.ui.CompositionState
import org.quicklauncher.contracts.ui.EditorMode
import org.quicklauncher.contracts.ui.LauncherTheme
import org.quicklauncher.contracts.ui.LayoutRenderState
import org.quicklauncher.contracts.ui.PlacedChild
import org.quicklauncher.contracts.ui.PlacementMode
import org.quicklauncher.contracts.ui.PlacementState
import org.quicklauncher.contracts.ui.PreparedContentItem
import org.quicklauncher.contracts.ui.PreparedContentKind
import org.quicklauncher.contracts.ui.PreparedContentRenderer
import org.quicklauncher.contracts.ui.PreparedHostContent
import org.quicklauncher.contracts.ui.PreparedHostSurface
import org.quicklauncher.contracts.ui.PreparedSurfaceKind
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.contracts.ui.RenderStatus
import org.quicklauncher.contracts.ui.SlotRenderState
import org.quicklauncher.contracts.ui.SlotRenderer
import org.quicklauncher.contracts.ui.ThemeMode
import org.quicklauncher.contracts.ui.WindowInfo
import org.quicklauncher.contracts.ui.WindowOrientation

class FakeCancellationSignal : CancellationSignal {
    private var cancelled = false

    override val isCancelled: Boolean
        get() = cancelled

    fun cancel() {
        cancelled = true
    }
}

class FakeInstanceLifecycle : AutoCloseable {
    private val job = SupervisorJob()
    val scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default)
    val hasActiveChildren: Boolean get() = job.children.any { child -> child.isActive }
    val isClosed: Boolean get() = !job.isActive

    override fun close() {
        scope.cancel()
    }
}

class RecordingActionSink<A> : ActionSink<A> {
    private val recorded = mutableListOf<A>()
    private var disposed = false

    val actions: List<A>
        get() = Collections.unmodifiableList(ArrayList(recorded))

    override fun emit(action: A): ActionDispatchResult {
        if (disposed) {
            return ActionDispatchResult.Rejected(
                StableKey.parse("disposed"),
                "The fake host instance is disposed",
            )
        }
        recorded += action
        return ActionDispatchResult.Accepted
    }

    fun dispose() {
        disposed = true
    }
}

class RecordingSlotRenderer : SlotRenderer {
    private val recorded = mutableListOf<SlotRenderState>()
    val slots: List<SlotRenderState> get() = Collections.unmodifiableList(ArrayList(recorded))

    @Composable
    override fun Render(slot: SlotRenderState, modifier: Modifier) {
        recorded += slot
    }
}

class RecordingPreparedContentRenderer : PreparedContentRenderer {
    private val recordedItems = mutableListOf<PreparedContentItem>()
    private val recordedSurfaces = mutableListOf<PreparedHostSurface>()
    val items: List<PreparedContentItem> get() = Collections.unmodifiableList(ArrayList(recordedItems))
    val surfaces: List<PreparedHostSurface> get() = Collections.unmodifiableList(ArrayList(recordedSurfaces))

    @Composable
    override fun RenderItem(item: PreparedContentItem, modifier: Modifier) {
        recordedItems += item
    }

    @Composable
    override fun RenderSurface(surface: PreparedHostSurface, modifier: Modifier) {
        recordedSurfaces += surface
    }
}

object FakeHostStates {
    private val permissionCapability =
        CapabilityId.parse("org.quicklauncher.capability/sample-permission")
    private val profile = ProfileSerial.of(10)

    fun layout(
        scenario: PreviewScenario,
        orientation: WindowOrientation = WindowOrientation.PORTRAIT,
        themeMode: ThemeMode = ThemeMode.DARK,
        reducedMotion: Boolean = false,
    ): LayoutRenderState = LayoutRenderState(
        theme = theme(scenario, themeMode, reducedMotion),
        window = window(orientation),
        backgroundContrast = contrast(themeMode),
        status = status(scenario),
        editorMode = if (scenario == PreviewScenario.NORMAL) EditorMode.BROWSING else EditorMode.PREVIEW,
        composition = composition(scenario),
        placement = placement(scenario, parentSlotId = null),
        slots = listOf(sampleSlot(scenario)),
    )

    fun block(
        scenario: PreviewScenario,
        orientation: WindowOrientation = WindowOrientation.PORTRAIT,
        themeMode: ThemeMode = ThemeMode.DARK,
        reducedMotion: Boolean = false,
    ): BlockRenderState = BlockRenderState(
        theme = theme(scenario, themeMode, reducedMotion),
        window = window(orientation),
        backgroundContrast = contrast(themeMode),
        status = status(scenario),
        editorMode = if (scenario == PreviewScenario.NORMAL) EditorMode.EDITING else EditorMode.PREVIEW,
        composition = composition(scenario),
        placement = placement(scenario, parentSlotId = StableKey.parse("main")),
        content = if (scenario == PreviewScenario.NORMAL) sampleContent() else PreparedHostContent.Empty,
        childSlots = listOf(
            SlotRenderState(
                id = StableKey.parse("children"),
                type = SlotTypeId.parse("org.quicklauncher.slot/leaf"),
                status = status(scenario),
                placements = emptyList(),
            ),
        ),
    )

    fun status(scenario: PreviewScenario): RenderStatus = when (scenario) {
        PreviewScenario.EMPTY -> RenderStatus.Empty
        PreviewScenario.NORMAL -> RenderStatus.Ready
        PreviewScenario.LOADING -> RenderStatus.Loading
        PreviewScenario.PERMISSION_DENIED -> RenderStatus.PermissionDenied(permissionCapability)
        PreviewScenario.PROFILE_LOCKED -> RenderStatus.ProfileLocked(profile)
        PreviewScenario.LARGE_TEXT -> RenderStatus.Ready
        PreviewScenario.ERROR -> RenderStatus.Error(
            StableKey.parse("sample-error"),
            "Deterministic fake error",
        )
    }

    fun theme(
        scenario: PreviewScenario,
        mode: ThemeMode = ThemeMode.DARK,
        reducedMotion: Boolean = false,
    ): LauncherTheme = LauncherTheme(
        mode = mode,
        foreground = ArgbColor.of(if (mode == ThemeMode.DARK) 0xffeeeeee else 0xff111111),
        background = ArgbColor.of(if (mode == ThemeMode.DARK) 0xff111111 else 0xffeeeeee),
        accent = ArgbColor.of(if (mode == ThemeMode.DARK) 0xff4488ff else 0xff0055cc),
        textScale = if (scenario == PreviewScenario.LARGE_TEXT) 2f else 1f,
        reducedMotion = reducedMotion,
    )

    private fun window(orientation: WindowOrientation): WindowInfo = when (orientation) {
        WindowOrientation.PORTRAIT -> WindowInfo(360, 800, orientation)
        WindowOrientation.LANDSCAPE -> WindowInfo(800, 360, orientation)
    }

    private fun contrast(mode: ThemeMode): BackgroundContrast = BackgroundContrast(
        textContrastRatio = 15f,
        nonTextContrastRatio = 5.5f,
        prefersLightForeground = mode == ThemeMode.DARK,
    )

    private fun composition(scenario: PreviewScenario): CompositionState = when (scenario) {
        PreviewScenario.NORMAL -> CompositionState(CompositionRole.CURRENT, isInteractive = true)
        PreviewScenario.LOADING,
        PreviewScenario.PERMISSION_DENIED,
        -> CompositionState(CompositionRole.NEIGHBOR_PREVIEW, isInteractive = false)
        else -> CompositionState(CompositionRole.EDITOR_PREVIEW, isInteractive = false)
    }

    private fun placement(scenario: PreviewScenario, parentSlotId: StableKey?): PlacementState =
        PlacementState(
            parentSlotId = parentSlotId,
            index = 0,
            mode = when (scenario) {
                PreviewScenario.LOADING -> PlacementMode.DRAGGING
                PreviewScenario.PERMISSION_DENIED -> PlacementMode.DROP_TARGET
                PreviewScenario.PROFILE_LOCKED,
                PreviewScenario.ERROR,
                -> PlacementMode.REJECTED
                else -> PlacementMode.PLACED
            },
            canMove = parentSlotId != null,
            canRemove = parentSlotId != null,
        )

    private fun sampleSlot(scenario: PreviewScenario): SlotRenderState = SlotRenderState(
        id = StableKey.parse("main"),
        type = SlotTypeId.parse("org.quicklauncher.slot/content"),
        status = status(scenario),
        placements = if (scenario == PreviewScenario.NORMAL) {
            listOf(
                PlacedChild(
                    ModuleInstanceId.parse("org.quicklauncher.instance/sample-block"),
                    ContributionId.parse("org.quicklauncher.samples/block"),
                    0,
                ),
            )
        } else {
            emptyList()
        },
    )

    private fun sampleContent(): PreparedHostContent = PreparedHostContent(
        items = listOf(
            PreparedContentItem(
                ContentItemId.parse("org.quicklauncher.content/sample-app"),
                "Sample app",
                null,
                PreparedContentKind.APP,
                enabled = true,
            ),
        ),
        surfaces = listOf(
            PreparedHostSurface(
                StableKey.parse("sample-surface"),
                PreparedSurfaceKind.WIDGET,
                RenderStatus.Ready,
            ),
        ),
    )
}
