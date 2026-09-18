package org.quicklauncher.host.runtime.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.draggable2D
import androidx.compose.foundation.gestures.rememberDraggable2DState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.host.data.preferences.GestureMode
import org.quicklauncher.host.data.store.DestinationRecord

/**
 * Observable Compose adapter around [SpatialNavigator]. The same neighbor mapping drives touch,
 * keyboard, D-pad, and accessibility movement. Predictive-Back owners call [cancelPreview] before
 * consuming Back; the navigation root never turns Back into map movement.
 */
@Stable
class SpatialNavigationState(
    destinations: Collection<DestinationRecord>,
    current: DestinationId,
    internal val navigator: SpatialNavigator = DefaultSpatialNavigator(),
    onDestinationChanged: (DestinationId) -> Unit = {},
) {
    var frame: NavigationFrame by mutableStateOf(navigator.update(destinations, current))
        private set

    private var destinationChanged = onDestinationChanged
    private var activeConnection: SpatialNestedScrollConnection? = null
    private var destinationSnapshot = destinations.toList()

    fun update(
        destinations: Collection<DestinationRecord>,
        current: DestinationId,
        onDestinationChanged: (DestinationId) -> Unit = destinationChanged,
    ) {
        destinationChanged = onDestinationChanged
        val updatedDestinations = destinations.toList()
        if (frame.current != current || destinationSnapshot != updatedDestinations) {
            destinationSnapshot = updatedDestinations
            frame = navigator.update(updatedDestinations, current)
        }
    }

    fun move(direction: SpatialDirection): Boolean {
        val target = navigator.move(direction) ?: return false
        frame = navigator.frame
        destinationChanged(target)
        return true
    }

    /** Returns true only when an active gesture preview was dismissed. */
    fun cancelPreview(): Boolean {
        val result = activeConnection?.cancelPreview() ?: navigator.cancel()?.also {
            frame = navigator.frame
        }
        return result != null
    }

    internal fun bind(connection: SpatialNestedScrollConnection?) {
        activeConnection = connection
    }

    internal fun publish(updated: NavigationFrame) {
        frame = updated
    }

    internal fun complete(result: NavigationEnd) {
        if (result is NavigationEnd.Settled) destinationChanged(result.destinationId)
    }
}

@Composable
fun rememberSpatialNavigationState(
    destinations: Collection<DestinationRecord>,
    current: DestinationId,
    onDestinationChanged: (DestinationId) -> Unit,
): SpatialNavigationState {
    val state = remember { SpatialNavigationState(destinations, current, onDestinationChanged = onDestinationChanged) }
    SideEffect { state.update(destinations, current, onDestinationChanged) }
    return state
}

/** Hosts exactly the current destination and its cardinal preview neighbors. */
@Composable
fun SpatialNavigationSurface(
    state: SpatialNavigationState,
    mode: GestureMode,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    destination: @Composable (destinationId: DestinationId) -> Unit,
) {
    var viewportWidthPx by remember { mutableStateOf(1f) }
    var viewportHeightPx by remember { mutableStateOf(1f) }
    val connection = remember(state, mode) {
        SpatialNestedScrollConnection(
            navigator = state.navigator,
            mode = mode,
            viewportWidthPx = { viewportWidthPx },
            viewportHeightPx = { viewportHeightPx },
            onNavigationEnd = state::complete,
            onFrameChanged = state::publish,
        )
    }
    DisposableEffect(connection) {
        state.bind(connection)
        onDispose { state.bind(null) }
    }
    val fallbackDrag = rememberDraggable2DState { delta ->
        if (mode == GestureMode.EDGE_ACTIVATION) {
            connection.onPreScroll(delta, androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput)
        } else {
            connection.onPostScroll(
                Offset.Zero,
                delta,
                androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput,
            )
        }
    }

    val activeDirection = state.frame.drag?.direction
    var displayedDirection by remember { mutableStateOf(activeDirection) }
    if (activeDirection != null) displayedDirection = activeDirection
    val displayedProgress by animateFloatAsState(
        targetValue = state.frame.drag?.progress ?: 0f,
        animationSpec = SpatialNavigationMotion.settleAnimationSpec(reducedMotion),
        label = "destination preview",
    )

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged {
                viewportWidthPx = it.width.coerceAtLeast(1).toFloat()
                viewportHeightPx = it.height.coerceAtLeast(1).toFloat()
            }
            .pointerInput(connection) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        event.changes.firstOrNull()?.position?.let(connection::updatePointerPosition)
                    } while (event.changes.any { it.pressed })
                }
            }
            .nestedScroll(connection)
            .draggable2D(
                state = fallbackDrag,
                onDragStarted = connection::updatePointerPosition,
                onDragStopped = { connection.settle(it) },
            )
            .onPreviewKeyEvent { event ->
                val direction = event.key.spatialDirection()
                event.type == KeyEventType.KeyDown && direction != null && state.move(direction)
            }
            .focusable()
            .semantics {
                stateDescription = if (reducedMotion) "Reduced motion" else "Spatial navigation"
                customActions = state.frame.neighbors.keys.map { direction ->
                    CustomAccessibilityAction(direction.accessibilityLabel()) { state.move(direction) }
                }
            }
            .fillMaxSize(),
    ) {
        state.frame.composedDestinations.forEach { id ->
            val direction = state.frame.neighbors.entries.singleOrNull { it.value == id }?.key
            val interactive = id == state.frame.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val baseX = when (direction) {
                            SpatialDirection.LEFT -> -viewportWidthPx
                            SpatialDirection.RIGHT -> viewportWidthPx
                            else -> 0f
                        }
                        val baseY = when (direction) {
                            SpatialDirection.UP -> -viewportHeightPx
                            SpatialDirection.DOWN -> viewportHeightPx
                            else -> 0f
                        }
                        val previewX = when (displayedDirection) {
                            SpatialDirection.LEFT -> displayedProgress * viewportWidthPx
                            SpatialDirection.RIGHT -> -displayedProgress * viewportWidthPx
                            else -> 0f
                        }
                        val previewY = when (displayedDirection) {
                            SpatialDirection.UP -> displayedProgress * viewportHeightPx
                            SpatialDirection.DOWN -> -displayedProgress * viewportHeightPx
                            else -> 0f
                        }
                        translationX = baseX + previewX
                        translationY = baseY + previewY
                    }
                    .then(if (interactive) Modifier else Modifier.nonInteractivePreview()),
            ) {
                destination(id)
            }
        }
    }
}

object SpatialNavigationMotion {
    const val SETTLE_DURATION_MILLIS: Int = 160

    internal fun settleAnimationSpec(reducedMotion: Boolean): DurationBasedAnimationSpec<Float> =
        if (reducedMotion) snap() else tween(SETTLE_DURATION_MILLIS)
}

private fun Modifier.nonInteractivePreview(): Modifier =
    clearAndSetSemantics { }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }

private fun Key.spatialDirection(): SpatialDirection? = when (this) {
    Key.DirectionLeft -> SpatialDirection.LEFT
    Key.DirectionRight -> SpatialDirection.RIGHT
    Key.DirectionUp -> SpatialDirection.UP
    Key.DirectionDown -> SpatialDirection.DOWN
    else -> null
}

private fun SpatialDirection.accessibilityLabel(): String = when (this) {
    SpatialDirection.LEFT -> "Move to destination on the left"
    SpatialDirection.RIGHT -> "Move to destination on the right"
    SpatialDirection.UP -> "Move to destination above"
    SpatialDirection.DOWN -> "Move to destination below"
}
