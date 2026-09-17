package org.quicklauncher.contracts.contribution

import java.util.Collections
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ContractMajor
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ContributionTypeId
import org.quicklauncher.contracts.domain.SlotTypeId
import org.quicklauncher.contracts.domain.StableKey

private fun <T> immutableDescriptorList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableDescriptorSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

@JvmInline
value class DisplayText private constructor(val value: String) {
    companion object {
        fun of(value: String): DisplayText {
            require(value.isNotBlank()) { "Display text must not be blank" }
            require(value.length <= 2_000) { "Display text must not exceed 2,000 characters" }
            require('\u0000' !in value) { "Display text must not contain a null character" }
            return DisplayText(value)
        }
    }
}

object ContributionTypes {
    val LAYOUT: ContributionTypeId = ContributionTypeId.parse("org.quicklauncher.contribution/layout")
    val BLOCK: ContributionTypeId = ContributionTypeId.parse("org.quicklauncher.contribution/block")
    val SEARCH_PROVIDER: ContributionTypeId =
        ContributionTypeId.parse("org.quicklauncher.contribution/search-provider")
    val LAUNCHER_COMMAND: ContributionTypeId =
        ContributionTypeId.parse("org.quicklauncher.contribution/launcher-command")
    val DESTINATION_TEMPLATE: ContributionTypeId =
        ContributionTypeId.parse("org.quicklauncher.contribution/destination-template")

    val firstRelease: Set<ContributionTypeId> = immutableDescriptorSet(
        listOf(LAYOUT, BLOCK, SEARCH_PROVIDER, LAUNCHER_COMMAND, DESTINATION_TEMPLATE),
    )
}

object ContractCompatibility {
    private val supported = ContributionTypes.firstRelease.associateWith { setOf(ContractMajor.of(1)) }

    fun isSupported(typeId: ContributionTypeId, major: ContractMajor): Boolean =
        major in supported.orEmptyFor(typeId)

    private fun Map<ContributionTypeId, Set<ContractMajor>>.orEmptyFor(
        typeId: ContributionTypeId,
    ): Set<ContractMajor> = get(typeId) ?: emptySet()
}

class ContributionMetadata(
    val id: ContributionId,
    val typeId: ContributionTypeId,
    val contractMajor: ContractMajor,
    val displayName: DisplayText,
    val description: DisplayText,
    providedCapabilities: Collection<CapabilityId>,
    requiredCapabilities: Collection<CapabilityId>,
    val settings: SettingsSchema?,
    val configType: ConfigTypeId,
) {
    val providedCapabilities: Set<CapabilityId> = immutableDescriptorSet(providedCapabilities)
    val requiredCapabilities: Set<CapabilityId> = immutableDescriptorSet(requiredCapabilities)
}

interface ContributionDescriptor {
    val metadata: ContributionMetadata
}

enum class ScrollAxis {
    HORIZONTAL,
    VERTICAL,
}

class SlotDescriptor(
    val id: StableKey,
    val type: SlotTypeId,
    acceptedBlocks: Collection<ContributionId> = emptySet(),
    requiredCapabilities: Collection<CapabilityId>,
    val maximumChildren: Int,
    allowedScrollAxes: Collection<ScrollAxis>,
) {
    val acceptedBlocks: Set<ContributionId> = immutableDescriptorSet(acceptedBlocks)
    val requiredCapabilities: Set<CapabilityId> = immutableDescriptorSet(requiredCapabilities)
    val allowedScrollAxes: Set<ScrollAxis> = immutableDescriptorSet(allowedScrollAxes)
}

class LayoutDescriptor(
    override val metadata: ContributionMetadata,
    slots: Collection<SlotDescriptor>,
) : ContributionDescriptor {
    val slots: List<SlotDescriptor> = immutableDescriptorList(slots)
}

class BlockDescriptor(
    override val metadata: ContributionMetadata,
    compatibleSlotTypes: Collection<SlotTypeId>,
    childSlots: Collection<SlotDescriptor>,
    occupiedScrollAxes: Collection<ScrollAxis> = emptySet(),
    val requiresSearchPresentation: Boolean = false,
) : ContributionDescriptor {
    val compatibleSlotTypes: Set<SlotTypeId> = immutableDescriptorSet(compatibleSlotTypes)
    val childSlots: List<SlotDescriptor> = immutableDescriptorList(childSlots)
    val occupiedScrollAxes: Set<ScrollAxis> = immutableDescriptorSet(occupiedScrollAxes)
}

enum class SearchResultKind {
    APP,
    SHORTCUT,
    CONTACT,
    FILE,
    SETTING,
    WEB,
    COMMAND,
    INFORMATION,
}

class SearchProviderDescriptor(
    override val metadata: ContributionMetadata,
    resultKinds: Collection<SearchResultKind>,
    val minimumQueryLength: Int,
) : ContributionDescriptor {
    val resultKinds: Set<SearchResultKind> = immutableDescriptorSet(resultKinds)
}

enum class CommandContext {
    SEARCH,
    GESTURE,
    ITEM_ACTIONS,
    RECOVERY,
    SETTINGS,
}

enum class CommandResultKind {
    HOST_ACTION,
    INFORMATION,
}

class LauncherCommandDescriptor(
    override val metadata: ContributionMetadata,
    contexts: Collection<CommandContext>,
    resultKinds: Collection<CommandResultKind>,
) : ContributionDescriptor {
    val contexts: Set<CommandContext> = immutableDescriptorSet(contexts)
    val resultKinds: Set<CommandResultKind> = immutableDescriptorSet(resultKinds)
}

class DestinationTemplateDescriptor(
    override val metadata: ContributionMetadata,
    requiredContributions: Collection<ContributionId>,
    val maximumBlocks: Int,
) : ContributionDescriptor {
    val requiredContributions: Set<ContributionId> = immutableDescriptorSet(requiredContributions)
}
