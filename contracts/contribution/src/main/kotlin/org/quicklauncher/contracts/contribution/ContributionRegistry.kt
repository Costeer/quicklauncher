package org.quicklauncher.contracts.contribution

import java.util.Collections
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ContributionTypeId

interface RegisteredContribution<C : Any> {
    val descriptor: ContributionDescriptor
    val target: Contribution
    val codec: ConfigurationCodec<C>
    val contractTests: ContributionContractDeclaration<C>
}

data class RegisteredLayout<C : Any>(
    override val descriptor: LayoutDescriptor,
    override val target: LayoutContribution<C>,
    override val codec: ConfigurationCodec<C>,
    override val contractTests: LayoutContractTestDeclaration<C>,
) : RegisteredContribution<C>

data class RegisteredBlock<C : Any>(
    override val descriptor: BlockDescriptor,
    override val target: BlockContribution<C>,
    override val codec: ConfigurationCodec<C>,
    override val contractTests: BlockContractTestDeclaration<C>,
) : RegisteredContribution<C>

data class RegisteredSearchProvider<C : Any>(
    override val descriptor: SearchProviderDescriptor,
    override val target: SearchProviderContribution<C>,
    override val codec: ConfigurationCodec<C>,
    override val contractTests: SearchProviderContractTestDeclaration<C>,
) : RegisteredContribution<C>

data class RegisteredLauncherCommand<C : Any>(
    override val descriptor: LauncherCommandDescriptor,
    override val target: LauncherCommandContribution<C>,
    override val codec: ConfigurationCodec<C>,
    override val contractTests: LauncherCommandContractTestDeclaration<C>,
) : RegisteredContribution<C>

data class RegisteredDestinationTemplate<C : Any>(
    override val descriptor: DestinationTemplateDescriptor,
    override val target: DestinationTemplateContribution<C>,
    override val codec: ConfigurationCodec<C>,
    override val contractTests: DestinationTemplateContractTestDeclaration<C>,
) : RegisteredContribution<C>

interface ContributionRegistry {
    val categoryIds: Set<ContributionTypeId>
    val entries: List<RegisteredContribution<*>>
}

fun ContributionRegistry.find(id: ContributionId): RegisteredContribution<*>? =
    entries.firstOrNull { it.descriptor.metadata.id == id }

fun contributionEntriesOf(
    values: Collection<RegisteredContribution<*>>,
): List<RegisteredContribution<*>> = Collections.unmodifiableList(ArrayList(values))

fun contributionTypeIdsOf(
    values: Collection<ContributionTypeId>,
): Set<ContributionTypeId> = Collections.unmodifiableSet(LinkedHashSet(values))
