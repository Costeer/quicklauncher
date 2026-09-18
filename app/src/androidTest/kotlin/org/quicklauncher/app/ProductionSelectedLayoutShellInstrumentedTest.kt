@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package org.quicklauncher.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.host.data.preferences.GestureMode
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.DestinationRecord

@RunWith(AndroidJUnit4::class)
@SdkSuppress(maxSdkVersion = 36) // Espresso 3.7 calls removed InputManager.getInstance on API 37.
class ProductionSelectedLayoutShellInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `map and settings chrome traverse activate and retain destination content`() {
        var mapOpens = 0
        var settingsOpens = 0
        lateinit var requestKeyboardInput: () -> Boolean
        compose.setContent {
            val inputModeManager = LocalInputModeManager.current
            requestKeyboardInput = { inputModeManager.requestInputMode(InputMode.Keyboard) }
            MaterialTheme {
                ProductionSelectedLayoutShell(
                    destinations = DESTINATIONS,
                    current = CENTER,
                    mode = GestureMode.CONTENT_HANDOFF,
                    reducedMotion = true,
                    onDestinationChanged = {},
                    onOpenMap = { mapOpens += 1 },
                    onOpenSettings = { settingsOpens += 1 },
                ) { destinationId ->
                    Text(
                        text = destinationId.value,
                        modifier = Modifier.testTag("destination-content"),
                    )
                }
            }
        }
        compose.runOnIdle { check(requestKeyboardInput()) }

        compose.onNodeWithTag("destination-content").assertIsDisplayed()
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Reduced motion"),
        )
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Map")
            .assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Settings")
            .assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }

        compose.runOnIdle {
            assertEquals(1, mapOpens)
            assertEquals(1, settingsOpens)
        }
        compose.onNodeWithTag("destination-content").assertIsDisplayed()
    }

    private companion object {
        val CENTER = DestinationId.parse("org.quicklauncher.destination/shell-focus-center")
        val RIGHT = DestinationId.parse("org.quicklauncher.destination/shell-focus-right")
        val DESTINATIONS = listOf(
            DestinationRecord(CENTER, "Center", DestinationCoordinate(0, 0)),
            DestinationRecord(RIGHT, "Right", DestinationCoordinate(1, 0)),
        )
    }
}
