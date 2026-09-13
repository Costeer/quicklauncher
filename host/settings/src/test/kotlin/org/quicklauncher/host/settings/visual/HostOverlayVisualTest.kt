package org.quicklauncher.host.settings.visual

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.LauncherDestination
import org.quicklauncher.host.runtime.RecoveryInstance
import org.quicklauncher.host.runtime.actions.ItemAction
import org.quicklauncher.host.runtime.actions.ItemActionOverlayState
import org.quicklauncher.host.runtime.actions.ItemAvailability
import org.quicklauncher.host.runtime.folders.FolderMemberKind
import org.quicklauncher.host.runtime.folders.FolderMemberPresentation
import org.quicklauncher.host.runtime.folders.FolderOverlayState
import org.quicklauncher.host.runtime.permissions.HomeRoleState
import org.quicklauncher.host.runtime.profile.PrivateSpaceState
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileTransition
import org.quicklauncher.host.runtime.profile.SecureOverlayProtection
import org.quicklauncher.host.settings.FolderOverlaySurface
import org.quicklauncher.host.settings.ItemActionOverlaySurface
import org.quicklauncher.host.settings.LauncherSettingsSurface
import org.quicklauncher.host.settings.AppRecoverySurface
import org.quicklauncher.host.settings.MapOverviewSurface
import org.quicklauncher.host.settings.PrivateSpaceOverlay
import org.quicklauncher.host.settings.RecoverySurface

class HostOverlayVisualTest {
    @get:Rule
    val paparazzi = Paparazzi(
        validateAccessibility = true,
        showSystemUi = false,
    )

    @Test
    fun `settings remains accessible across visual modes`() {
        variants.forEach { variant ->
            snapshot(variant, "settings") {
                LauncherSettingsSurface(
                    homeRoleState = HomeRoleState.AVAILABLE_NOT_HELD,
                    fallbackAvailable = true,
                    onRequestHomeRole = {},
                    onOpenHomeSettings = {},
                    onOpenAppRecovery = {},
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun `app recovery remains accessible across visual modes`() {
        variants.forEach { variant ->
            snapshot(variant, "app-recovery") {
                AppRecoverySurface(
                    apps = recoveryApps,
                    onLaunch = {},
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun `map overview remains accessible across visual modes`() {
        variants.forEach { variant ->
            snapshot(variant, "map") {
                MapOverviewSurface(
                    destinations = destinations,
                    onSelectDestination = {},
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun `recovery remains accessible across visual modes`() {
        variants.forEach { variant ->
            snapshot(variant, "recovery") {
                RecoverySurface(
                    instances = recoveryInstances,
                    onRetry = {},
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun `phase five overlays remain accessible across visual modes and policy states`() {
        variants.forEach { variant ->
            snapshot(variant, "folder") {
                FolderOverlaySurface(
                    state = folder,
                    onActivateMember = {},
                    onMoveMember = { _, _ -> },
                    onRemoveMember = {},
                    onRename = {},
                    onDelete = {},
                    onClose = {},
                )
            }
            snapshot(variant, "item-actions") {
                ItemActionOverlaySurface(
                    state = itemActions,
                    onCommand = {},
                    onClose = {},
                )
            }
        }
        snapshot(variants.first(), "private-locked") {
            PrivateSpaceOverlay(
                state = PrivateSpaceState(
                    ProfileSerial.of(20),
                    ProfileAvailability.LOCKED,
                    ProfileTransition.IDLE,
                    emptyList(),
                ),
                protection = noOpProtection,
                onLock = {},
                onUnlock = {},
                onLaunch = {},
                onOpenSettings = {},
                onClose = {},
            )
        }
        snapshot(variants.first(), "private-loading") {
            PrivateSpaceOverlay(
                state = PrivateSpaceState(
                    ProfileSerial.of(20),
                    ProfileAvailability.LOCKED,
                    ProfileTransition.UNLOCKING,
                    emptyList(),
                ),
                protection = noOpProtection,
                onLock = {},
                onUnlock = {},
                onLaunch = {},
                onOpenSettings = {},
                onClose = {},
            )
        }
        snapshot(variants.first(), "private-empty") {
            PrivateSpaceOverlay(
                state = PrivateSpaceState(
                    ProfileSerial.of(20),
                    ProfileAvailability.AVAILABLE,
                    ProfileTransition.IDLE,
                    emptyList(),
                ),
                protection = noOpProtection,
                onLock = {},
                onUnlock = {},
                onLaunch = {},
                onOpenSettings = {},
                onClose = {},
            )
        }
    }

    private fun snapshot(
        variant: VisualVariant,
        surface: String,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        paparazzi.unsafeUpdateConfig(variant.device)
        paparazzi.snapshot(name = "$surface-${variant.id}") {
            MaterialTheme(
                colorScheme = if (variant.dark) darkColorScheme() else lightColorScheme(),
                content = content,
            )
        }
    }

    private companion object {
        val destinations = listOf(
            LauncherDestination(
                id = DestinationId.parse("org.quicklauncher.fixture/start"),
                name = "Start",
                x = 0,
                y = 0,
                start = true,
                current = true,
            ),
            LauncherDestination(
                id = DestinationId.parse("org.quicklauncher.fixture/adjacent"),
                name = "A destination with a deliberately long name",
                x = 1,
                y = 0,
                start = false,
                current = false,
            ),
        )

        val recoveryInstances = listOf(
            RecoveryInstance(
                id = ModuleInstanceId.parse("org.quicklauncher.fixture/retryable"),
                contributionLabel = "Retryable layout",
                code = "renderer_restore_failed",
                retryAllowed = true,
            ),
            RecoveryInstance(
                id = ModuleInstanceId.parse("org.quicklauncher.fixture/configuration"),
                contributionLabel = "Layout with a deliberately long contribution name",
                code = "configuration_migration_failed",
                retryAllowed = false,
            ),
        )

        val recoveryApps = listOf(
            org.quicklauncher.host.runtime.LauncherApp(
                identity = org.quicklauncher.contracts.domain.AppActivityIdentity(
                    profile = org.quicklauncher.contracts.domain.ProfileSerial.of(0),
                    packageName = org.quicklauncher.contracts.domain.PackageName.parse("org.example.personal"),
                    activityName = org.quicklauncher.contracts.domain.ActivityName.parse(
                        "org.example.personal.MainActivity",
                    ),
                ),
                label = "Personal app",
                workProfile = false,
            ),
            org.quicklauncher.host.runtime.LauncherApp(
                identity = org.quicklauncher.contracts.domain.AppActivityIdentity(
                    profile = org.quicklauncher.contracts.domain.ProfileSerial.of(10),
                    packageName = org.quicklauncher.contracts.domain.PackageName.parse("org.example.work"),
                    activityName = org.quicklauncher.contracts.domain.ActivityName.parse(
                        "org.example.work.MainActivity",
                    ),
                ),
                label = "Work app with a deliberately long label",
                workProfile = true,
            ),
        )

        val folder = FolderOverlayState.Open(
            id = ContentItemId.parse("org.quicklauncher.fixture/folder"),
            name = "Travel",
            members = listOf(
                FolderMemberPresentation(
                    id = ContentItemId.parse("org.quicklauncher.fixture/personal"),
                    label = "Maps",
                    kind = FolderMemberKind.APP,
                    workBadge = false,
                    available = true,
                    durableIndex = 0,
                ),
                FolderMemberPresentation(
                    id = ContentItemId.parse("org.quicklauncher.fixture/work"),
                    label = "Work calendar",
                    kind = FolderMemberKind.APP,
                    workBadge = true,
                    available = false,
                    durableIndex = 1,
                ),
            ),
            durableMemberCount = 3,
        )

        val itemActions = ItemActionOverlayState.Open(
            itemId = ContentItemId.parse("org.quicklauncher.fixture/actions"),
            label = "Maps",
            availability = ItemAvailability.AVAILABLE,
            actions = setOf(ItemAction.FAVORITE, ItemAction.HIDE, ItemAction.OPEN_DETAILS),
            folderIds = setOf(folder.id),
            memberOfFolderIds = emptySet(),
            folderLabels = mapOf(folder.id to folder.name),
        )

        val noOpProtection = SecureOverlayProtection { AutoCloseable {} }

        val variants = listOf(
            VisualVariant(
                id = "light-portrait",
                dark = false,
                device = device(false, false),
            ),
            VisualVariant(
                id = "dark-landscape",
                dark = true,
                device = device(true, false),
            ),
            VisualVariant(
                id = "large-text-light-portrait",
                dark = false,
                device = device(false, true),
            ),
        )

        fun device(landscape: Boolean, largeText: Boolean): DeviceConfig =
            DeviceConfig.PIXEL_5.copy(
                screenWidth = if (landscape) 2_340 else 1_080,
                screenHeight = if (landscape) 1_080 else 2_340,
                orientation = if (landscape) {
                    ScreenOrientation.LANDSCAPE
                } else {
                    ScreenOrientation.PORTRAIT
                },
                nightMode = if (landscape) NightMode.NIGHT else NightMode.NOTNIGHT,
                fontScale = if (largeText) 2f else 1f,
            )
    }
}

private data class VisualVariant(
    val id: String,
    val dark: Boolean,
    val device: DeviceConfig,
)
