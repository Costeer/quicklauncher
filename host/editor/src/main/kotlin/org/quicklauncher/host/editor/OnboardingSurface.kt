package org.quicklauncher.host.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp

/** Explicit first-run flow: choose, inspect, install, then request the Home role. */
@Composable
fun LauncherOnboardingSurface(
    templates: List<OnboardingTemplate>,
    preview: OnboardingPreview?,
    installed: Boolean,
    message: String?,
    onPreview: (OnboardingTemplate, String) -> Unit,
    onInstall: (OnboardingPreview) -> Unit,
    onRequestHomeRole: () -> Unit,
    modifier: Modifier = Modifier,
    previewContent: @Composable (OnboardingPreview) -> Unit = { PlanSummary(it) },
) {
    Column(
        modifier.fillMaxSize().safeDrawingPadding().semantics { isTraversalGroup = true }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Set up Quicklauncher",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading(); traversalIndex = 0f },
        )
        Text("Choose a starting map. You can inspect it before anything is installed.")
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(
            modifier = Modifier.weight(1f).semantics { traversalIndex = 1f },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(templates, key = { it.id.value }) { template ->
                var configuration by remember(template.id) {
                    mutableStateOf(template.defaultConfiguration)
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(template.name, style = MaterialTheme.typography.titleMedium)
                        Text(template.description)
                        template.settings.forEach { Text("Setting: $it") }
                        OutlinedTextField(
                            value = configuration,
                            onValueChange = { configuration = it },
                            label = { Text("Template configuration JSON") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(onClick = { onPreview(template, configuration) }) {
                            Text("Preview configured ${template.name}")
                        }
                    }
                }
            }
            preview?.let { selected ->
                item(key = "preview") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Plan preview", style = MaterialTheme.typography.titleMedium)
                            previewContent(selected)
                            Button(
                                enabled = selected.canInstall && !installed,
                                onClick = { onInstall(selected) },
                            ) { Text(if (installed) "Installed" else "Install this plan") }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().semantics { traversalIndex = 2f },
            horizontalArrangement = Arrangement.End,
        ) {
            Button(enabled = installed, onClick = onRequestHomeRole) {
                Text("Use Quicklauncher as Home")
            }
        }
    }
}

@Composable
private fun PlanSummary(selected: OnboardingPreview) {
    selected.plan.destinations.forEach { destination ->
        Text(
            "${destination.draft.name.value}: " +
                "${destination.coordinate.x}, ${destination.coordinate.y}" +
                if (destination.isStart) " (start)" else "",
        )
    }
    Text("${selected.plan.destinations.sumOf { it.draft.blocks.size }} blocks")
}
