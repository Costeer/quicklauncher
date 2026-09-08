package org.quicklauncher.host.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.BlockDescriptor
import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.Contribution
import org.quicklauncher.contracts.contribution.ContributionContractDeclaration
import org.quicklauncher.contracts.contribution.ContributionDescriptor
import org.quicklauncher.contracts.contribution.ContributionMetadata
import org.quicklauncher.contracts.contribution.ContributionRegistry
import org.quicklauncher.contracts.contribution.ContributionTypes
import org.quicklauncher.contracts.contribution.DisplayText
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.contribution.LayoutDescriptor
import org.quicklauncher.contracts.contribution.RegisteredContribution
import org.quicklauncher.contracts.contribution.ScrollAxis
import org.quicklauncher.contracts.contribution.SlotDescriptor
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContractMajor
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.SlotTypeId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.ui.PerformanceHookDeclaration
import org.quicklauncher.contracts.ui.PreviewScenario

class RegistryPlacementPolicyTest {
    private val capability = CapabilityId.parse("org.quicklauncher.capability/content")
    private val slotType = SlotTypeId.parse("org.quicklauncher.slot/content")
    private val childId = ContributionId.parse("org.quicklauncher.block/child")
    private val parentId = ContributionId.parse("org.quicklauncher.layout/parent")

    @Test
    fun `compatible child is accepted until the slot reaches its maximum`() {
        val policy = policy(
            slot = slot(accepted = setOf(childId), required = setOf(capability), allowed = setOf(ScrollAxis.VERTICAL)),
            child = block(
                compatible = setOf(slotType),
                provided = setOf(capability),
                occupied = setOf(ScrollAxis.VERTICAL),
            ),
        )
        val request = request(childCountAfterDrop = 1)

        assertSame(null, policy.validate(request))
        assertEquals(StoreRejectionCode.SLOT_AT_CAPACITY, policy.validate(
            request.copy(childCountAfterDrop = 2),
        )!!.code)
    }

    @Test
    fun `accepted id slot type capabilities and scroll axes each reject incompatibility`() {
        val incompatiblePolicies = listOf(
            policy(
                slot(setOf(ContributionId.parse("org.quicklauncher.block/other")), setOf(capability), setOf(ScrollAxis.VERTICAL)),
                block(setOf(slotType), setOf(capability), setOf(ScrollAxis.VERTICAL)),
            ),
            policy(
                slot(setOf(childId), setOf(capability), setOf(ScrollAxis.VERTICAL)),
                block(setOf(SlotTypeId.parse("org.quicklauncher.slot/other")), setOf(capability), setOf(ScrollAxis.VERTICAL)),
            ),
            policy(
                slot(setOf(childId), setOf(capability), setOf(ScrollAxis.VERTICAL)),
                block(setOf(slotType), emptySet(), setOf(ScrollAxis.VERTICAL)),
            ),
            policy(
                slot(setOf(childId), setOf(capability), setOf(ScrollAxis.HORIZONTAL)),
                block(setOf(slotType), setOf(capability), setOf(ScrollAxis.VERTICAL)),
            ),
        )

        incompatiblePolicies.forEach { policy ->
            val rejection = policy.validate(request(1))
            assertTrue(rejection != null)
            assertEquals(StoreRejectionCode.INCOMPATIBLE_PLACEMENT, rejection!!.code)
        }
    }

    private fun request(childCountAfterDrop: Int): PlacementRequest = PlacementRequest(
        parent = module("parent", parentId),
        child = module("child", childId),
        parentSlotId = StableKey.parse("main"),
        childCountAfterDrop = childCountAfterDrop,
    )

    private fun policy(slot: SlotDescriptor, child: BlockDescriptor): RegistryPlacementPolicy {
        val layout = LayoutDescriptor(metadata(parentId, ContributionTypes.LAYOUT), listOf(slot))
        val registry = object : ContributionRegistry {
            override val categoryIds = ContributionTypes.firstRelease
            override val entries: List<RegisteredContribution<*>> = listOf(entry(layout), entry(child))
        }
        return RegistryPlacementPolicy(registry)
    }

    private fun slot(
        accepted: Set<ContributionId>,
        required: Set<CapabilityId>,
        allowed: Set<ScrollAxis>,
    ): SlotDescriptor = SlotDescriptor(
        id = StableKey.parse("main"),
        type = slotType,
        acceptedBlocks = accepted,
        requiredCapabilities = required,
        maximumChildren = 1,
        allowedScrollAxes = allowed,
    )

    private fun block(
        compatible: Set<SlotTypeId>,
        provided: Set<CapabilityId>,
        occupied: Set<ScrollAxis>,
    ): BlockDescriptor = BlockDescriptor(
        metadata = metadata(childId, ContributionTypes.BLOCK, provided),
        compatibleSlotTypes = compatible,
        childSlots = emptyList(),
        occupiedScrollAxes = occupied,
    )

    private fun metadata(
        id: ContributionId,
        type: org.quicklauncher.contracts.domain.ContributionTypeId,
        provided: Set<CapabilityId> = emptySet(),
    ): ContributionMetadata = ContributionMetadata(
        id = id,
        typeId = type,
        contractMajor = ContractMajor.of(1),
        displayName = DisplayText.of(id.value),
        description = DisplayText.of("Test descriptor"),
        providedCapabilities = provided,
        requiredCapabilities = emptySet(),
        settings = null,
        configType = ConfigTypeId.parse("org.quicklauncher.config/${id.value.substringAfterLast('/')}")
    )

    private fun entry(descriptor: ContributionDescriptor): RegisteredContribution<String> =
        object : RegisteredContribution<String> {
            override val descriptor: ContributionDescriptor = descriptor
            override val target: Contribution = object : Contribution {}
            override val codec: ConfigurationCodec<String> = object : ConfigurationCodec<String> {
                override val configType: ConfigTypeId = descriptor.metadata.configType
                override val currentSchemaVersion: SchemaVersion = SchemaVersion.of(1)
                override val default: String = "{}"
                override fun encode(value: String): EncodedConfiguration = EncodedConfiguration.of(value)
                override fun decode(encoded: EncodedConfiguration): CodecResult<String> =
                    CodecResult.Decoded(encoded.value)
            }
            override val contractTests: ContributionContractDeclaration<String> =
                object : ContributionContractDeclaration<String> {
                    override val contributionId: ContributionId = descriptor.metadata.id
                    override val scenarios: Set<PreviewScenario> = emptySet()
                    override val performanceHooks: List<PerformanceHookDeclaration> = emptyList()
                }
        }

    private fun module(local: String, contributionId: ContributionId): ModuleInstanceRecord =
        ModuleInstanceRecord(
            id = ModuleInstanceId.parse("org.quicklauncher.instance/$local"),
            contributionId = contributionId,
            configurationDocumentId = ConfigurationDocumentId.parse("org.quicklauncher.configuration/$local"),
        )
}
