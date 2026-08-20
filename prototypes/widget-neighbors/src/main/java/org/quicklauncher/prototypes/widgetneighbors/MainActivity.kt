package org.quicklauncher.prototypes.widgetneighbors

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val appWidgetManager by lazy { AppWidgetManager.getInstance(this) }
    private val appWidgetHost by lazy { AppWidgetHost(this, PROTOTYPE_HOST_ID) }

    private var ledger by mutableStateOf(AllocationLedger())
    private var message by mutableStateOf("Bind one diagnostic widget to each destination.")
    private var flowStage: SelectionStage? = null

    private val widgetFlow = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        handleWidgetResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreState(savedInstanceState)
        setContent {
            WidgetNeighborPrototypeApp(
                ledger = ledger,
                message = message,
                widgetInfo = appWidgetManager::getAppWidgetInfo,
                createHostView = { id, info ->
                    appWidgetHost.createView(this, id, info)
                },
                onBindDiagnostic = ::beginDiagnosticFlow,
                onRemove = ::removeWidget,
                onReset = ::resetPrototype,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        appWidgetHost.startListening()
    }

    override fun onStop() {
        appWidgetHost.stopListening()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        ledger.current?.let { outState.putInt(KEY_CURRENT_ID, it.appWidgetId) }
        ledger.neighbor?.let { outState.putInt(KEY_NEIGHBOR_ID, it.appWidgetId) }
        ledger.pending?.let {
            outState.putInt(KEY_PENDING_ID, it.appWidgetId)
            outState.putString(KEY_PENDING_SLOT, it.slot.name)
        }
        flowStage?.let { outState.putString(KEY_FLOW_STAGE, it.name) }
    }

    override fun onDestroy() {
        if (isFinishing) {
            applyChange(ledger.clear())
        }
        super.onDestroy()
    }

    private fun beginDiagnosticFlow(slot: WidgetSlot) {
        if (ledger.pending != null) return

        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        ledger = ledger.begin(slot, appWidgetId)
        val provider = slot.diagnosticProvider
        val boundWithoutPrompt = try {
            appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider)
        } catch (error: RuntimeException) {
            cancelPending("Binding failed: ${error.javaClass.simpleName}. Deleted ID $appWidgetId.")
            return
        }
        if (boundWithoutPrompt) {
            continueWithBoundWidget()
            return
        }

        flowStage = SelectionStage.BIND
        message = "Allocated ID $appWidgetId for ${slot.label}. Waiting for binding approval."
        launchOrCancel(
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider),
        )
    }

    private fun handleWidgetResult(resultCode: Int, data: Intent?) {
        val allocation = ledger.pending ?: return
        if (resultCode != Activity.RESULT_OK) {
            cancelPending("Cancelled. Deleted unused ID ${allocation.appWidgetId}.")
            return
        }

        val returnedId = data?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            allocation.appWidgetId,
        ) ?: allocation.appWidgetId
        if (returnedId != allocation.appWidgetId) {
            cancelPending("Android returned ID $returnedId for pending ID ${allocation.appWidgetId}. Deleted the pending ID.")
            return
        }

        when (flowStage) {
            SelectionStage.BIND -> continueWithBoundWidget()
            SelectionStage.CONFIGURE -> commitPendingWidget()
            null -> cancelPending("The widget result had no active flow. Deleted the unused ID.")
        }
    }

    private fun continueWithBoundWidget() {
        val allocation = ledger.pending ?: return
        val info = appWidgetManager.getAppWidgetInfo(allocation.appWidgetId)
        if (info == null) {
            cancelPending("Android did not bind ID ${allocation.appWidgetId}. Deleted it.")
            return
        }

        val configure = info.configure
        if (configure == null) {
            commitPendingWidget()
            return
        }

        flowStage = SelectionStage.CONFIGURE
        message = "ID ${allocation.appWidgetId} is bound. Waiting for provider configuration."
        launchOrCancel(
            Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setComponent(configure)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, allocation.appWidgetId),
        )
    }

    private fun commitPendingWidget() {
        val allocation = ledger.pending ?: return
        val info = appWidgetManager.getAppWidgetInfo(allocation.appWidgetId)
        if (info == null) {
            cancelPending("The configured widget is no longer bound. Deleted its ID.")
            return
        }

        val provider = info.provider.flattenToShortString()
        applyChange(ledger.commit(allocation.appWidgetId, provider))
        flowStage = null
        message = "Hosted $provider as ${allocation.slot.label} with ID ${allocation.appWidgetId}."
    }

    private fun removeWidget(slot: WidgetSlot) {
        val removed = ledger.binding(slot) ?: return
        applyChange(ledger.remove(slot))
        message = "Removed ${slot.label} and deleted ID ${removed.appWidgetId}."
    }

    private fun resetPrototype() {
        val cleared = ledger.clear()
        val ownedIds = cleared.appWidgetIdsToDelete
        applyChange(cleared)
        flowStage = null
        message = if (ownedIds.isEmpty()) {
            "Nothing was allocated."
        } else {
            "Deleted all prototype IDs: ${ownedIds.sorted().joinToString()}."
        }
    }

    private fun launchOrCancel(intent: Intent) {
        try {
            widgetFlow.launch(intent)
        } catch (error: RuntimeException) {
            cancelPending("System widget activity failed: ${error.javaClass.simpleName}. Deleted the unused ID.")
        }
    }

    private fun cancelPending(reason: String) {
        val pending = ledger.pending
        if (pending != null) {
            applyChange(ledger.cancel())
        }
        flowStage = null
        message = reason
    }

    private fun applyChange(change: LedgerChange) {
        change.appWidgetIdsToDelete.forEach(appWidgetHost::deleteAppWidgetId)
        ledger = change.ledger
    }

    private fun restoreState(savedState: Bundle?) {
        if (savedState == null) return

        var restored = AllocationLedger()
        restored = restoreBinding(savedState, KEY_CURRENT_ID, WidgetSlot.CURRENT, restored)
        restored = restoreBinding(savedState, KEY_NEIGHBOR_ID, WidgetSlot.NEIGHBOR, restored)

        val pendingId = savedState.getInt(KEY_PENDING_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val pendingSlot = savedState.getString(KEY_PENDING_SLOT)?.let { WidgetSlot.valueOf(it) }
        val restoredStage = savedState.getString(KEY_FLOW_STAGE)?.let { SelectionStage.valueOf(it) }
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID && pendingSlot != null && restoredStage != null) {
            restored = restored.begin(pendingSlot, pendingId)
            flowStage = restoredStage
        } else if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            appWidgetHost.deleteAppWidgetId(pendingId)
        }

        ledger = restored
        message = "Restored ${restored.bindings.size} hosted widget${if (restored.bindings.size == 1) "" else "s"}."
    }

    private fun restoreBinding(
        state: Bundle,
        key: String,
        slot: WidgetSlot,
        initial: AllocationLedger,
    ): AllocationLedger {
        val id = state.getInt(key, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return initial

        val info = appWidgetManager.getAppWidgetInfo(id)
        if (info == null) {
            appWidgetHost.deleteAppWidgetId(id)
            return initial
        }
        return initial.restore(slot, WidgetBinding(id, info.provider.flattenToShortString()))
    }

    private val WidgetSlot.label: String
        get() = when (this) {
            WidgetSlot.CURRENT -> "current destination"
            WidgetSlot.NEIGHBOR -> "neighbor preview"
        }

    private val WidgetSlot.diagnosticProvider: ComponentName
        get() = when (this) {
            WidgetSlot.CURRENT -> ComponentName(
                this@MainActivity,
                CurrentDiagnosticWidgetProvider::class.java,
            )
            WidgetSlot.NEIGHBOR -> ComponentName(
                this@MainActivity,
                NeighborDiagnosticWidgetProvider::class.java,
            )
        }

    private enum class SelectionStage {
        BIND,
        CONFIGURE,
    }

    private companion object {
        const val PROTOTYPE_HOST_ID = 0x514C
        const val KEY_CURRENT_ID = "current-id"
        const val KEY_NEIGHBOR_ID = "neighbor-id"
        const val KEY_PENDING_ID = "pending-id"
        const val KEY_PENDING_SLOT = "pending-slot"
        const val KEY_FLOW_STAGE = "flow-stage"
    }
}

@Composable
private fun WidgetNeighborPrototypeApp(
    ledger: AllocationLedger,
    message: String,
    widgetInfo: (Int) -> AppWidgetProviderInfo?,
    createHostView: (Int, AppWidgetProviderInfo) -> android.appwidget.AppWidgetHostView,
    onBindDiagnostic: (WidgetSlot) -> Unit,
    onRemove: (WidgetSlot) -> Unit,
    onReset: () -> Unit,
) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Separate widget ID risk prototype", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Question: can the current destination and a live neighbor host different Android widget IDs at the same time?",
                    style = MaterialTheme.typography.bodySmall,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(allocationStatus(WidgetSlot.CURRENT, ledger.current, widgetInfo))
                        Text(allocationStatus(WidgetSlot.NEIGHBOR, ledger.neighbor, widgetInfo))
                        ledger.pending?.let {
                            Text("pending: ${it.slot.name.lowercase()} id=${it.appWidgetId}")
                        }
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    WidgetDestination(
                        title = "Current destination",
                        slot = WidgetSlot.CURRENT,
                        binding = ledger.current,
                        info = ledger.current?.let { widgetInfo(it.appWidgetId) },
                        createHostView = createHostView,
                        enabled = ledger.pending == null,
                        onBindDiagnostic = onBindDiagnostic,
                        onRemove = onRemove,
                    )
                    WidgetDestination(
                        title = "Live neighbor preview",
                        slot = WidgetSlot.NEIGHBOR,
                        binding = ledger.neighbor,
                        info = ledger.neighbor?.let { widgetInfo(it.appWidgetId) },
                        createHostView = createHostView,
                        enabled = ledger.pending == null,
                        onBindDiagnostic = onBindDiagnostic,
                        onRemove = onRemove,
                    )
                }
                OutlinedButton(
                    onClick = onReset,
                    enabled = ledger.pending == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete all prototype widget IDs")
                }
            }
        }
    }
}

@Composable
private fun RowScope.WidgetDestination(
    title: String,
    slot: WidgetSlot,
    binding: WidgetBinding?,
    info: AppWidgetProviderInfo?,
    createHostView: (Int, AppWidgetProviderInfo) -> android.appwidget.AppWidgetHostView,
    enabled: Boolean,
    onBindDiagnostic: (WidgetSlot) -> Unit,
    onRemove: (WidgetSlot) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxHeight()
            .weight(1f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (binding != null && info != null) {
                    key(binding.appWidgetId) {
                        AndroidView(
                            factory = { createHostView(binding.appWidgetId, info) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Text(
                        "No widget",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = { onBindDiagnostic(slot) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (binding == null) "Bind diagnostic" else "Replace diagnostic")
            }
            OutlinedButton(
                onClick = { onRemove(slot) },
                enabled = enabled && binding != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Remove")
            }
        }
    }
}

private fun allocationStatus(
    slot: WidgetSlot,
    binding: WidgetBinding?,
    widgetInfo: (Int) -> AppWidgetProviderInfo?,
): String {
    val name = slot.name.lowercase()
    if (binding == null) return "$name: no ID"
    val isBound = widgetInfo(binding.appWidgetId) != null
    return "$name: id=${binding.appWidgetId} bound=$isBound provider=${binding.provider}"
}
