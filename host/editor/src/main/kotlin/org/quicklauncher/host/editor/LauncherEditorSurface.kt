package org.quicklauncher.host.editor

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Host-owned map editor. Every drag operation also has an explicit command here. */
@Composable
fun MapEditorSurface(
    state: LauncherEditorState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Edit destinations", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            TextButton(onClick = { onAction(EditorAction.Close) }) { Text("Close") }
        }
        state.rejection?.let { rejection ->
            Text("Change rejected: ${rejection.message}", color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.destinations, key = { it.id.value }) { destination ->
                DestinationEditorCard(destination, onAction)
            }
        }
    }
}

/** Destination content editor with explicit reset and removal commands. */
@Composable
fun DestinationEditorSurface(
    state: LauncherEditorState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Edit destination content", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            TextButton(onClick = { onAction(EditorAction.Close) }) { Text("Close") }
        }
        state.rejection?.let { rejection ->
            Text("Change rejected: ${rejection.message}", color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.modules, key = { it.id.value }) { module ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(module.contributionId.value, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                module.quarantined -> "Needs recovery"
                                module.selectedLayout -> "Selected layout"
                                module.placedBlock -> "Placed block"
                                else -> "Dormant layout"
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onAction(EditorAction.ResetConfiguration(module.id)) }) {
                                Text("Reset settings")
                            }
                            if (module.placedBlock) {
                                OutlinedButton(
                                    onClick = {
                                        onAction(EditorAction.RemovePlacedModule(module.id, confirmed = true))
                                    },
                                ) { Text("Remove block") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationEditorCard(
    destination: EditorDestination,
    onAction: (EditorAction) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(destination.name, style = MaterialTheme.typography.titleMedium)
            Text("Position ${destination.x}, ${destination.y}${if (destination.start) "; start destination" else ""}")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DirectionButton("Left", destination.id, EditorDirection.LEFT, onAction)
                DirectionButton("Right", destination.id, EditorDirection.RIGHT, onAction)
                DirectionButton("Up", destination.id, EditorDirection.UP, onAction)
                DirectionButton("Down", destination.id, EditorDirection.DOWN, onAction)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAction(EditorAction.OpenDestination(destination.id)) }) {
                    Text("Edit content")
                }
                if (!destination.start) {
                    OutlinedButton(onClick = { onAction(EditorAction.SetStart(destination.id)) }) {
                        Text("Set as start")
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectionButton(
    label: String,
    destinationId: org.quicklauncher.contracts.domain.DestinationId,
    direction: EditorDirection,
    onAction: (EditorAction) -> Unit,
) {
    TextButton(onClick = { onAction(EditorAction.Move(setOf(destinationId), direction)) }) {
        Text(label)
    }
}
