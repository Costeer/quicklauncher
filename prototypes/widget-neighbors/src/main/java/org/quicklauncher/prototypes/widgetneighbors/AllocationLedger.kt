package org.quicklauncher.prototypes.widgetneighbors

internal enum class WidgetSlot {
    CURRENT,
    NEIGHBOR,
}

internal data class WidgetBinding(
    val appWidgetId: Int,
    val provider: String,
)

internal data class PendingAllocation(
    val slot: WidgetSlot,
    val appWidgetId: Int,
)

internal data class LedgerChange(
    val ledger: AllocationLedger,
    val appWidgetIdsToDelete: Set<Int> = emptySet(),
)

/**
 * Keeps allocation ownership explicit. Android owns the widget IDs; this state only records
 * which destination owns each ID and which uncommitted ID must be deleted on cancellation.
 */
internal data class AllocationLedger(
    val current: WidgetBinding? = null,
    val neighbor: WidgetBinding? = null,
    val pending: PendingAllocation? = null,
) {
    val bindings: List<WidgetBinding>
        get() = listOfNotNull(current, neighbor)

    fun binding(slot: WidgetSlot): WidgetBinding? = when (slot) {
        WidgetSlot.CURRENT -> current
        WidgetSlot.NEIGHBOR -> neighbor
    }

    fun begin(slot: WidgetSlot, appWidgetId: Int): AllocationLedger {
        require(appWidgetId > 0) { "Android app widget IDs must be positive" }
        require(pending == null) { "Only one system widget flow may run at a time" }
        require(bindings.none { it.appWidgetId == appWidgetId }) {
            "A new destination needs an independently allocated widget ID"
        }
        return copy(pending = PendingAllocation(slot, appWidgetId))
    }

    fun commit(appWidgetId: Int, provider: String): LedgerChange {
        val allocation = requireNotNull(pending) { "No allocation is waiting to be committed" }
        require(allocation.appWidgetId == appWidgetId) { "The result does not own the pending ID" }
        require(provider.isNotBlank()) { "A bound widget needs a provider" }

        val otherBinding = binding(allocation.slot.other())
        require(otherBinding?.appWidgetId != appWidgetId) {
            "Current and neighbor destinations cannot share a widget ID"
        }

        val previous = binding(allocation.slot)
        val replacement = WidgetBinding(appWidgetId, provider)
        val updated = when (allocation.slot) {
            WidgetSlot.CURRENT -> copy(current = replacement, pending = null)
            WidgetSlot.NEIGHBOR -> copy(neighbor = replacement, pending = null)
        }
        return LedgerChange(
            ledger = updated,
            appWidgetIdsToDelete = setOfNotNull(previous?.appWidgetId),
        )
    }

    fun cancel(): LedgerChange {
        val allocation = requireNotNull(pending) { "No allocation is waiting to be cancelled" }
        return LedgerChange(
            ledger = copy(pending = null),
            appWidgetIdsToDelete = setOf(allocation.appWidgetId),
        )
    }

    fun remove(slot: WidgetSlot): LedgerChange {
        val previous = binding(slot) ?: return LedgerChange(this)
        val updated = when (slot) {
            WidgetSlot.CURRENT -> copy(current = null)
            WidgetSlot.NEIGHBOR -> copy(neighbor = null)
        }
        return LedgerChange(updated, setOf(previous.appWidgetId))
    }

    fun restore(slot: WidgetSlot, binding: WidgetBinding): AllocationLedger {
        require(bindings.none { it.appWidgetId == binding.appWidgetId }) {
            "Restored destinations cannot share a widget ID"
        }
        return when (slot) {
            WidgetSlot.CURRENT -> copy(current = binding)
            WidgetSlot.NEIGHBOR -> copy(neighbor = binding)
        }
    }

    fun clear(): LedgerChange {
        val ids = bindings.mapTo(mutableSetOf()) { it.appWidgetId }
        pending?.let { ids += it.appWidgetId }
        return LedgerChange(AllocationLedger(), ids)
    }

    private fun WidgetSlot.other(): WidgetSlot = when (this) {
        WidgetSlot.CURRENT -> WidgetSlot.NEIGHBOR
        WidgetSlot.NEIGHBOR -> WidgetSlot.CURRENT
    }
}
