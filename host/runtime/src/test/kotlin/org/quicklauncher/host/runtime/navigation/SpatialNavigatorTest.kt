package org.quicklauncher.host.runtime.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.host.data.preferences.GestureMode
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.DestinationRecord

class SpatialNavigatorTest {
    @Test
    fun `frame contains only the current destination and its cardinal neighbors`() {
        val navigator = DefaultSpatialNavigator()

        val frame = navigator.update(destinations(), CENTER)

        assertEquals(CENTER, frame.current)
        assertEquals(LEFT, frame.neighbors[SpatialDirection.LEFT])
        assertEquals(RIGHT, frame.neighbors[SpatialDirection.RIGHT])
        assertEquals(UP, frame.neighbors[SpatialDirection.UP])
        assertEquals(DOWN, frame.neighbors[SpatialDirection.DOWN])
        assertTrue(FAR !in frame.composedDestinations)
        assertEquals(setOf(CENTER, LEFT, RIGHT, UP, DOWN), frame.composedDestinations)
    }

    @Test
    fun `content handoff settles on a neighbor only after unconsumed movement crosses threshold`() {
        val navigator = DefaultSpatialNavigator(settleFraction = 0.4f)
        navigator.update(destinations(), CENTER)
        assertEquals(
            NavigationStart.Started(RIGHT),
            navigator.begin(
                SpatialDirection.RIGHT,
                GestureMode.CONTENT_HANDOFF,
                pointerPositionPx = 500f,
                viewportExtentPx = 1_000f,
            ),
        )

        assertEquals(0.39f, navigator.drag(unconsumedDistancePx = -390f, viewportDistancePx = 1_000f).progress)
        assertEquals(NavigationEnd.Cancelled(CENTER), navigator.end())

        navigator.begin(SpatialDirection.RIGHT, GestureMode.CONTENT_HANDOFF, 500f, 1_000f)
        navigator.drag(-410f, 1_000f)
        assertEquals(NavigationEnd.Settled(RIGHT), navigator.end())
        assertEquals(RIGHT, navigator.frame.current)
    }

    @Test
    fun `opposing movement unwinds the preview and cannot settle`() {
        val navigator = DefaultSpatialNavigator(settleFraction = 0.25f)
        navigator.update(destinations(), CENTER)
        navigator.begin(SpatialDirection.RIGHT, GestureMode.CONTENT_HANDOFF, 500f, 1_000f)

        assertEquals(0.2f, navigator.drag(-200f, 1_000f).progress)
        assertEquals(0.05f, navigator.drag(-50f, 1_000f).progress)
        assertEquals(0f, navigator.drag(100f, 1_000f).progress)

        assertEquals(NavigationEnd.Cancelled(CENTER), navigator.end())
    }

    @Test
    fun `continuing fling settles a short preview and opposing fling cancels it`() {
        val navigator = DefaultSpatialNavigator(
            settleFraction = 0.25f,
            minimumSettleVelocityPxPerSecond = 1_800f,
        )
        navigator.update(destinations(), CENTER)
        navigator.begin(SpatialDirection.RIGHT, GestureMode.CONTENT_HANDOFF, 500f, 1_000f)
        navigator.drag(-20f, 1_000f)
        assertEquals(NavigationEnd.Settled(RIGHT), navigator.end(-2_000f))

        navigator.begin(SpatialDirection.LEFT, GestureMode.CONTENT_HANDOFF, 500f, 1_000f)
        navigator.drag(20f, 1_000f)
        assertEquals(NavigationEnd.Cancelled(RIGHT), navigator.end(-2_000f))
    }

    @Test
    fun `explicit cancellation clears a preview for predictive Back without moving`() {
        val navigator = DefaultSpatialNavigator()
        navigator.update(destinations(), CENTER)
        navigator.begin(SpatialDirection.UP, GestureMode.CONTENT_HANDOFF, 500f, 1_000f)
        navigator.drag(500f, 1_000f)

        assertEquals(NavigationEnd.Cancelled(CENTER), navigator.cancel())
        assertNull(navigator.frame.drag)
        assertEquals(CENTER, navigator.frame.current)
        assertNull(navigator.cancel())
    }

    @Test
    fun `edge activation rejects an interior start and accepts a start inside either activation band`() {
        val navigator = DefaultSpatialNavigator(edgeActivationWidthPx = 48f)
        navigator.update(destinations(), CENTER)

        assertEquals(
            NavigationStart.Rejected(NavigationRejection.OUTSIDE_ACTIVATION_BAND),
            navigator.begin(SpatialDirection.RIGHT, GestureMode.EDGE_ACTIVATION, 500f, 1_000f),
        )
        assertEquals(
            NavigationStart.Started(RIGHT),
            navigator.begin(SpatialDirection.RIGHT, GestureMode.EDGE_ACTIVATION, 970f, 1_000f),
        )
    }

    @Test
    fun `edge activation rejects pointer coordinates outside the viewport`() {
        val navigator = DefaultSpatialNavigator()
        navigator.update(destinations(), CENTER)

        assertEquals(
            NavigationStart.Rejected(NavigationRejection.OUTSIDE_VIEWPORT),
            navigator.begin(SpatialDirection.LEFT, GestureMode.EDGE_ACTIVATION, -1f, 1_000f),
        )
        assertEquals(
            NavigationStart.Rejected(NavigationRejection.OUTSIDE_VIEWPORT),
            navigator.begin(SpatialDirection.RIGHT, GestureMode.EDGE_ACTIVATION, 1_001f, 1_000f),
        )
    }

    @Test
    fun `missing neighbor rejects drag and accessible movement without changing current destination`() {
        val navigator = DefaultSpatialNavigator()
        navigator.update(destinations().filterNot { it.id == DOWN }, CENTER)

        assertEquals(
            NavigationStart.Rejected(NavigationRejection.NO_NEIGHBOR),
            navigator.begin(SpatialDirection.DOWN, GestureMode.CONTENT_HANDOFF, 500f, 1_000f),
        )
        assertNull(navigator.move(SpatialDirection.DOWN))
        assertEquals(CENTER, navigator.frame.current)
    }

    @Test
    fun `accessible movement uses the same neighbor mapping as gesture settlement`() {
        val navigator = DefaultSpatialNavigator()
        navigator.update(destinations(), CENTER)

        assertEquals(UP, navigator.move(SpatialDirection.UP))
        assertEquals(UP, navigator.frame.current)
        assertEquals(setOf(UP, CENTER), navigator.frame.composedDestinations)
        assertEquals(CENTER, navigator.move(SpatialDirection.DOWN))
    }

    private fun destinations(): List<DestinationRecord> = listOf(
        destination(CENTER, 0, 0),
        destination(LEFT, -1, 0),
        destination(RIGHT, 1, 0),
        destination(UP, 0, -1),
        destination(DOWN, 0, 1),
        destination(FAR, 2, 0),
    )

    private fun destination(id: DestinationId, x: Long, y: Long) = DestinationRecord(
        id = id,
        name = id.value.substringAfterLast('/'),
        coordinate = DestinationCoordinate(x, y),
    )

    private companion object {
        val CENTER = DestinationId.parse("org.quicklauncher.destination/center")
        val LEFT = DestinationId.parse("org.quicklauncher.destination/left")
        val RIGHT = DestinationId.parse("org.quicklauncher.destination/right")
        val UP = DestinationId.parse("org.quicklauncher.destination/up")
        val DOWN = DestinationId.parse("org.quicklauncher.destination/down")
        val FAR = DestinationId.parse("org.quicklauncher.destination/far")
    }
}
