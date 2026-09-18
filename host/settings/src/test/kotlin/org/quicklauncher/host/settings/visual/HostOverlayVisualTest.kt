package org.quicklauncher.host.settings.visual

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.host.runtime.LauncherDestination
import org.quicklauncher.host.runtime.RecoveryInstance
import org.quicklauncher.host.runtime.catalog.AppIcon
import org.quicklauncher.host.runtime.actions.ItemAction
import org.quicklauncher.host.runtime.actions.ItemActionOverlayState
import org.quicklauncher.host.runtime.actions.ItemAvailability
import org.quicklauncher.host.runtime.folders.FolderMemberKind
import org.quicklauncher.host.runtime.folders.FolderMemberPresentation
import org.quicklauncher.host.runtime.folders.FolderOverlayState
import org.quicklauncher.host.runtime.permissions.HomeRoleState
import org.quicklauncher.host.runtime.profile.PrivateSpaceState
import org.quicklauncher.host.runtime.profile.PrivateProfileItem
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileTransition
import org.quicklauncher.host.runtime.profile.SecureOverlayProtection
import org.quicklauncher.host.settings.FolderOverlaySurface
import org.quicklauncher.host.settings.CreateFolderDialog
import org.quicklauncher.host.settings.ItemActionOverlaySurface
import org.quicklauncher.host.settings.LauncherSettingsSurface
import org.quicklauncher.host.settings.AppRecoverySurface
import org.quicklauncher.host.settings.MapOverviewSurface
import org.quicklauncher.host.settings.PrivateSpaceOverlay
import org.quicklauncher.host.settings.RecoverySurface
import org.quicklauncher.host.settings.ThemeProfileSetting
import org.quicklauncher.host.settings.ThemeProfileSettingsContent
import org.quicklauncher.host.settings.ThemeSettingsContent
import org.quicklauncher.host.settings.ThemeOverrideSetting
import org.quicklauncher.host.settings.IconPackSetting
import org.quicklauncher.host.settings.WallpaperConfirmationContent
import org.quicklauncher.host.settings.ManualPaletteSetting
import org.quicklauncher.host.settings.ManualPaletteSettingsContent
import org.quicklauncher.host.settings.BackupSettingsContent
import org.quicklauncher.host.settings.BackupSnapshotSetting
import org.quicklauncher.host.settings.BackupRestoreReviewSetting
import org.quicklauncher.host.runtime.theme.IconPackDialect
import org.quicklauncher.host.runtime.theme.IconPackSelection

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
    fun `theme profile and wallpaper confirmation remain accessible across visual modes`() {
        variants.forEach { variant ->
            snapshot(variant, "theme-profiles") {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemeProfileSettingsContent(
                            themeProfiles = listOf(
                                ThemeProfileSetting(
                                    ThemeProfileId.parse("org.quicklauncher.theme/system"),
                                    "System",
                                    selected = true,
                                    previewed = false,
                                ),
                                ThemeProfileSetting(
                                    ThemeProfileId.parse("org.quicklauncher.theme/synthetic"),
                                    "Synthetic expressive profile",
                                    selected = false,
                                    previewed = true,
                                    deletable = true,
                                ),
                            ),
                        )
                    }
                }
            }
            snapshot(variant, "wallpaper-confirmation") {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WallpaperConfirmationContent(
                            visible = true,
                            previewContent = {
                                Box(
                                    Modifier.fillMaxWidth().height(120.dp)
                                        .background(Color(0xff28547a)),
                                ) {
                                    Text(
                                        "Synthetic crop preview",
                                        color = Color.White,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            snapshot(variant, "manual-palette") {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ManualPaletteSettingsContent(
                            ManualPaletteSetting(
                                ThemeProfileId.parse("org.quicklauncher.theme/synthetic-manual"),
                                ArgbColor.of(0xff2949a3L),
                                ArgbColor.of(0xffffffffL),
                                ArgbColor.of(0xfff5f5f5L),
                                ArgbColor.of(0xff161616L),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `missing settings states remain accessible at large text`() {
        snapshot(largeTextVariant, "create-folder-dialog") {
            CreateFolderDialog(onCreate = {}, onDismiss = {})
        }
        listOf(
            "theme-settings-top" to 0,
            "theme-settings-middle" to 760,
            "theme-settings-bottom" to 1_500,
            "theme-settings-end" to 10_000,
        ).forEach { (surface, scrollDp) ->
            snapshot(largeTextVariant, surface) {
                val initialScroll = with(LocalDensity.current) { scrollDp.dp.roundToPx() }
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState(initial = initialScroll))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemeSettingsContent(
                            themeProfiles = themeProfiles,
                            themeMessage = "Preview changes are local until selected.",
                            manualPalette = manualPalette,
                            themeOverrides = themeOverrides,
                            iconPacks = iconPacks,
                        )
                    }
                }
            }
        }
        listOf(
            "private-locked" to PrivateSpaceState(
                ProfileSerial.of(20),
                ProfileAvailability.LOCKED,
                ProfileTransition.IDLE,
                emptyList(),
            ),
            "private-loading" to PrivateSpaceState(
                ProfileSerial.of(20),
                ProfileAvailability.LOCKED,
                ProfileTransition.UNLOCKING,
                emptyList(),
            ),
            "private-empty" to PrivateSpaceState(
                ProfileSerial.of(20),
                ProfileAvailability.AVAILABLE,
                ProfileTransition.IDLE,
                emptyList(),
            ),
            "private-unlocked" to PrivateSpaceState(
                ProfileSerial.of(20),
                ProfileAvailability.AVAILABLE,
                ProfileTransition.IDLE,
                listOf(privateItem),
            ),
        ).forEach { (surface, state) ->
            snapshot(largeTextVariant, surface) {
                PrivateSpaceOverlay(
                    state = state,
                    protection = noOpProtection,
                    onLock = {},
                    onUnlock = {},
                    onLaunch = {},
                    onOpenSettings = {},
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun `backup review remains usable across visual modes`() {
        variants.forEach { variant ->
            val captures = buildList {
                add("backup-review" to false)
                if (variant.id == "large-text-light-portrait") {
                    add("backup-review-actions" to true)
                }
            }
            captures.forEach { (surface, showReviewActions) ->
                snapshot(variant, surface) {
                    val initialScroll = with(LocalDensity.current) {
                        if (showReviewActions) 940.dp.roundToPx() else 0
                    }
                    val scrollState = rememberScrollState(initial = initialScroll)
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BackupSettingsContent(
                                folderSelected = true,
                                automaticEnabled = true,
                                manualBackups = listOf(
                                    BackupSnapshotSetting(
                                        "manual-1",
                                        "Manual backup · today",
                                        "Encrypted · 428 KB",
                                    ),
                                ),
                                automaticBackups = listOf(
                                    BackupSnapshotSetting(
                                        "auto-1",
                                        "Automatic backup · yesterday",
                                        "Charging snapshot · 417 KB",
                                    ),
                                ),
                                message = "Review the restore before applying it.",
                                restoreReview = BackupRestoreReviewSetting(3, 14, 2, 1, 2),
                            )
                        }
                    }
                }
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

        val privateItem = PrivateProfileItem(
            identity = AppActivityIdentity(
                ProfileSerial.of(20),
                PackageName.parse("org.quicklauncher.fixture.private"),
                ActivityName.parse("org.quicklauncher.fixture.private.MainActivity"),
            ),
            label = "Private app with a deliberately long label",
            icon = AppIcon.of(byteArrayOf(1)),
        )

        val themeProfiles = listOf(
            ThemeProfileSetting(
                ThemeProfileId.parse("org.quicklauncher.theme/system"),
                "System",
                selected = true,
                previewed = false,
            ),
            ThemeProfileSetting(
                ThemeProfileId.parse("org.quicklauncher.theme/expressive"),
                "Expressive theme with a deliberately long label",
                selected = false,
                previewed = true,
                deletable = true,
            ),
        )
        val manualPalette = ManualPaletteSetting(
            ThemeProfileId.parse("org.quicklauncher.theme/manual"),
            ArgbColor.of(0xff2949a3L),
            ArgbColor.of(0xffffffffL),
            ArgbColor.of(0xfff5f5f5L),
            ArgbColor.of(0xff161616L),
        )
        val themeOverrides = listOf(
            ThemeOverrideSetting(
                ContributionId.parse("org.quicklauncher.block/favorites"),
                "Favorites block accent",
                ArgbColor.of(0xff6750a4L),
            ),
        )
        val iconPacks = listOf(
            IconPackSetting(
                IconPackSelection("org.quicklauncher.fixture.icons", IconPackDialect.NOVA),
                "Fixture icon pack with a deliberately long label",
            ),
        )

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
        val largeTextVariant = variants.single { it.id == "large-text-light-portrait" }

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
