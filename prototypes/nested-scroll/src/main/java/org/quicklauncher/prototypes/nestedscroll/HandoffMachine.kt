package org.quicklauncher.prototypes.nestedscroll

import kotlin.math.abs
import kotlin.math.sign

internal enum class VelocityOwner {
    LIST,
    NAVIGATOR,
}

internal data class GestureResult(
    val velocityOwner: VelocityOwner,
    val navigateBy: Int,
    val settleTargetPx: Float,
)

/**
 * Pure state machine for the drag boundary. Pointer movement uses Compose's sign convention:
 * a finger moving up is negative and advances to the destination below the current one.
 */
internal class HandoffMachine(
    thresholdPx: Float = 1f,
    pageExtentPx: Float = 1f,
    private val minimumNavigationFlingPxPerSecond: Float = 1_800f,
) {
    var navigationOffsetPx: Float = 0f
        private set

    var reversalCount: Int = 0
        private set

    private var thresholdPx = thresholdPx.coerceAtLeast(1f)
    private var pageExtentPx = pageExtentPx.coerceAtLeast(1f)
    private var reversing = false

    fun updateLimits(thresholdPx: Float, pageExtentPx: Float) {
        this.thresholdPx = thresholdPx.coerceAtLeast(1f)
        this.pageExtentPx = pageExtentPx.coerceAtLeast(1f)
        navigationOffsetPx = navigationOffsetPx.coerceIn(-this.pageExtentPx, this.pageExtentPx)
    }

    /** Consumes only enough opposing movement to unwind an active destination preview. */
    fun consumeBeforeList(deltaY: Float): Float {
        if (navigationOffsetPx == 0f || deltaY == 0f || sign(deltaY) == sign(navigationOffsetPx)) {
            reversing = false
            return 0f
        }

        if (!reversing) {
            reversalCount += 1
            reversing = true
        }

        val consumed = sign(deltaY) * minOf(abs(deltaY), abs(navigationOffsetPx))
        navigationOffsetPx += consumed
        if (navigationOffsetPx == 0f) {
            reversing = false
        }
        return consumed
    }

    /** Receives only movement the list could not consume at its top or bottom boundary. */
    fun consumeAfterList(remainingY: Float): Float {
        if (remainingY == 0f) return 0f

        val before = navigationOffsetPx
        navigationOffsetPx = (navigationOffsetPx + remainingY)
            .coerceIn(-pageExtentPx, pageExtentPx)
        reversing = false
        return navigationOffsetPx - before
    }

    fun finishGesture(velocityY: Float): GestureResult {
        if (navigationOffsetPx == 0f) {
            return GestureResult(
                velocityOwner = VelocityOwner.LIST,
                navigateBy = 0,
                settleTargetPx = 0f,
            )
        }

        val velocityContinuesDrag =
            velocityY != 0f && sign(velocityY) == sign(navigationOffsetPx)
        val crossesDistance = abs(navigationOffsetPx) >= thresholdPx
        val crossesVelocity =
            velocityContinuesDrag && abs(velocityY) >= minimumNavigationFlingPxPerSecond
        val navigateBy = if (crossesDistance || crossesVelocity) {
            if (navigationOffsetPx < 0f) 1 else -1
        } else {
            0
        }

        return GestureResult(
            velocityOwner = VelocityOwner.NAVIGATOR,
            navigateBy = navigateBy,
            settleTargetPx = if (navigateBy == 0) 0f else sign(navigationOffsetPx) * pageExtentPx,
        )
    }

    fun setAnimatedOffset(offsetPx: Float) {
        navigationOffsetPx = offsetPx.coerceIn(-pageExtentPx, pageExtentPx)
    }

    fun resetPreview() {
        navigationOffsetPx = 0f
        reversing = false
    }

    fun resetMeasurements() {
        resetPreview()
        reversalCount = 0
    }
}
