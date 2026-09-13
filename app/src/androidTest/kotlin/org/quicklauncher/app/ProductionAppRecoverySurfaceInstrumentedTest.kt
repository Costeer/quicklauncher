package org.quicklauncher.app

import org.quicklauncher.host.runtime.contentItemId

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.LauncherApp

@RunWith(AndroidJUnit4::class)
@SdkSuppress(maxSdkVersion = 36) // Espresso 3.7 calls removed InputManager.getInstance on API 37.
class ProductionAppRecoverySurfaceInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `launch and actions are distinct controls with typed targets`() {
        val app = app(profile = 10, label = "Documents", workProfile = true)
        val launched = mutableListOf<AppActivityIdentity>()
        val openedActions = mutableListOf<ContentItemId>()

        compose.setContent {
            ProductionAppRecoverySurface(
                apps = listOf(app),
                onLaunch = launched::add,
                onOpenActions = openedActions::add,
                onClose = {},
            )
        }

        compose.onNodeWithText("Documents, Work").assertIsDisplayed()
        compose.onNodeWithContentDescription("Launch Documents")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        assertEquals(listOf(app.identity), launched)
        assertEquals(emptyList<ContentItemId>(), openedActions)

        compose.onNodeWithContentDescription("Actions for Documents")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        assertEquals(listOf(app.identity), launched)
        assertEquals(listOf(contentItemId(app)), openedActions)
    }

    @Test
    fun `close remains available when the recovery collection is empty`() {
        var closeCount = 0
        compose.setContent {
            ProductionAppRecoverySurface(
                apps = emptyList(),
                onLaunch = {},
                onOpenActions = {},
                onClose = { closeCount += 1 },
            )
        }

        compose.onNodeWithText("No apps are available for recovery").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(1, closeCount)
    }

    private fun app(profile: Long, label: String, workProfile: Boolean): LauncherApp {
        val packageName = "org.quicklauncher.fixture.documents"
        return LauncherApp(
            identity = AppActivityIdentity(
                profile = ProfileSerial.of(profile),
                packageName = PackageName.parse(packageName),
                activityName = ActivityName.parse("$packageName.DocumentsActivity"),
            ),
            label = label,
            workProfile = workProfile,
        )
    }
}
