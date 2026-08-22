package org.quicklauncher.testing.contracts

import org.quicklauncher.contracts.contribution.BlockContribution
import org.quicklauncher.contracts.contribution.BlockContractTestDeclaration
import org.quicklauncher.contracts.contribution.CommandInput
import org.quicklauncher.contracts.contribution.CommandResult
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.ConfigurationMigration
import org.quicklauncher.contracts.contribution.ContributionContractDeclaration
import org.quicklauncher.contracts.contribution.ContributionDescriptor
import org.quicklauncher.contracts.contribution.DestinationTemplateContribution
import org.quicklauncher.contracts.contribution.DestinationTemplateContractTestDeclaration
import org.quicklauncher.contracts.contribution.LauncherCommandContribution
import org.quicklauncher.contracts.contribution.LauncherCommandContractTestDeclaration
import org.quicklauncher.contracts.contribution.LayoutContribution
import org.quicklauncher.contracts.contribution.LayoutContractTestDeclaration
import org.quicklauncher.contracts.contribution.SearchProviderContribution
import org.quicklauncher.contracts.contribution.SearchProviderContractTestDeclaration
import org.quicklauncher.contracts.contribution.SearchQuery
import org.quicklauncher.contracts.contribution.TemplateInput
import org.quicklauncher.contracts.contribution.TemplateResult
import org.quicklauncher.contracts.contribution.ProviderResult
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.ui.AccessibilityDeclaration
import org.quicklauncher.contracts.ui.BlockRenderState
import org.quicklauncher.contracts.ui.BlockAction
import org.quicklauncher.contracts.ui.LayoutAction
import org.quicklauncher.contracts.ui.LayoutRenderState
import org.quicklauncher.contracts.ui.PerformanceHookDeclaration
import org.quicklauncher.contracts.ui.PreviewScenario

data class ConfigurationContractCases<C : Any>(
    val roundTripValue: C,
    val migrationDocument: ConfigurationDocument,
    val migrations: List<ConfigurationMigration>,
    val expectedMigratedValue: C,
    val failingDocument: ConfigurationDocument,
    val failingMigrations: List<ConfigurationMigration>,
    val decodeFailureDocument: ConfigurationDocument,
)

data class PerformanceCheck(
    val declaration: PerformanceHookDeclaration,
    val run: () -> PerformanceObservation,
)

data class PerformanceObservation(
    val metric: org.quicklauncher.contracts.ui.PerformanceMetric,
    val probeId: StableKey,
    val observedOutcome: String,
) {
    init {
        require(observedOutcome.isNotBlank()) { "Performance observation outcome must not be blank" }
    }
}

interface BlackBoxContractDeclaration<C : Any> : ContributionContractDeclaration<C> {
    val descriptor: ContributionDescriptor
    val codec: ConfigurationCodec<C>
    val configurationCases: ConfigurationContractCases<C>
    val previewAccessibility: Map<PreviewScenario, AccessibilityDeclaration>
    val performanceChecks: List<PerformanceCheck>
}

data class LayoutContractFixture(
    val screenshotId: StableKey,
    val scenario: PreviewScenario,
    val state: LayoutRenderState,
    val expectedActions: List<LayoutAction>,
    val expectedCustomActionLabels: List<String> = emptyList(),
)

interface LayoutContractDeclaration<C : Any> :
    BlackBoxContractDeclaration<C>,
    LayoutContractTestDeclaration<C> {
    val target: LayoutContribution<C>
    val instanceId: ModuleInstanceId
    val fixtures: List<LayoutContractFixture>
}

data class BlockContractFixture(
    val screenshotId: StableKey,
    val scenario: PreviewScenario,
    val state: BlockRenderState,
    val expectedActions: List<BlockAction>,
    val expectedCustomActionLabels: List<String> = emptyList(),
)

interface BlockContractDeclaration<C : Any> :
    BlackBoxContractDeclaration<C>,
    BlockContractTestDeclaration<C> {
    val target: BlockContribution<C>
    val instanceId: ModuleInstanceId
    val fixtures: List<BlockContractFixture>
}

data class SearchContractFixture(
    val scenario: PreviewScenario,
    val query: SearchQuery,
    val expectedResults: List<ProviderResult>,
)

interface SearchProviderContractDeclaration<C : Any> :
    BlackBoxContractDeclaration<C>,
    SearchProviderContractTestDeclaration<C> {
    val target: SearchProviderContribution<C>
    val instanceId: ModuleInstanceId
    val fixtures: List<SearchContractFixture>
}

data class CommandContractFixture<C : Any>(
    val scenario: PreviewScenario,
    val input: CommandInput<C>,
    val expectedResult: CommandResult,
)

interface LauncherCommandContractDeclaration<C : Any> :
    BlackBoxContractDeclaration<C>,
    LauncherCommandContractTestDeclaration<C> {
    val target: LauncherCommandContribution<C>
    val fixtures: List<CommandContractFixture<C>>
}

data class TemplateContractFixture<C : Any>(
    val scenario: PreviewScenario,
    val input: TemplateInput<C>,
    val expectedResult: TemplateResult,
)

interface DestinationTemplateContractDeclaration<C : Any> :
    BlackBoxContractDeclaration<C>,
    DestinationTemplateContractTestDeclaration<C> {
    val target: DestinationTemplateContribution<C>
    val fixtures: List<TemplateContractFixture<C>>
}
