package org.quicklauncher.registry.ksp

import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ContributionTypeId

enum class RegistrationKind(
    val annotationType: String,
    val persistedTypeId: String,
    val contractType: String,
    val contractTestsType: String,
    val executableContractTestsType: String,
    val registeredType: String,
    val descriptorType: String,
    val requiredPerformanceMetrics: Set<String>,
) {
    LAYOUT(
        "org.quicklauncher.registry.annotations.RegisterLayout",
        "org.quicklauncher.contribution/layout",
        "org.quicklauncher.contracts.contribution.LayoutContribution",
        "org.quicklauncher.contracts.contribution.LayoutContractTestDeclaration",
        "org.quicklauncher.testing.contracts.LayoutContractDeclaration",
        "RegisteredLayout",
        "LayoutDescriptor",
        setOf("FIRST_RENDER", "ACTION_DISPATCH", "DISPOSAL"),
    ),
    BLOCK(
        "org.quicklauncher.registry.annotations.RegisterBlock",
        "org.quicklauncher.contribution/block",
        "org.quicklauncher.contracts.contribution.BlockContribution",
        "org.quicklauncher.contracts.contribution.BlockContractTestDeclaration",
        "org.quicklauncher.testing.contracts.BlockContractDeclaration",
        "RegisteredBlock",
        "BlockDescriptor",
        setOf("FIRST_RENDER", "ACTION_DISPATCH", "DISPOSAL"),
    ),
    SEARCH_PROVIDER(
        "org.quicklauncher.registry.annotations.RegisterSearchProvider",
        "org.quicklauncher.contribution/search-provider",
        "org.quicklauncher.contracts.contribution.SearchProviderContribution",
        "org.quicklauncher.contracts.contribution.SearchProviderContractTestDeclaration",
        "org.quicklauncher.testing.contracts.SearchProviderContractDeclaration",
        "RegisteredSearchProvider",
        "SearchProviderDescriptor",
        setOf("FIRST_RESULT", "QUERY_REPLACEMENT", "DISPOSAL"),
    ),
    LAUNCHER_COMMAND(
        "org.quicklauncher.registry.annotations.RegisterLauncherCommand",
        "org.quicklauncher.contribution/launcher-command",
        "org.quicklauncher.contracts.contribution.LauncherCommandContribution",
        "org.quicklauncher.contracts.contribution.LauncherCommandContractTestDeclaration",
        "org.quicklauncher.testing.contracts.LauncherCommandContractDeclaration",
        "RegisteredLauncherCommand",
        "LauncherCommandDescriptor",
        setOf("COMMAND_EXECUTION"),
    ),
    DESTINATION_TEMPLATE(
        "org.quicklauncher.registry.annotations.RegisterDestinationTemplate",
        "org.quicklauncher.contribution/destination-template",
        "org.quicklauncher.contracts.contribution.DestinationTemplateContribution",
        "org.quicklauncher.contracts.contribution.DestinationTemplateContractTestDeclaration",
        "org.quicklauncher.testing.contracts.DestinationTemplateContractDeclaration",
        "RegisteredDestinationTemplate",
        "DestinationTemplateDescriptor",
        setOf("DRAFT_CREATION"),
    ),
}

data class CategoryDefinition(
    val persistedTypeId: String,
    val contractType: String,
    val supportedMajors: Set<Int>,
    val registrationKind: RegistrationKind,
    val declaredBy: String,
)

object CategoryCatalog {
    fun firstRelease(): List<CategoryDefinition> = RegistrationKind.entries
        .map { kind ->
            CategoryDefinition(
                persistedTypeId = kind.persistedTypeId,
                contractType = kind.contractType,
                supportedMajors = setOf(1),
                registrationKind = kind,
                declaredBy = "Quicklauncher first-release catalog",
            )
        }
        .sortedBy { it.persistedTypeId }
}

data class RawSlot(
    val id: String,
    val type: String,
    val acceptedBlocks: List<String>,
    val requiredCapabilities: List<String>,
    val maximumChildren: Int,
    val allowedScrollAxes: List<String>,
)

data class RawSetting(
    val key: String,
    val label: String,
    val kind: String,
    val defaultValue: String,
    val minimum: Int,
    val maximum: Int,
    val options: List<String>,
    val allowedFontRoles: List<String>,
    val allowMultiple: Boolean,
    val enabledWhen: String,
)

data class RawSettings(
    val declarationName: String,
    val hasSchemaAnnotation: Boolean,
    val fields: List<RawSetting>,
)

sealed interface RawSpecificDescriptor {
    data class Layout(val slots: List<RawSlot>) : RawSpecificDescriptor

    data class Block(
        val compatibleSlotTypes: List<String>,
        val childSlots: List<RawSlot>,
        val occupiedScrollAxes: List<String> = emptyList(),
    ) : RawSpecificDescriptor

    data class SearchProvider(
        val resultKinds: List<String>,
        val minimumQueryLength: Int,
    ) : RawSpecificDescriptor

    data class LauncherCommand(
        val contexts: List<String>,
        val resultKinds: List<String>,
    ) : RawSpecificDescriptor

    data class DestinationTemplate(
        val requiredContributions: List<String>,
        val maximumBlocks: Int,
    ) : RawSpecificDescriptor
}

data class RawRegistration(
    val targetName: String,
    val kind: RegistrationKind,
    val id: String,
    val contractMajor: Int,
    val configTypeId: String,
    val displayName: String,
    val description: String,
    val providedCapabilities: List<String>,
    val requiredCapabilities: List<String>,
    val settings: RawSettings?,
    val codecName: String,
    val contractTestsName: String,
    val targetImplementsContract: Boolean,
    val targetIsObject: Boolean,
    val codecImplementsContract: Boolean,
    val codecIsObject: Boolean,
    val testsImplementContract: Boolean,
    val testsAreObject: Boolean,
    val specific: RawSpecificDescriptor,
    val testsImplementCategoryContract: Boolean = false,
    val targetConfigurationType: String? = null,
    val codecConfigurationType: String? = null,
    val codecManifestConfigTypeId: String? = null,
    val codecHasManifest: Boolean = false,
    val contractTestsConfigurationType: String? = null,
    val contractTestsCategory: String? = null,
    val contractTestsContributionId: String? = null,
    val contractTestsScenarios: List<String> = emptyList(),
    val contractTestsPerformanceMetrics: List<String> = emptyList(),
    val contractTestsHaveManifest: Boolean = false,
)

object MissingNames {
    const val CODEC = "org.quicklauncher.registry.annotations.MissingConfigurationCodec"
    const val CONTRACT_TESTS = "org.quicklauncher.registry.annotations.MissingContractTests"
    const val SETTINGS = "org.quicklauncher.registry.annotations.NoSettingsSchema"
}

data class RegistryValidationIssue(
    val code: String,
    val targetName: String,
    val contributionId: String?,
    val message: String,
)

data class ValidatedRegistration(
    val raw: RawRegistration,
    val id: ContributionId,
    val configTypeId: ConfigTypeId,
    val categoryTypeId: ContributionTypeId,
    val providedCapabilities: Set<CapabilityId>,
    val requiredCapabilities: Set<CapabilityId>,
)

sealed interface RegistryValidationResult {
    data class Valid(
        val categories: List<CategoryDefinition>,
        val registrations: List<ValidatedRegistration>,
    ) : RegistryValidationResult

    data class Invalid(val issues: List<RegistryValidationIssue>) : RegistryValidationResult
}
