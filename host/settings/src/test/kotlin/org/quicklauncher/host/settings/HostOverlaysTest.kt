package org.quicklauncher.host.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.host.runtime.LauncherDestination
import org.quicklauncher.host.runtime.RecoveryInstance
import org.quicklauncher.host.runtime.permissions.HomeRoleState
import org.quicklauncher.host.runtime.notifications.NotificationAccessState
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.profile.ProfileState
import org.quicklauncher.host.runtime.profile.ProfileTransition
import org.quicklauncher.host.runtime.shortcuts.ShortcutTargetIdentity
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
    fun `map overview creates a named folder through an explicit command`() {
        val names = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                MapOverviewSurface(
                    destinations = emptyList(),
                    onSelectDestination = {},
                    onCreateFolder = { names += it },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Create folder").assertIsDisplayed().performClick()
        compose.onNodeWithText("Folder name").performTextInput("  Travel  ")
        compose.onNodeWithText("Create", substring = false).performClick()

        assertEquals(listOf("Travel"), names)
    }

    @Test
    fun `map overview permanently exposes Private Space`() {
        var opened = false
        compose.setContent {
            MaterialTheme {
                MapOverviewSurface(
                    destinations = emptyList(),
                    onSelectDestination = {},
                    onOpenPrivateSpace = { opened = true },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Private Space").assertIsDisplayed().performClick()
        assertEquals(true, opened)
    }

    @Test
    fun `hidden Private Space retains a label-free recovery command`() {
        var requestedShow = false
        compose.setContent {
            MaterialTheme {
                MapOverviewSurface(
                    destinations = emptyList(),
                    onSelectDestination = {},
                    privateSpaceVisible = false,
                    onOpenPrivateSpace = { requestedShow = true },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Private Space", substring = false).assertIsNotDisplayed()
        compose.onNodeWithText("Show Private Space").assertIsDisplayed().performClick()
        assertEquals(true, requestedShow)
    }

    @Test
    fun `system hidden locked Private Space removes the map entry point`() {
        compose.setContent {
            MaterialTheme {
                MapOverviewSurface(
                    destinations = emptyList(),
                    onSelectDestination = {},
                    privateSpaceEntryPointAvailable = false,
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Private Space", substring = false).assertIsNotDisplayed()
        compose.onNodeWithText("Show Private Space").assertIsNotDisplayed()
    }

    @Test
    fun `settings explicitly hides and shows the Private Space container`() {
        var selected: PrivateSpaceVisibilityOption? = null
        var systemSettingsOpened = false
        compose.setContent {
            MaterialTheme {
                LauncherSettingsSurface(
                    homeRoleState = HomeRoleState.HELD,
                    fallbackAvailable = false,
                    onRequestHomeRole = {},
                    onOpenHomeSettings = {},
                    onOpenAppRecovery = {},
                    privateSpaceVisibility = PrivateSpaceVisibilityOption.VISIBLE,
                    onPrivateSpaceVisibility = { selected = it },
                    onOpenPrivateSpaceSettings = { systemSettingsOpened = true },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Hide Private Space").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Private Space settings").performScrollTo().performClick()
        assertEquals(PrivateSpaceVisibilityOption.HIDDEN, selected)
        assertEquals(true, systemSettingsOpened)
    }

    @Test
    fun `notification access is requested only by an explicit settings action`() {
        var requested = false
        compose.setContent {
            MaterialTheme {
                LauncherSettingsSurface(
                    homeRoleState = HomeRoleState.HELD,
                    fallbackAvailable = false,
                    onRequestHomeRole = {},
                    onOpenHomeSettings = {},
                    onOpenAppRecovery = {},
                    notificationStyle = IndicatorStyleOption.DOT,
                    notificationAccess = NotificationAccessState.ACTION_REQUIRED,
                    onNotificationStyle = {},
                    onRequestNotificationAccess = { requested = true },
                    onOpenNotificationRecovery = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Allow notification indicators").assertIsDisplayed().performClick()
        assertEquals(true, requested)
    }

    @Test
    fun `paused work profile retains an explicit resume action`() {
        val serial = ProfileSerial.of(10)
        var request: Pair<ProfileSerial, Boolean>? = null
        compose.setContent {
            MaterialTheme {
                LauncherSettingsSurface(
                    homeRoleState = HomeRoleState.HELD,
                    fallbackAvailable = false,
                    onRequestHomeRole = {},
                    onOpenHomeSettings = {},
                    onOpenAppRecovery = {},
                    workProfiles = listOf(
                        ProfileState(
                            serial,
                            ProfileKind.WORK,
                            ProfileAvailability.QUIET,
                            ProfileTransition.IDLE,
                        ),
                    ),
                    onSetWorkMode = { profile, enabled -> request = profile to enabled },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Resume work apps").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(serial to true, request)
    }

    @Test
    fun `shortcut picker exposes sanitized work state and permits duplicate placements`() {
        val target = ShortcutTargetIdentity(
            ProfileSerial.of(10),
            PackageName.parse("org.quicklauncher.mail"),
            ShortcutId.parse("compose"),
        )
        val additions = mutableListOf<ShortcutTargetIdentity>()
        compose.setContent {
            MaterialTheme {
                LauncherSettingsSurface(
                    homeRoleState = HomeRoleState.HELD,
                    fallbackAvailable = false,
                    onRequestHomeRole = {},
                    onOpenHomeSettings = {},
                    onOpenAppRecovery = {},
                    shortcuts = listOf(
                        ShortcutSelectionItem(
                            target,
                            "Compose",
                            workProfile = true,
                            dynamic = true,
                            pinned = false,
                        ),
                    ),
                    onAddShortcut = additions::add,
                    onClose = {},
                )
            }
        }

        val add = compose.onNodeWithText("Add Compose shortcut, Work")
            .performScrollTo()
            .assertIsDisplayed()
        add.performClick()
        add.performClick()

        assertEquals(listOf(target, target), additions)
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
