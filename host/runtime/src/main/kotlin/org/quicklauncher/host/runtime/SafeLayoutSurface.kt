package org.quicklauncher.host.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.host.runtime.catalog.AppCatalogStatus
import org.quicklauncher.host.runtime.permissions.HomeRoleState

/** Host-owned emergency launcher surface; it never invokes optional contribution code. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SafeLayoutSurface(
    state: LauncherRuntimeState,
    onQueryChanged: (String) -> Unit,
    onLaunch: (AppActivityIdentity) -> Unit,
    onOpenMap: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecovery: () -> Unit,
    onRequestHomeRole: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val localFilterDescription = stringResource(R.string.local_app_filter)
    val mapDescription = stringResource(R.string.map_overview)
    val settingsDescription = stringResource(R.string.launcher_settings)
    val recoveryDescription = stringResource(R.string.launcher_recovery)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.launcher_name),
                        Modifier.semantics { heading() },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(
                        onClick = onOpenMap,
                        modifier = Modifier.semantics { contentDescription = mapDescription },
                    ) {
                        Text("⌖")
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.semantics { contentDescription = settingsDescription },
                    ) {
                        Text("⚙")
                    }
                    IconButton(
                        onClick = onOpenRecovery,
                        modifier = Modifier.semantics { contentDescription = recoveryDescription },
                    ) {
                        Text("!")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.homeRoleState == HomeRoleState.AVAILABLE_NOT_HELD) {
                Button(onClick = onRequestHomeRole, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.use_as_home))
                }
            }
            if (state.status == LauncherRuntimeStatus.ERROR) {
                Text(
                    stringResource(R.string.launcher_startup_error),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            OutlinedTextField(
                value = state.localQuery,
                onValueChange = onQueryChanged,
                singleLine = true,
                label = { Text(stringResource(R.string.search_apps)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = localFilterDescription
                    },
            )
            if (state.catalogStatus == AppCatalogStatus.ERROR) {
                Text(stringResource(R.string.app_catalog_error))
            }
            when {
                state.catalogStatus == AppCatalogStatus.LOADING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.app_catalog_loading))
                    }
                }
                state.visibleApps.isEmpty() -> {
                    Text(stringResource(R.string.app_catalog_empty))
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(state.visibleApps, key = { it.identity.toString() }) { app ->
                            Button(
                                onClick = { onLaunch(app.identity) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (app.workProfile) {
                                        stringResource(R.string.work_app_label, app.label)
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
    }
}
