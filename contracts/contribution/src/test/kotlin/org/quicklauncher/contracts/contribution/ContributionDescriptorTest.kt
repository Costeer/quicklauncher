package org.quicklauncher.contracts.contribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ContractMajor
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.SlotTypeId
import org.quicklauncher.contracts.domain.StableKey

class ContributionDescriptorTest {
    @Test
    fun `first release category IDs are stable strings with exact major support`() {
        assertEquals("org.quicklauncher.contribution/layout", ContributionTypes.LAYOUT.value)
        assertEquals("org.quicklauncher.contribution/block", ContributionTypes.BLOCK.value)
        assertEquals("org.quicklauncher.contribution/search-provider", ContributionTypes.SEARCH_PROVIDER.value)
        assertEquals("org.quicklauncher.contribution/launcher-command", ContributionTypes.LAUNCHER_COMMAND.value)
        assertEquals("org.quicklauncher.contribution/destination-template", ContributionTypes.DESTINATION_TEMPLATE.value)

        assertTrue(ContractCompatibility.isSupported(ContributionTypes.LAYOUT, ContractMajor.of(1)))
        assertTrue(!ContractCompatibility.isSupported(ContributionTypes.LAYOUT, ContractMajor.of(2)))
    }

    @Test
    fun `one valid descriptor of every first release type passes the common validator`() {
        val descriptors = listOf(
            LayoutDescriptor(
                metadata(ContributionTypes.LAYOUT, "layout", "layout-config"),
                slots = listOf(slot("main")),
            ),
            BlockDescriptor(
                metadata(ContributionTypes.BLOCK, "block", "block-config"),
                compatibleSlotTypes = setOf(slotType),
                childSlots = emptyList(),
            ),
            SearchProviderDescriptor(
                metadata(ContributionTypes.SEARCH_PROVIDER, "search", "search-config"),
                resultKinds = setOf(SearchResultKind.APP, SearchResultKind.COMMAND),
                minimumQueryLength = 0,
            ),
            LauncherCommandDescriptor(
                metadata(ContributionTypes.LAUNCHER_COMMAND, "command", "command-config"),
                contexts = setOf(CommandContext.SEARCH, CommandContext.GESTURE),
                resultKinds = setOf(CommandResultKind.HOST_ACTION),
            ),
            DestinationTemplateDescriptor(
                metadata(ContributionTypes.DESTINATION_TEMPLATE, "template", "template-config"),
                requiredContributions = emptySet(),
                maximumBlocks = 4,
            ),
        )

        descriptors.forEach { descriptor ->
            assertEquals(descriptor.metadata.id.toString(), ValidationResult.Valid, DescriptorValidator.validate(descriptor))
        }
    }

    @Test
    fun `descriptor validation reports category settings capability and type specific errors`() {
        val guardedByUndeclared = CapabilityId.parse("org.quicklauncher.capability/undeclared")
        val metadata = ContributionMetadata(
            id = ContributionId.parse("org.quicklauncher.samples/broken-layout"),
            typeId = ContributionTypes.BLOCK,
            contractMajor = ContractMajor.of(2),
            displayName = DisplayText.of("Broken layout"),
            description = DisplayText.of("A deliberately invalid descriptor"),
            providedCapabilities = setOf(capability),
            requiredCapabilities = setOf(capability),
            settings = SettingsSchema(
                listOf(BooleanSetting(StableKey.parse("enabled"), "Enabled", true, guardedByUndeclared)),
            ),
            configType = ConfigTypeId.parse("org.quicklauncher.samples/broken-layout-config"),
        )
        val descriptor = LayoutDescriptor(
            metadata = metadata,
            slots = listOf(slot("main"), slot("main", maximumChildren = 0)),
        )

        val result = DescriptorValidator.validate(descriptor) as ValidationResult.Invalid

        assertEquals(
            setOf(
                "descriptor.category",
                "descriptor.unsupported-major",
                "descriptor.capability-self-dependency",
                "descriptor.setting-capability",
                "layout.duplicate-slot",
                "layout.slot-multiplicity",
            ),
            result.errors.map { it.code }.toSet(),
        )
        assertTrue(result.errors.all { it.message.contains("broken-layout") })
    }

    private val slotType = SlotTypeId.parse("org.quicklauncher.slot/content")
    private val capability = CapabilityId.parse("org.quicklauncher.capability/content")

    private fun metadata(
        typeId: org.quicklauncher.contracts.domain.ContributionTypeId,
        name: String,
        configName: String,
    ) = ContributionMetadata(
        id = ContributionId.parse("org.quicklauncher.samples/$name"),
        typeId = typeId,
        contractMajor = ContractMajor.of(1),
        displayName = DisplayText.of(name.replaceFirstChar(Char::uppercase)),
        description = DisplayText.of("Sample $name contribution"),
        providedCapabilities = setOf(capability),
        requiredCapabilities = emptySet(),
        settings = null,
        configType = ConfigTypeId.parse("org.quicklauncher.samples/$configName"),
    )

    private fun slot(name: String, maximumChildren: Int = 1) = SlotDescriptor(
        id = StableKey.parse(name),
        type = slotType,
        requiredCapabilities = emptySet(),
        maximumChildren = maximumChildren,
        allowedScrollAxes = emptySet(),
    )
}
