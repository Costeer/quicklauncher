package org.quicklauncher.host.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.host.runtime.LauncherDestination
import org.quicklauncher.host.runtime.RecoveryInstance
import org.quicklauncher.host.runtime.permissions.HomeRoleState
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HostOverlaysTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `Home role action exists only when the platform reports it available`() {
        var openedAppRecovery = false
        compose.setContent {
            MaterialTheme {
                LauncherSettingsSurface(
                    homeRoleState = HomeRoleState.HELD,
                    fallbackAvailable = false,
                    onRequestHomeRole = {},
                    onOpenHomeSettings = {},
                    onOpenAppRecovery = { openedAppRecovery = true },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Quicklauncher is your Home app").assertIsDisplayed()
        compose.onNodeWithText("Use Quicklauncher as Home").assertIsNotDisplayed()
        compose.onNodeWithText("Open Android Home settings").assertIsNotDisplayed()
        compose.onNodeWithText("Open app recovery list").assertIsDisplayed().performClick()
        assertEquals(true, openedAppRecovery)
    }

    @Test
    fun `map destination selection has a non-drag action`() {
        val startId = DestinationId.parse("org.quicklauncher.destination/start")
        var selected: DestinationId? = null
        compose.setContent {
            MaterialTheme {
                MapOverviewSurface(
                    destinations = listOf(
                        LauncherDestination(startId, "Start", 0, 0, start = true, current = true),
                    ),
                    onSelectDestination = { selected = it },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Start", substring = false).performClick()

        assertEquals(startId, selected)
    }

    @Test
    fun `renderer quarantine exposes retry while configuration quarantine does not`() {
        compose.setContent {
            MaterialTheme {
                RecoverySurface(
                    instances = listOf(
                        RecoveryInstance(
                            ModuleInstanceId.parse("org.quicklauncher.instance/renderer"),
                            "Renderer",
                            "renderer.exception",
                            retryAllowed = true,
                        ),
                        RecoveryInstance(
                            ModuleInstanceId.parse("org.quicklauncher.instance/configuration"),
                            "Configuration",
                            "configuration.invalid",
                            retryAllowed = false,
                        ),
                    ),
                    onRetry = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Retry renderer").assertIsDisplayed()
        compose.onNodeWithText("Configuration repair required").assertIsDisplayed()
        compose.onNodeWithText("renderer.exception", substring = true).assertIsNotDisplayed()
        compose.onNodeWithText("configuration.invalid", substring = true).assertIsNotDisplayed()
    }
}
