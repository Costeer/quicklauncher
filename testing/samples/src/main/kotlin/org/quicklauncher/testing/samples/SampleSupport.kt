package org.quicklauncher.testing.samples

import java.util.Collections
import org.quicklauncher.contracts.contribution.BooleanSetting
import org.quicklauncher.contracts.contribution.ContributionMetadata
import org.quicklauncher.contracts.contribution.DisplayText
import org.quicklauncher.contracts.contribution.SettingsSchema
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ContractMajor
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ContributionTypeId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.ui.AccessibilityDeclaration
import org.quicklauncher.contracts.ui.AccessibilityRole
import org.quicklauncher.contracts.ui.AccessibilitySemantics
import org.quicklauncher.contracts.ui.PerformanceHookDeclaration
import org.quicklauncher.contracts.ui.PerformanceMetric
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.contracts.ui.ThemeMode
import org.quicklauncher.contracts.ui.WindowOrientation
import org.quicklauncher.registry.annotations.SettingKind
import org.quicklauncher.registry.annotations.SettingSpec
import org.quicklauncher.registry.annotations.SettingsSchemaSpec
import org.quicklauncher.testing.contracts.PerformanceCheck
import org.quicklauncher.testing.contracts.PerformanceObservation

object SampleIds {
    val LAYOUT = ContributionId.parse("org.quicklauncher.samples/layout")
    val BLOCK = ContributionId.parse("org.quicklauncher.samples/block")
    val SEARCH = ContributionId.parse("org.quicklauncher.samples/search")
    val COMMAND = ContributionId.parse("org.quicklauncher.samples/command")
    val TEMPLATE = ContributionId.parse("org.quicklauncher.samples/template")

    val LAYOUT_INSTANCE = ModuleInstanceId.parse("org.quicklauncher.instance/sample-layout")
    val BLOCK_INSTANCE = ModuleInstanceId.parse("org.quicklauncher.instance/sample-block")
    val SEARCH_INSTANCE = ModuleInstanceId.parse("org.quicklauncher.instance/sample-search")
}

@SettingsSchemaSpec(
    fields = [
        SettingSpec(
            key = "enabled",
            label = "Enabled",
            kind = SettingKind.BOOLEAN,
            defaultValue = "true",
        ),
    ],
)
object SampleSettings

fun sampleMetadata(
    id: ContributionId,
    typeId: ContributionTypeId,
    configType: ConfigTypeId,
    name: String,
    providedCapabilities: Set<CapabilityId> = emptySet(),
): ContributionMetadata = ContributionMetadata(
    id = id,
    typeId = typeId,
    contractMajor = ContractMajor.of(1),
    displayName = DisplayText.of(name),
    description = DisplayText.of("Skeletal $name contribution used by Phase 1 contract tests"),
    providedCapabilities = providedCapabilities,
    requiredCapabilities = emptySet(),
    settings = SettingsSchema(
        listOf(BooleanSetting(StableKey.parse("enabled"), "Enabled", true)),
    ),
    configType = configType,
)

fun sampleAccessibility(scenario: PreviewScenario): AccessibilityDeclaration {
    val root = StableKey.parse("root")
    return AccessibilityDeclaration(
        semantics = listOf(
            AccessibilitySemantics(
                id = root,
                label = "${scenario.name.lowercase().replace('_', ' ')} sample",
                role = AccessibilityRole.STATUS,
                stateDescription = scenario.name,
                actions = emptyList(),
            ),
        ),
        focusOrder = listOf(root),
        supportsLargeText = true,
        supportsKeyboardNavigation = true,
        supportsReducedMotion = true,
    )
}

fun sampleAccessibilityFixtures(): Map<PreviewScenario, AccessibilityDeclaration> = immutableSampleMap(
    PreviewScenario.entries.associateWith(::sampleAccessibility),
)

internal fun <T> immutableSampleList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

internal fun <T> immutableSampleSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

internal fun <K, V> immutableSampleMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

fun samplePerformanceCheck(
    metric: PerformanceMetric,
    description: String,
    action: () -> Unit,
): PerformanceCheck {
    val declaration = PerformanceHookDeclaration(metric, description)
    return PerformanceCheck(declaration) {
        action()
        PerformanceObservation(
            metric,
            StableKey.parse(metric.name.lowercase().replace('_', '-')),
            description,
        )
    }
}

fun sampleOrientation(scenario: PreviewScenario): WindowOrientation = when (scenario) {
    PreviewScenario.LOADING,
    PreviewScenario.PERMISSION_DENIED,
    PreviewScenario.ERROR,
    -> WindowOrientation.LANDSCAPE
    else -> WindowOrientation.PORTRAIT
}

fun sampleThemeMode(scenario: PreviewScenario): ThemeMode = when (scenario) {
    PreviewScenario.EMPTY,
    PreviewScenario.LOADING,
    PreviewScenario.PROFILE_LOCKED,
    -> ThemeMode.LIGHT
    else -> ThemeMode.DARK
}

fun sampleReducedMotion(scenario: PreviewScenario): Boolean = when (scenario) {
    PreviewScenario.PROFILE_LOCKED,
    PreviewScenario.ERROR,
    -> true
    else -> false
}

fun sampleScreenshotId(prefix: String, scenario: PreviewScenario): StableKey = StableKey.parse(
    "$prefix-${scenario.name.lowercase().replace('_', '-')}",
)
