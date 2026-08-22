package org.quicklauncher.registry.annotations

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RegisterLayout(
    val id: String,
    val contractMajor: Int,
    val configTypeId: String,
    val displayName: String,
    val description: String,
    val providedCapabilities: Array<String> = [],
    val requiredCapabilities: Array<String> = [],
    val settings: KClass<*> = NoSettingsSchema::class,
    val codec: KClass<*> = MissingConfigurationCodec::class,
    val contractTests: KClass<*> = MissingContractTests::class,
    val slots: Array<SlotSpec> = [],
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RegisterBlock(
    val id: String,
    val contractMajor: Int,
    val configTypeId: String,
    val displayName: String,
    val description: String,
    val providedCapabilities: Array<String> = [],
    val requiredCapabilities: Array<String> = [],
    val settings: KClass<*> = NoSettingsSchema::class,
    val codec: KClass<*> = MissingConfigurationCodec::class,
    val contractTests: KClass<*> = MissingContractTests::class,
    val compatibleSlotTypes: Array<String>,
    val childSlots: Array<SlotSpec> = [],
    val occupiedScrollAxes: Array<ScrollAxisSpec> = [],
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RegisterSearchProvider(
    val id: String,
    val contractMajor: Int,
    val configTypeId: String,
    val displayName: String,
    val description: String,
    val providedCapabilities: Array<String> = [],
    val requiredCapabilities: Array<String> = [],
    val settings: KClass<*> = NoSettingsSchema::class,
    val codec: KClass<*> = MissingConfigurationCodec::class,
    val contractTests: KClass<*> = MissingContractTests::class,
    val resultKinds: Array<SearchResultKindSpec>,
    val minimumQueryLength: Int = 0,
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RegisterLauncherCommand(
    val id: String,
    val contractMajor: Int,
    val configTypeId: String,
    val displayName: String,
    val description: String,
    val providedCapabilities: Array<String> = [],
    val requiredCapabilities: Array<String> = [],
    val settings: KClass<*> = NoSettingsSchema::class,
    val codec: KClass<*> = MissingConfigurationCodec::class,
    val contractTests: KClass<*> = MissingContractTests::class,
    val contexts: Array<CommandContextSpec>,
    val resultKinds: Array<CommandResultKindSpec>,
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RegisterDestinationTemplate(
    val id: String,
    val contractMajor: Int,
    val configTypeId: String,
    val displayName: String,
    val description: String,
    val providedCapabilities: Array<String> = [],
    val requiredCapabilities: Array<String> = [],
    val settings: KClass<*> = NoSettingsSchema::class,
    val codec: KClass<*> = MissingConfigurationCodec::class,
    val contractTests: KClass<*> = MissingContractTests::class,
    val requiredContributions: Array<String> = [],
    val maximumBlocks: Int = 0,
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class ConfigurationCodecSpec(
    val configTypeId: String,
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class ContractTestSpec(
    val contributionId: String,
    val category: ContributionCategorySpec,
    val scenarios: Array<PreviewScenarioSpec> = [],
    val performanceHooks: Array<PerformanceMetricSpec> = [],
)

enum class ContributionCategorySpec {
    LAYOUT,
    BLOCK,
    SEARCH_PROVIDER,
    LAUNCHER_COMMAND,
    DESTINATION_TEMPLATE,
}

enum class PreviewScenarioSpec {
    EMPTY,
    NORMAL,
    LOADING,
    PERMISSION_DENIED,
    PROFILE_LOCKED,
    LARGE_TEXT,
    ERROR,
}

enum class PerformanceMetricSpec {
    FIRST_RENDER,
    ACTION_DISPATCH,
    DISPOSAL,
    FIRST_RESULT,
    QUERY_REPLACEMENT,
    COMMAND_EXECUTION,
    DRAFT_CREATION,
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class SettingsSchemaSpec(
    val fields: Array<SettingSpec>,
)

@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class SlotSpec(
    val id: String,
    val type: String,
    val acceptedBlocks: Array<String> = [],
    val requiredCapabilities: Array<String> = [],
    val maximumChildren: Int = 1,
    val allowedScrollAxes: Array<ScrollAxisSpec> = [],
)

@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class SettingSpec(
    val key: String,
    val label: String,
    val kind: SettingKind,
    val defaultValue: String = "",
    val minimum: Int = 0,
    val maximum: Int = 0,
    val options: Array<String> = [],
    val allowedFontRoles: Array<FontRoleSpec> = [],
    val allowMultiple: Boolean = false,
    val enabledWhen: String = "",
)

enum class SettingKind {
    BOOLEAN,
    CHOICE,
    NUMBER,
    DIMENSION,
    COLOR,
    FONT,
    APP_SELECTOR,
}

enum class FontRoleSpec {
    DISPLAY,
    HEADLINE,
    TITLE,
    BODY,
    LABEL,
}

enum class ScrollAxisSpec {
    HORIZONTAL,
    VERTICAL,
}

enum class SearchResultKindSpec {
    APP,
    SHORTCUT,
    CONTACT,
    FILE,
    SETTING,
    WEB,
    COMMAND,
    INFORMATION,
}

enum class CommandContextSpec {
    SEARCH,
    GESTURE,
    ITEM_ACTIONS,
    RECOVERY,
    SETTINGS,
}

enum class CommandResultKindSpec {
    HOST_ACTION,
    INFORMATION,
}

class NoSettingsSchema private constructor()
class MissingConfigurationCodec private constructor()
class MissingContractTests private constructor()
