package org.quicklauncher.testing.samples

import org.quicklauncher.contracts.contribution.ConfigurationPipeline
import org.quicklauncher.contracts.contribution.ContributionTypes
import org.quicklauncher.contracts.contribution.DestinationDraft
import org.quicklauncher.contracts.contribution.DestinationTemplateContribution
import org.quicklauncher.contracts.contribution.DestinationTemplateDescriptor
import org.quicklauncher.contracts.contribution.DisplayText
import org.quicklauncher.contracts.contribution.EncodedPlacementData
import org.quicklauncher.contracts.contribution.ModuleDraft
import org.quicklauncher.contracts.contribution.PlacementData
import org.quicklauncher.contracts.contribution.PlacementDraft
import org.quicklauncher.contracts.contribution.PositionedDestinationDraft
import org.quicklauncher.contracts.contribution.TemplateCoordinate
import org.quicklauncher.contracts.contribution.TemplateDestinationInput
import org.quicklauncher.contracts.contribution.TemplateInput
import org.quicklauncher.contracts.contribution.TemplatePlan
import org.quicklauncher.contracts.contribution.TemplateResult
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.ui.PerformanceMetric
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.registry.annotations.RegisterDestinationTemplate
import org.quicklauncher.registry.annotations.ContractTestSpec
import org.quicklauncher.registry.annotations.ContributionCategorySpec
import org.quicklauncher.registry.annotations.PerformanceMetricSpec
import org.quicklauncher.registry.annotations.PreviewScenarioSpec
import org.quicklauncher.testing.contracts.DestinationTemplateContractDeclaration
import org.quicklauncher.testing.contracts.TemplateContractFixture
import org.quicklauncher.testing.fakes.FakeCancellationSignal

private val templateCapability = CapabilityId.parse("org.quicklauncher.capability/sample-template")

@RegisterDestinationTemplate(
    id = "org.quicklauncher.samples/template",
    contractMajor = 1,
    configTypeId = "org.quicklauncher.samples/template-config",
    displayName = "Sample destination template",
    description = "Skeletal Sample destination template contribution used by Phase 1 contract tests",
    providedCapabilities = ["org.quicklauncher.capability/sample-template"],
    settings = SampleSettings::class,
    codec = TemplateConfigurationCodec::class,
    contractTests = SampleTemplateContract::class,
    requiredContributions = ["org.quicklauncher.samples/layout", "org.quicklauncher.samples/block"],
    maximumBlocks = 1,
)
object SampleTemplate : DestinationTemplateContribution<SampleConfiguration> {
    override fun create(input: TemplateInput<SampleConfiguration>): TemplateResult {
        input.cancellation.ensureActive()
        if (!input.configuration.enabled || input.destinations.isEmpty() ||
            input.availableModuleIdentities.size < 2
        ) {
            return TemplateResult.Invalid(
                StableKey.parse("missing-input"),
                DisplayText.of("Template requires one destination and two module identities"),
            )
        }
        val scenario = input.destinations.first().name.value.substringBefore(' ')
        if (scenario !in setOf("normal", "large-text")) {
            return TemplateResult.Invalid(
                StableKey.parse("$scenario-state"),
                DisplayText.of("Template preview is in $scenario state"),
            )
        }
        return TemplateResult.Created(
            TemplatePlan(
                listOf(
                    PositionedDestinationDraft(
                        TemplateCoordinate(0, 0),
                        sampleDestinationDraft(input),
                        isStart = true,
                    ),
                ),
            ),
        )
    }
}

@ContractTestSpec(
    contributionId = "org.quicklauncher.samples/template",
    category = ContributionCategorySpec.DESTINATION_TEMPLATE,
    scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING,
        PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED,
        PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
    performanceHooks = [PerformanceMetricSpec.DRAFT_CREATION],
)
object SampleTemplateContract : DestinationTemplateContractDeclaration<SampleConfiguration> {
    override val contributionId = SampleIds.TEMPLATE
    override val target = SampleTemplate
    override val codec = TemplateConfigurationCodec
    override val descriptor = DestinationTemplateDescriptor(
        metadata = sampleMetadata(
            SampleIds.TEMPLATE,
            ContributionTypes.DESTINATION_TEMPLATE,
            codec.configType,
            "Sample destination template",
            setOf(templateCapability),
        ),
        requiredContributions = setOf(SampleIds.LAYOUT, SampleIds.BLOCK),
        maximumBlocks = 1,
    )
    override val configurationCases = sampleConfigurationCases(codec)
    override val fixtures = immutableSampleList(PreviewScenario.entries.map { scenario ->
        val key = scenario.name.lowercase().replace('_', '-')
        val input = TemplateInput(
            listOf(
                TemplateDestinationInput(
                    DestinationId.parse("org.quicklauncher.destination/$key"),
                    DisplayText.of("$key destination"),
                ),
            ),
            listOf(
                org.quicklauncher.contracts.contribution.ModuleDraftIdentity(
                    ModuleInstanceId.parse("org.quicklauncher.instance/$key-layout"),
                    ConfigurationDocumentId.parse("org.quicklauncher.configuration/$key-layout"),
                ),
                org.quicklauncher.contracts.contribution.ModuleDraftIdentity(
                    ModuleInstanceId.parse("org.quicklauncher.instance/$key-block"),
                    ConfigurationDocumentId.parse("org.quicklauncher.configuration/$key-block"),
                ),
            ),
            codec.default,
            FakeCancellationSignal(),
        )
        TemplateContractFixture(scenario, input, expectedTemplateResult(scenario, input))
    })
    override val scenarios = immutableSampleSet(PreviewScenario.entries)
    override val previewAccessibility = sampleAccessibilityFixtures()
    override val performanceChecks = immutableSampleList(listOf(
        samplePerformanceCheck(PerformanceMetric.DRAFT_CREATION, "Create a single destination draft") {
            target.create(fixtures.single { it.scenario == PreviewScenario.NORMAL }.input)
        },
    ))
    override val performanceHooks = immutableSampleList(performanceChecks.map { it.declaration })
}

private fun expectedTemplateResult(
    scenario: PreviewScenario,
    input: TemplateInput<SampleConfiguration>,
): TemplateResult = if (scenario in setOf(PreviewScenario.NORMAL, PreviewScenario.LARGE_TEXT)) {
    TemplateResult.Created(
        TemplatePlan(
            listOf(
                PositionedDestinationDraft(
                    TemplateCoordinate(0, 0),
                    sampleDestinationDraft(input),
                    isStart = true,
                ),
            ),
        ),
    )
} else {
    val key = scenario.name.lowercase().replace('_', '-')
    TemplateResult.Invalid(
        StableKey.parse("$key-state"),
        DisplayText.of("Template preview is in $key state"),
    )
}

private fun sampleDestinationDraft(input: TemplateInput<SampleConfiguration>): DestinationDraft {
    val destination = input.destinations.first()
    val layoutIdentity = input.availableModuleIdentities[0]
    val blockIdentity = input.availableModuleIdentities[1]
    val layout = ModuleDraft(
        layoutIdentity.instanceId,
        SampleIds.LAYOUT,
        layoutIdentity.configurationDocumentId,
        ConfigurationPipeline.defaultDocument(LayoutConfigurationCodec),
    )
    val block = ModuleDraft(
        blockIdentity.instanceId,
        SampleIds.BLOCK,
        blockIdentity.configurationDocumentId,
        ConfigurationPipeline.defaultDocument(BlockConfigurationCodec),
    )
    return DestinationDraft(
        destination.destinationId,
        destination.name,
        layout,
        listOf(block),
        listOf(
            PlacementDraft(
                parentInstanceId = layout.instanceId,
                parentSlotId = StableKey.parse("main"),
                childInstanceId = block.instanceId,
                index = 0,
                data = PlacementData(
                    schemaVersion = SchemaVersion.of(1),
                    encoded = EncodedPlacementData.of("{}"),
                ),
            ),
        ),
    )
}
