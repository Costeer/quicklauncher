package org.quicklauncher.host.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.host.runtime.LauncherDestination
import org.quicklauncher.host.runtime.RecoveryInstance
import org.quicklauncher.host.runtime.permissions.HomeRoleState
import org.quicklauncher.host.runtime.notifications.NotificationAccessState
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.profile.ProfileState
import org.quicklauncher.host.runtime.profile.ProfileTransition
import org.quicklauncher.host.runtime.shortcuts.ShortcutTargetIdentity
import org.quicklauncher.host.runtime.theme.WallpaperTarget
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HostOverlaysTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `backup restore requires preview and preserves explicit cancel action`() {
        var previewed: String? = null
        var restored = false
        var cancelled = false
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BackupSettingsContent(
                        folderSelected = true,
                        automaticEnabled = true,
                        manualBackups = listOf(
                            BackupSnapshotSetting("snapshot-1", "Manual snapshot", "Created locally"),
                        ),
                        automaticBackups = emptyList(),
                        restoreReview = BackupRestoreReviewSetting(2, 4, 1, 1, 1),
                        onPreview = { id, _ -> previewed = id },
                        onRestore = { restored = true },
                        onCancelRestore = { cancelled = true },
                    )
                }
            }
        }

        compose.onNodeWithText("Manual snapshot").performScrollTo().performClick()
        compose.onNodeWithText("Back up current state and restore").performScrollTo().performClick()
        compose.onNodeWithText("Cancel restore").performScrollTo().performClick()

        assertEquals("snapshot-1", previewed)
        assertEquals(true, restored)
        assertEquals(true, cancelled)
    }

    @Test
    fun `backup pages separate manual archives from automatic snapshots`() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BackupSettingsContent(
                        folderSelected = true,
                        automaticEnabled = true,
                        manualBackups = listOf(
                            BackupSnapshotSetting("manual", "Manual archive", "Created locally"),
                        ),
                        automaticBackups = listOf(
                            BackupSnapshotSetting("automatic", "Automatic snapshot", "Created nightly"),
                        ),
                    )
                }
            }
        }

        compose.onNodeWithText("Manual archive").assertIsDisplayed()
        compose.onNodeWithText("Automatic snapshot").assertIsNotDisplayed()
        compose.onNodeWithText("Automatic backups").performScrollTo().performClick()
        compose.onNodeWithText("Manual archive").assertIsNotDisplayed()
        compose.onNodeWithText("Automatic snapshot").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `restore selection keeps theme assets coupled and web adapters independent`() {
        var selected: BackupRestoreSelectionSetting? = null
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BackupSettingsContent(
                        folderSelected = true,
                        automaticEnabled = false,
                        manualBackups = emptyList(),
                        automaticBackups = emptyList(),
                        restoreReview = BackupRestoreReviewSetting(2, 4, 1, 1, 1),
                        onRestoreSelection = { selection, _ -> selected = selection },
                    )
                }
            }
        }

        compose.onNodeWithText("Themes and imported assets ✓").performScrollTo().performClick()
        compose.onNodeWithText("Web search adapters ✓").performScrollTo().performClick()
        compose.onNodeWithText("Back up current state and restore").performScrollTo().performClick()

        assertEquals(
            BackupRestoreSelectionSetting(
                launcherMap = true,
                themesAndAssets = false,
                webAdapters = false,
            ),
            selected,
        )
    }

    @Test
    fun `post restore recovery exposes every required action`() {
        val invoked = linkedSetOf<String>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BackupSettingsContent(
                        folderSelected = true,
                        automaticEnabled = false,
                        manualBackups = emptyList(),
                        automaticBackups = emptyList(),
                        recovery = BackupRecoverySetting(2, 1, 3, listOf(10L, 11L, 12L)),
                        onReauthorizeDocuments = { invoked += "documents" },
                        onOpenPermissionSettings = { invoked += "permissions" },
                        onContinueWidgetRebinding = { invoked += "widgets" },
                        onReviewQuarantine = { invoked += "quarantine" },
                        onReviewProfiles = { invoked += "profiles" },
                    )
                }
            }
        }

        listOf(
            "Reauthorize document access" to "documents",
            "Open app permission settings" to "permissions",
            "Continue 2 widget bindings" to "widgets",
            "Review 1 quarantined modules" to "quarantine",
            "Review 3 profile-scoped apps" to "profiles",
        ).forEach { (label, event) ->
            compose.onNodeWithText(label).performScrollTo().performClick()
            assertEquals(true, event in invoked)
        }
        compose.onNodeWithText("Restored profile serial 10").assertIsDisplayed()
    }

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

    @Test
    fun `theme preview selection import and wallpaper target require distinct explicit actions`() {
        val profileId = ThemeProfileId.parse("org.quicklauncher.theme/test")
        var selected: ThemeProfileId? = null
        var previewed: ThemeProfileId? = null
        var imported = false
        var target: WallpaperTarget? = null
        var cancelled = false
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column(
                    Modifier.verticalScroll(rememberScrollState()),
                ) {
                    ThemeSettingsContent(
                        themeProfiles = listOf(
                            ThemeProfileSetting(profileId, "Synthetic profile", selected = true, previewed = false),
                        ),
                        onSelectThemeProfile = { selected = it },
                        onPreviewThemeProfile = { previewed = it },
                        onImportFont = { _ -> imported = true },
                        wallpaperConfirmationVisible = true,
                        wallpaperPreviewContent = {
                            androidx.compose.material3.Text("Synthetic crop preview")
                        },
                        onConfirmWallpaper = { target = it },
                        onCancelWallpaper = { cancelled = true },
                    )
                }
            }
        }

        compose.onNodeWithText("Synthetic profile").assertIsDisplayed().performClick()
        compose.onNodeWithText("Preview Synthetic profile").performClick()
        compose.onNodeWithText("Import body font").performScrollTo().performClick()
        compose.onNodeWithText("Synthetic crop preview").performScrollTo().assertIsDisplayed()
        assertEquals(profileId, selected)
        assertEquals(profileId, previewed)
        assertEquals(true, imported)
        assertEquals(null, target)

        compose.onNodeWithText("Both", substring = false).performScrollTo().performClick()
        assertEquals(WallpaperTarget.BOTH, target)
        compose.onNodeWithText("Cancel wallpaper change").performScrollTo().performClick()
        assertEquals(true, cancelled)
    }

    @Test
    fun `manual palette and registered module overrides validate before dispatch`() {
        val module = ContributionId.parse("org.quicklauncher.block/synthetic")
        var palette: ManualPaletteValues? = null
        var override: Pair<ContributionId, ArgbColor?>? = null
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ThemeSettingsContent(
                        themeProfiles = emptyList(),
                        manualPalette = ManualPaletteSetting(
                            ThemeProfileId.parse("org.quicklauncher.theme/synthetic-manual"),
                            ArgbColor.of(0xff2949a3L),
                            ArgbColor.of(0xffffffffL),
                            ArgbColor.of(0xfff5f5f5L),
                            ArgbColor.of(0xff161616L),
                        ),
                        onSaveManualPalette = { palette = it },
                        themeOverrides = listOf(ThemeOverrideSetting(module, "Synthetic module", null)),
                        onSaveThemeOverride = { id, accent -> override = id to accent },
                    )
                }
            }
        }

        compose.onNodeWithText("Primary").performScrollTo().performTextReplacement("bad")
        compose.onNodeWithText("Save manual palette").performScrollTo().assertIsNotEnabled()
        assertEquals(null, palette)
        compose.onNodeWithText("Primary").performTextReplacement("#FF2949A3")
        compose.onNodeWithText("Save manual palette").performClick()
        assertEquals(0xff2949a3L, palette?.primary?.value)

        compose.onNodeWithText("Save override").performScrollTo().performClick()
        assertEquals(module, override?.first)
        assertEquals(0xff2949a3L, override?.second?.value)
    }

    @Test
    fun `wallpaper confirmation exposes an explicit crop choice`() {
        var crop = FullWallpaperCrop
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ThemeSettingsContent(
                        themeProfiles = emptyList(),
                        wallpaperConfirmationVisible = true,
                        wallpaperPreviewContent = {
                            androidx.compose.material3.Text("Synthetic crop preview")
                        },
                        wallpaperCrop = crop,
                        onWallpaperCrop = { crop = it },
                    )
                }
            }
        }

        compose.onNodeWithText("Full image").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Center crop").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(CenterWallpaperCrop, crop)
    }
}
