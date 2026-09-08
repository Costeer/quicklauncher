package org.quicklauncher.host.runtime.navigation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import org.quicklauncher.host.data.preferences.GestureMode

/**
 * Converts only movement left unconsumed by child content into destination navigation.
 * Edge-only mode uses the same state machine but may begin during pre-scroll inside the edge band.
 */
class SpatialNestedScrollConnection(
    private val navigator: SpatialNavigator,
    private val mode: GestureMode,
    private val viewportWidthPx: () -> Float,
    private val viewportHeightPx: () -> Float,
    private val onNavigationEnd: (NavigationEnd) -> Unit,
    private val onFrameChanged: (NavigationFrame) -> Unit = {},
) : NestedScrollConnection {
    private var pointer = Offset.Unspecified
    private var accumulated = 0f
    private var activeDirection: SpatialDirection? = null

    fun updatePointerPosition(position: Offset) {
        require(position.isSpecified && position.x.isFinite() && position.y.isFinite()) {
            "Pointer position must be finite"
        }
        pointer = position
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        val unwound = unwindPreview(available)
        if (unwound != Offset.Zero || mode != GestureMode.EDGE_ACTIVATION) return unwound
        return consumeNavigation(available)
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = if (mode == GestureMode.CONTENT_HANDOFF && source == NestedScrollSource.UserInput) {
        consumeNavigation(available)
    } else {
        Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val direction = activeDirection ?: return Velocity.Zero
        finish(direction.velocity(available))
        return direction.velocityOnly(available)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // A child can bypass pre-fling when no velocity remains. Settle an open preview by
        // distance, but never reinterpret residual child velocity as destination movement.
        finish(velocityPxPerSecond = 0f)
        return Velocity.Zero
    }

    fun cancelOrSettle(): NavigationEnd? = finish(velocityPxPerSecond = 0f)

    fun settle(velocity: Velocity): NavigationEnd? {
        val direction = activeDirection ?: return null
        return finish(direction.velocity(velocity))
    }

    /** Lets a predictive-Back owner dismiss a preview before handling its own Back action. */
    fun cancelPreview(): NavigationEnd? {
        if (activeDirection == null) return null
        val result = navigator.cancel()
        clearDrag()
        result?.let(onNavigationEnd)
        onFrameChanged(navigator.frame)
        return result
    }

    private fun consumeNavigation(available: Offset): Offset {
        if (available == Offset.Zero) return Offset.Zero
        val direction = activeDirection ?: directionOf(available)
        if (activeDirection == null) {
            val pointerPosition = when (direction) {
                SpatialDirection.LEFT, SpatialDirection.RIGHT -> pointer.x
                SpatialDirection.UP, SpatialDirection.DOWN -> pointer.y
            }
            if (!pointerPosition.isFinite()) return Offset.Zero
            val viewport = extent(direction)
            if (navigator.begin(direction, mode, pointerPosition, viewport) !is NavigationStart.Started) {
                return Offset.Zero
            }
            activeDirection = direction
        }
        val distance = when (direction) {
            SpatialDirection.LEFT, SpatialDirection.RIGHT -> available.x
            SpatialDirection.UP, SpatialDirection.DOWN -> available.y
        }
        val before = accumulated
        accumulated = direction.clampDistance(accumulated + distance, extent(direction))
        navigator.drag(accumulated, extent(direction))
        onFrameChanged(navigator.frame)
        val consumed = accumulated - before
        return when (direction) {
            SpatialDirection.LEFT, SpatialDirection.RIGHT -> Offset(consumed, 0f)
            SpatialDirection.UP, SpatialDirection.DOWN -> Offset(0f, consumed)
        }
    }

    private fun unwindPreview(available: Offset): Offset {
        val direction = activeDirection ?: return Offset.Zero
        val distance = direction.distance(available)
        if (accumulated == 0f || distance == 0f || kotlin.math.sign(distance) == kotlin.math.sign(accumulated)) {
            return Offset.Zero
        }
        val consumed = kotlin.math.sign(distance) * minOf(kotlin.math.abs(distance), kotlin.math.abs(accumulated))
        accumulated += consumed
        navigator.drag(accumulated, extent(direction))
        onFrameChanged(navigator.frame)
        return direction.offset(consumed)
    }

    private fun finish(velocityPxPerSecond: Float): NavigationEnd? {
        if (activeDirection == null) return null
        val result = navigator.end(velocityPxPerSecond)
        clearDrag()
        onNavigationEnd(result)
        onFrameChanged(navigator.frame)
        return result
    }

    private fun clearDrag() {
        activeDirection = null
        accumulated = 0f
    }

    private fun extent(direction: SpatialDirection): Float = when (direction) {
        SpatialDirection.LEFT, SpatialDirection.RIGHT -> viewportWidthPx()
        SpatialDirection.UP, SpatialDirection.DOWN -> viewportHeightPx()
    }

    private fun directionOf(offset: Offset): SpatialDirection =
        if (kotlin.math.abs(offset.x) >= kotlin.math.abs(offset.y)) {
            if (offset.x < 0f) SpatialDirection.RIGHT else SpatialDirection.LEFT
        } else {
            if (offset.y < 0f) SpatialDirection.DOWN else SpatialDirection.UP
        }
}

private fun SpatialDirection.distance(offset: Offset): Float = when (this) {
    SpatialDirection.LEFT, SpatialDirection.RIGHT -> offset.x
    SpatialDirection.UP, SpatialDirection.DOWN -> offset.y
}

private fun SpatialDirection.offset(distance: Float): Offset = when (this) {
    SpatialDirection.LEFT, SpatialDirection.RIGHT -> Offset(distance, 0f)
    SpatialDirection.UP, SpatialDirection.DOWN -> Offset(0f, distance)
}

private fun SpatialDirection.velocity(velocity: Velocity): Float = when (this) {
    SpatialDirection.LEFT, SpatialDirection.RIGHT -> velocity.x
    SpatialDirection.UP, SpatialDirection.DOWN -> velocity.y
}

private fun SpatialDirection.velocityOnly(velocity: Velocity): Velocity = when (this) {
    SpatialDirection.LEFT, SpatialDirection.RIGHT -> Velocity(velocity.x, 0f)
    SpatialDirection.UP, SpatialDirection.DOWN -> Velocity(0f, velocity.y)
}

private fun SpatialDirection.clampDistance(distance: Float, extent: Float): Float = when (this) {
    SpatialDirection.LEFT, SpatialDirection.UP -> distance.coerceIn(0f, extent)
    SpatialDirection.RIGHT, SpatialDirection.DOWN -> distance.coerceIn(-extent, 0f)
}
