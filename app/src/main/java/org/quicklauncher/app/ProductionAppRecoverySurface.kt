package org.quicklauncher.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.host.runtime.LauncherApp
import org.quicklauncher.host.runtime.contentItemId

/** Settings-only recovery for the sanitized app list, including collection-hidden apps. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ProductionAppRecoverySurface(
    apps: List<LauncherApp>,
    onLaunch: (AppActivityIdentity) -> Unit,
    onOpenActions: (ContentItemId) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("App recovery", Modifier.semantics { heading() }) },
                actions = { TextButton(onClick = onClose) { Text("Close") } },
            )
        },
    ) { padding ->
        if (apps.isEmpty()) {
            Text("No apps are available for recovery", Modifier.padding(padding).padding(16.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(apps, key = { it.identity.toString() }) { app ->
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(if (app.workProfile) "${app.label}, Work" else app.label)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onLaunch(app.identity) },
                                modifier = Modifier.heightIn(min = 48.dp).semantics {
                                    contentDescription = "Launch ${app.label}"
                                },
                            ) { Text("Launch") }
                            OutlinedButton(
                                onClick = { onOpenActions(contentItemId(app)) },
                                modifier = Modifier.heightIn(min = 48.dp).semantics {
                                    contentDescription = "Actions for ${app.label}"
                                },
                            ) { Text("Actions") }
                        }
                    }
                }
            }
        }
    }
}
