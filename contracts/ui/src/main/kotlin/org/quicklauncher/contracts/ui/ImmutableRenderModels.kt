package org.quicklauncher.contracts.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.Collections
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.SlotTypeId
import org.quicklauncher.contracts.domain.StableKey

internal fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

data class LauncherTheme(
    val mode: ThemeMode,
    val foreground: ArgbColor,
    val background: ArgbColor,
    val accent: ArgbColor,
    val textScale: Float,
    val reducedMotion: Boolean,
) {
    init {
        require(textScale > 0f && textScale.isFinite()) {
            "Theme text scale must be finite and greater than zero"
        }
    }
}

enum class ThemeMode {
    LIGHT,
    DARK,
}

data class BackgroundContrast(
    val textContrastRatio: Float,
    val nonTextContrastRatio: Float,
    val prefersLightForeground: Boolean,
) {
    init {
        require(textContrastRatio.isFinite() && textContrastRatio in 1f..21f) {
            "Text contrast ratio must be finite and between 1 and 21"
        }
        require(nonTextContrastRatio.isFinite() && nonTextContrastRatio in 1f..21f) {
            "Non-text contrast ratio must be finite and between 1 and 21"
        }
    }
}

enum class WindowOrientation {
    PORTRAIT,
    LANDSCAPE,
}

data class WindowInfo(
    val widthDp: Int,
    val heightDp: Int,
    val orientation: WindowOrientation,
) {
    init {
        require(widthDp > 0 && heightDp > 0) { "Window dimensions must be greater than zero" }
    }
}

enum class EditorMode {
    BROWSING,
    EDITING,
    PREVIEW,
}

enum class CompositionRole {
    CURRENT,
    NEIGHBOR_PREVIEW,
    EDITOR_PREVIEW,
}

data class CompositionState(
    val role: CompositionRole,
    val isInteractive: Boolean,
) {
    init {
        require(role == CompositionRole.CURRENT || !isInteractive) {
            "Only the current destination composition may be interactive"
        }
    }
}

enum class PlacementMode {
    PLACED,
    DRAGGING,
    DROP_TARGET,
    REJECTED,
}

data class PlacementState(
    val parentSlotId: StableKey?,
    val index: Int,
    val mode: PlacementMode,
    val canMove: Boolean,
    val canRemove: Boolean,
) {
    init {
        require(index >= 0) { "Placement index must not be negative" }
    }

    companion object {
        fun root(): PlacementState = PlacementState(
            parentSlotId = null,
            index = 0,
            mode = PlacementMode.PLACED,
            canMove = false,
            canRemove = false,
        )
    }
}

@JvmInline
value class EncodedPlacement private constructor(val value: String) {
    companion object {
        fun of(value: String): EncodedPlacement = EncodedPlacement(value)
    }
}

data class PlacementRenderData(
    val schemaVersion: Int,
    val encoded: EncodedPlacement,
) {
    init {
        require(schemaVersion >= 1) { "Placement schema version must be positive" }
    }
}

data class PlacedChild(
    val instanceId: ModuleInstanceId,
    val contributionId: ContributionId,
    val index: Int,
    val placement: PlacementRenderData = PlacementRenderData(
        schemaVersion = 1,
        encoded = EncodedPlacement.of("{}"),
    ),
) {
    init {
        require(index >= 0) { "Child placement index must not be negative" }
    }
}

sealed interface RenderStatus {
    data object Empty : RenderStatus
    data object Ready : RenderStatus
    data object Loading : RenderStatus
    data class PermissionDenied(val capability: CapabilityId) : RenderStatus
    data class ProfileLocked(val profile: ProfileSerial) : RenderStatus
    data class Error(val code: StableKey, val message: String) : RenderStatus {
        init {
            require(message.isNotBlank()) { "Render error message must not be blank" }
        }
    }
}

class SlotRenderState(
    val id: StableKey,
    val type: SlotTypeId,
    val status: RenderStatus,
    placements: Collection<PlacedChild>,
) {
    val placements: List<PlacedChild> = immutableList(placements)
}

enum class PreparedContentKind {
    APP,
    SHORTCUT,
    FOLDER,
    TEXT,
}

data class PreparedContentItem(
    val id: ContentItemId,
    val label: String,
    val supportingText: String?,
    val kind: PreparedContentKind,
    val enabled: Boolean,
) {
    init {
        require(label.isNotBlank()) { "Prepared content label must not be blank" }
        require(supportingText == null || supportingText.isNotBlank()) {
            "Prepared supporting text must be null or nonblank"
        }
    }
}

enum class PreparedSurfaceKind {
    WIDGET,
    HOST_FOLDER,
}

data class PreparedHostSurface(
    val key: StableKey,
    val kind: PreparedSurfaceKind,
    val status: RenderStatus,
)

class PreparedHostContent(
    items: Collection<PreparedContentItem>,
    surfaces: Collection<PreparedHostSurface>,
) {
    val items: List<PreparedContentItem> = immutableList(items)
    val surfaces: List<PreparedHostSurface> = immutableList(surfaces)

    override fun equals(other: Any?): Boolean = other is PreparedHostContent &&
        items == other.items && surfaces == other.surfaces

    override fun hashCode(): Int = 31 * items.hashCode() + surfaces.hashCode()
    override fun toString(): String = "PreparedHostContent(items=$items, surfaces=$surfaces)"

    companion object {
        val Empty = PreparedHostContent(emptyList(), emptyList())
    }
}

interface SlotRenderer {
    @Composable
    fun Render(slot: SlotRenderState, modifier: Modifier = Modifier)

    /** Renders one child so the parent contribution keeps ownership of spatial arrangement. */
    @Composable
    fun RenderChild(
        slot: SlotRenderState,
        child: PlacedChild,
        modifier: Modifier = Modifier,
    ) {
        require(child in slot.placements) { "Child '${child.instanceId}' is not present in slot '${slot.id}'" }
        Render(
            SlotRenderState(slot.id, slot.type, slot.status, listOf(child)),
            modifier,
        )
    }
}

interface PreparedContentRenderer {
    @Composable
    fun RenderItem(item: PreparedContentItem, modifier: Modifier = Modifier)

    @Composable
    fun RenderSurface(surface: PreparedHostSurface, modifier: Modifier = Modifier)
}

class LayoutRenderState(
    val theme: LauncherTheme,
    val window: WindowInfo,
    val backgroundContrast: BackgroundContrast,
    val status: RenderStatus,
    val editorMode: EditorMode,
    val composition: CompositionState,
    val placement: PlacementState,
    slots: Collection<SlotRenderState>,
) {
    val slots: List<SlotRenderState> = immutableList(slots)
}

class BlockRenderState(
    val theme: LauncherTheme,
    val window: WindowInfo,
    val backgroundContrast: BackgroundContrast,
    val status: RenderStatus,
    val editorMode: EditorMode,
    val composition: CompositionState,
    val placement: PlacementState,
    val content: PreparedHostContent,
    childSlots: Collection<SlotRenderState>,
) {
    val childSlots: List<SlotRenderState> = immutableList(childSlots)
}

data class LayoutRenderInput(
    val instanceId: ModuleInstanceId,
    val state: LayoutRenderState,
    val slots: SlotRenderer,
    val actions: ActionSink<LayoutAction>,
)

data class BlockRenderInput(
    val instanceId: ModuleInstanceId,
    val state: BlockRenderState,
    val slots: SlotRenderer,
    val content: PreparedContentRenderer,
    val actions: ActionSink<BlockAction>,
)

fun interface ActionSink<in A> {
    fun emit(action: A): ActionDispatchResult
}

sealed interface ActionDispatchResult {
    data object Accepted : ActionDispatchResult

    data class Rejected(
        val reason: StableKey,
        val message: String,
    ) : ActionDispatchResult {
        init {
            require(message.isNotBlank()) { "Action rejection message must not be blank" }
        }
    }
}

sealed interface LayoutAction {
    data class OpenSettings(val instanceId: ModuleInstanceId) : LayoutAction
    data class SelectSlot(val slotId: StableKey) : LayoutAction
}

sealed interface BlockAction {
    data class OpenSettings(val instanceId: ModuleInstanceId) : BlockAction
    data class ActivateItem(val itemId: org.quicklauncher.contracts.domain.ContentItemId) : BlockAction
    data class OpenItemActions(val itemId: org.quicklauncher.contracts.domain.ContentItemId) : BlockAction
}

enum class PreviewScenario {
    EMPTY,
    NORMAL,
    LOADING,
    PERMISSION_DENIED,
    PROFILE_LOCKED,
    LARGE_TEXT,
    ERROR,
}

data class PreviewFixture<T>(
    val scenario: PreviewScenario,
    val input: T,
)

enum class PerformanceMetric {
    FIRST_RENDER,
    ACTION_DISPATCH,
    DISPOSAL,
    FIRST_RESULT,
    QUERY_REPLACEMENT,
    COMMAND_EXECUTION,
    DRAFT_CREATION,
}

data class PerformanceHookDeclaration(
    val metric: PerformanceMetric,
    val description: String,
) {
    init {
        require(description.isNotBlank()) { "Performance hook description must not be blank" }
    }
}
