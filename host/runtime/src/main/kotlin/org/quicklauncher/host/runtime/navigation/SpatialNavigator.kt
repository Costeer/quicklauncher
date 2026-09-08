package org.quicklauncher.host.runtime.navigation

import java.util.Collections
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.host.data.preferences.GestureMode
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.DestinationRecord

enum class SpatialDirection {
    LEFT,
    RIGHT,
    UP,
    DOWN,
}

enum class NavigationRejection {
    NO_NEIGHBOR,
    OUTSIDE_ACTIVATION_BAND,
    OUTSIDE_VIEWPORT,
    ALREADY_DRAGGING,
}

sealed interface NavigationStart {
    data class Started(val target: DestinationId) : NavigationStart
    data class Rejected(val reason: NavigationRejection) : NavigationStart
}

sealed interface NavigationEnd {
    data class Settled(val destinationId: DestinationId) : NavigationEnd
    data class Cancelled(val destinationId: DestinationId) : NavigationEnd
}

data class NavigationDrag(
    val current: DestinationId,
    val target: DestinationId,
    val direction: SpatialDirection,
    val progress: Float,
)

class NavigationFrame internal constructor(
    val current: DestinationId,
    neighbors: Map<SpatialDirection, DestinationId>,
    val drag: NavigationDrag?,
) {
    val neighbors: Map<SpatialDirection, DestinationId> =
        Collections.unmodifiableMap(LinkedHashMap(neighbors))
    val composedDestinations: Set<DestinationId> = Collections.unmodifiableSet(
        linkedSetOf(current).apply { addAll(neighbors.values) },
    )
}

interface SpatialNavigator {
    val frame: NavigationFrame

    fun update(
        destinations: Collection<DestinationRecord>,
        current: DestinationId,
    ): NavigationFrame

    fun begin(
        direction: SpatialDirection,
        mode: GestureMode,
        pointerPositionPx: Float,
        viewportExtentPx: Float,
    ): NavigationStart

    fun drag(unconsumedDistancePx: Float, viewportDistancePx: Float): NavigationDrag

    fun end(velocityPxPerSecond: Float = 0f): NavigationEnd

    /** Clears an in-progress preview without interpreting it as destination movement. */
    fun cancel(): NavigationEnd?

    fun move(direction: SpatialDirection): DestinationId?
}

class DefaultSpatialNavigator(
    private val settleFraction: Float = 0.25f,
    private val edgeActivationWidthPx: Float = 48f,
    private val minimumSettleVelocityPxPerSecond: Float = 1_800f,
) : SpatialNavigator {
    private lateinit var currentFrame: NavigationFrame
    private var destinations: List<DestinationRecord> = emptyList()

    init {
        require(settleFraction in 0f..1f && settleFraction.isFinite()) {
            "Settle fraction must be finite and between zero and one"
        }
        require(edgeActivationWidthPx >= 0f && edgeActivationWidthPx.isFinite()) {
            "Edge activation width must be finite and nonnegative"
        }
        require(minimumSettleVelocityPxPerSecond >= 0f && minimumSettleVelocityPxPerSecond.isFinite()) {
            "Minimum settle velocity must be finite and nonnegative"
        }
    }

    override val frame: NavigationFrame
        get() = checkNotNull(if (::currentFrame.isInitialized) currentFrame else null) {
            "Spatial navigator has not been initialized"
        }

    override fun update(
        destinations: Collection<DestinationRecord>,
        current: DestinationId,
    ): NavigationFrame {
        this.destinations = destinations.toList()
        val currentRecord = this.destinations.singleOrNull { it.id == current }
            ?: throw IllegalArgumentException("Current destination '$current' is unavailable")
        val byCoordinate = this.destinations.associateBy(DestinationRecord::coordinate)
        val neighbors = SpatialDirection.entries.mapNotNull { direction ->
            val coordinate = currentRecord.coordinate.neighbor(direction) ?: return@mapNotNull null
            byCoordinate[coordinate]?.id?.let { direction to it }
        }.toMap(LinkedHashMap())
        return NavigationFrame(current, neighbors, drag = null).also { currentFrame = it }
    }

    override fun begin(
        direction: SpatialDirection,
        mode: GestureMode,
        pointerPositionPx: Float,
        viewportExtentPx: Float,
    ): NavigationStart {
        require(pointerPositionPx.isFinite()) { "Pointer position must be finite" }
        require(viewportExtentPx > 0f && viewportExtentPx.isFinite()) {
            "Viewport extent must be finite and positive"
        }
        if (pointerPositionPx !in 0f..viewportExtentPx) {
            return NavigationStart.Rejected(NavigationRejection.OUTSIDE_VIEWPORT)
        }
        if (frame.drag != null) return NavigationStart.Rejected(NavigationRejection.ALREADY_DRAGGING)
        val target = frame.neighbors[direction]
            ?: return NavigationStart.Rejected(NavigationRejection.NO_NEIGHBOR)
        if (mode == GestureMode.EDGE_ACTIVATION && !isInsideActivationBand(
                direction,
                pointerPositionPx,
                viewportExtentPx,
            )
        ) {
            return NavigationStart.Rejected(NavigationRejection.OUTSIDE_ACTIVATION_BAND)
        }
        currentFrame = NavigationFrame(
            current = frame.current,
            neighbors = frame.neighbors,
            drag = NavigationDrag(frame.current, target, direction, progress = 0f),
        )
        return NavigationStart.Started(target)
    }

    override fun drag(
        unconsumedDistancePx: Float,
        viewportDistancePx: Float,
    ): NavigationDrag {
        require(unconsumedDistancePx.isFinite()) { "Unconsumed movement must be finite" }
        require(viewportDistancePx > 0f && viewportDistancePx.isFinite()) {
            "Viewport distance must be finite and positive"
        }
        val active = checkNotNull(frame.drag) { "Navigation drag has not started" }
        val updated = active.copy(progress = active.direction.forwardDistance(unconsumedDistancePx)
            .div(viewportDistancePx)
            .coerceIn(0f, 1f))
        currentFrame = NavigationFrame(frame.current, frame.neighbors, updated)
        return updated
    }

    override fun end(velocityPxPerSecond: Float): NavigationEnd {
        require(velocityPxPerSecond.isFinite()) { "Settle velocity must be finite" }
        val active = checkNotNull(frame.drag) { "Navigation drag has not started" }
        val velocityContinuesDrag =
            active.direction.forwardDistance(velocityPxPerSecond) >= minimumSettleVelocityPxPerSecond
        return if (active.progress >= settleFraction || velocityContinuesDrag) {
            val settled = active.target
            update(destinations, settled)
            NavigationEnd.Settled(settled)
        } else {
            currentFrame = NavigationFrame(active.current, frame.neighbors, drag = null)
            NavigationEnd.Cancelled(active.current)
        }
    }

    override fun cancel(): NavigationEnd? {
        val active = frame.drag ?: return null
        currentFrame = NavigationFrame(active.current, frame.neighbors, drag = null)
        return NavigationEnd.Cancelled(active.current)
    }

    override fun move(direction: SpatialDirection): DestinationId? {
        val target = frame.neighbors[direction] ?: return null
        update(destinations, target)
        return target
    }

    private fun isInsideActivationBand(
        direction: SpatialDirection,
        pointerPositionPx: Float,
        viewportExtentPx: Float,
    ): Boolean = when (direction) {
        SpatialDirection.LEFT,
        SpatialDirection.UP,
        -> pointerPositionPx <= edgeActivationWidthPx
        SpatialDirection.RIGHT,
        SpatialDirection.DOWN,
        -> pointerPositionPx >= viewportExtentPx - edgeActivationWidthPx
    }
}

private fun SpatialDirection.forwardDistance(pointerDistancePx: Float): Float = when (this) {
    SpatialDirection.LEFT, SpatialDirection.UP -> pointerDistancePx
    SpatialDirection.RIGHT, SpatialDirection.DOWN -> -pointerDistancePx
}

private fun DestinationCoordinate.neighbor(direction: SpatialDirection): DestinationCoordinate? = try {
    when (direction) {
        SpatialDirection.LEFT -> copy(x = Math.subtractExact(x, 1L))
        SpatialDirection.RIGHT -> copy(x = Math.addExact(x, 1L))
        SpatialDirection.UP -> copy(y = Math.subtractExact(y, 1L))
        SpatialDirection.DOWN -> copy(y = Math.addExact(y, 1L))
    }
} catch (_: ArithmeticException) {
    null
}
