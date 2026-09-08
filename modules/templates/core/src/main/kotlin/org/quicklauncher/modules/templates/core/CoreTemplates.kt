package org.quicklauncher.modules.templates.core

import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.DestinationDraft
import org.quicklauncher.contracts.contribution.DestinationTemplateContractTestDeclaration
import org.quicklauncher.contracts.contribution.DestinationTemplateContribution
import org.quicklauncher.contracts.contribution.DisplayText
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.contribution.EncodedPlacementData
import org.quicklauncher.contracts.contribution.ModuleDraft
import org.quicklauncher.contracts.contribution.ModuleDraftIdentity
import org.quicklauncher.contracts.contribution.PlacementData
import org.quicklauncher.contracts.contribution.PlacementDraft
import org.quicklauncher.contracts.contribution.PositionedDestinationDraft
import org.quicklauncher.contracts.contribution.TemplateCoordinate
import org.quicklauncher.contracts.contribution.TemplateDestinationInput
import org.quicklauncher.contracts.contribution.TemplateInput
import org.quicklauncher.contracts.contribution.TemplatePlan
import org.quicklauncher.contracts.contribution.TemplateResult
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.ui.PerformanceHookDeclaration
import org.quicklauncher.contracts.ui.PerformanceMetric
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.registry.annotations.ConfigurationCodecSpec
import org.quicklauncher.registry.annotations.ContractTestSpec
import org.quicklauncher.registry.annotations.ContributionCategorySpec
import org.quicklauncher.registry.annotations.PerformanceMetricSpec
import org.quicklauncher.registry.annotations.PreviewScenarioSpec
import org.quicklauncher.registry.annotations.RegisterDestinationTemplate
import org.quicklauncher.registry.annotations.SettingKind
import org.quicklauncher.registry.annotations.SettingSpec
import org.quicklauncher.registry.annotations.SettingsSchemaSpec

data class PopulatedTemplateConfiguration(
    val includeClock: Boolean,
    val includeFavorites: Boolean,
)

data class BlankTemplateConfiguration(val columns: Int)

@SettingsSchemaSpec(
    fields = [
        SettingSpec(
            key = "include-clock",
            label = "Include clock and date",
            kind = SettingKind.BOOLEAN,
            defaultValue = "true",
        ),
        SettingSpec(
            key = "include-favorites",
            label = "Include favorites",
            kind = SettingKind.BOOLEAN,
            defaultValue = "true",
        ),
    ],
)
object PopulatedTemplateSettings

@SettingsSchemaSpec(
    fields = [
        SettingSpec(
            key = "columns",
            label = "Grid columns",
            kind = SettingKind.NUMBER,
            defaultValue = "4",
            minimum = 1,
            maximum = 12,
        ),
    ],
)
object BlankTemplateSettings

abstract class PopulatedTemplateCodec(private val typeId: String) :
    ConfigurationCodec<PopulatedTemplateConfiguration> {
    final override val configType = ConfigTypeId.parse(typeId)
    final override val currentSchemaVersion = SchemaVersion.of(1)
    final override val default = PopulatedTemplateConfiguration(
        includeClock = true,
        includeFavorites = true,
    )

    final override fun encode(value: PopulatedTemplateConfiguration): EncodedConfiguration =
        EncodedConfiguration.of(
            "{\"includeClock\":${value.includeClock}," +
                "\"includeFavorites\":${value.includeFavorites}}",
        )

    final override fun decode(encoded: EncodedConfiguration): CodecResult<PopulatedTemplateConfiguration> {
        val match = POPULATED_JSON.matchEntire(encoded.value)
            ?: return CodecResult.Failed("template configuration must use the registered JSON shape")
        return CodecResult.Decoded(
            PopulatedTemplateConfiguration(
                includeClock = match.groupValues[1].toBooleanStrict(),
                includeFavorites = match.groupValues[2].toBooleanStrict(),
            ),
        )
    }

    private companion object {
        val POPULATED_JSON = Regex(
            """\{"includeClock":(true|false),"includeFavorites":(true|false)}""",
        )
    }
}

@ConfigurationCodecSpec(configTypeId = CoreTemplateIds.MODULAR_CONFIG)
object ModularTemplateCodec : PopulatedTemplateCodec(CoreTemplateIds.MODULAR_CONFIG)

@ConfigurationCodecSpec(configTypeId = CoreTemplateIds.TRADITIONAL_CONFIG)
object TraditionalTemplateCodec : PopulatedTemplateCodec(CoreTemplateIds.TRADITIONAL_CONFIG)

@ConfigurationCodecSpec(configTypeId = CoreTemplateIds.BLANK_CONFIG)
object BlankTemplateCodec : ConfigurationCodec<BlankTemplateConfiguration> {
    override val configType = ConfigTypeId.parse(CoreTemplateIds.BLANK_CONFIG)
    override val currentSchemaVersion = SchemaVersion.of(1)
    override val default = BlankTemplateConfiguration(columns = 4)

    override fun encode(value: BlankTemplateConfiguration): EncodedConfiguration =
        EncodedConfiguration.of("{\"columns\":${value.columns}}")

    override fun decode(encoded: EncodedConfiguration): CodecResult<BlankTemplateConfiguration> {
        val columns = BLANK_JSON.matchEntire(encoded.value)?.groupValues?.get(1)?.toIntOrNull()
            ?: return CodecResult.Failed("blank template configuration must contain integer columns")
        return if (columns in 1..12) {
            CodecResult.Decoded(BlankTemplateConfiguration(columns))
        } else {
            CodecResult.Failed("columns must be between 1 and 12")
        }
    }

    private val BLANK_JSON = Regex("""\{"columns":(-?\d+)}""")
}

@RegisterDestinationTemplate(
    id = CoreTemplateIds.MODULAR,
    contractMajor = 1,
    configTypeId = CoreTemplateIds.MODULAR_CONFIG,
    displayName = "Modular",
    description = "Creates a center grid, an entry destination above, and alphabetical apps to the right",
    settings = PopulatedTemplateSettings::class,
    codec = ModularTemplateCodec::class,
    contractTests = ModularTemplateContract::class,
    requiredContributions = [
        CoreContributionIds.GRID_LAYOUT,
        CoreContributionIds.SINGLE_LAYOUT,
        CoreContributionIds.ALPHABETICAL_BLOCK,
        CoreContributionIds.APP_GRID_BLOCK,
        CoreContributionIds.FAVORITES_BLOCK,
        CoreContributionIds.CLOCK_BLOCK,
    ],
    maximumBlocks = 4,
)
object ModularTemplate : DestinationTemplateContribution<PopulatedTemplateConfiguration> {
    override fun create(input: TemplateInput<PopulatedTemplateConfiguration>): TemplateResult =
        createModular(input)
}

@RegisterDestinationTemplate(
    id = CoreTemplateIds.TRADITIONAL,
    contractMajor = 1,
    configTypeId = CoreTemplateIds.TRADITIONAL_CONFIG,
    displayName = "Traditional",
    description = "Creates a familiar grid start destination with an adjacent app grid",
    settings = PopulatedTemplateSettings::class,
    codec = TraditionalTemplateCodec::class,
    contractTests = TraditionalTemplateContract::class,
    requiredContributions = [
        CoreContributionIds.GRID_LAYOUT,
        CoreContributionIds.SINGLE_LAYOUT,
        CoreContributionIds.APP_GRID_BLOCK,
        CoreContributionIds.FAVORITES_BLOCK,
        CoreContributionIds.CLOCK_BLOCK,
    ],
    maximumBlocks = 3,
)
object TraditionalTemplate : DestinationTemplateContribution<PopulatedTemplateConfiguration> {
    override fun create(input: TemplateInput<PopulatedTemplateConfiguration>): TemplateResult =
        createTraditional(input)
}

@RegisterDestinationTemplate(
    id = CoreTemplateIds.BLANK,
    contractMajor = 1,
    configTypeId = CoreTemplateIds.BLANK_CONFIG,
    displayName = "Blank",
    description = "Creates one empty grid destination with host recovery routes still available",
    settings = BlankTemplateSettings::class,
    codec = BlankTemplateCodec::class,
    contractTests = BlankTemplateContract::class,
    requiredContributions = [CoreContributionIds.GRID_LAYOUT],
    maximumBlocks = 0,
)
object BlankTemplate : DestinationTemplateContribution<BlankTemplateConfiguration> {
    override fun create(input: TemplateInput<BlankTemplateConfiguration>): TemplateResult {
        input.cancellation.ensureActive()
        if (input.destinations.isEmpty() || input.availableModuleIdentities.isEmpty()) {
            return invalid("identity-pool", "Blank template requires one destination and one module identity")
        }
        val destination = input.destinations[0]
        val layout = module(
            input.availableModuleIdentities[0],
            CoreContributionIds.GRID_LAYOUT,
            CoreConfigurationIds.GRID,
            "{\"columns\":${input.configuration.columns}}",
        )
        return TemplateResult.Created(
            TemplatePlan(
                listOf(
                    positioned(destination, TemplateCoordinate(0, 0), true, layout),
                ),
            ),
        )
    }
}

private fun createModular(
    input: TemplateInput<PopulatedTemplateConfiguration>,
): TemplateResult {
    input.cancellation.ensureActive()
    val blockCount = 2 + input.configuration.optionalBlockCount
    if (input.destinations.size < 3 || input.availableModuleIdentities.size < 3 + blockCount) {
        return invalid(
            "identity-pool",
            "Modular template requires three destinations and ${3 + blockCount} module identities",
        )
    }
    val identities = IdentityCursor(input.availableModuleIdentities)
    val centerLayout = identities.module(
        CoreContributionIds.GRID_LAYOUT,
        CoreConfigurationIds.GRID,
        CoreConfigurationDefaults.GRID,
    )
    val centerBlocks = populatedCenterBlocks(input.configuration, identities)
    val entryLayout = identities.module(
        CoreContributionIds.SINGLE_LAYOUT,
        CoreConfigurationIds.SINGLE,
        CoreConfigurationDefaults.SINGLE,
    )
    val entryBlock = identities.module(
        CoreContributionIds.APP_GRID_BLOCK,
        CoreConfigurationIds.APP_GRID,
        CoreConfigurationDefaults.APP_GRID,
    )
    val appsLayout = identities.module(
        CoreContributionIds.SINGLE_LAYOUT,
        CoreConfigurationIds.SINGLE,
        CoreConfigurationDefaults.SINGLE,
    )
    val appsBlock = identities.module(
        CoreContributionIds.ALPHABETICAL_BLOCK,
        CoreConfigurationIds.ALPHABETICAL,
        CoreConfigurationDefaults.ALPHABETICAL,
    )
    input.cancellation.ensureActive()
    return TemplateResult.Created(
        TemplatePlan(
            listOf(
                positioned(
                    input.destinations[0],
                    TemplateCoordinate(0, 0),
                    true,
                    centerLayout,
                    centerBlocks,
                ),
                positioned(
                    input.destinations[1],
                    TemplateCoordinate(0, -1),
                    false,
                    entryLayout,
                    listOf(entryBlock),
                ),
                positioned(
                    input.destinations[2],
                    TemplateCoordinate(1, 0),
                    false,
                    appsLayout,
                    listOf(appsBlock),
                ),
            ),
        ),
    )
}

private fun createTraditional(
    input: TemplateInput<PopulatedTemplateConfiguration>,
): TemplateResult {
    input.cancellation.ensureActive()
    val blockCount = 1 + input.configuration.optionalBlockCount
    if (input.destinations.size < 2 || input.availableModuleIdentities.size < 2 + blockCount) {
        return invalid(
            "identity-pool",
            "Traditional template requires two destinations and ${2 + blockCount} module identities",
        )
    }
    val identities = IdentityCursor(input.availableModuleIdentities)
    val centerLayout = identities.module(
        CoreContributionIds.GRID_LAYOUT,
        CoreConfigurationIds.GRID,
        CoreConfigurationDefaults.GRID,
    )
    val centerBlocks = populatedCenterBlocks(input.configuration, identities)
    val appsLayout = identities.module(
        CoreContributionIds.SINGLE_LAYOUT,
        CoreConfigurationIds.SINGLE,
        CoreConfigurationDefaults.SINGLE,
    )
    val appsBlock = identities.module(
        CoreContributionIds.APP_GRID_BLOCK,
        CoreConfigurationIds.APP_GRID,
        CoreConfigurationDefaults.APP_GRID,
    )
    input.cancellation.ensureActive()
    return TemplateResult.Created(
        TemplatePlan(
            listOf(
                positioned(
                    input.destinations[0],
                    TemplateCoordinate(0, 0),
                    true,
                    centerLayout,
                    centerBlocks,
                ),
                positioned(
                    input.destinations[1],
                    TemplateCoordinate(1, 0),
                    false,
                    appsLayout,
                    listOf(appsBlock),
                ),
            ),
        ),
    )
}

private val PopulatedTemplateConfiguration.optionalBlockCount: Int
    get() = (if (includeFavorites) 1 else 0) + (if (includeClock) 1 else 0)

private fun populatedCenterBlocks(
    configuration: PopulatedTemplateConfiguration,
    identities: IdentityCursor,
): List<ModuleDraft> = buildList {
    if (configuration.includeFavorites) {
        add(
            identities.module(
                CoreContributionIds.FAVORITES_BLOCK,
                CoreConfigurationIds.FAVORITES,
                CoreConfigurationDefaults.FAVORITES,
            ),
        )
    }
    if (configuration.includeClock) {
        add(
            identities.module(
                CoreContributionIds.CLOCK_BLOCK,
                CoreConfigurationIds.CLOCK,
                CoreConfigurationDefaults.CLOCK,
            ),
        )
    }
}

private class IdentityCursor(private val identities: List<ModuleDraftIdentity>) {
    private var index = 0

    fun module(contributionId: String, configTypeId: String, encoded: String): ModuleDraft =
        module(identities[index++], contributionId, configTypeId, encoded)
}

private fun module(
    identity: ModuleDraftIdentity,
    contributionId: String,
    configTypeId: String,
    encoded: String,
): ModuleDraft = ModuleDraft(
    identity.instanceId,
    ContributionId.parse(contributionId),
    identity.configurationDocumentId,
    ConfigurationDocument(
        ConfigTypeId.parse(configTypeId),
        SchemaVersion.of(1),
        EncodedConfiguration.of(encoded),
    ),
)

private fun positioned(
    input: TemplateDestinationInput,
    coordinate: TemplateCoordinate,
    isStart: Boolean,
    layout: ModuleDraft,
    blocks: List<ModuleDraft> = emptyList(),
): PositionedDestinationDraft = PositionedDestinationDraft(
    coordinate,
    DestinationDraft(
        input.destinationId,
        input.name,
        layout,
        blocks,
        blocks.mapIndexed { index, block ->
            PlacementDraft(
                parentInstanceId = layout.instanceId,
                parentSlotId = StableKey.parse("content"),
                childInstanceId = block.instanceId,
                index = index,
                data = PlacementData(
                    SchemaVersion.of(1),
                    EncodedPlacementData.of(
                        if (layout.contributionId == ContributionId.parse(CoreContributionIds.GRID_LAYOUT)) {
                            "{\"column\":$index,\"row\":0,\"columnSpan\":1,\"rowSpan\":1}"
                        } else {
                            "{}"
                        },
                    ),
                ),
            )
        },
    ),
    isStart,
)

private fun invalid(code: String, message: String) = TemplateResult.Invalid(
    StableKey.parse(code),
    DisplayText.of(message),
)

abstract class CoreTemplateContract<C : Any>(id: String) :
    DestinationTemplateContractTestDeclaration<C> {
    final override val contributionId = ContributionId.parse(id)
    final override val scenarios = PreviewScenario.entries.toSet()
    final override val performanceHooks = listOf(
        PerformanceHookDeclaration(
            PerformanceMetric.DRAFT_CREATION,
            "Create and validate the complete destination plan",
        ),
    )
}

@ContractTestSpec(
    contributionId = CoreTemplateIds.MODULAR,
    category = ContributionCategorySpec.DESTINATION_TEMPLATE,
    scenarios = [
        PreviewScenarioSpec.EMPTY,
        PreviewScenarioSpec.NORMAL,
        PreviewScenarioSpec.LOADING,
        PreviewScenarioSpec.PERMISSION_DENIED,
        PreviewScenarioSpec.PROFILE_LOCKED,
        PreviewScenarioSpec.LARGE_TEXT,
        PreviewScenarioSpec.ERROR,
    ],
    performanceHooks = [PerformanceMetricSpec.DRAFT_CREATION],
)
object ModularTemplateContract :
    CoreTemplateContract<PopulatedTemplateConfiguration>(CoreTemplateIds.MODULAR)

@ContractTestSpec(
    contributionId = CoreTemplateIds.TRADITIONAL,
    category = ContributionCategorySpec.DESTINATION_TEMPLATE,
    scenarios = [
        PreviewScenarioSpec.EMPTY,
        PreviewScenarioSpec.NORMAL,
        PreviewScenarioSpec.LOADING,
        PreviewScenarioSpec.PERMISSION_DENIED,
        PreviewScenarioSpec.PROFILE_LOCKED,
        PreviewScenarioSpec.LARGE_TEXT,
        PreviewScenarioSpec.ERROR,
    ],
    performanceHooks = [PerformanceMetricSpec.DRAFT_CREATION],
)
object TraditionalTemplateContract :
    CoreTemplateContract<PopulatedTemplateConfiguration>(CoreTemplateIds.TRADITIONAL)

@ContractTestSpec(
    contributionId = CoreTemplateIds.BLANK,
    category = ContributionCategorySpec.DESTINATION_TEMPLATE,
    scenarios = [
        PreviewScenarioSpec.EMPTY,
        PreviewScenarioSpec.NORMAL,
        PreviewScenarioSpec.LOADING,
        PreviewScenarioSpec.PERMISSION_DENIED,
        PreviewScenarioSpec.PROFILE_LOCKED,
        PreviewScenarioSpec.LARGE_TEXT,
        PreviewScenarioSpec.ERROR,
    ],
    performanceHooks = [PerformanceMetricSpec.DRAFT_CREATION],
)
object BlankTemplateContract : CoreTemplateContract<BlankTemplateConfiguration>(CoreTemplateIds.BLANK)

object CoreTemplateIds {
    const val MODULAR = "org.quicklauncher.template/modular"
    const val MODULAR_CONFIG = "org.quicklauncher.template/modular-config"
    const val TRADITIONAL = "org.quicklauncher.template/traditional"
    const val TRADITIONAL_CONFIG = "org.quicklauncher.template/traditional-config"
    const val BLANK = "org.quicklauncher.template/blank"
    const val BLANK_CONFIG = "org.quicklauncher.template/blank-config"
}

private object CoreContributionIds {
    const val GRID_LAYOUT = "org.quicklauncher.layout/grid"
    const val SINGLE_LAYOUT = "org.quicklauncher.layout/single-block"
    const val ALPHABETICAL_BLOCK = "org.quicklauncher.block/alphabetical-apps"
    const val APP_GRID_BLOCK = "org.quicklauncher.block/app-grid"
    const val FAVORITES_BLOCK = "org.quicklauncher.block/favorites"
    const val CLOCK_BLOCK = "org.quicklauncher.block/clock-date"
}

private object CoreConfigurationIds {
    const val GRID = "org.quicklauncher.layout/grid-config"
    const val SINGLE = "org.quicklauncher.layout/single-block-config"
    const val ALPHABETICAL = "org.quicklauncher.block/alphabetical-apps-config"
    const val APP_GRID = "org.quicklauncher.block/app-grid-config"
    const val FAVORITES = "org.quicklauncher.block/favorites-config"
    const val CLOCK = "org.quicklauncher.block/clock-date-config"
}

private object CoreConfigurationDefaults {
    const val GRID = "{\"columns\":4}"
    const val SINGLE = "{}"
    const val ALPHABETICAL = "{\"showSectionHeaders\":true}"
    const val APP_GRID = "{\"columns\":4}"
    const val FAVORITES = "{\"maximumItems\":5}"
    const val CLOCK = "{\"showDate\":true}"
}
