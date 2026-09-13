package org.quicklauncher.host.editor

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.DestinationId

/** Host-owned map editor. Every drag operation also has an explicit command here. */
@Composable
fun MapEditorSurface(
    state: LauncherEditorState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newName by remember { mutableStateOf("New destination") }
    var newX by remember { mutableStateOf("0") }
    var newY by remember { mutableStateOf("0") }
    var moveGroup by remember(state.revision) {
        mutableStateOf(emptySet<DestinationId>())
    }
    Column(
        modifier = modifier.fillMaxSize().safeDrawingPadding().semantics { isTraversalGroup = true }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Edit destinations",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading(); traversalIndex = 0f },
            )
            TextButton(onClick = { onAction(EditorAction.Close) }) { Text("Close") }
        }
        state.rejection?.let { rejection ->
            Text("Change rejected: ${rejection.message}", color = MaterialTheme.colorScheme.error)
        }
        Card(Modifier.fillMaxWidth().semantics { traversalIndex = 1f }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Create destination", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(newName, { newName = it }, label = { Text("Name") })
                OutlinedTextField(newX, { newX = it }, label = { Text("Horizontal coordinate") })
                OutlinedTextField(newY, { newY = it }, label = { Text("Vertical coordinate") })
                Button(
                    enabled = newName.isNotBlank() && newX.toLongOrNull() != null && newY.toLongOrNull() != null,
                    onClick = {
                        onAction(
                            EditorAction.CreateDestination(
                                newName,
                                checkNotNull(newX.toLongOrNull()),
                                checkNotNull(newY.toLongOrNull()),
                            ),
                        )
                    },
                ) { Text("Create destination") }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).testTag("destination-list").semantics { traversalIndex = 2f },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "move-group") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Move a connected group", style = MaterialTheme.typography.titleMedium)
                        Text("Choose destinations, then use an explicit direction command.")
                        state.destinations.forEach { destination ->
                            val selected = destination.id in moveGroup
                            OutlinedButton(
                                onClick = {
                                    moveGroup = if (selected) {
                                        moveGroup - destination.id
                                    } else {
                                        moveGroup + destination.id
                                    }
                                },
                            ) {
                                Text(
                                    if (selected) {
                                        "Remove ${destination.name} from move group"
                                    } else {
                                        "Include ${destination.name} in move group"
                                    },
                                )
                            }
                        }
                        EditorDirection.entries.forEach { direction ->
                            OutlinedButton(
                                enabled = moveGroup.isNotEmpty(),
                                onClick = { onAction(EditorAction.Move(moveGroup, direction)) },
                            ) {
                                Text("Move selected group ${direction.name.lowercase()}")
                            }
                        }
                    }
                }
            }
            items(state.destinations, key = { it.id.value }) { destination ->
                DestinationEditorCard(
                    destination,
                    if (destination.id in moveGroup) moveGroup else setOf(destination.id),
                    state.pendingConfirmation == EditorConfirmation.DeleteDestination(destination.id),
                    onAction,
                )
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
        modifier = modifier.fillMaxSize().safeDrawingPadding().semantics { isTraversalGroup = true }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Edit destination content",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading(); traversalIndex = 0f },
            )
            TextButton(onClick = { onAction(EditorAction.Close) }) { Text("Close") }
        }
        state.rejection?.let { rejection ->
            Text("Change rejected: ${rejection.message}", color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).testTag("module-list").semantics { traversalIndex = 1f },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.layoutOptions.isNotEmpty()) {
                item(key = "layout-catalog") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Add a layout", style = MaterialTheme.typography.titleMedium)
                            state.layoutOptions
                                .filter { option -> state.modules.none { it.contributionId == option.id } }
                                .forEach { option ->
                                    OutlinedButton(
                                        onClick = {
                                            state.selectedDestinationId?.let { destinationId ->
                                                onAction(
                                                    EditorAction.RetainLayout(
                                                        destinationId,
                                                        option.id,
                                                        option.defaultConfiguration,
                                                    ),
                                                )
                                            }
                                        },
                                    ) { Text("Add ${option.name} layout") }
                                }
                        }
                    }
                }
            }
            state.dropTargets.forEach { target ->
                item(key = "block-catalog-${target.parentInstanceId.value}-${target.parentSlotId.value}") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Add a block", style = MaterialTheme.typography.titleMedium)
                            state.blockOptions.filter { it.id in target.acceptedBlocks }.forEach { option ->
                                OutlinedButton(
                                    enabled = target.remainingCapacity > 0,
                                    onClick = {
                                        onAction(
                                            EditorAction.AddBlock(
                                                target.layoutInstanceId,
                                                target.parentInstanceId,
                                                target.parentSlotId,
                                                option.id,
                                                option.defaultConfiguration,
                                                target.nextIndex,
                                            ),
                                        )
                                    },
                                ) { Text("Add ${option.name} block") }
                            }
                        }
                    }
                }
            }
            items(state.modules, key = { it.id.value }) { module ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        var encodedConfiguration by remember(module.id, module.configuration) {
                            mutableStateOf(module.configuration.encoded.value)
                        }
                        Text(module.contributionId.value, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                module.quarantined -> "Needs recovery"
                                module.selectedLayout -> "Selected layout"
                                module.placedBlock -> "Placed block"
                                else -> "Dormant layout"
                            },
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = encodedConfiguration,
                                onValueChange = { encodedConfiguration = it },
                                label = { Text("Configuration JSON") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(
                                enabled = encodedConfiguration.isNotBlank() &&
                                    encodedConfiguration != module.configuration.encoded.value,
                                onClick = {
                                    onAction(
                                        EditorAction.ReplaceConfiguration(
                                            module.id,
                                            ConfigurationDocument(
                                                module.configuration.configType,
                                                module.configuration.schemaVersion,
                                                EncodedConfiguration.of(encodedConfiguration),
                                            ),
                                        ),
                                    )
                                },
                            ) { Text("Save settings") }
                            OutlinedButton(onClick = { onAction(EditorAction.ResetConfiguration(module.id)) }) {
                                Text("Reset settings")
                            }
                            if (module.placedBlock) {
                                val placement = checkNotNull(module.placement)
                                BlockDragHandle(module, placement, onAction)
                                OutlinedButton(
                                    enabled = placement.index > 0,
                                    onClick = {
                                        onAction(
                                            EditorAction.MoveBlock(
                                                module.id,
                                                placement.parentInstanceId,
                                                placement.parentSlotId,
                                                (placement.index - 1).coerceAtLeast(0),
                                                EditorEncodedPlacement.of(
                                                    placement.encoded,
                                                ),
                                                placement.schemaVersion,
                                            ),
                                        )
                                    },
                                ) { Text("Move earlier") }
                                OutlinedButton(
                                    enabled = placement.index < placement.siblingCount - 1,
                                    onClick = {
                                        onAction(
                                            EditorAction.MoveBlock(
                                                module.id,
                                                placement.parentInstanceId,
                                                placement.parentSlotId,
                                                placement.index + 1,
                                                EditorEncodedPlacement.of(placement.encoded),
                                                placement.schemaVersion,
                                            ),
                                        )
                                    },
                                ) { Text("Move later") }
                                OutlinedButton(
                                    onClick = {
                                        onAction(
                                            EditorAction.CopyBlock(
                                                module.id,
                                                placement.parentInstanceId,
                                                placement.parentSlotId,
                                                placement.index + 1,
                                                EditorEncodedPlacement.of(
                                                    placement.encoded,
                                                ),
                                                placement.schemaVersion,
                                            ),
                                        )
                                    },
                                ) { Text("Copy block") }
                                state.dropTargets
                                    .filter { target ->
                                        module.contributionId in target.acceptedBlocks &&
                                            target.remainingCapacity > 0 &&
                                            (target.parentInstanceId != placement.parentInstanceId ||
                                                target.parentSlotId != placement.parentSlotId)
                                    }
                                    .forEach { target ->
                                        val targetLabel = "${target.parentSlotId.value} in ${target.parentInstanceId.value}"
                                        OutlinedButton(
                                            onClick = {
                                                onAction(
                                                    EditorAction.MoveBlock(
                                                        module.id,
                                                        target.parentInstanceId,
                                                        target.parentSlotId,
                                                        target.nextIndex,
                                                        EditorEncodedPlacement.of(placement.encoded),
                                                        placement.schemaVersion,
                                                    ),
                                                )
                                            },
                                        ) { Text("Move to $targetLabel") }
                                        OutlinedButton(
                                            onClick = {
                                                onAction(
                                                    EditorAction.CopyBlock(
                                                        module.id,
                                                        target.parentInstanceId,
                                                        target.parentSlotId,
                                                        target.nextIndex,
                                                        EditorEncodedPlacement.of(placement.encoded),
                                                        placement.schemaVersion,
                                                    ),
                                                )
                                            },
                                        ) { Text("Copy to $targetLabel") }
                                    }
                                OutlinedButton(
                                    onClick = {
                                        onAction(
                                            EditorAction.RemovePlacedModule(
                                                module.id,
                                                confirmed = state.pendingConfirmation ==
                                                    EditorConfirmation.RemovePlacedModule(module.id),
                                            ),
                                        )
                                    },
                                ) { Text("Remove block") }
                            } else if (!module.selectedLayout) {
                                OutlinedButton(
                                    onClick = {
                                        state.selectedDestinationId?.let { destinationId ->
                                            onAction(EditorAction.SelectLayout(destinationId, module.id))
                                        }
                                    },
                                ) { Text("Use this layout") }
                            }
                            if (module.quarantined) {
                                OutlinedButton(
                                    onClick = {
                                        onAction(
                                            EditorAction.RemoveQuarantinedModule(
                                                module.id,
                                                confirmed = state.pendingConfirmation ==
                                                    EditorConfirmation.RemoveQuarantinedModule(module.id),
                                            ),
                                        )
                                    },
                                ) { Text("Delete unavailable module") }
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
    draggedDestinationIds: Set<DestinationId>,
    confirmationPending: Boolean,
    onAction: (EditorAction) -> Unit,
) {
    var name by remember(destination.id, destination.name) { mutableStateOf(destination.name) }
    val dragThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(destination.name, style = MaterialTheme.typography.titleMedium)
            Text("Position ${destination.x}, ${destination.y}${if (destination.start) "; start destination" else ""}")
            Text(
                "Drag to move destination",
                modifier = Modifier
                    .semantics {
                        contentDescription = "Drag ${destination.name}"
                    }
                    .pointerInput(destination.id, draggedDestinationIds, dragThreshold) {
                        var distance = Offset.Zero
                        detectDragGesturesAfterLongPress(
                            onDragStart = { distance = Offset.Zero },
                            onDragEnd = {
                                dominantEditorDirection(distance, dragThreshold)?.let { direction ->
                                    onAction(EditorAction.Move(draggedDestinationIds, direction))
                                }
                            },
                            onDragCancel = { distance = Offset.Zero },
                            onDrag = { change, amount ->
                                change.consume()
                                distance += amount
                            },
                        )
                    },
            )
            OutlinedTextField(name, { name = it }, label = { Text("Destination name") })
            OutlinedButton(
                enabled = name.isNotBlank() && name != destination.name,
                onClick = { onAction(EditorAction.Rename(destination.id, name)) },
            ) { Text("Rename") }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                if (!destination.start) {
                    OutlinedButton(
                        onClick = {
                            onAction(EditorAction.Delete(destination.id, confirmed = confirmationPending))
                        },
                    ) { Text(if (confirmationPending) "Confirm deletion" else "Delete destination") }
                }
            }
        }
    }
}

@Composable
private fun BlockDragHandle(
    module: EditorModule,
    placement: EditorPlacement,
    onAction: (EditorAction) -> Unit,
) {
    val dragThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    Text(
        "Drag to reorder block",
        modifier = Modifier
            .semantics {
                contentDescription = "Drag ${module.contributionId.value} block"
            }
            .pointerInput(module.id, placement, dragThreshold) {
                var verticalDistance = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { verticalDistance = 0f },
                    onDragEnd = {
                        val targetIndex = when {
                            verticalDistance <= -dragThreshold && placement.index > 0 -> placement.index - 1
                            verticalDistance >= dragThreshold && placement.index < placement.siblingCount - 1 -> {
                                placement.index + 1
                            }
                            else -> null
                        }
                        targetIndex?.let { index ->
                            onAction(
                                EditorAction.MoveBlock(
                                    module.id,
                                    placement.parentInstanceId,
                                    placement.parentSlotId,
                                    index,
                                    EditorEncodedPlacement.of(placement.encoded),
                                    placement.schemaVersion,
                                ),
                            )
                        }
                    },
                    onDragCancel = { verticalDistance = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        verticalDistance += amount.y
                    },
                )
            },
    )
}

internal fun dominantEditorDirection(distance: Offset, threshold: Float): EditorDirection? {
    require(threshold > 0f) { "Drag threshold must be positive" }
    if (maxOf(abs(distance.x), abs(distance.y)) < threshold) return null
    return if (abs(distance.x) >= abs(distance.y)) {
        if (distance.x < 0f) EditorDirection.LEFT else EditorDirection.RIGHT
    } else {
        if (distance.y < 0f) EditorDirection.UP else EditorDirection.DOWN
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
