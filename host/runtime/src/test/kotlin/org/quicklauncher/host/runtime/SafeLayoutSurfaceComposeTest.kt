@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package org.quicklauncher.host.runtime

import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.catalog.AppCatalogStatus
import org.quicklauncher.host.runtime.permissions.HomeRoleState
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SafeLayoutSurfaceComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `safe layout traverses and activates search app map settings and recovery controls`() {
        val app = app("Calendar")
        val queries = mutableListOf<String>()
        val launched = mutableListOf<AppActivityIdentity>()
        var mapCount = 0
        var settingsCount = 0
        var recoveryCount = 0
        var query by mutableStateOf("")
        compose.setContent {
            SafeLayoutSurface(
                state = state(app, query),
                onQueryChanged = { value ->
                    query = value
                    queries += value
                },
                onLaunch = launched::add,
                onOpenMap = { mapCount += 1 },
                onOpenSettings = { settingsCount += 1 },
                onOpenRecovery = { recoveryCount += 1 },
                onRequestHomeRole = {},
            )
        }

        compose.onNodeWithContentDescription("Map")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithContentDescription("Settings")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
            .performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Recovery")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
            .performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Local app filter")
            .assertIsFocused()
            .performTextInput("cal")
        compose.onNodeWithContentDescription("Local app filter")
            .performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithText("Calendar")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }

        assertEquals(listOf("cal"), queries)
        assertEquals(listOf(app.identity), launched)
        assertEquals(1, mapCount)
        assertEquals(1, settingsCount)
        assertEquals(1, recoveryCount)
    }

    private fun state(app: LauncherApp, query: String) = LauncherRuntimeState(
        status = LauncherRuntimeStatus.READY,
        currentDestinationId = null,
        surface = LauncherSurface.SAFE_LAYOUT,
        localQuery = query,
        homeRoleState = HomeRoleState.UNAVAILABLE,
        catalogStatus = AppCatalogStatus.READY,
        visibleApps = listOf(app),
        destinations = emptyList(),
        recoveryInstances = emptyList(),
    )

    private fun app(label: String): LauncherApp {
        val packageName = "org.quicklauncher.fixture.calendar"
        return LauncherApp(
            identity = AppActivityIdentity(
                ProfileSerial.of(0),
                PackageName.parse(packageName),
                ActivityName.parse("$packageName.CalendarActivity"),
            ),
            label = label,
            workProfile = false,
        )
    }
}
