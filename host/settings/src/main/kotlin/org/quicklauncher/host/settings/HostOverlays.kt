package org.quicklauncher.host.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.host.runtime.LauncherDestination
import org.quicklauncher.host.runtime.LauncherApp
import org.quicklauncher.host.runtime.theme.IconPackSelection
import org.quicklauncher.host.runtime.theme.ThemeFontRole
import org.quicklauncher.host.runtime.theme.WallpaperCrop
import org.quicklauncher.host.runtime.theme.WallpaperTarget
import org.quicklauncher.host.runtime.RecoveryInstance
import org.quicklauncher.host.runtime.permissions.HomeRoleState
import org.quicklauncher.host.runtime.notifications.NotificationAccessState
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileState
import org.quicklauncher.host.runtime.profile.ProfileTransition
import org.quicklauncher.host.runtime.shortcuts.ShortcutTargetIdentity

enum class IndicatorStyleOption { HIDDEN, DOT, APPROXIMATE_COUNT }
enum class PrivateSpaceVisibilityOption { VISIBLE, HIDDEN }

data class ShortcutSelectionItem(
    val target: ShortcutTargetIdentity,
    val label: String,
    val workProfile: Boolean,
    val dynamic: Boolean,
    val pinned: Boolean,
) {
    init {
        require(label.isNotBlank()) { "Shortcut selection labels must not be blank" }
        require(dynamic || pinned) { "Only dynamic or pinned shortcuts may be selected" }
    }
}

data class SearchProviderSetting(
    val id: ContributionId,
    val label: String,
    val enabled: Boolean,
) {
    init {
        require(label.isNotBlank()) { "Search provider label must not be blank" }
    }
}

data class ThemeProfileSetting(
    val id: ThemeProfileId,
    val label: String,
    val selected: Boolean,
    val previewed: Boolean,
    val deletable: Boolean = false,
) {
    init {
        require(label.isNotBlank()) { "Theme profile label must not be blank" }
    }
}

enum class ThemeProfileCreation {
    MATERIAL_YOU,
    MATERIAL_EXPRESSIVE,
    MANUAL,
}

data class IconPackSetting(
    val selection: IconPackSelection,
    val label: String,
) {
    init {
        require(label.isNotBlank()) { "Icon-pack label must not be blank" }
    }
}

data class ManualPaletteSetting(
    val profileId: ThemeProfileId,
    val primary: ArgbColor,
    val onPrimary: ArgbColor,
    val surface: ArgbColor,
    val onSurface: ArgbColor,
)

data class ManualPaletteValues(
    val primary: ArgbColor,
    val onPrimary: ArgbColor,
    val surface: ArgbColor,
    val onSurface: ArgbColor,
)

data class ThemeOverrideSetting(
    val contributionId: ContributionId,
    val label: String,
    val accent: ArgbColor?,
) {
    init {
        require(label.isNotBlank()) { "Theme override labels must not be blank" }
    }
}

data class BackupSnapshotSetting(
    val id: String,
    val label: String,
    val summary: String,
) {
    init {
        require(id.isNotBlank() && label.isNotBlank() && summary.isNotBlank())
    }
}

data class BackupRestoreReviewSetting(
    val destinations: Int,
    val modules: Int,
    val widgetsToRebind: Int,
    val quarantined: Int,
    val profilesToReview: Int,
    val profileSerials: List<Long> = emptyList(),
)

data class BackupRestoreSelectionSetting(
    val launcherMap: Boolean = true,
    val themesAndAssets: Boolean = true,
    val webAdapters: Boolean = true,
) {
    init {
        require(launcherMap || themesAndAssets || webAdapters) {
            "At least one restore category is required"
        }
    }
}

data class BackupRecoverySetting(
    val widgetsToRebind: Int,
    val quarantined: Int,
    val profilesToReview: Int,
    val profileSerials: List<Long> = emptyList(),
) {
    init {
        require(widgetsToRebind >= 0 && quarantined >= 0 && profilesToReview >= 0)
        require(profileSerials.size <= profilesToReview)
    }
}

@Composable
fun LauncherSettingsSurface(
    homeRoleState: HomeRoleState,
    fallbackAvailable: Boolean,
    onRequestHomeRole: () -> Unit,
    onOpenHomeSettings: () -> Unit,
    onOpenAppRecovery: () -> Unit,
    notificationStyle: IndicatorStyleOption = IndicatorStyleOption.HIDDEN,
    notificationAccess: NotificationAccessState = NotificationAccessState.ACTION_REQUIRED,
    onNotificationStyle: (IndicatorStyleOption) -> Unit = {},
    onRequestNotificationAccess: () -> Unit = {},
    onOpenNotificationRecovery: () -> Unit = {},
    privateSpaceVisibility: PrivateSpaceVisibilityOption = PrivateSpaceVisibilityOption.VISIBLE,
    onPrivateSpaceVisibility: (PrivateSpaceVisibilityOption) -> Unit = {},
    onOpenPrivateSpaceSettings: () -> Unit = {},
    workProfiles: List<ProfileState> = emptyList(),
    onSetWorkMode: (org.quicklauncher.contracts.domain.ProfileSerial, Boolean) -> Unit = { _, _ -> },
    shortcuts: List<ShortcutSelectionItem> = emptyList(),
    onAddShortcut: (ShortcutTargetIdentity) -> Unit = {},
    searchProviders: List<SearchProviderSetting> = emptyList(),
    localSearchHistory: Boolean = false,
    onSearchProviderEnabled: (ContributionId, Boolean) -> Unit = { _, _ -> },
    onLocalSearchHistory: (Boolean) -> Unit = {},
    themeProfiles: List<ThemeProfileSetting> = emptyList(),
    themeMessage: String? = null,
    onSelectThemeProfile: (ThemeProfileId) -> Unit = {},
    onPreviewThemeProfile: (ThemeProfileId?) -> Unit = {},
    onDeleteThemeProfile: (ThemeProfileId) -> Unit = {},
    onCreateThemeProfile: (ThemeProfileCreation) -> Unit = {},
    manualPalette: ManualPaletteSetting? = null,
    onSaveManualPalette: (ManualPaletteValues) -> Unit = {},
    themeOverrides: List<ThemeOverrideSetting> = emptyList(),
    onSaveThemeOverride: (ContributionId, ArgbColor?) -> Unit = { _, _ -> },
    onImportFont: (ThemeFontRole) -> Unit = {},
    iconPacks: List<IconPackSetting> = emptyList(),
    onSelectIconPack: (IconPackSelection?) -> Unit = {},
    onLauncherBackground: (String) -> Unit = {},
    onDestinationBackground: (String) -> Unit = {},
    onImportLauncherBackground: () -> Unit = {},
    onImportDestinationBackground: () -> Unit = {},
    onDeriveThemeFromImage: () -> Unit = {},
    wallpaperConfirmationVisible: Boolean = false,
    wallpaperPreviewContent: (@Composable () -> Unit)? = null,
    wallpaperCrop: WallpaperCrop = FullWallpaperCrop,
    wallpaperResult: String? = null,
    onChooseWallpaper: () -> Unit = {},
    onWallpaperCrop: (WallpaperCrop) -> Unit = {},
    onConfirmWallpaper: (WallpaperTarget) -> Unit = {},
    onCancelWallpaper: () -> Unit = {},
    backupSupported: Boolean = false,
    backupFolderSelected: Boolean = false,
    automaticBackupsEnabled: Boolean = false,
    manualBackups: List<BackupSnapshotSetting> = emptyList(),
    automaticBackups: List<BackupSnapshotSetting> = emptyList(),
    preRestoreBackups: List<BackupSnapshotSetting> = emptyList(),
    backupMessage: String? = null,
    backupRestoreReview: BackupRestoreReviewSetting? = null,
    onChooseBackupFolder: () -> Unit = {},
    onAutomaticBackups: (Boolean) -> Unit = {},
    onExportBackup: (CharArray?) -> Unit = {},
    onPreviewBackup: (String, CharArray?) -> Unit = { _, _ -> },
    onRestoreBackup: (CharArray?) -> Unit = {},
    onCancelRestore: () -> Unit = {},
    onExportSupportBundle: () -> Unit = {},
    backupRecovery: BackupRecoverySetting? = null,
    onRestoreSelection: ((BackupRestoreSelectionSetting, CharArray?) -> Unit)? = null,
    onReauthorizeDocuments: () -> Unit = {},
    onOpenPermissionSettings: () -> Unit = {},
    onContinueWidgetRebinding: () -> Unit = {},
    onReviewQuarantine: () -> Unit = {},
    onReviewProfiles: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverlayScaffold(stringResource(R.string.settings_title), onClose, modifier) { contentModifier ->
        Column(
            modifier = contentModifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = when (homeRoleState) {
                    HomeRoleState.HELD -> stringResource(R.string.home_role_held)
                    HomeRoleState.AVAILABLE_NOT_HELD -> stringResource(R.string.home_role_available)
                    HomeRoleState.UNAVAILABLE -> stringResource(R.string.home_role_unavailable)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (homeRoleState == HomeRoleState.AVAILABLE_NOT_HELD) {
                Button(onClick = onRequestHomeRole) {
                    Text(stringResource(R.string.request_home_role))
                }
            }
            if (fallbackAvailable) {
                OutlinedButton(onClick = onOpenHomeSettings) {
                    Text(stringResource(R.string.open_home_settings))
                }
            }
            OutlinedButton(onClick = onOpenAppRecovery) {
                Text(stringResource(R.string.open_app_recovery))
            }
            Text(
                stringResource(R.string.notification_indicators),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(notificationAccess.explanationResource()))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IndicatorStyleOption.entries.forEach { style ->
                    OutlinedButton(onClick = { onNotificationStyle(style) }) {
                        Text(
                            stringResource(style.labelResource()) +
                                if (style == notificationStyle) " ✓" else "",
                        )
                    }
                }
            }
            if (notificationAccess == NotificationAccessState.ACTION_REQUIRED ||
                notificationAccess == NotificationAccessState.DENIED ||
                notificationAccess == NotificationAccessState.REVOKED
            ) {
                Button(onClick = onRequestNotificationAccess) {
                    Text(stringResource(R.string.notification_access_request))
                }
            }
            if (notificationAccess == NotificationAccessState.DENIED ||
                notificationAccess == NotificationAccessState.REVOKED ||
                notificationAccess == NotificationAccessState.DISCONNECTED
            ) {
                OutlinedButton(onClick = onOpenNotificationRecovery) {
                    Text(stringResource(R.string.notification_access_recovery))
                }
            }
            Text(
                stringResource(R.string.private_space_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(
                    if (privateSpaceVisibility == PrivateSpaceVisibilityOption.VISIBLE) {
                        R.string.private_space_container_visible
                    } else {
                        R.string.private_space_container_hidden
                    },
                ),
            )
            OutlinedButton(
                onClick = {
                    onPrivateSpaceVisibility(
                        if (privateSpaceVisibility == PrivateSpaceVisibilityOption.VISIBLE) {
                            PrivateSpaceVisibilityOption.HIDDEN
                        } else {
                            PrivateSpaceVisibilityOption.VISIBLE
                        },
                    )
                },
            ) {
                Text(
                    stringResource(
                        if (privateSpaceVisibility == PrivateSpaceVisibilityOption.VISIBLE) {
                            R.string.private_space_hide
                        } else {
                            R.string.private_space_show
                        },
                    ),
                )
            }
            OutlinedButton(onClick = onOpenPrivateSpaceSettings) {
                Text(stringResource(R.string.private_space_settings))
            }
            if (workProfiles.isNotEmpty()) {
                Text(
                    "Work profiles",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                workProfiles.forEachIndexed { index, profile ->
                    val enabled = profile.availability == ProfileAvailability.AVAILABLE
                    val transitioning = profile.transition != ProfileTransition.IDLE
                    Text(
                        when {
                            transitioning -> "Work profile ${index + 1}: changing mode"
                            enabled -> "Work profile ${index + 1}: available"
                            profile.availability == ProfileAvailability.QUIET ->
                                "Work profile ${index + 1}: paused"
                            else -> "Work profile ${index + 1}: unavailable"
                        },
                    )
                    OutlinedButton(
                        onClick = { onSetWorkMode(profile.serial, !enabled) },
                        enabled = !transitioning &&
                            profile.availability != ProfileAvailability.UNRESOLVED,
                    ) {
                        Text(if (enabled) "Pause work apps" else "Resume work apps")
                    }
                }
            }
            Text(
                stringResource(R.string.app_shortcuts),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (shortcuts.isEmpty()) {
                Text(stringResource(R.string.no_app_shortcuts))
            } else {
                shortcuts.forEach { shortcut ->
                    OutlinedButton(
                        onClick = { onAddShortcut(shortcut.target) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (shortcut.workProfile) {
                                stringResource(R.string.add_work_shortcut, shortcut.label)
                            } else {
                                stringResource(R.string.add_shortcut, shortcut.label)
                            },
                        )
                    }
                }
            }
            Text(
                "Search providers",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            searchProviders.forEach { provider ->
                OutlinedButton(
                    onClick = { onSearchProviderEnabled(provider.id, !provider.enabled) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(provider.label + if (provider.enabled) " ✓" else "")
                }
            }
            OutlinedButton(onClick = { onLocalSearchHistory(!localSearchHistory) }) {
                Text("Local app and shortcut history" + if (localSearchHistory) " ✓" else "")
            }
            ThemeSettingsContent(
                themeProfiles,
                themeMessage,
                onSelectThemeProfile,
                onPreviewThemeProfile,
                onDeleteThemeProfile,
                onCreateThemeProfile,
                manualPalette,
                onSaveManualPalette,
                themeOverrides,
                onSaveThemeOverride,
                onImportFont,
                iconPacks,
                onSelectIconPack,
                onLauncherBackground,
                onDestinationBackground,
                onImportLauncherBackground,
                onImportDestinationBackground,
                onDeriveThemeFromImage,
                wallpaperConfirmationVisible,
                wallpaperPreviewContent,
                wallpaperCrop,
                wallpaperResult,
                onChooseWallpaper,
                onWallpaperCrop,
                onConfirmWallpaper,
                onCancelWallpaper,
            )
            if (backupSupported) {
                BackupSettingsContent(
                    backupFolderSelected,
                    automaticBackupsEnabled,
                    manualBackups,
                    automaticBackups,
                    backupMessage,
                    backupRestoreReview,
                    onChooseBackupFolder,
                    onAutomaticBackups,
                    onExportBackup,
                    onPreviewBackup,
                    onRestoreBackup,
                    onCancelRestore,
                    onExportSupportBundle,
                    backupRecovery,
                    onRestoreSelection,
                    onReauthorizeDocuments,
                    onOpenPermissionSettings,
                    onContinueWidgetRebinding,
                    onReviewQuarantine,
                    onReviewProfiles,
                    preRestoreBackups = preRestoreBackups,
                )
            }
        }
    }
}

@Composable
fun BackupSettingsContent(
    folderSelected: Boolean,
    automaticEnabled: Boolean,
    manualBackups: List<BackupSnapshotSetting>,
    automaticBackups: List<BackupSnapshotSetting>,
    message: String? = null,
    restoreReview: BackupRestoreReviewSetting? = null,
    onChooseFolder: () -> Unit = {},
    onAutomaticEnabled: (Boolean) -> Unit = {},
    onExport: (CharArray?) -> Unit = {},
    onPreview: (String, CharArray?) -> Unit = { _, _ -> },
    onRestore: (CharArray?) -> Unit = {},
    onCancelRestore: () -> Unit = {},
    onExportSupportBundle: () -> Unit = {},
    recovery: BackupRecoverySetting? = null,
    onRestoreSelection: ((BackupRestoreSelectionSetting, CharArray?) -> Unit)? = null,
    onReauthorizeDocuments: () -> Unit = {},
    onOpenPermissionSettings: () -> Unit = {},
    onContinueWidgetRebinding: () -> Unit = {},
    onReviewQuarantine: () -> Unit = {},
    onReviewProfiles: () -> Unit = {},
    preRestoreBackups: List<BackupSnapshotSetting> = emptyList(),
) {
    var passphrase by remember { mutableStateOf("") }
    var plaintext by remember { mutableStateOf(false) }
    var automaticPage by remember { mutableStateOf(false) }
    var restoreSelection by remember(restoreReview) {
        mutableStateOf(BackupRestoreSelectionSetting())
    }
    val suppliedPassphrase = { if (plaintext) null else passphrase.toCharArray() }
    Text(
        "Backup and restore",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        if (folderSelected) {
            "Backup folder authorized"
        } else {
            "Choose a folder before exporting or restoring backups."
        },
    )
    Button(onClick = onChooseFolder) {
        Text(if (folderSelected) "Change backup folder" else "Choose backup folder")
    }
    if (folderSelected) {
        OutlinedButton(onClick = { automaticPage = false }) {
            Text("Manual backups" + if (!automaticPage) " ✓" else "")
        }
        OutlinedButton(onClick = { automaticPage = true }) {
            Text("Automatic backups" + if (automaticPage) " ✓" else "")
        }
        if (automaticPage) {
            OutlinedButton(onClick = { onAutomaticEnabled(!automaticEnabled) }) {
                Text("Nightly charging-only backups" + if (automaticEnabled) " ✓" else "")
            }
            BackupInventoryContent("Automatic snapshots", automaticBackups) { id ->
                onPreview(id, null)
            }
        } else {
            OutlinedButton(onClick = { plaintext = !plaintext }) {
                Text(if (plaintext) "Plaintext archive selected" else "Protect archive with passphrase")
            }
            if (!plaintext) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { if (it.length <= MAX_BACKUP_PASSPHRASE_CHARS) passphrase = it },
                    label = { Text("Backup passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                enabled = plaintext || passphrase.isNotEmpty(),
                onClick = {
                    val value = suppliedPassphrase()
                    onExport(value)
                    value?.fill('\u0000')
                    passphrase = ""
                },
            ) { Text("Export backup now") }
            BackupInventoryContent("Manual snapshots", manualBackups) { id ->
                val value = suppliedPassphrase()
                onPreview(id, value)
                value?.fill('\u0000')
                passphrase = ""
            }
            if (preRestoreBackups.isNotEmpty()) {
                BackupInventoryContent("Safety snapshots", preRestoreBackups) { id ->
                    val value = suppliedPassphrase()
                    onPreview(id, value)
                    value?.fill('\u0000')
                    passphrase = ""
                }
            }
        }
    }
    restoreReview?.let { review ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Review restore", style = MaterialTheme.typography.titleSmall)
                Text("${review.destinations} destinations, ${review.modules} modules")
                Text("${review.widgetsToRebind} widgets require rebinding")
                Text("${review.quarantined} unavailable modules will be quarantined")
                Text("${review.profilesToReview} profiles require review")
                Text("Permissions and document grants require reauthorization.")
                RestoreCategoryButton(
                    label = "Destination map",
                    selected = restoreSelection.launcherMap,
                    enabled = restoreSelection.themesAndAssets || restoreSelection.webAdapters,
                ) {
                    restoreSelection = restoreSelection.copy(launcherMap = !restoreSelection.launcherMap)
                }
                RestoreCategoryButton(
                    label = "Themes and imported assets",
                    selected = restoreSelection.themesAndAssets,
                    enabled = restoreSelection.launcherMap || restoreSelection.webAdapters,
                ) {
                    restoreSelection = restoreSelection.copy(
                        themesAndAssets = !restoreSelection.themesAndAssets,
                    )
                }
                RestoreCategoryButton(
                    label = "Web search adapters",
                    selected = restoreSelection.webAdapters,
                    enabled = restoreSelection.launcherMap || restoreSelection.themesAndAssets,
                ) {
                    restoreSelection = restoreSelection.copy(webAdapters = !restoreSelection.webAdapters)
                }
                Button(onClick = {
                    val value = suppliedPassphrase()
                    val selectedRestore = onRestoreSelection
                    if (selectedRestore == null) onRestore(value) else selectedRestore(restoreSelection, value)
                    value?.fill('\u0000')
                    passphrase = ""
                }) { Text("Back up current state and restore") }
                OutlinedButton(onClick = onCancelRestore) { Text("Cancel restore") }
            }
        }
    }
    message?.let { Text(it, modifier = Modifier.semantics { stateDescription = it }) }
    recovery?.let { value ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Finish restore recovery", style = MaterialTheme.typography.titleSmall)
                Text("Restored permissions and document grants are never reused automatically.")
                OutlinedButton(onClick = onReauthorizeDocuments) { Text("Reauthorize document access") }
                OutlinedButton(onClick = onOpenPermissionSettings) { Text("Open app permission settings") }
                if (value.widgetsToRebind > 0) {
                    OutlinedButton(onClick = onContinueWidgetRebinding) {
                        Text("Continue ${value.widgetsToRebind} widget bindings")
                    }
                }
                if (value.quarantined > 0) {
                    OutlinedButton(onClick = onReviewQuarantine) {
                        Text("Review ${value.quarantined} quarantined modules")
                    }
                }
                if (value.profilesToReview > 0) {
                    value.profileSerials.forEach { serial ->
                        Text("Restored profile serial $serial")
                    }
                    Text("Unavailable profile data stays hidden until its Android profile is available.")
                    OutlinedButton(onClick = onReviewProfiles) {
                        Text("Review ${value.profilesToReview} profile-scoped apps")
                    }
                }
            }
        }
    }
    OutlinedButton(onClick = onExportSupportBundle) { Text("Export redacted support bundle") }
}

@Composable
private fun RestoreCategoryButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(enabled = enabled, onClick = onClick) {
        Text(label + if (selected) " ✓" else "")
    }
}

@Composable
private fun BackupInventoryContent(
    title: String,
    snapshots: List<BackupSnapshotSetting>,
    onPreview: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    if (snapshots.isEmpty()) {
        Text("No ${title.lowercase()}")
    } else {
        snapshots.forEach { snapshot ->
            OutlinedButton(
                onClick = { onPreview(snapshot.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Text(snapshot.label)
                    Text(snapshot.summary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun ThemeSettingsContent(
    themeProfiles: List<ThemeProfileSetting>,
    themeMessage: String? = null,
    onSelectThemeProfile: (ThemeProfileId) -> Unit = {},
    onPreviewThemeProfile: (ThemeProfileId?) -> Unit = {},
    onDeleteThemeProfile: (ThemeProfileId) -> Unit = {},
    onCreateThemeProfile: (ThemeProfileCreation) -> Unit = {},
    manualPalette: ManualPaletteSetting? = null,
    onSaveManualPalette: (ManualPaletteValues) -> Unit = {},
    themeOverrides: List<ThemeOverrideSetting> = emptyList(),
    onSaveThemeOverride: (ContributionId, ArgbColor?) -> Unit = { _, _ -> },
    onImportFont: (ThemeFontRole) -> Unit = {},
    iconPacks: List<IconPackSetting> = emptyList(),
    onSelectIconPack: (IconPackSelection?) -> Unit = {},
    onLauncherBackground: (String) -> Unit = {},
    onDestinationBackground: (String) -> Unit = {},
    onImportLauncherBackground: () -> Unit = {},
    onImportDestinationBackground: () -> Unit = {},
    onDeriveThemeFromImage: () -> Unit = {},
    wallpaperConfirmationVisible: Boolean = false,
    wallpaperPreviewContent: (@Composable () -> Unit)? = null,
    wallpaperCrop: WallpaperCrop = FullWallpaperCrop,
    wallpaperResult: String? = null,
    onChooseWallpaper: () -> Unit = {},
    onWallpaperCrop: (WallpaperCrop) -> Unit = {},
    onConfirmWallpaper: (WallpaperTarget) -> Unit = {},
    onCancelWallpaper: () -> Unit = {},
) {
    ThemeProfileSettingsContent(
        themeProfiles,
        onSelectThemeProfile,
        onPreviewThemeProfile,
        onDeleteThemeProfile,
        onCreateThemeProfile,
    )
    manualPalette?.let { setting ->
        ManualPaletteSettingsContent(setting, onSaveManualPalette)
    }
    if (themeOverrides.isNotEmpty()) {
        Text("Module accent overrides", style = MaterialTheme.typography.titleSmall)
        themeOverrides.take(MAX_THEME_OVERRIDE_SETTINGS).forEach { setting ->
            key(setting.contributionId) {
                ThemeOverrideSettingsContent(setting, manualPalette?.primary, onSaveThemeOverride)
            }
        }
    }
    Text("Font roles", style = MaterialTheme.typography.titleSmall)
    ThemeFontRole.entries.forEach { role ->
        OutlinedButton(onClick = { onImportFont(role) }) {
            Text("Import ${role.name.lowercase()} font")
        }
    }
    Text("Icon pack", style = MaterialTheme.typography.titleSmall)
    OutlinedButton(onClick = { onSelectIconPack(null) }) { Text("Use application icons") }
    iconPacks.forEach { pack ->
        OutlinedButton(onClick = { onSelectIconPack(pack.selection) }, modifier = Modifier.fillMaxWidth()) {
            Text(pack.label)
        }
    }
    Text("Launcher background", style = MaterialTheme.typography.titleSmall)
    OutlinedButton(onClick = { onLauncherBackground("solid") }) { Text("Solid") }
    OutlinedButton(onClick = { onLauncherBackground("gradient") }) { Text("Gradient") }
    OutlinedButton(onClick = onImportLauncherBackground) { Text("Image") }
    Text("Current destination background", style = MaterialTheme.typography.titleSmall)
    OutlinedButton(onClick = { onDestinationBackground("inherit") }) { Text("Inherit") }
    OutlinedButton(onClick = { onDestinationBackground("solid") }) { Text("Solid") }
    OutlinedButton(onClick = { onDestinationBackground("gradient") }) { Text("Gradient") }
    OutlinedButton(onClick = onImportDestinationBackground) { Text("Image") }
    OutlinedButton(onClick = onDeriveThemeFromImage) { Text("Derive theme from image") }
    WallpaperConfirmationContent(
        wallpaperConfirmationVisible,
        wallpaperPreviewContent,
        wallpaperCrop,
        wallpaperResult,
        onChooseWallpaper,
        onWallpaperCrop,
        onConfirmWallpaper,
        onCancelWallpaper,
    )
    themeMessage?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { stateDescription = "Appearance error: $it" },
        )
    }
}

@Composable
fun ManualPaletteSettingsContent(
    setting: ManualPaletteSetting,
    onSave: (ManualPaletteValues) -> Unit = {},
) {
    var primary by remember(setting) { mutableStateOf(setting.primary.themeHex()) }
    var onPrimary by remember(setting) { mutableStateOf(setting.onPrimary.themeHex()) }
    var surface by remember(setting) { mutableStateOf(setting.surface.themeHex()) }
    var onSurface by remember(setting) { mutableStateOf(setting.onSurface.themeHex()) }
    val parsed = listOf(primary, onPrimary, surface, onSurface).map(::parseThemeHex)

    Text("Manual palette values", style = MaterialTheme.typography.titleSmall)
    ThemeColorField("Primary", primary, { primary = it })
    ThemeColorField("On primary", onPrimary, { onPrimary = it })
    ThemeColorField("Surface", surface, { surface = it })
    ThemeColorField("On surface", onSurface, { onSurface = it })
    Button(
        enabled = parsed.all { it != null },
        onClick = {
            onSave(
                ManualPaletteValues(
                    requireNotNull(parsed[0]),
                    requireNotNull(parsed[1]),
                    requireNotNull(parsed[2]),
                    requireNotNull(parsed[3]),
                ),
            )
        },
    ) { Text("Save manual palette") }
}

@Composable
private fun ThemeOverrideSettingsContent(
    setting: ThemeOverrideSetting,
    fallbackAccent: ArgbColor?,
    onSave: (ContributionId, ArgbColor?) -> Unit,
) {
    var accent by remember(setting) {
        mutableStateOf((setting.accent ?: fallbackAccent ?: ArgbColor.of(0xff2949a3L)).themeHex())
    }
    Text(setting.label)
    ThemeColorField("Accent for ${setting.label}", accent, { accent = it })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = parseThemeHex(accent) != null,
            onClick = { onSave(setting.contributionId, requireNotNull(parseThemeHex(accent))) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save override") }
        if (setting.accent != null) {
            OutlinedButton(
                onClick = { onSave(setting.contributionId, null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Remove override")
            }
        }
    }
}

@Composable
private fun ThemeColorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val invalid = parseThemeHex(value) == null
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 9) onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) ({ Text("Use #AARRGGBB") }) else null,
        modifier = Modifier.fillMaxWidth().semantics {
            if (invalid) error("Color must use #AARRGGBB")
        },
    )
}

private fun parseThemeHex(value: String): ArgbColor? {
    if (!THEME_HEX.matches(value)) return null
    return runCatching { ArgbColor.of(value.drop(1).toLong(16)) }.getOrNull()
}

private fun ArgbColor.themeHex(): String = "#%08X".format(value)

private val THEME_HEX = Regex("#[0-9A-Fa-f]{8}")
private const val MAX_THEME_OVERRIDE_SETTINGS = 32
private const val MAX_BACKUP_PASSPHRASE_CHARS = 256

@Composable
fun ThemeProfileSettingsContent(
    themeProfiles: List<ThemeProfileSetting>,
    onSelect: (ThemeProfileId) -> Unit = {},
    onPreview: (ThemeProfileId?) -> Unit = {},
    onDelete: (ThemeProfileId) -> Unit = {},
    onCreate: (ThemeProfileCreation) -> Unit = {},
) {
    Text(
        "Appearance",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    themeProfiles.forEach { profile ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = { onSelect(profile.id) },
                modifier = Modifier.fillMaxWidth().semantics {
                    role = Role.RadioButton
                    selected = profile.selected
                    stateDescription = if (profile.selected) "Selected" else "Not selected"
                },
            ) { Text(profile.label) }
            OutlinedButton(onClick = { onPreview(if (profile.previewed) null else profile.id) }) {
                Text(if (profile.previewed) "End preview" else "Preview ${profile.label}")
            }
            if (profile.deletable) {
                OutlinedButton(onClick = { onDelete(profile.id) }) { Text("Delete ${profile.label}") }
            }
        }
    }
    OutlinedButton(onClick = { onCreate(ThemeProfileCreation.MATERIAL_YOU) }) { Text("New Material You") }
    OutlinedButton(onClick = { onCreate(ThemeProfileCreation.MATERIAL_EXPRESSIVE) }) { Text("New Expressive") }
    OutlinedButton(onClick = { onCreate(ThemeProfileCreation.MANUAL) }) { Text("New manual palette") }
}

@Composable
fun WallpaperConfirmationContent(
    visible: Boolean,
    previewContent: (@Composable () -> Unit)? = null,
    crop: WallpaperCrop = FullWallpaperCrop,
    result: String? = null,
    onChoose: () -> Unit = {},
    onCrop: (WallpaperCrop) -> Unit = {},
    onConfirm: (WallpaperTarget) -> Unit = {},
    onCancel: () -> Unit = {},
) {
    if (visible) {
        previewContent?.invoke()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onCrop(FullWallpaperCrop) },
                modifier = Modifier.semantics { selected = crop == FullWallpaperCrop },
            ) { Text("Full image") }
            OutlinedButton(
                onClick = { onCrop(CenterWallpaperCrop) },
                modifier = Modifier.semantics { selected = crop == CenterWallpaperCrop },
            ) { Text("Center crop") }
        }
        Text("Changing wallpaper may replace a static or live wallpaper that Quicklauncher cannot restore.")
        OutlinedButton(onClick = { onConfirm(WallpaperTarget.HOME) }) { Text("Home") }
        OutlinedButton(onClick = { onConfirm(WallpaperTarget.LOCK) }) { Text("Lock") }
        OutlinedButton(onClick = { onConfirm(WallpaperTarget.BOTH) }) { Text("Both") }
        OutlinedButton(onClick = onCancel) { Text("Cancel wallpaper change") }
    } else {
        OutlinedButton(onClick = onChoose) { Text("Choose embedded wallpaper") }
    }
    result?.let { Text(it) }
}

val FullWallpaperCrop = WallpaperCrop(0f, 0f, 1f, 1f)
val CenterWallpaperCrop = WallpaperCrop(0.125f, 0f, 0.875f, 1f)

@Composable
fun AppRecoverySurface(
    apps: List<LauncherApp>,
    onLaunch: (org.quicklauncher.contracts.domain.AppActivityIdentity) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverlayScaffold(stringResource(R.string.app_recovery_title), onClose, modifier) { contentModifier ->
        if (apps.isEmpty()) {
            Text(stringResource(R.string.no_recovery_apps), modifier = contentModifier)
        } else {
            LazyColumn(
                modifier = contentModifier,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(apps, key = { it.identity.toString() }) { app ->
                    Button(
                        onClick = { onLaunch(app.identity) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (app.workProfile) {
                                stringResource(R.string.recovery_work_app_label, app.label)
                            } else {
                                app.label
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MapOverviewSurface(
    destinations: List<LauncherDestination>,
    onSelectDestination: (DestinationId) -> Unit,
    onOpenPrivateSpace: () -> Unit = {},
    privateSpaceVisible: Boolean = true,
    privateSpaceEntryPointAvailable: Boolean = true,
    onCreateFolder: (String) -> Unit = {},
    onEditMap: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var creatingFolder by remember { mutableStateOf(false) }
    OverlayScaffold(stringResource(R.string.map_title), onClose, modifier) { contentModifier ->
        LazyColumn(
            modifier = contentModifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (privateSpaceEntryPointAvailable) item {
                Button(
                    onClick = onOpenPrivateSpace,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (privateSpaceVisible) {
                                R.string.private_space_title
                            } else {
                                R.string.private_space_show
                            },
                        ),
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = { creatingFolder = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Create folder")
                }
            }
            item {
                OutlinedButton(
                    onClick = onEditMap,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.edit_map))
                }
            }
            items(destinations, key = { it.id.value }) { destination ->
                Card(
                    onClick = { onSelectDestination(destination.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(destination.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.destination_position, destination.x, destination.y))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (destination.start) Text(stringResource(R.string.start_destination))
                            if (destination.current) Text(stringResource(R.string.current_destination))
                        }
                    }
                }
            }
        }
    }
    if (creatingFolder) {
        CreateFolderDialog(
            onCreate = { name ->
                creatingFolder = false
                onCreateFolder(name)
            },
            onDismiss = { creatingFolder = false },
        )
    }
}

private fun IndicatorStyleOption.labelResource(): Int = when (this) {
    IndicatorStyleOption.HIDDEN -> R.string.notification_style_hidden
    IndicatorStyleOption.DOT -> R.string.notification_style_dot
    IndicatorStyleOption.APPROXIMATE_COUNT -> R.string.notification_style_count
}

private fun NotificationAccessState.explanationResource(): Int = when (this) {
    NotificationAccessState.ACTION_REQUIRED -> R.string.notification_access_action_required
    NotificationAccessState.AWAITING_DECISION -> R.string.notification_access_awaiting
    NotificationAccessState.CONNECTED -> R.string.notification_access_connected
    NotificationAccessState.DISCONNECTED -> R.string.notification_access_disconnected
    NotificationAccessState.DENIED -> R.string.notification_access_denied
    NotificationAccessState.REVOKED -> R.string.notification_access_revoked
    NotificationAccessState.UNAVAILABLE -> R.string.notification_access_unavailable
}

@Composable
fun RecoverySurface(
    instances: List<RecoveryInstance>,
    onRetry: (ModuleInstanceId) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverlayScaffold(stringResource(R.string.recovery_title), onClose, modifier) { contentModifier ->
        if (instances.isEmpty()) {
            Text(stringResource(R.string.no_recovery_items), modifier = contentModifier)
        } else {
            LazyColumn(
                modifier = contentModifier,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(instances, key = { it.id.value }) { instance ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(instance.contributionLabel, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(
                                    if (instance.retryAllowed) {
                                        R.string.renderer_recovery_explanation
                                    } else {
                                        R.string.configuration_recovery_explanation
                                    },
                                ),
                            )
                            if (instance.retryAllowed) {
                                Button(onClick = { onRetry(instance.id) }) {
                                    Text(stringResource(R.string.retry_renderer))
                                }
                            } else {
                                Text(stringResource(R.string.configuration_repair_required))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun OverlayScaffold(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title, Modifier.semantics { heading() }) },
                actions = {
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.close))
                    }
                },
            )
        },
    ) { padding ->
        content(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        )
    }
}
