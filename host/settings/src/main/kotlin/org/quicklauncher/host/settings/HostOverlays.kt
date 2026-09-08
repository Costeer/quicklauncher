package org.quicklauncher.host.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

@Composable
fun LauncherSettingsSurface(
    homeRoleState: HomeRoleState,
    fallbackAvailable: Boolean,
    onRequestHomeRole: () -> Unit,
    onOpenHomeSettings: () -> Unit,
    onOpenAppRecovery: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverlayScaffold(stringResource(R.string.settings_title), onClose, modifier) { contentModifier ->
        Column(
            modifier = contentModifier,
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
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverlayScaffold(stringResource(R.string.map_title), onClose, modifier) { contentModifier ->
        LazyColumn(
            modifier = contentModifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
private fun OverlayScaffold(
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
