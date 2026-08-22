package org.quicklauncher.testing.samples

import kotlinx.coroutines.runBlocking
import org.quicklauncher.contracts.contribution.CommandContext
import org.quicklauncher.contracts.contribution.CommandInput
import org.quicklauncher.contracts.contribution.CommandParameters
import org.quicklauncher.contracts.contribution.CommandResult
import org.quicklauncher.contracts.contribution.CommandResultKind
import org.quicklauncher.contracts.contribution.ContributionTypes
import org.quicklauncher.contracts.contribution.DisplayText
import org.quicklauncher.contracts.contribution.LauncherCommandAction
import org.quicklauncher.contracts.contribution.LauncherCommandContribution
import org.quicklauncher.contracts.contribution.LauncherCommandDescriptor
import org.quicklauncher.contracts.contribution.SettingValue
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.CommandInvocationId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.ui.PerformanceMetric
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.registry.annotations.CommandContextSpec
import org.quicklauncher.registry.annotations.CommandResultKindSpec
import org.quicklauncher.registry.annotations.RegisterLauncherCommand
import org.quicklauncher.registry.annotations.ContractTestSpec
import org.quicklauncher.registry.annotations.ContributionCategorySpec
import org.quicklauncher.registry.annotations.PerformanceMetricSpec
import org.quicklauncher.registry.annotations.PreviewScenarioSpec
import org.quicklauncher.testing.contracts.CommandContractFixture
import org.quicklauncher.testing.contracts.LauncherCommandContractDeclaration
import org.quicklauncher.testing.fakes.FakeCancellationSignal

private val commandCapability = CapabilityId.parse("org.quicklauncher.capability/sample-command")
private val scenarioParameter = StableKey.parse("scenario")

@RegisterLauncherCommand(
    id = "org.quicklauncher.samples/command",
    contractMajor = 1,
    configTypeId = "org.quicklauncher.samples/command-config",
    displayName = "Sample command",
    description = "Skeletal Sample command contribution used by Phase 1 contract tests",
    providedCapabilities = ["org.quicklauncher.capability/sample-command"],
    settings = SampleSettings::class,
    codec = CommandConfigurationCodec::class,
    contractTests = SampleCommandContract::class,
    contexts = [CommandContextSpec.SEARCH, CommandContextSpec.GESTURE],
    resultKinds = [CommandResultKindSpec.HOST_ACTION, CommandResultKindSpec.INFORMATION],
)
object SampleCommand : LauncherCommandContribution<SampleConfiguration> {
    override suspend fun execute(input: CommandInput<SampleConfiguration>): CommandResult {
        input.cancellation.ensureActive()
        val scenario = (input.parameters.values[scenarioParameter] as? SettingValue.ChoiceValue)?.value?.value
        return when (scenario) {
            "empty" -> CommandResult.Unavailable(DisplayText.of("No command action is available"))
            "normal" -> CommandResult.Succeeded(LauncherCommandAction.OpenLauncherSettings)
            "loading" -> CommandResult.Unavailable(DisplayText.of("Command is still loading"))
            "permission-denied" -> CommandResult.MissingAccess(commandCapability)
            "profile-locked" -> CommandResult.Unavailable(DisplayText.of("Profile is locked"))
            "large-text" -> CommandResult.Succeeded(null)
            "cancelled" -> CommandResult.Cancelled
            else -> CommandResult.RecoverableFailure(
                StableKey.parse("sample-error"),
                DisplayText.of("Sample command failed"),
            )
        }
    }
}

@ContractTestSpec(
    contributionId = "org.quicklauncher.samples/command",
    category = ContributionCategorySpec.LAUNCHER_COMMAND,
    scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING,
        PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED,
        PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
    performanceHooks = [PerformanceMetricSpec.COMMAND_EXECUTION],
)
object SampleCommandContract : LauncherCommandContractDeclaration<SampleConfiguration> {
    override val contributionId = SampleIds.COMMAND
    override val target = SampleCommand
    override val codec = CommandConfigurationCodec
    override val descriptor = LauncherCommandDescriptor(
        metadata = sampleMetadata(
            SampleIds.COMMAND,
            ContributionTypes.LAUNCHER_COMMAND,
            codec.configType,
            "Sample command",
            setOf(commandCapability),
        ),
        contexts = setOf(CommandContext.SEARCH, CommandContext.GESTURE),
        resultKinds = setOf(CommandResultKind.HOST_ACTION, CommandResultKind.INFORMATION),
    )
    override val configurationCases = sampleConfigurationCases(codec)
    override val fixtures = immutableSampleList(
        PreviewScenario.entries.map { scenario ->
            val key = scenario.name.lowercase().replace('_', '-')
            val input = commandInput(key)
            CommandContractFixture(scenario, input, expectedCommandResult(scenario))
        } + CommandContractFixture(
            PreviewScenario.ERROR,
            commandInput("cancelled"),
            CommandResult.Cancelled,
        ),
    )
    override val scenarios = immutableSampleSet(PreviewScenario.entries)
    override val previewAccessibility = sampleAccessibilityFixtures()
    override val performanceChecks = immutableSampleList(listOf(
        samplePerformanceCheck(PerformanceMetric.COMMAND_EXECUTION, "Execute a typed sample command") {
            runBlocking { target.execute(fixtures.single { it.scenario == PreviewScenario.NORMAL }.input) }
        },
    ))
    override val performanceHooks = immutableSampleList(performanceChecks.map { it.declaration })
}

private fun commandInput(key: String): CommandInput<SampleConfiguration> = CommandInput(
    CommandInvocationId.parse("org.quicklauncher.invocation/$key"),
    CommandConfigurationCodec.default,
    CommandParameters(mapOf(scenarioParameter to SettingValue.ChoiceValue(StableKey.parse(key)))),
    FakeCancellationSignal(),
)

private fun expectedCommandResult(scenario: PreviewScenario): CommandResult = when (scenario) {
    PreviewScenario.EMPTY -> CommandResult.Unavailable(DisplayText.of("No command action is available"))
    PreviewScenario.NORMAL -> CommandResult.Succeeded(LauncherCommandAction.OpenLauncherSettings)
    PreviewScenario.LOADING -> CommandResult.Unavailable(DisplayText.of("Command is still loading"))
    PreviewScenario.PERMISSION_DENIED -> CommandResult.MissingAccess(commandCapability)
    PreviewScenario.PROFILE_LOCKED -> CommandResult.Unavailable(DisplayText.of("Profile is locked"))
    PreviewScenario.LARGE_TEXT -> CommandResult.Succeeded(null)
    PreviewScenario.ERROR -> CommandResult.RecoverableFailure(
        StableKey.parse("sample-error"),
        DisplayText.of("Sample command failed"),
    )
}
