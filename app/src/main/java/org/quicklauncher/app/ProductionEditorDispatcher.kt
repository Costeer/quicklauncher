package org.quicklauncher.app

import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.editor.EditorAction
import org.quicklauncher.host.editor.EditorConfirmation
import org.quicklauncher.host.editor.EditorResult
import org.quicklauncher.host.editor.LauncherEditor
import org.quicklauncher.host.runtime.widgets.WidgetCoordinator
import org.quicklauncher.host.runtime.widgets.WidgetRemovalResult

/**
 * Coordinates editor tree deletion with framework-owned widget IDs.
 *
 * The store rejects structural removal while an affected widget row still owns an ID. Production
 * therefore completes each recoverable widget deletion before asking the editor to commit the
 * structural change. A failed framework deletion remains durable and makes the structural edit
 * reject instead of leaking the ID.
 */
internal class ProductionEditorDispatcher(
    private val editor: LauncherEditor,
    private val store: LauncherStore,
    private val widgets: WidgetCoordinator?,
) {
    suspend fun dispatch(action: EditorAction): EditorResult {
        if (!action.isConfirmedDestructive() || !action.matches(editor.state.value.pendingConfirmation)) {
            return editor.dispatch(action)
        }
        val snapshot = store.read()
        val affected = snapshot.affectedInstances(action)
        val widgetInstances = snapshot.widgetPlacements
            .asSequence()
            .filter { it.moduleInstanceId in affected }
            .map { it.moduleInstanceId }
            .distinct()
            .sortedBy { it.value }
            .toList()
        val coordinator = widgets
        if (widgetInstances.isNotEmpty() && coordinator != null) {
            for (instanceId in widgetInstances) {
                when (coordinator.remove(instanceId)) {
                    WidgetRemovalResult.Removed,
                    is WidgetRemovalResult.NotFound,
                    -> Unit
                    is WidgetRemovalResult.CleanupRequired,
                    is WidgetRemovalResult.Rejected,
                    -> return editor.dispatch(action)
                }
            }
        }
        return editor.dispatch(action)
    }
}

private fun EditorAction.matches(confirmation: EditorConfirmation?): Boolean = when (this) {
    is EditorAction.Delete -> confirmation == EditorConfirmation.DeleteDestination(destinationId)
    is EditorAction.RemovePlacedModule ->
        confirmation == EditorConfirmation.RemovePlacedModule(instanceId)
    is EditorAction.RemoveQuarantinedModule ->
        confirmation == EditorConfirmation.RemoveQuarantinedModule(instanceId)
    else -> false
}

private fun EditorAction.isConfirmedDestructive(): Boolean = when (this) {
    is EditorAction.Delete -> confirmed
    is EditorAction.RemovePlacedModule -> confirmed
    is EditorAction.RemoveQuarantinedModule -> confirmed
    else -> false
}

private fun LauncherSnapshot.affectedInstances(action: EditorAction): Set<ModuleInstanceId> {
    val roots = when (action) {
        is EditorAction.Delete -> destinationLayouts
            .filter { it.destinationId == action.destinationId }
            .mapTo(linkedSetOf()) { it.layoutInstanceId }
        is EditorAction.RemovePlacedModule -> placements
            .firstOrNull { it.childInstanceId == action.instanceId }
            ?.let { linkedSetOf(it.childInstanceId) }
            ?: emptySet()
        is EditorAction.RemoveQuarantinedModule -> {
            placements.firstOrNull { it.childInstanceId == action.instanceId }
                ?.let { linkedSetOf(it.childInstanceId) }
                ?: destinationLayouts.firstOrNull { it.layoutInstanceId == action.instanceId }
                    ?.let { linkedSetOf(it.layoutInstanceId) }
                ?: emptySet()
        }
        else -> emptySet()
    }
    if (roots.isEmpty()) return emptySet()
    val affected = LinkedHashSet(roots)
    val pending = ArrayDeque(roots)
    while (pending.isNotEmpty()) {
        val parent = pending.removeFirst()
        placements.asSequence()
            .filter { it.parentInstanceId == parent }
            .map { it.childInstanceId }
            .forEach { child -> if (affected.add(child)) pending.addLast(child) }
    }
    return affected
}
