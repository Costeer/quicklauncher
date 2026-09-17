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
import org.quicklauncher.contracts.domain.SearchResultId
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
    val colors: ResolvedColorRoles = ResolvedColorRoles.basic(
        foreground = foreground,
        background = background,
        accent = accent,
    ),
    val fonts: ResolvedFontRoles = ResolvedFontRoles.System,
    val shapes: ResolvedShapeRoles = ResolvedShapeRoles.Default,
    val spacing: ResolvedSpacing = ResolvedSpacing.Default,
    val icons: ResolvedIconRendering = ResolvedIconRendering.Default,
    val accessibility: ResolvedAccessibilityValues = ResolvedAccessibilityValues.Default,
) {
    init {
        require(textScale > 0f && textScale.isFinite()) {
            "Theme text scale must be finite and greater than zero"
        }
    }
}

data class ResolvedCornerRadii(
    val extraSmallDp: Float,
    val smallDp: Float,
    val mediumDp: Float,
    val largeDp: Float,
    val extraLargeDp: Float,
) {
    init {
        require(listOf(extraSmallDp, smallDp, mediumDp, largeDp, extraLargeDp).all {
            it.isFinite() && it in 0f..96f
        }) { "Resolved corner radii must be finite and between zero and 96 dp" }
    }

    companion object {
        val Default = ResolvedCornerRadii(4f, 8f, 12f, 16f, 28f)
    }
}

data class ResolvedShapeRoles(
    val corners: ResolvedCornerRadii,
    val fullyRoundedDp: Float,
) {
    init {
        require(fullyRoundedDp.isFinite() && fullyRoundedDp in 0f..1_024f) {
            "Fully rounded shape size must be finite and bounded"
        }
    }

    companion object {
        val Default = ResolvedShapeRoles(ResolvedCornerRadii.Default, 1_024f)
    }
}

data class ResolvedSpacing(
    val extraSmallDp: Float,
    val smallDp: Float,
    val mediumDp: Float,
    val largeDp: Float,
    val extraLargeDp: Float,
) {
    init {
        require(listOf(extraSmallDp, smallDp, mediumDp, largeDp, extraLargeDp).all {
            it.isFinite() && it in 0f..128f
        }) { "Resolved spacing must be finite and between zero and 128 dp" }
    }

    companion object {
        val Default = ResolvedSpacing(4f, 8f, 16f, 24f, 32f)
    }
}

data class ResolvedIconRendering(
    val smallSizeDp: Float,
    val standardSizeDp: Float,
    val largeSizeDp: Float,
    val opticalScale: Float,
    val useRoundedMasks: Boolean,
) {
    init {
        require(listOf(smallSizeDp, standardSizeDp, largeSizeDp).all {
            it.isFinite() && it in 1f..256f
        }) { "Resolved icon sizes must be finite and bounded" }
        require(opticalScale.isFinite() && opticalScale in 0.5f..1.5f) {
            "Resolved icon optical scale must be finite and bounded"
        }
    }

    companion object {
        val Default = ResolvedIconRendering(24f, 48f, 64f, 1f, true)
    }
}

data class ResolvedAccessibilityValues(
    val minimumTouchTargetDp: Float,
    val highContrast: Boolean,
    val boldText: Boolean,
) {
    init {
        require(minimumTouchTargetDp.isFinite() && minimumTouchTargetDp in 24f..96f) {
            "Minimum touch target must be finite and between 24 and 96 dp"
        }
    }

    companion object {
        val Default = ResolvedAccessibilityValues(48f, false, false)
    }
}

/** Complete platform-neutral Material color roles selected by the host. */
data class ResolvedColorRoles(
    val primary: ArgbColor,
    val onPrimary: ArgbColor,
    val primaryContainer: ArgbColor,
    val onPrimaryContainer: ArgbColor,
    val secondary: ArgbColor,
    val onSecondary: ArgbColor,
    val secondaryContainer: ArgbColor,
    val onSecondaryContainer: ArgbColor,
    val tertiary: ArgbColor,
    val onTertiary: ArgbColor,
    val tertiaryContainer: ArgbColor,
    val onTertiaryContainer: ArgbColor,
    val error: ArgbColor,
    val onError: ArgbColor,
    val errorContainer: ArgbColor,
    val onErrorContainer: ArgbColor,
    val surface: ArgbColor,
    val onSurface: ArgbColor,
    val surfaceVariant: ArgbColor,
    val onSurfaceVariant: ArgbColor,
    val outline: ArgbColor,
    val outlineVariant: ArgbColor,
    val inverseSurface: ArgbColor,
    val inverseOnSurface: ArgbColor,
    val inversePrimary: ArgbColor,
    val scrim: ArgbColor,
) {
    companion object {
        fun basic(
            foreground: ArgbColor,
            background: ArgbColor,
            accent: ArgbColor,
        ): ResolvedColorRoles = ResolvedColorRoles(
            primary = accent,
            onPrimary = background,
            primaryContainer = accent,
            onPrimaryContainer = background,
            secondary = accent,
            onSecondary = background,
            secondaryContainer = background,
            onSecondaryContainer = foreground,
            tertiary = accent,
            onTertiary = background,
            tertiaryContainer = background,
            onTertiaryContainer = foreground,
            error = ArgbColor.of(0xffba1a1aL),
            onError = ArgbColor.of(0xffffffffL),
            errorContainer = ArgbColor.of(0xffffdad6L),
            onErrorContainer = ArgbColor.of(0xff410002L),
            surface = background,
            onSurface = foreground,
            surfaceVariant = background,
            onSurfaceVariant = foreground,
            outline = foreground,
            outlineVariant = foreground,
            inverseSurface = foreground,
            inverseOnSurface = background,
            inversePrimary = accent,
            scrim = ArgbColor.of(0xff000000L),
        )
    }
}

enum class ResolvedFontSource {
    SYSTEM,
    IMPORTED,
}

data class ResolvedFontRole(
    val source: ResolvedFontSource,
    val assetToken: StableKey?,
    val weight: Int,
    val italic: Boolean,
) {
    init {
        require(weight in 1..1000) { "Resolved font weight must be between 1 and 1000" }
        require((source == ResolvedFontSource.IMPORTED) == (assetToken != null)) {
            "Imported font roles require an opaque asset token and system roles must not have one"
        }
    }

    companion object {
        val System = ResolvedFontRole(ResolvedFontSource.SYSTEM, null, 400, false)
    }
}

data class ResolvedFontRoles(
    val display: ResolvedFontRole,
    val headline: ResolvedFontRole,
    val title: ResolvedFontRole,
    val body: ResolvedFontRole,
    val label: ResolvedFontRole,
) {
    companion object {
        val System = ResolvedFontRoles(
            display = ResolvedFontRole.System,
            headline = ResolvedFontRole.System,
            title = ResolvedFontRole.System,
            body = ResolvedFontRole.System,
            label = ResolvedFontRole.System,
        )
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

/** Profile information safe for ordinary contribution rendering. Private profiles never cross this seam. */
enum class PreparedProfileKind {
    PERSONAL,
    WORK,
    NOT_APPLICABLE,
}

enum class PreparedAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

enum class PreparedIconSource {
    APPLICATION,
    ICON_PACK,
}

/** Host-decoded bounded icon bytes. Contributions may only hand this value to the host renderer. */
class PreparedIcon private constructor(
    bytes: ByteArray,
    val source: PreparedIconSource,
) {
    private val value = bytes.copyOf()

    fun bytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is PreparedIcon &&
        source == other.source && value.contentEquals(other.value)

    override fun hashCode(): Int = 31 * value.contentHashCode() + source.hashCode()
    override fun toString(): String = "PreparedIcon(source=$source, content=redacted)"

    companion object {
        fun of(bytes: ByteArray, source: PreparedIconSource): PreparedIcon {
            require(bytes.size in 1..MAX_PREPARED_ICON_BYTES) { "Prepared icon exceeds its byte bound" }
            return PreparedIcon(bytes, source)
        }

        private const val MAX_PREPARED_ICON_BYTES = 1024 * 1024
    }
}

/** Sanitized notification state. It deliberately has no field capable of carrying content. */
sealed interface NotificationIndicator {
    data object None : NotificationIndicator
    data object Dot : NotificationIndicator

    data class ApproximateCount(val bucket: Int) : NotificationIndicator {
        init {
            require(bucket > 0) { "Approximate notification count must be positive" }
        }
    }
}

data class PreparedContentItem(
    val id: ContentItemId,
    val label: String,
    val supportingText: String?,
    val kind: PreparedContentKind,
    val enabled: Boolean,
    val profile: PreparedProfileKind = PreparedProfileKind.NOT_APPLICABLE,
    val availability: PreparedAvailability = if (enabled) {
        PreparedAvailability.AVAILABLE
    } else {
        PreparedAvailability.UNAVAILABLE
    },
    val indicator: NotificationIndicator = NotificationIndicator.None,
    val icon: PreparedIcon? = null,
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

enum class SearchPresentationProviderState {
    IDLE,
    LOADING,
    READY,
    DENIED,
    UNAVAILABLE,
    TIMED_OUT,
    FAILED,
}

data class SearchPresentationToken(
    val sessionId: Long,
    val generation: Long,
    val providerId: ContributionId,
    val resultId: SearchResultId,
)

data class PreparedSearchResult(
    val token: SearchPresentationToken,
    val providerLabel: String,
    val title: String,
    val subtitle: String?,
    val workBadged: Boolean,
) {
    init {
        require(providerLabel.isNotBlank()) { "Prepared search provider label must not be blank" }
        require(title.isNotBlank()) { "Prepared search title must not be blank" }
        require(subtitle == null || subtitle.isNotBlank()) {
            "Prepared search subtitle must be null or nonblank"
        }
    }

    override fun toString(): String = "PreparedSearchResult(workBadged=$workBadged, content=redacted)"
}

class SearchPresentationState(
    val query: String,
    results: Collection<PreparedSearchResult>,
    providerStates: Map<ContributionId, SearchPresentationProviderState>,
    providerLabels: Map<ContributionId, String> = emptyMap(),
    val focusRequestId: Long = 0L,
) {
    val results: List<PreparedSearchResult> = immutableList(results)
    val providerStates: Map<ContributionId, SearchPresentationProviderState> =
        Collections.unmodifiableMap(LinkedHashMap(providerStates))
    val providerLabels: Map<ContributionId, String> =
        Collections.unmodifiableMap(LinkedHashMap(providerLabels))

    init {
        require(query.length <= 512) { "Presentation query must not exceed 512 characters" }
        require('\u0000' !in query) { "Presentation query must not contain a null character" }
        require(providerLabels.values.all(String::isNotBlank)) {
            "Presentation provider labels must not be blank"
        }
        require(focusRequestId >= 0L) { "Search focus request identity must not be negative" }
    }
}

sealed interface SearchPresentationAction {
    data class QueryChanged(
        val value: String,
        val minimumCharacters: Int = 0,
    ) : SearchPresentationAction {
        init {
            require(minimumCharacters in 0..32) {
                "Minimum search characters must be between 0 and 32"
            }
        }
        override fun toString(): String = "QueryChanged(value=redacted)"
    }
    data class Activate(val token: SearchPresentationToken) : SearchPresentationAction
    data object Dismiss : SearchPresentationAction
}

data class SearchPresentation(
    val state: SearchPresentationState,
    val actions: ActionSink<SearchPresentationAction>,
)

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
    val search: SearchPresentation? = null,
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
