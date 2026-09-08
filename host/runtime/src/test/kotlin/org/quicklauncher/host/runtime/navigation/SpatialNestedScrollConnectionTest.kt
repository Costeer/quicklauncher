package org.quicklauncher.host.runtime.navigation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.quicklauncher.host.data.preferences.GestureMode
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.DestinationRecord

class SpatialNestedScrollConnectionTest {
    @Test
    fun `content handoff ignores child-consumed movement and settles from cumulative remainder`() = runTest {
        val navigator = DefaultSpatialNavigator(settleFraction = 0.35f)
        navigator.update(spatialTestDestinations(), SpatialTestIds.CENTER)
        val completed = mutableListOf<NavigationEnd>()
        val connection = SpatialNestedScrollConnection(
            navigator,
            GestureMode.CONTENT_HANDOFF,
            viewportWidthPx = { 1_000f },
            viewportHeightPx = { 2_000f },
            onNavigationEnd = completed::add,
        )
        connection.updatePointerPosition(Offset(500f, 500f))

        assertEquals(Offset.Zero, connection.onPreScroll(Offset(-400f, 0f), NestedScrollSource.UserInput))
        assertEquals(Offset(-200f, 0f), connection.onPostScroll(Offset(-200f, 0f), Offset(-200f, 0f), NestedScrollSource.UserInput))
        connection.onPostScroll(Offset.Zero, Offset(-160f, 0f), NestedScrollSource.UserInput)
        connection.onPostFling(androidx.compose.ui.unit.Velocity.Zero, androidx.compose.ui.unit.Velocity.Zero)

        assertEquals(listOf(NavigationEnd.Settled(SpatialTestIds.RIGHT)), completed)
    }

    @Test
    fun `edge mode begins only in the configured edge band during pre scroll`() {
        val navigator = DefaultSpatialNavigator(edgeActivationWidthPx = 48f)
        navigator.update(spatialTestDestinations(), SpatialTestIds.CENTER)
        val connection = SpatialNestedScrollConnection(
            navigator,
            GestureMode.EDGE_ACTIVATION,
            viewportWidthPx = { 1_000f },
            viewportHeightPx = { 2_000f },
            onNavigationEnd = {},
        )

        connection.updatePointerPosition(Offset(500f, 200f))
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(-100f, 0f), NestedScrollSource.UserInput))
        connection.updatePointerPosition(Offset(990f, 200f))
        assertEquals(Offset(-100f, 0f), connection.onPreScroll(Offset(-100f, 0f), NestedScrollSource.UserInput))
    }


    @Test
    fun `reversal is consumed only until the destination preview is unwound`() {
        val navigator = DefaultSpatialNavigator(settleFraction = 0.25f)
        navigator.update(spatialTestDestinations(), SpatialTestIds.CENTER)
        val connection = connection(navigator)
        connection.updatePointerPosition(Offset(500f, 500f))
        connection.onPostScroll(Offset.Zero, Offset(-200f, 0f), NestedScrollSource.UserInput)

        assertEquals(
            Offset(200f, 0f),
            connection.onPreScroll(Offset(500f, 0f), NestedScrollSource.UserInput),
        )
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(50f, 0f), NestedScrollSource.UserInput))
        assertEquals(0f, navigator.frame.drag?.progress ?: 0f)
    }

    @Test
    fun `active preview owns fling and settles only from continuing velocity`() = runTest {
        val navigator = DefaultSpatialNavigator(
            settleFraction = 0.25f,
            minimumSettleVelocityPxPerSecond = 1_800f,
        )
        navigator.update(spatialTestDestinations(), SpatialTestIds.CENTER)
        val completed = mutableListOf<NavigationEnd>()
        val connection = connection(navigator, completed::add)
        connection.updatePointerPosition(Offset(500f, 500f))
        connection.onPostScroll(Offset.Zero, Offset(-20f, 0f), NestedScrollSource.UserInput)

        assertEquals(Velocity(-2_000f, 0f), connection.onPreFling(Velocity(-2_000f, 0f)))
        assertEquals(listOf(NavigationEnd.Settled(SpatialTestIds.RIGHT)), completed)
        assertEquals(Velocity.Zero, connection.onPostFling(Velocity.Zero, Velocity(600f, 0f)))
        assertEquals(1, completed.size)
    }

    @Test
    fun `non user scroll never begins destination navigation`() {
        val navigator = DefaultSpatialNavigator()
        navigator.update(spatialTestDestinations(), SpatialTestIds.CENTER)
        val connection = connection(navigator)
        connection.updatePointerPosition(Offset(500f, 500f))

        assertEquals(
            Offset.Zero,
            connection.onPostScroll(Offset.Zero, Offset(-500f, 0f), NestedScrollSource.SideEffect),
        )
        assertEquals(null, navigator.frame.drag)
    }

    private fun connection(
        navigator: SpatialNavigator,
        onNavigationEnd: (NavigationEnd) -> Unit = {},
    ) = SpatialNestedScrollConnection(
        navigator,
        GestureMode.CONTENT_HANDOFF,
        viewportWidthPx = { 1_000f },
        viewportHeightPx = { 2_000f },
        onNavigationEnd = onNavigationEnd,
    )
}

private object SpatialTestIds {
    val CENTER = DestinationId.parse("org.quicklauncher.destination/center")
    val RIGHT = DestinationId.parse("org.quicklauncher.destination/right")
}

private fun spatialTestDestinations() = listOf(
    DestinationRecord(SpatialTestIds.CENTER, "center", DestinationCoordinate(0, 0)),
    DestinationRecord(SpatialTestIds.RIGHT, "right", DestinationCoordinate(1, 0)),
)
