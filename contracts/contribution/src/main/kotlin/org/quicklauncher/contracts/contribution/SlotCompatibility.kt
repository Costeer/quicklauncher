package org.quicklauncher.contracts.contribution

/** The shared, host-independent compatibility result for placing a block in a typed slot. */
enum class SlotCompatibilityProblem {
    BLOCK_NOT_ACCEPTED,
    SLOT_TYPE_MISMATCH,
    MISSING_CAPABILITY,
    SCROLL_AXIS_CONFLICT,
    CAPACITY_EXCEEDED,
}

/**
 * Applies the complete descriptor-level placement policy. Keeping this in the public contract
 * prevents persistence, composition, onboarding, and editors from drifting apart.
 */
fun SlotDescriptor.compatibilityProblem(
    block: BlockDescriptor,
    childCount: Int,
): SlotCompatibilityProblem? {
    require(childCount >= 0) { "Child count must not be negative" }
    return when {
        block.metadata.id !in acceptedBlocks -> SlotCompatibilityProblem.BLOCK_NOT_ACCEPTED
        type !in block.compatibleSlotTypes -> SlotCompatibilityProblem.SLOT_TYPE_MISMATCH
        !block.metadata.providedCapabilities.containsAll(requiredCapabilities) ->
            SlotCompatibilityProblem.MISSING_CAPABILITY
        !allowedScrollAxes.containsAll(block.occupiedScrollAxes) ->
            SlotCompatibilityProblem.SCROLL_AXIS_CONFLICT
        childCount > maximumChildren -> SlotCompatibilityProblem.CAPACITY_EXCEEDED
        else -> null
    }
}
