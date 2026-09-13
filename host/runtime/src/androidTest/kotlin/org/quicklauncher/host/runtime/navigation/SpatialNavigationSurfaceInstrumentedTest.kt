package org.quicklauncher.host.runtime.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.swipe
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.host.data.preferences.GestureMode
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.DestinationRecord

@RunWith(AndroidJUnit4::class)
@SdkSuppress(maxSdkVersion = 36) // Espresso 3.7 calls removed InputManager.getInstance on API 37.
class SpatialNavigationSurfaceInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun accessibilityMovementUsesTheCardinalNeighborMappingAndRejectsMissingNeighbors() {
        val changes = mutableListOf<DestinationId>()
        lateinit var state: SpatialNavigationState
        compose.setContent {
            state = rememberSpatialNavigationState(DESTINATIONS, CENTER, changes::add)
            SpatialNavigationSurface(
                state = state,
                mode = GestureMode.CONTENT_HANDOFF,
                reducedMotion = true,
                modifier = Modifier.testTag("navigation"),
            ) { destinationId ->
                Text(destinationId.value.substringAfterLast('/'))
            }
        }

        val actions = compose.onNodeWithTag("navigation").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertEquals(
                listOf(
                    "Move to destination on the right",
                    "Move to destination below",
                ),
                actions.map { it.label },
            )
            assertTrue(actions.single { it.label.endsWith("right") }.action())
        }

        compose.runOnIdle {
            assertEquals(listOf(RIGHT), changes)
            assertEquals(RIGHT, state.frame.current)
            assertEquals(null, state.frame.neighbors[SpatialDirection.RIGHT])
            assertEquals(false, state.move(SpatialDirection.RIGHT))
        }
    }

    @Test
    fun contentHandoffSwipeSettlesAndNeighborPreviewCannotReceiveActions() {
        val changes = mutableListOf<DestinationId>()
        compose.setContent {
            val state = rememberSpatialNavigationState(DESTINATIONS, CENTER, changes::add)
            SpatialNavigationSurface(
                state = state,
                mode = GestureMode.CONTENT_HANDOFF,
                reducedMotion = false,
                modifier = Modifier.testTag("navigation"),
            ) { destinationId ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("destination-${destinationId.value.substringAfterLast('/')}")
                        .clickable { },
                )
            }
        }

        compose.onNodeWithTag("destination-center").assertHasClickAction()
        compose.onAllNodesWithTag("destination-right").assertCountEquals(0)
        compose.onNodeWithTag("navigation").performTouchInput {
            swipe(
                start = Offset(width * 0.85f, height * 0.5f),
                end = Offset(width * 0.25f, height * 0.5f),
                durationMillis = 300,
            )
        }

        compose.runOnIdle { assertEquals(listOf(RIGHT), changes) }
    }

    @Test
    fun contentHandoffWaitsUntilScrollableChildLeavesMovementUnconsumed() {
        val changes = mutableListOf<DestinationId>()
        compose.setContent {
            val state = rememberSpatialNavigationState(DESTINATIONS, CENTER, changes::add)
            SpatialNavigationSurface(
                state = state,
                mode = GestureMode.CONTENT_HANDOFF,
                reducedMotion = true,
                modifier = Modifier.testTag("navigation"),
            ) { destinationId ->
                if (destinationId == CENTER) {
                    LazyColumn(Modifier.fillMaxSize().testTag("scrollable-child")) {
                        items((0 until 40).toList()) { index ->
                            Text("Item $index", Modifier.height(64.dp))
                        }
                    }
                } else {
                    Text(destinationId.value.substringAfterLast('/'))
                }
            }
        }

        compose.onNodeWithTag("scrollable-child").performTouchInput {
            swipe(
                start = Offset(centerX, height * 0.8f),
                end = Offset(centerX, height * 0.2f),
                durationMillis = 300,
            )
        }
        compose.runOnIdle { assertTrue(changes.isEmpty()) }

        compose.onNodeWithTag("scrollable-child").performScrollToIndex(39)
        compose.onNodeWithTag("scrollable-child").performTouchInput {
            swipe(
                start = Offset(centerX, height * 0.8f),
                end = Offset(centerX, height * 0.15f),
                durationMillis = 300,
            )
        }
        compose.runOnIdle { assertEquals(listOf(DOWN), changes) }
    }

    @Test
    fun edgeModeRejectsCenterDragAndAcceptsTheSameDragFromTheRequiredEdge() {
        val changes = mutableListOf<DestinationId>()
        compose.setContent {
            val state = rememberSpatialNavigationState(DESTINATIONS, CENTER, changes::add)
            SpatialNavigationSurface(
                state = state,
                mode = GestureMode.EDGE_ACTIVATION,
                reducedMotion = true,
                modifier = Modifier.testTag("navigation"),
            ) { destinationId -> Text(destinationId.value.substringAfterLast('/')) }
        }

        compose.onNodeWithTag("navigation").performTouchInput {
            swipe(
                start = center,
                end = Offset(width * 0.1f, centerY),
                durationMillis = 300,
            )
        }
        compose.runOnIdle { assertTrue(changes.isEmpty()) }

        compose.onNodeWithTag("navigation").performTouchInput {
            swipe(
                start = Offset(width - 2f, centerY),
                end = Offset(width * 0.35f, centerY),
                durationMillis = 300,
            )
        }
        compose.runOnIdle { assertEquals(listOf(RIGHT), changes) }
    }

    @Test
    fun reducedMotionIsExposedAsStateForAssistiveTechnology() {
        compose.setContent {
            val state = rememberSpatialNavigationState(DESTINATIONS, CENTER) { }
            SpatialNavigationSurface(
                state = state,
                mode = GestureMode.CONTENT_HANDOFF,
                reducedMotion = true,
                modifier = Modifier.testTag("navigation"),
            ) { destinationId -> Text(destinationId.value.substringAfterLast('/')) }
        }

        compose.onNodeWithTag("navigation").assert(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Reduced motion",
            ),
        )
    }

    private companion object {
        val CENTER = DestinationId.parse("org.quicklauncher.destination/center")
        val RIGHT = DestinationId.parse("org.quicklauncher.destination/right")
        val DOWN = DestinationId.parse("org.quicklauncher.destination/down")
        val DESTINATIONS = listOf(
            DestinationRecord(CENTER, "Center", DestinationCoordinate(0, 0)),
            DestinationRecord(RIGHT, "Right", DestinationCoordinate(1, 0)),
            DestinationRecord(DOWN, "Down", DestinationCoordinate(0, 1)),
        )
    }
}
