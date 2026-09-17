package org.quicklauncher.modules.command.core

import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.CommandInput
import org.quicklauncher.contracts.contribution.CommandResult
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.LauncherCommandAction
import org.quicklauncher.contracts.contribution.LauncherCommandContribution
import org.quicklauncher.contracts.contribution.LauncherCommandContractTestDeclaration
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.ui.PerformanceHookDeclaration
import org.quicklauncher.contracts.ui.PerformanceMetric
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.registry.annotations.CommandContextSpec
import org.quicklauncher.registry.annotations.CommandResultKindSpec
import org.quicklauncher.registry.annotations.ConfigurationCodecSpec
import org.quicklauncher.registry.annotations.ContractTestSpec
import org.quicklauncher.registry.annotations.ContributionCategorySpec
import org.quicklauncher.registry.annotations.PerformanceMetricSpec
import org.quicklauncher.registry.annotations.PreviewScenarioSpec
import org.quicklauncher.registry.annotations.RegisterLauncherCommand

data object CoreCommandConfiguration

abstract class CoreCommandCodec(configTypeId: String) : ConfigurationCodec<CoreCommandConfiguration> {
    final override val configType = ConfigTypeId.parse(configTypeId)
    final override val currentSchemaVersion = SchemaVersion.of(1)
    final override val default = CoreCommandConfiguration
    final override fun encode(value: CoreCommandConfiguration) = EncodedConfiguration.of("{}")
    final override fun decode(encoded: EncodedConfiguration): CodecResult<CoreCommandConfiguration> =
        if (encoded.value == "{}") CodecResult.Decoded(default) else CodecResult.Failed("Command configuration is invalid")
}

@ConfigurationCodecSpec(configTypeId = CoreCommandIds.OPEN_SETTINGS_CONFIG)
object OpenSettingsCommandCodec : CoreCommandCodec(CoreCommandIds.OPEN_SETTINGS_CONFIG)

@ConfigurationCodecSpec(configTypeId = CoreCommandIds.OPEN_SEARCH_CONFIG)
object OpenSearchCommandCodec : CoreCommandCodec(CoreCommandIds.OPEN_SEARCH_CONFIG)

@RegisterLauncherCommand(
    id = CoreCommandIds.OPEN_SETTINGS,
    contractMajor = 1,
    configTypeId = CoreCommandIds.OPEN_SETTINGS_CONFIG,
    displayName = "Open launcher settings",
    description = "Opens host-owned launcher settings",
    codec = OpenSettingsCommandCodec::class,
    contractTests = OpenSettingsCommandContract::class,
    contexts = [CommandContextSpec.SEARCH, CommandContextSpec.RECOVERY, CommandContextSpec.SETTINGS],
    resultKinds = [CommandResultKindSpec.HOST_ACTION],
)
object OpenSettingsCommand : LauncherCommandContribution<CoreCommandConfiguration> {
    override suspend fun execute(input: CommandInput<CoreCommandConfiguration>): CommandResult {
        input.cancellation.ensureActive()
        require(input.parameters.values.isEmpty()) { "Open settings command accepts no parameters" }
        return CommandResult.Succeeded(LauncherCommandAction.OpenLauncherSettings)
    }
}

@RegisterLauncherCommand(
    id = CoreCommandIds.OPEN_SEARCH,
    contractMajor = 1,
    configTypeId = CoreCommandIds.OPEN_SEARCH_CONFIG,
    displayName = "Open search",
    description = "Opens the replaceable host search presentation",
    codec = OpenSearchCommandCodec::class,
    contractTests = OpenSearchCommandContract::class,
    contexts = [CommandContextSpec.GESTURE, CommandContextSpec.RECOVERY],
    resultKinds = [CommandResultKindSpec.HOST_ACTION],
)
object OpenSearchCommand : LauncherCommandContribution<CoreCommandConfiguration> {
    override suspend fun execute(input: CommandInput<CoreCommandConfiguration>): CommandResult {
        input.cancellation.ensureActive()
        require(input.parameters.values.isEmpty()) { "Open search command accepts no parameters" }
        return CommandResult.Succeeded(LauncherCommandAction.OpenSearch)
    }
}

abstract class CoreCommandContract(id: String) :
    LauncherCommandContractTestDeclaration<CoreCommandConfiguration> {
    final override val contributionId = ContributionId.parse(id)
    final override val scenarios = PreviewScenario.entries.toSet()
    final override val performanceHooks = listOf(
        PerformanceHookDeclaration(PerformanceMetric.COMMAND_EXECUTION, "Execute a typed host command"),
    )
}

@ContractTestSpec(
    contributionId = CoreCommandIds.OPEN_SETTINGS,
    category = ContributionCategorySpec.LAUNCHER_COMMAND,
    scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
    performanceHooks = [PerformanceMetricSpec.COMMAND_EXECUTION],
)
object OpenSettingsCommandContract : CoreCommandContract(CoreCommandIds.OPEN_SETTINGS)

@ContractTestSpec(
    contributionId = CoreCommandIds.OPEN_SEARCH,
    category = ContributionCategorySpec.LAUNCHER_COMMAND,
    scenarios = [PreviewScenarioSpec.EMPTY, PreviewScenarioSpec.NORMAL, PreviewScenarioSpec.LOADING, PreviewScenarioSpec.PERMISSION_DENIED, PreviewScenarioSpec.PROFILE_LOCKED, PreviewScenarioSpec.LARGE_TEXT, PreviewScenarioSpec.ERROR],
    performanceHooks = [PerformanceMetricSpec.COMMAND_EXECUTION],
)
object OpenSearchCommandContract : CoreCommandContract(CoreCommandIds.OPEN_SEARCH)

object CoreCommandIds {
    const val OPEN_SETTINGS = "org.quicklauncher.command/open-settings"
    const val OPEN_SEARCH = "org.quicklauncher.command/open-search"
    const val OPEN_SETTINGS_CONFIG = "org.quicklauncher.command/open-settings-config"
    const val OPEN_SEARCH_CONFIG = "org.quicklauncher.command/open-search-config"
}
