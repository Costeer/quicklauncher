package org.quicklauncher.host.data.store

import org.quicklauncher.contracts.contribution.BlockDescriptor
import org.quicklauncher.contracts.contribution.ContributionRegistry
import org.quicklauncher.contracts.contribution.LayoutDescriptor
import org.quicklauncher.contracts.contribution.SlotDescriptor
import org.quicklauncher.contracts.contribution.find

data class PlacementRequest(
    val parent: ModuleInstanceRecord,
    val child: ModuleInstanceRecord,
    val parentSlotId: org.quicklauncher.contracts.domain.StableKey,
    val childCountAfterDrop: Int,
) {
    init {
        require(childCountAfterDrop >= 1) { "Drop child count must be positive" }
    }
}

fun interface PlacementPolicy {
    /** Returns null when the drop is compatible, or a typed rejection otherwise. */
    fun validate(request: PlacementRequest): StoreRejection?
}

class RegistryPlacementPolicy(
    private val registry: ContributionRegistry,
) : PlacementPolicy {
    override fun validate(request: PlacementRequest): StoreRejection? {
        val parentEntry = registry.find(request.parent.contributionId)
            ?: return incompatible("Parent contribution '${request.parent.contributionId}' is unavailable")
        val childEntry = registry.find(request.child.contributionId)
            ?: return incompatible("Child contribution '${request.child.contributionId}' is unavailable")
        val child = childEntry.descriptor as? BlockDescriptor
            ?: return incompatible("Only a block contribution may be placed into a slot")
        val slots = when (val descriptor = parentEntry.descriptor) {
            is LayoutDescriptor -> descriptor.slots
            is BlockDescriptor -> descriptor.childSlots
            else -> return incompatible("The placement parent does not expose slots")
        }
        val slot = slots.firstOrNull { it.id == request.parentSlotId }
            ?: return incompatible("Parent slot '${request.parentSlotId}' does not exist")

        return validateCompatibility(slot, child, request)
    }

    private fun validateCompatibility(
        slot: SlotDescriptor,
        child: BlockDescriptor,
        request: PlacementRequest,
    ): StoreRejection? {
        if (request.child.contributionId !in slot.acceptedBlocks) {
            return incompatible("Parent slot does not accept '${request.child.contributionId}'")
        }
        if (slot.type !in child.compatibleSlotTypes) {
            return incompatible("Child block does not accept slot type '${slot.type}'")
        }
        if (!child.metadata.providedCapabilities.containsAll(slot.requiredCapabilities)) {
            return incompatible("Child block does not provide every capability required by the slot")
        }
        if (!slot.allowedScrollAxes.containsAll(child.occupiedScrollAxes)) {
            return incompatible("Child block occupies a scroll axis disallowed by the slot")
        }
        if (request.childCountAfterDrop > slot.maximumChildren) {
            return StoreRejection(
                StoreRejectionCode.SLOT_AT_CAPACITY,
                "Parent slot accepts at most ${slot.maximumChildren} children",
            )
        }
        return null
    }

    private fun incompatible(message: String): StoreRejection = StoreRejection(
        StoreRejectionCode.INCOMPATIBLE_PLACEMENT,
        message,
    )
}

internal val RejectUnresolvedPlacementPolicy = PlacementPolicy {
    StoreRejection(
        StoreRejectionCode.PLACEMENT_POLICY_UNAVAILABLE,
        "Placement validation requires a configured contribution registry",
    )
}
