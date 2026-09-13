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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.host.runtime.LauncherDestination
import org.quicklauncher.host.runtime.LauncherApp
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
        }
    }
}

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
