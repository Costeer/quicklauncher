package org.quicklauncher.host.data.spatial

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.DestinationId

class DestinationMapTest {
    @Test
    fun `bootstrap creates the only start destination and add requires cardinal adjacency`() {
        val startId = destinationId("start")
        val startCoordinate = DestinationCoordinate(x = 12, y = -4)
        val map = DestinationMap.bootstrap(startId, "Start", startCoordinate)

        assertEquals(1, map.size)
        assertEquals(startId, map.startDestination.id)
        assertEquals(
            DestinationNode(startId, "Start", startCoordinate, isStart = true),
            map.destination(startId),
        )

        val diagonal = map.add(
            destinationId("diagonal"),
            "Diagonal",
            DestinationCoordinate(x = 13, y = -3),
        )
        assertRejected(DestinationMapRejection.NOT_ADJACENT, diagonal)

        val added = map.add(
            destinationId("right"),
            "Right",
            DestinationCoordinate(x = 13, y = -4),
        ).updatedMap()

        assertEquals(2, added.size)
        assertEquals(startId, added.startDestination.id)
        assertFalse(added.destination(destinationId("right"))!!.isStart)
        assertNull(map.destination(destinationId("right")))
    }

    @Test
    fun `add rejects blank names duplicate identities and occupied coordinates without mutation`() {
        val startId = destinationId("start")
        val map = DestinationMap.bootstrap(startId, "Start", DestinationCoordinate(0, 0))

        assertRejected(
            DestinationMapRejection.BLANK_NAME,
            map.add(destinationId("blank"), " ", DestinationCoordinate(1, 0)),
        )
        assertRejected(
            DestinationMapRejection.DUPLICATE_ID,
            map.add(startId, "Again", DestinationCoordinate(1, 0)),
        )
        assertRejected(
            DestinationMapRejection.COORDINATE_OCCUPIED,
            map.add(destinationId("occupied"), "Occupied", DestinationCoordinate(0, 0)),
        )

        assertEquals(1, map.size)
        @Suppress("UNCHECKED_CAST")
        val mutableDestinations = map.destinations as MutableList<DestinationNode>
        assertThrows(UnsupportedOperationException::class.java) {
            mutableDestinations.clear()
        }
    }

    @Test
    fun `rename and set start update only the selected destination`() {
        val startId = destinationId("start")
        val rightId = destinationId("right")
        val original = twoDestinationMap(startId, rightId)

        val renamed = original.rename(rightId, "Applications").updatedMap()
        val changedStart = renamed.setStart(rightId).updatedMap()

        assertEquals("Applications", changedStart.destination(rightId)!!.name)
        assertTrue(changedStart.destination(rightId)!!.isStart)
        assertFalse(changedStart.destination(startId)!!.isStart)
        assertEquals("Right", original.destination(rightId)!!.name)
        assertTrue(original.destination(startId)!!.isStart)

        assertRejected(
            DestinationMapRejection.BLANK_NAME,
            changedStart.rename(rightId, "  "),
        )
        assertRejected(
            DestinationMapRejection.UNKNOWN_DESTINATION,
            changedStart.setStart(destinationId("missing")),
        )
    }

    @Test
    fun `connected group move validates checked coordinates and the final map`() {
        val startId = destinationId("start")
        val rightId = destinationId("right")
        val original = twoDestinationMap(startId, rightId)

        val shifted = original.move(
            setOf(startId, rightId),
            DestinationVector(deltaX = 1, deltaY = 0),
        ).updatedMap()

        assertEquals(DestinationCoordinate(1, 0), shifted.destination(startId)!!.coordinate)
        assertEquals(DestinationCoordinate(2, 0), shifted.destination(rightId)!!.coordinate)
        assertEquals(DestinationCoordinate(0, 0), original.destination(startId)!!.coordinate)
        assertRejected(
            DestinationMapRejection.EMPTY_GROUP,
            original.move(emptySet(), DestinationVector(1, 0)),
        )
        assertRejected(
            DestinationMapRejection.UNKNOWN_DESTINATION,
            original.move(setOf(destinationId("missing")), DestinationVector(1, 0)),
        )

        val threeInLine = original.add(
            destinationId("far-right"),
            "Far right",
            DestinationCoordinate(2, 0),
        ).updatedMap()
        assertRejected(
            DestinationMapRejection.MAP_DISCONNECTED,
            threeInLine.move(setOf(destinationId("far-right")), DestinationVector(1, 0)),
        )
        assertRejected(
            DestinationMapRejection.COORDINATE_OCCUPIED,
            threeInLine.move(setOf(destinationId("far-right")), DestinationVector(-2, 0)),
        )

        val branch = original.add(
            destinationId("left"),
            "Left",
            DestinationCoordinate(-1, 0),
        ).updatedMap()
        assertRejected(
            DestinationMapRejection.GROUP_DISCONNECTED,
            branch.move(
                setOf(destinationId("left"), rightId),
                DestinationVector(0, 1),
            ),
        )

        val atLimit = DestinationMap.bootstrap(
            startId,
            "Start",
            DestinationCoordinate(Long.MAX_VALUE, 0),
        )
        assertRejected(
            DestinationMapRejection.COORDINATE_OVERFLOW,
            atLimit.move(setOf(startId), DestinationVector(1, 0)),
        )
    }

    @Test
    fun `delete rejects the last start and any edit that would disconnect the map`() {
        val startId = destinationId("start")
        val middleId = destinationId("middle")
        val leafId = destinationId("leaf")
        val single = DestinationMap.bootstrap(startId, "Start", DestinationCoordinate(0, 0))

        assertRejected(
            DestinationMapRejection.CANNOT_DELETE_LAST_DESTINATION,
            single.delete(startId),
        )

        val line = single
            .add(middleId, "Middle", DestinationCoordinate(1, 0)).updatedMap()
            .add(leafId, "Leaf", DestinationCoordinate(2, 0)).updatedMap()
        assertRejected(DestinationMapRejection.CANNOT_DELETE_START, line.delete(startId))
        assertRejected(DestinationMapRejection.MAP_DISCONNECTED, line.delete(middleId))

        val withoutLeaf = line.delete(leafId).updatedMap()
        assertEquals(2, withoutLeaf.size)
        assertNull(withoutLeaf.destination(leafId))

        val reassigned = line.setStart(middleId).updatedMap().delete(startId).updatedMap()
        assertEquals(middleId, reassigned.startDestination.id)
        assertEquals(2, reassigned.size)
    }

    @Test
    fun `deterministic randomized edits preserve every map invariant`() {
        val random = Random(0x51A71A1)
        var nextId = 1
        var map = DestinationMap.bootstrap(
            destinationId("random-start"),
            "Start",
            DestinationCoordinate(0, 0),
        )

        repeat(2_000) {
            val before = map.destinations
            val result = when (random.nextInt(5)) {
                0 -> {
                    val anchor = map.destinations.random(random)
                    val (deltaX, deltaY) = cardinalVectors.random(random)
                    map.add(
                        destinationId("generated-${nextId++}"),
                        "Generated $nextId",
                        DestinationCoordinate(
                            anchor.coordinate.x + deltaX,
                            anchor.coordinate.y + deltaY,
                        ),
                    )
                }
                1 -> {
                    val selected = map.destinations.random(random)
                    map.rename(selected.id, "Renamed ${random.nextInt(100)}")
                }
                2 -> map.setStart(map.destinations.random(random).id)
                3 -> {
                    val selected = if (random.nextInt(4) == 0) {
                        map.destinations.map(DestinationNode::id).toSet()
                    } else {
                        map.destinations.filter { random.nextBoolean() }.map(DestinationNode::id).toSet()
                    }
                    map.move(
                        selected,
                        DestinationVector(
                            deltaX = random.nextLong(from = -1, until = 2),
                            deltaY = random.nextLong(from = -1, until = 2),
                        ),
                    )
                }
                else -> map.delete(map.destinations.random(random).id)
            }

            map = when (result) {
                is DestinationMapEditResult.Updated -> result.map
                is DestinationMapEditResult.Rejected -> {
                    assertEquals(before, map.destinations)
                    map
                }
            }
            assertMapInvariants(map)
        }
    }

    private fun destinationId(localName: String): DestinationId =
        DestinationId.parse("org.quicklauncher.destination/$localName")

    private fun twoDestinationMap(
        startId: DestinationId,
        rightId: DestinationId,
    ): DestinationMap = DestinationMap.bootstrap(
        startId,
        "Start",
        DestinationCoordinate(0, 0),
    ).add(
        rightId,
        "Right",
        DestinationCoordinate(1, 0),
    ).updatedMap()

    private fun assertRejected(
        expected: DestinationMapRejection,
        result: DestinationMapEditResult,
    ) {
        assertTrue(result is DestinationMapEditResult.Rejected)
        assertEquals(expected, (result as DestinationMapEditResult.Rejected).reason)
    }

    private fun DestinationMapEditResult.updatedMap(): DestinationMap {
        assertTrue(this is DestinationMapEditResult.Updated)
        return (this as DestinationMapEditResult.Updated).map
    }

    private fun assertMapInvariants(map: DestinationMap) {
        assertTrue(map.destinations.isNotEmpty())
        assertEquals(map.size, map.destinations.map(DestinationNode::id).toSet().size)
        assertEquals(map.size, map.destinations.map(DestinationNode::coordinate).toSet().size)
        assertEquals(1, map.destinations.count(DestinationNode::isStart))

        val reached = mutableSetOf(map.startDestination.coordinate)
        val pending = ArrayDeque<DestinationCoordinate>()
        pending += map.startDestination.coordinate
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            map.destinations
                .map(DestinationNode::coordinate)
                .filter { candidate -> candidate !in reached && independentlyAdjacent(current, candidate) }
                .forEach { neighbor ->
                    reached += neighbor
                    pending += neighbor
                }
        }
        assertEquals(map.size, reached.size)
    }

    private fun independentlyAdjacent(
        first: DestinationCoordinate,
        second: DestinationCoordinate,
    ): Boolean =
        (first.x == second.x && abs(first.y - second.y) == 1L) ||
            (first.y == second.y && abs(first.x - second.x) == 1L)

    private companion object {
        val cardinalVectors = listOf(
            1L to 0L,
            -1L to 0L,
            0L to 1L,
            0L to -1L,
        )
    }
}
