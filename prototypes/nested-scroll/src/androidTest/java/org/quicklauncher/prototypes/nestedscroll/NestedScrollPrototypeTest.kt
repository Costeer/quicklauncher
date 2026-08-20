package org.quicklauncher.prototypes.nestedscroll

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.up
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NestedScrollPrototypeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun reset() {
        compose.onNodeWithTag("mode-handoff").performClick()
        compose.onNodeWithTag("list-long").performClick()
        compose.onNodeWithTag("reset").performClick()
        compose.waitForIdle()
    }

    @Test
    fun slowDragIsConsumedByScrollableList() {
        compose.onNodeWithTag("app-list").performTouchInput {
            swipeUp(durationMillis = 900)
        }
        compose.waitForIdle()

        compose.onNodeWithTag("app-0").assertDoesNotExist()
        assertStatus("nav=0")
        assertStatus("velocity=LIST")
    }

    @Test
    fun bottomBoundaryRemainderNavigatesBelow() {
        compose.onNodeWithTag("app-list").performScrollToIndex(79)
        compose.onNodeWithTag("app-list").performTouchInput {
            swipeUp(durationMillis = 900)
        }
        compose.waitForIdle()

        assertStatus("destination=1")
        assertStatus("nav=1")
        assertStatus("velocity=NAVIGATOR")
    }

    @Test
    fun topBoundaryRemainderNavigatesAbove() {
        compose.onNodeWithTag("app-list").performTouchInput {
            swipeDown(durationMillis = 900)
        }
        compose.waitForIdle()

        assertStatus("destination=-1")
        assertStatus("nav=1")
    }

    @Test
    fun reversingOneGestureUnwindsPreviewThenMovesList() {
        compose.onNodeWithTag("app-list").performScrollToIndex(79)
        compose.onNodeWithTag("app-list").performTouchInput {
            down(center)
            advanceEventTime(250)
            moveBy(Offset(0f, -200f))
            advanceEventTime(250)
            moveBy(Offset(0f, 500f))
            advanceEventTime(300)
            up()
        }
        compose.waitForIdle()

        compose.onNodeWithTag("app-79").assertDoesNotExist()
        assertStatus("nav=0")
        assertStatus("offset=0px")
        assertStatus("reversals=1")
        assertStatus("velocity=LIST")
    }

    @Test
    fun fastListFlingDoesNotAlsoNavigate() {
        compose.onNodeWithTag("list-short").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("app-list").performTouchInput {
            down(center)
            advanceEventTime(16)
            moveBy(Offset(0f, -60f))
            advanceEventTime(16)
            up()
        }
        compose.waitForIdle()

        compose.onNodeWithTag("app-11").assertIsDisplayed()
        assertStatus("nav=0")
        assertStatus("velocity=LIST")
    }

    @Test
    fun largeTextAndShortListKeepBoundaryHandoff() {
        compose.onNodeWithTag("text-scale").performClick()
        compose.onNodeWithTag("list-short").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("app-list").performScrollToIndex(11)
        compose.onNodeWithTag("app-list").performTouchInput {
            swipeUp(durationMillis = 900)
        }
        compose.waitForIdle()

        compose.onNodeWithTag("app-11").assertIsDisplayed()
        assertStatus("nav=1")
    }

    @Test
    fun listTooSmallToScrollHandsWholeDragToNavigator() {
        compose.onNodeWithTag("list-tiny").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("app-list").performTouchInput {
            swipeUp(durationMillis = 900)
        }
        compose.waitForIdle()

        assertStatus("destination=1")
        assertStatus("nav=1")
        assertStatus("listDrag=0px")
    }

    @Test
    fun edgeOnlyModeIgnoresCenterDragAndNavigatesFromBand() {
        compose.onNodeWithTag("list-tiny").performClick()
        compose.onNodeWithTag("mode-edge-only").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("app-list").performTouchInput {
            swipeUp(durationMillis = 900)
        }
        compose.waitForIdle()
        assertStatus("nav=0")

        compose.onNodeWithTag("edge-band").performTouchInput {
            swipeUp(durationMillis = 900)
        }
        compose.waitForIdle()
        assertStatus("nav=1")
    }

    private fun assertStatus(fragment: String) {
        compose.onNodeWithTag("status").assertTextContains(fragment, substring = true)
    }
}
