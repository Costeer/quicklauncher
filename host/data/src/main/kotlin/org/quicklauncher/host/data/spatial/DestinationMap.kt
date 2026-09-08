package org.quicklauncher.host.data.spatial

import java.util.Collections
import org.quicklauncher.contracts.domain.DestinationId

data class DestinationCoordinate(
    val x: Long,
    val y: Long,
) {
    fun isCardinalNeighborOf(other: DestinationCoordinate): Boolean =
        (x == other.x && y.isOneStepFrom(other.y)) ||
            (y == other.y && x.isOneStepFrom(other.x))

    private fun Long.isOneStepFrom(other: Long): Boolean =
        (this != Long.MAX_VALUE && this + 1L == other) ||
            (other != Long.MAX_VALUE && other + 1L == this)

    internal fun translatedBy(vector: DestinationVector): DestinationCoordinate? = try {
        DestinationCoordinate(
            x = Math.addExact(x, vector.deltaX),
            y = Math.addExact(y, vector.deltaY),
        )
    } catch (_: ArithmeticException) {
        null
    }
}

data class DestinationVector(
    val deltaX: Long,
    val deltaY: Long,
)

data class DestinationNode(
    val id: DestinationId,
    val name: String,
    val coordinate: DestinationCoordinate,
    val isStart: Boolean,
) {
    init {
        require(name.isNotBlank()) { "Destination name must not be blank" }
    }
}

enum class DestinationMapRejection {
    BLANK_NAME,
    DUPLICATE_ID,
    COORDINATE_OCCUPIED,
    NOT_ADJACENT,
    UNKNOWN_DESTINATION,
    EMPTY_GROUP,
    GROUP_DISCONNECTED,
    COORDINATE_OVERFLOW,
    MAP_DISCONNECTED,
    CANNOT_DELETE_LAST_DESTINATION,
    CANNOT_DELETE_START,
}

sealed interface DestinationMapEditResult {
    data class Updated(val map: DestinationMap) : DestinationMapEditResult

    data class Rejected(val reason: DestinationMapRejection) : DestinationMapEditResult
}

class DestinationMap private constructor(nodes: Collection<DestinationNode>) {
    private val nodeSnapshot = nodes.toList()
    private val nodesById: Map<DestinationId, DestinationNode> = nodeSnapshot.associateBy(DestinationNode::id)

    init {
        require(nodeSnapshot.isNotEmpty()) { "A destination map must be bootstrapped atomically" }
        require(nodesById.size == nodeSnapshot.size) { "Destination IDs must be unique" }
        require(nodeSnapshot.map(DestinationNode::coordinate).toSet().size == nodeSnapshot.size) {
            "Destination coordinates must be unique"
        }
        require(nodeSnapshot.count(DestinationNode::isStart) == 1) {
            "A destination map must have exactly one start destination"
        }
        require(nodeSnapshot.areCardinallyConnected()) {
            "Every destination must be cardinally reachable from the start destination"
        }
    }

    val destinations: List<DestinationNode> = Collections.unmodifiableList(
        nodesById.values.sortedBy { it.id.value },
    )

    val size: Int
        get() = nodesById.size

    val startDestination: DestinationNode
        get() = nodesById.values.single(DestinationNode::isStart)

    fun destination(id: DestinationId): DestinationNode? = nodesById[id]

    fun rename(id: DestinationId, name: String): DestinationMapEditResult {
        if (name.isBlank()) return DestinationMapEditResult.Rejected(DestinationMapRejection.BLANK_NAME)
        if (id !in nodesById) {
            return DestinationMapEditResult.Rejected(DestinationMapRejection.UNKNOWN_DESTINATION)
        }
        return DestinationMapEditResult.Updated(
            DestinationMap(nodesById.values.map { node ->
                if (node.id == id) node.copy(name = name) else node
            }),
        )
    }

    fun setStart(id: DestinationId): DestinationMapEditResult {
        if (id !in nodesById) {
            return DestinationMapEditResult.Rejected(DestinationMapRejection.UNKNOWN_DESTINATION)
        }
        return DestinationMapEditResult.Updated(
            DestinationMap(nodesById.values.map { node -> node.copy(isStart = node.id == id) }),
        )
    }

    fun move(
        destinationIds: Set<DestinationId>,
        vector: DestinationVector,
    ): DestinationMapEditResult {
        if (destinationIds.isEmpty()) {
            return DestinationMapEditResult.Rejected(DestinationMapRejection.EMPTY_GROUP)
        }
        if (!nodesById.keys.containsAll(destinationIds)) {
            return DestinationMapEditResult.Rejected(DestinationMapRejection.UNKNOWN_DESTINATION)
        }

        val movingNodes = destinationIds.map(nodesById::getValue)
        if (!movingNodes.areCardinallyConnected()) {
            return DestinationMapEditResult.Rejected(DestinationMapRejection.GROUP_DISCONNECTED)
        }

        val movedNodes = ArrayList<DestinationNode>(nodesById.size)
        for (node in nodesById.values) {
            if (node.id !in destinationIds) {
                movedNodes += node
                continue
            }
            val movedCoordinate = node.coordinate.translatedBy(vector)
                ?: return DestinationMapEditResult.Rejected(DestinationMapRejection.COORDINATE_OVERFLOW)
            movedNodes += node.copy(coordinate = movedCoordinate)
        }

        if (movedNodes.map(DestinationNode::coordinate).toSet().size != movedNodes.size) {
            return DestinationMapEditResult.Rejected(DestinationMapRejection.COORDINATE_OCCUPIED)
        }
        if (!movedNodes.areCardinallyConnected()) {
            return DestinationMapEditResult.Rejected(DestinationMapRejection.MAP_DISCONNECTED)
        }
        return DestinationMapEditResult.Updated(DestinationMap(movedNodes))
    }

    fun delete(id: DestinationId): DestinationMapEditResult {
        val destination = nodesById[id]
            ?: return DestinationMapEditResult.Rejected(DestinationMapRejection.UNKNOWN_DESTINATION)
        if (size == 1) {
            return DestinationMapEditResult.Rejected(DestinationMapRejection.CANNOT_DELETE_LAST_DESTINATION)
        }
        if (destination.isStart) {
            return DestinationMapEditResult.Rejected(DestinationMapRejection.CANNOT_DELETE_START)
        }

        val remaining = nodesById.values.filterNot { it.id == id }
        if (!remaining.areCardinallyConnected()) {
            return DestinationMapEditResult.Rejected(DestinationMapRejection.MAP_DISCONNECTED)
        }
        return DestinationMapEditResult.Updated(DestinationMap(remaining))
    }

    fun add(
        id: DestinationId,
        name: String,
        coordinate: DestinationCoordinate,
    ): DestinationMapEditResult {
        if (name.isBlank()) return DestinationMapEditResult.Rejected(DestinationMapRejection.BLANK_NAME)
        if (id in nodesById) return DestinationMapEditResult.Rejected(DestinationMapRejection.DUPLICATE_ID)
        if (nodesById.values.any { it.coordinate == coordinate }) {
            return DestinationMapEditResult.Rejected(DestinationMapRejection.COORDINATE_OCCUPIED)
        }
        if (nodesById.values.none { it.coordinate.isCardinalNeighborOf(coordinate) }) {
            return DestinationMapEditResult.Rejected(DestinationMapRejection.NOT_ADJACENT)
        }

        return DestinationMapEditResult.Updated(
            DestinationMap(nodesById.values + DestinationNode(id, name, coordinate, isStart = false)),
        )
    }

    companion object {
        internal fun restore(nodes: Collection<DestinationNode>): DestinationMap = DestinationMap(nodes)

        fun bootstrap(
            id: DestinationId,
            name: String,
            coordinate: DestinationCoordinate,
        ): DestinationMap = DestinationMap(
            listOf(DestinationNode(id, name, coordinate, isStart = true)),
        )
    }
}

private fun Collection<DestinationNode>.areCardinallyConnected(): Boolean {
    if (isEmpty()) return false

    val remaining = toMutableSet()
    val pending = ArrayDeque<DestinationNode>()
    pending += remaining.first()
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!remaining.remove(current)) continue
        remaining
            .filter { it.coordinate.isCardinalNeighborOf(current.coordinate) }
            .forEach(pending::addLast)
    }
    return remaining.isEmpty()
}
