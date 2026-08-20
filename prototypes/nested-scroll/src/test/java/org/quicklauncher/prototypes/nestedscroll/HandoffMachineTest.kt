package org.quicklauncher.prototypes.nestedscroll

import org.junit.Assert.assertEquals
import org.junit.Test

class HandoffMachineTest {
    private val machine = HandoffMachine(
        thresholdPx = 100f,
        pageExtentPx = 400f,
        minimumNavigationFlingPxPerSecond = 1_800f,
    )

    @Test
    fun `list keeps control while it leaves no remainder`() {
        assertEquals(0f, machine.consumeAfterList(0f))
        assertEquals(0f, machine.navigationOffsetPx)

        val result = machine.finishGesture(velocityY = -6_000f)

        assertEquals(VelocityOwner.LIST, result.velocityOwner)
        assertEquals(0, result.navigateBy)
    }

    @Test
    fun `bottom boundary remainder advances to the destination below`() {
        assertEquals(-120f, machine.consumeAfterList(-120f))

        val result = machine.finishGesture(velocityY = 0f)

        assertEquals(VelocityOwner.NAVIGATOR, result.velocityOwner)
        assertEquals(1, result.navigateBy)
        assertEquals(-400f, result.settleTargetPx)
    }

    @Test
    fun `top boundary remainder advances to the destination above`() {
        assertEquals(120f, machine.consumeAfterList(120f))

        val result = machine.finishGesture(velocityY = 0f)

        assertEquals(-1, result.navigateBy)
        assertEquals(400f, result.settleTargetPx)
    }

    @Test
    fun `reversal unwinds preview before returning its remainder to the list`() {
        machine.consumeAfterList(-80f)

        val consumedByNavigator = machine.consumeBeforeList(130f)
        val remainingForList = 130f - consumedByNavigator

        assertEquals(80f, consumedByNavigator)
        assertEquals(50f, remainingForList)
        assertEquals(0f, machine.navigationOffsetPx)
        assertEquals(1, machine.reversalCount)
        assertEquals(VelocityOwner.LIST, machine.finishGesture(500f).velocityOwner)
    }

    @Test
    fun `short slow boundary drag returns to rest`() {
        machine.consumeAfterList(-40f)

        val result = machine.finishGesture(velocityY = -500f)

        assertEquals(VelocityOwner.NAVIGATOR, result.velocityOwner)
        assertEquals(0, result.navigateBy)
        assertEquals(0f, result.settleTargetPx)
    }

    @Test
    fun `velocity belongs only to navigator after boundary movement`() {
        machine.consumeAfterList(-20f)

        val result = machine.finishGesture(velocityY = -2_000f)

        assertEquals(VelocityOwner.NAVIGATOR, result.velocityOwner)
        assertEquals(1, result.navigateBy)
    }

    @Test
    fun `opposing fling cannot complete destination movement`() {
        machine.consumeAfterList(-20f)

        val result = machine.finishGesture(velocityY = 6_000f)

        assertEquals(VelocityOwner.NAVIGATOR, result.velocityOwner)
        assertEquals(0, result.navigateBy)
    }
}
