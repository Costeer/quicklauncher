package org.quicklauncher.host.runtime.composition

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.ActionSink
import org.quicklauncher.contracts.ui.BackgroundContrast
import org.quicklauncher.contracts.ui.BlockAction
import org.quicklauncher.contracts.ui.CompositionState
import org.quicklauncher.contracts.ui.EditorMode
import org.quicklauncher.contracts.ui.LauncherTheme
import org.quicklauncher.contracts.ui.LayoutAction
import org.quicklauncher.contracts.ui.PreparedContentRenderer
import org.quicklauncher.contracts.ui.PreparedHostContent
import org.quicklauncher.contracts.ui.PreparedHostSurface
import org.quicklauncher.contracts.ui.PreparedContentItem
import org.quicklauncher.contracts.ui.WindowInfo
import org.quicklauncher.contracts.ui.SearchPresentation
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.runtime.SelectedLayoutRestorer
import org.quicklauncher.host.runtime.theme.ResolvedLauncherTheme

private fun <T> immutable(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

fun interface PreparedContentSource {
    fun contentFor(instanceId: ModuleInstanceId): PreparedBlockContent
}

fun interface SearchPresentationSource {
    val hasActiveQuery: StateFlow<Boolean>
        get() = InactiveSearchPresentation

    @Composable
    fun presentationFor(instanceId: ModuleInstanceId): SearchPresentation?

    fun setHostActive(active: Boolean) = Unit

    fun requestFocus(): Boolean = false

    fun dismissAll() = Unit
}

private val InactiveSearchPresentation = MutableStateFlow(false)

data class PreparedBlockContent(
    val status: org.quicklauncher.contracts.ui.RenderStatus,
    val content: PreparedHostContent,
) {
    companion object {
        val Empty = PreparedBlockContent(
            org.quicklauncher.contracts.ui.RenderStatus.Empty,
            PreparedHostContent.Empty,
        )

        fun ready(content: PreparedHostContent): PreparedBlockContent =
            PreparedBlockContent(org.quicklauncher.contracts.ui.RenderStatus.Ready, content)
    }
}

/** Supplies the cold-start state needed by [CompositionEngine.restore]. */
fun interface CompositionRestoreRequestSource {
    suspend fun request(selectedLayoutInstanceId: ModuleInstanceId): CompositionRequest
}

fun interface CompositionPlaceholderRenderer {
    @Composable
    fun Render(issue: CompositionIssue, modifier: Modifier)
}

fun interface RendererFailureSink {
    fun report(issue: CompositionIssue, failure: RuntimeException)
}

data class CompositionEnvironment(
    val resolvedTheme: ResolvedLauncherTheme,
    val destinationThemes: Map<DestinationId, ResolvedLauncherTheme> = emptyMap(),
    val fallbackResolvedTheme: ResolvedLauncherTheme = resolvedTheme,
    val window: WindowInfo,
    val editorMode: EditorMode,
    val contentSource: PreparedContentSource = PreparedContentSource { PreparedBlockContent.Empty },
    val contentRenderer: PreparedContentRenderer = EmptyPreparedContentRenderer,
    val placeholderRenderer: CompositionPlaceholderRenderer = CompositionPlaceholderRenderer { _, _ -> },
    val layoutActions: ActionSink<LayoutAction> = RejectingLayoutActions,
    val blockActions: ActionSink<BlockAction> = RejectingBlockActions,
    val searchPresentations: SearchPresentationSource = SearchPresentationSource { null },
    val rendererFailures: RendererFailureSink = RendererFailureSink { _, _ -> },
) {
    val theme: LauncherTheme
        get() = resolvedTheme.theme

    val backgroundContrast: BackgroundContrast
        get() = resolvedTheme.backgroundContrast

    fun forDestination(destinationId: DestinationId): CompositionEnvironment {
        val destinationTheme = destinationThemes[destinationId] ?: fallbackResolvedTheme
        return if (destinationTheme == resolvedTheme) this else copy(resolvedTheme = destinationTheme)
    }

    constructor(
        theme: LauncherTheme,
        window: WindowInfo,
        backgroundContrast: BackgroundContrast,
        editorMode: EditorMode,
        contentSource: PreparedContentSource = PreparedContentSource { PreparedBlockContent.Empty },
        contentRenderer: PreparedContentRenderer = EmptyPreparedContentRenderer,
        placeholderRenderer: CompositionPlaceholderRenderer = CompositionPlaceholderRenderer { _, _ -> },
        layoutActions: ActionSink<LayoutAction> = RejectingLayoutActions,
        blockActions: ActionSink<BlockAction> = RejectingBlockActions,
        searchPresentations: SearchPresentationSource = SearchPresentationSource { null },
        rendererFailures: RendererFailureSink = RendererFailureSink { _, _ -> },
    ) : this(
        resolvedTheme = ResolvedLauncherTheme(theme, backgroundContrast),
        window = window,
        editorMode = editorMode,
        contentSource = contentSource,
        contentRenderer = contentRenderer,
        placeholderRenderer = placeholderRenderer,
        layoutActions = layoutActions,
        blockActions = blockActions,
        searchPresentations = searchPresentations,
        rendererFailures = rendererFailures,
    )
}

data class CompositionRequest(
    val snapshot: LauncherSnapshot,
    val currentDestinationId: DestinationId,
    val environment: CompositionEnvironment,
    val currentInteractive: Boolean = true,
)

enum class CompositionIssueKind {
    MISSING_DESTINATION,
    MISSING_LAYOUT,
    MISSING_INSTANCE,
    MISSING_CONTRIBUTION,
    WRONG_CONTRIBUTION_CATEGORY,
    QUARANTINED,
    MISSING_CONFIGURATION,
    CONFIGURATION_FAILED,
    PREPARED_CONTENT_FAILED,
    INCOMPATIBLE_PLACEMENT,
    PLACEMENT_CYCLE,
    SESSION_FAILED,
    RENDER_FAILED,
}

data class CompositionIssue(
    val kind: CompositionIssueKind,
    val instanceId: ModuleInstanceId?,
    val message: String,
) {
    init {
        require(message.isNotBlank()) { "Composition issue message must not be blank" }
    }
}

class PreparedDestination internal constructor(
    val destinationId: DestinationId,
    val layoutInstanceId: ModuleInstanceId?,
    val composition: CompositionState,
    issues: Collection<CompositionIssue>,
    private val renderer: DestinationRenderer,
) {
    val issues: List<CompositionIssue> = immutable(issues)

    @Composable
    fun Render(modifier: Modifier = Modifier) {
        renderer.Render(modifier)
    }
}

class PreparedComposition internal constructor(
    destinations: Collection<PreparedDestination>,
) {
    val destinations: List<PreparedDestination> = immutable(destinations)

    val current: PreparedDestination
        get() = destinations.single { it.composition.role == org.quicklauncher.contracts.ui.CompositionRole.CURRENT }
}

interface CompositionEngine : SelectedLayoutRestorer, AutoCloseable {
    /** Prepares the current destination and its cardinal neighbors as one live session set. */
    fun prepare(request: CompositionRequest): PreparedComposition

    /** Disposes the live composition while keeping the engine available for a later prepare. */
    fun release()
}

internal fun interface DestinationRenderer {
    @Composable
    fun Render(modifier: Modifier)
}

private object EmptyPreparedContentRenderer : PreparedContentRenderer {
    @Composable
    override fun RenderItem(item: PreparedContentItem, modifier: Modifier) = Unit

    @Composable
    override fun RenderSurface(surface: PreparedHostSurface, modifier: Modifier) = Unit
}

private val RejectingLayoutActions = rejectingActions<LayoutAction>()
private val RejectingBlockActions = rejectingActions<BlockAction>()

private fun <A> rejectingActions(): ActionSink<A> = ActionSink {
    ActionDispatchResult.Rejected(
        StableKey.parse("composition-not-interactive"),
        "The composition does not accept this action",
    )
}
