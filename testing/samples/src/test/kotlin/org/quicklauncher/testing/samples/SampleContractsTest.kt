package org.quicklauncher.testing.samples

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.quicklauncher.contracts.contribution.BlockDescriptor
import org.quicklauncher.contracts.contribution.Contribution
import org.quicklauncher.contracts.contribution.ContributionDescriptor
import org.quicklauncher.contracts.contribution.DestinationTemplateDescriptor
import org.quicklauncher.contracts.contribution.LauncherCommandDescriptor
import org.quicklauncher.contracts.contribution.LayoutDescriptor
import org.quicklauncher.contracts.contribution.RegisteredContribution
import org.quicklauncher.contracts.contribution.SearchProviderDescriptor
import org.quicklauncher.contracts.contribution.ContributionTypes
import org.quicklauncher.contracts.contribution.DescriptorValidator
import org.quicklauncher.contracts.contribution.ValidationResult
import org.quicklauncher.generated.GeneratedContributionRegistry
import org.quicklauncher.testing.contracts.BlockContributionContractSuite
import org.quicklauncher.testing.contracts.DestinationTemplateContributionContractSuite
import org.quicklauncher.testing.contracts.LauncherCommandContributionContractSuite
import org.quicklauncher.testing.contracts.LayoutContributionContractSuite
import org.quicklauncher.testing.contracts.LayoutVisualSnapshotContractSuite
import org.quicklauncher.testing.contracts.LayoutVisualInteractionContractSuite
import org.quicklauncher.testing.contracts.SearchProviderContributionContractSuite
import org.quicklauncher.testing.contracts.BlockVisualSnapshotContractSuite
import org.quicklauncher.testing.contracts.BlockVisualInteractionContractSuite
import org.quicklauncher.testing.contracts.LayoutVisualAccessibilityContractSuite
import org.quicklauncher.testing.contracts.BlockVisualAccessibilityContractSuite

class SampleLayoutContractTest : LayoutContributionContractSuite<SampleConfiguration>() {
    override val contract = SampleLayoutContract
}

class SampleBlockContractTest : BlockContributionContractSuite<SampleConfiguration>() {
    override val contract = SampleBlockContract
}

class SampleLayoutVisualSnapshotTest : LayoutVisualSnapshotContractSuite<SampleConfiguration>() {
    override val contract = SampleLayoutContract
}

class SampleBlockVisualSnapshotTest : BlockVisualSnapshotContractSuite<SampleConfiguration>() {
    override val contract = SampleBlockContract
}

class SampleLayoutVisualAccessibilityTest : LayoutVisualAccessibilityContractSuite<SampleConfiguration>() {
    override val contract = SampleLayoutContract
}

class SampleBlockVisualAccessibilityTest : BlockVisualAccessibilityContractSuite<SampleConfiguration>() {
    override val contract = SampleBlockContract
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SampleLayoutVisualInteractionTest : LayoutVisualInteractionContractSuite<SampleConfiguration>() {
    override val contract = SampleLayoutContract
    @get:Rule
    override val compose = createComposeRule()
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SampleBlockVisualInteractionTest : BlockVisualInteractionContractSuite<SampleConfiguration>() {
    override val contract = SampleBlockContract
    @get:Rule
    override val compose = createComposeRule()
}

class SampleSearchContractTest : SearchProviderContributionContractSuite<SampleConfiguration>() {
    override val contract = SampleSearchContract
}

class SampleCommandContractTest : LauncherCommandContributionContractSuite<SampleConfiguration>() {
    override val contract = SampleCommandContract
}

class SampleTemplateContractTest : DestinationTemplateContributionContractSuite<SampleConfiguration>() {
    override val contract = SampleTemplateContract
}

class GeneratedSampleRegistryTest {
    @Test
    fun `generated registry contains all five valid samples in stable order`() {
        assertEquals(ContributionTypes.firstRelease, GeneratedContributionRegistry.categoryIds)
        assertEquals(
            listOf(
                SampleIds.BLOCK,
                SampleIds.TEMPLATE,
                SampleIds.COMMAND,
                SampleIds.LAYOUT,
                SampleIds.SEARCH,
            ),
            GeneratedContributionRegistry.entries.map { it.descriptor.metadata.id },
        )
    }

    @Test
    fun `generated entries bind descriptors codecs tests and targets without reflection`() {
        val declarations = listOf(
            SampleLayoutContract,
            SampleBlockContract,
            SampleSearchContract,
            SampleCommandContract,
            SampleTemplateContract,
        ).associateBy { it.contributionId }

        GeneratedContributionRegistry.entries.forEach { entry ->
            val declaration = declarations.getValue(entry.descriptor.metadata.id)
            assertEquals(ValidationResult.Valid, DescriptorValidator.validate(entry.descriptor))
            assertEquals(entry.descriptor.metadata.configType, entry.codec.configType)
            assertEquals(declaration.codec.configType, entry.codec.configType)
            assertEquals(declaration.contributionId, entry.contractTests.contributionId)
            assertEquals(descriptorSnapshot(declaration.descriptor), descriptorSnapshot(entry.descriptor))
            assertEquals(declaration.scenarios, entry.contractTests.scenarios)
            assertEquals(declaration.performanceHooks, entry.contractTests.performanceHooks)
            assertSame(declaration.codec, entry.codec)
            assertSame(declaration, entry.contractTests)
            assertSame(expectedTarget(entry), entry.target)
        }
    }

    private fun expectedTarget(entry: RegisteredContribution<*>): Contribution =
        when (entry.descriptor.metadata.id) {
            SampleIds.LAYOUT -> SampleLayout
            SampleIds.BLOCK -> SampleBlock
            SampleIds.SEARCH -> SampleSearchProvider
            SampleIds.COMMAND -> SampleCommand
            SampleIds.TEMPLATE -> SampleTemplate
            else -> error("Unexpected generated contribution ${entry.descriptor.metadata.id}")
        }

    private fun descriptorSnapshot(descriptor: ContributionDescriptor): DescriptorSnapshot {
        val metadata = descriptor.metadata
        val common = listOf(
            metadata.id,
            metadata.typeId,
            metadata.contractMajor,
            metadata.displayName,
            metadata.description,
            metadata.providedCapabilities,
            metadata.requiredCapabilities,
            metadata.settings,
            metadata.configType,
        )
        val specific: Any = when (descriptor) {
            is LayoutDescriptor -> descriptor.slots.map(::slotSnapshot)
            is BlockDescriptor -> listOf(
                descriptor.compatibleSlotTypes,
                descriptor.childSlots.map(::slotSnapshot),
                descriptor.occupiedScrollAxes,
            )
            is SearchProviderDescriptor -> listOf(descriptor.resultKinds, descriptor.minimumQueryLength)
            is LauncherCommandDescriptor -> listOf(descriptor.contexts, descriptor.resultKinds)
            is DestinationTemplateDescriptor ->
                listOf(descriptor.requiredContributions, descriptor.maximumBlocks)
            else -> error("Unsupported descriptor ${descriptor::class.qualifiedName}")
        }
        return DescriptorSnapshot(common, specific)
    }

    private fun slotSnapshot(slot: org.quicklauncher.contracts.contribution.SlotDescriptor): List<Any> =
        listOf(
            slot.id,
            slot.type,
            slot.acceptedBlocks,
            slot.requiredCapabilities,
            slot.maximumChildren,
            slot.allowedScrollAxes,
        )

    private data class DescriptorSnapshot(
        val common: List<Any?>,
        val specific: Any,
    )
}
