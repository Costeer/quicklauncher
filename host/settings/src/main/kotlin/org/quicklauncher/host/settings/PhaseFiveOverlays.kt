package org.quicklauncher.host.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.host.runtime.actions.ItemAction
import org.quicklauncher.host.runtime.actions.ItemActionCommand
import org.quicklauncher.host.runtime.actions.ItemActionOverlayState
import org.quicklauncher.host.runtime.actions.ItemAvailability
import org.quicklauncher.host.runtime.folders.FolderOverlayState

@Composable
fun CreateFolderDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim()) },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun FolderOverlaySurface(
    state: FolderOverlayState.Open,
    onActivateMember: (ContentItemId) -> Unit,
    onMoveMember: (ContentItemId, Int) -> Unit,
    onRemoveMember: (ContentItemId) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleting by remember(state.id) { mutableStateOf(false) }
    var renaming by remember(state.id) { mutableStateOf(false) }
    var newName by remember(state.id, state.name) { mutableStateOf(state.name) }
    OverlayScaffold(state.name, onClose, modifier) { contentModifier ->
        LazyColumn(
            modifier = contentModifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { renaming = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Rename folder")
                    }
                    OutlinedButton(onClick = { deleting = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Delete folder")
                    }
                }
            }
            if (state.members.isEmpty()) {
                item { Text("This folder has no available items") }
            }
            itemsIndexed(state.members, key = { _, member -> member.id.value }) { index, member ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onActivateMember(member.id) },
                            enabled = member.available,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(if (member.workBadge) "${member.label}, Work" else member.label)
                        }
                        if (!member.available) Text("Unavailable")
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (index > 0) {
                                TextButton(
                                    onClick = {
                                        onMoveMember(member.id, state.members[index - 1].durableIndex)
                                    },
                                ) { Text("Move ${member.label} up") }
                            }
                            if (index < state.members.lastIndex) {
                                TextButton(
                                    onClick = {
                                        onMoveMember(member.id, state.members[index + 1].durableIndex)
                                    },
                                ) { Text("Move ${member.label} down") }
                            }
                            TextButton(onClick = { onRemoveMember(member.id) }) {
                                Text("Remove ${member.label}")
                            }
                        }
                    }
                }
            }
            if (state.durableMemberCount > state.members.size) {
                item { Text("Some items are hidden or unavailable") }
            }
        }
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename ${state.name}") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = { onRename(newName.trim()); renaming = false },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        ConfirmationDialog(
            title = "Delete ${state.name}?",
            action = "Delete",
            onConfirm = { deleting = false; onDelete() },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
fun ItemActionOverlaySurface(
    state: ItemActionOverlayState.Open,
    onCommand: (ItemActionCommand) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingConfirmation by remember(state.itemId) { mutableStateOf<ItemAction?>(null) }
    var searchChoice by remember(state.itemId) { mutableStateOf(false) }
    var renaming by remember(state.itemId) { mutableStateOf(false) }
    var newName by remember(state.itemId, state.label) { mutableStateOf(state.label) }
    OverlayScaffold("Actions for ${state.label}", onClose, modifier) { contentModifier ->
        LazyColumn(contentModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    availabilityExplanation(state.availability),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            items(
                ItemAction.entries.filter { it in state.actions && !it.usesFolderControl() },
                key = { it.name },
            ) { action ->
                Button(
                    onClick = {
                        when (action) {
                            ItemAction.HIDE -> searchChoice = true
                            ItemAction.RENAME -> renaming = true
                            ItemAction.UNINSTALL, ItemAction.DISABLE, ItemAction.REMOVE_SHORTCUT ->
                                pendingConfirmation = action
                            ItemAction.ADD_TO_FOLDER, ItemAction.REMOVE_FROM_FOLDER -> Unit
                            else -> onCommand(action.command())
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(action.label()) }
            }
            state.folderIds.forEach { folderId ->
                val member = folderId in state.memberOfFolderIds
                item(key = "folder-${folderId.value}") {
                    OutlinedButton(
                        onClick = {
                            onCommand(
                                if (member) ItemActionCommand.RemoveFromFolder(folderId)
                                else ItemActionCommand.AddToFolder(folderId),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            (if (member) "Remove from " else "Add to ") +
                                state.folderLabels.getValue(folderId),
                        )
                    }
                }
            }
        }
    }
    if (searchChoice) {
        AlertDialog(
            onDismissRequest = { searchChoice = false },
            title = { Text("Hide ${state.label}?") },
            text = { Text("Choose whether search may still reveal this app.") },
            confirmButton = {
                TextButton(onClick = { searchChoice = false; onCommand(ItemActionCommand.Hide(false)) }) {
                    Text("Hide from search too")
                }
            },
            dismissButton = {
                TextButton(onClick = { searchChoice = false; onCommand(ItemActionCommand.Hide(true)) }) {
                    Text("Keep in search")
                }
            },
        )
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename ${state.label}") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Item name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = { renaming = false; onCommand(ItemActionCommand.Rename(newName.trim())) },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    pendingConfirmation?.let { action ->
        ConfirmationDialog(
            title = "${action.label()} ${state.label}?",
            action = "Confirm ${action.label().lowercase()}",
            onConfirm = { pendingConfirmation = null; onCommand(action.confirmedCommand()) },
            onDismiss = { pendingConfirmation = null },
        )
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    action: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun availabilityExplanation(value: ItemAvailability): String = when (value) {
    ItemAvailability.AVAILABLE -> "Available"
    ItemAvailability.PROFILE_LOCKED -> "This profile is locked"
    ItemAvailability.PROFILE_UNAVAILABLE -> "This profile is unavailable"
    ItemAvailability.PACKAGE_UNAVAILABLE -> "This app is unavailable"
    ItemAvailability.DISABLED -> "This app is disabled"
}

private fun ItemAction.label(): String = when (this) {
    ItemAction.FAVORITE -> "Favorite"
    ItemAction.UNFAVORITE -> "Unfavorite"
    ItemAction.HIDE -> "Hide"
    ItemAction.UNHIDE -> "Unhide"
    ItemAction.RENAME -> "Rename"
    ItemAction.ADD_TO_FOLDER -> "Add to folder"
    ItemAction.REMOVE_FROM_FOLDER -> "Remove from folder"
    ItemAction.OPEN_DETAILS -> "App details"
    ItemAction.UNINSTALL -> "Uninstall"
    ItemAction.DISABLE -> "Disable"
    ItemAction.REMOVE_SHORTCUT -> "Remove shortcut"
    ItemAction.PAUSE_PROFILE -> "Pause work profile"
    ItemAction.RESUME_PROFILE -> "Resume work profile"
}

private fun ItemAction.usesFolderControl(): Boolean =
    this == ItemAction.ADD_TO_FOLDER || this == ItemAction.REMOVE_FROM_FOLDER

private fun ItemAction.command(): ItemActionCommand = when (this) {
    ItemAction.FAVORITE -> ItemActionCommand.SetFavorite(true)
    ItemAction.UNFAVORITE -> ItemActionCommand.SetFavorite(false)
    ItemAction.UNHIDE -> ItemActionCommand.Unhide
    ItemAction.OPEN_DETAILS -> ItemActionCommand.OpenDetails
    ItemAction.PAUSE_PROFILE -> ItemActionCommand.PauseProfile
    ItemAction.RESUME_PROFILE -> ItemActionCommand.ResumeProfile
    else -> error("$this requires parameters or confirmation")
}

private fun ItemAction.confirmedCommand(): ItemActionCommand = when (this) {
    ItemAction.UNINSTALL -> ItemActionCommand.Uninstall(true)
    ItemAction.DISABLE -> ItemActionCommand.Disable(true)
    ItemAction.REMOVE_SHORTCUT -> ItemActionCommand.RemoveShortcut(true)
    else -> error("$this is not destructive")
}
