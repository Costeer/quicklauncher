package org.quicklauncher.prototypes.widgetneighbors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AllocationLedgerTest {
    @Test
    fun `current and neighbor keep independently allocated IDs`() {
        val current = AllocationLedger()
            .begin(WidgetSlot.CURRENT, 41)
            .commit(41, "example.clock")
            .ledger
        val both = current
            .begin(WidgetSlot.NEIGHBOR, 73)
            .commit(73, "example.weather")
            .ledger

        assertEquals(41, both.current?.appWidgetId)
        assertEquals(73, both.neighbor?.appWidgetId)
        assertEquals(2, both.bindings.map { it.appWidgetId }.distinct().size)
    }

    @Test
    fun `cancel deletes only the uncommitted allocation`() {
        val existing = AllocationLedger()
            .begin(WidgetSlot.CURRENT, 41)
            .commit(41, "example.clock")
            .ledger
        val cancelled = existing
            .begin(WidgetSlot.NEIGHBOR, 73)
            .cancel()

        assertEquals(setOf(73), cancelled.appWidgetIdsToDelete)
        assertEquals(41, cancelled.ledger.current?.appWidgetId)
        assertNull(cancelled.ledger.neighbor)
        assertNull(cancelled.ledger.pending)
    }

    @Test
    fun `replacing a slot deletes its old ID after commit`() {
        val existing = AllocationLedger()
            .begin(WidgetSlot.CURRENT, 41)
            .commit(41, "example.clock")
            .ledger
        val replaced = existing
            .begin(WidgetSlot.CURRENT, 99)
            .commit(99, "example.calendar")

        assertEquals(setOf(41), replaced.appWidgetIdsToDelete)
        assertEquals(99, replaced.ledger.current?.appWidgetId)
    }

    @Test
    fun `a destination cannot reuse an active widget ID`() {
        val existing = AllocationLedger()
            .begin(WidgetSlot.CURRENT, 41)
            .commit(41, "example.clock")
            .ledger

        assertThrows(IllegalArgumentException::class.java) {
            existing.begin(WidgetSlot.NEIGHBOR, 41)
        }
    }

    @Test
    fun `clear returns every owned ID including a pending allocation`() {
        val ledger = AllocationLedger()
            .begin(WidgetSlot.CURRENT, 41)
            .commit(41, "example.clock")
            .ledger
            .begin(WidgetSlot.NEIGHBOR, 73)

        val cleared = ledger.clear()

        assertEquals(setOf(41, 73), cleared.appWidgetIdsToDelete)
        assertEquals(AllocationLedger(), cleared.ledger)
    }
}
