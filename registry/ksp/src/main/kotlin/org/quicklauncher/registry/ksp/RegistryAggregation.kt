package org.quicklauncher.registry.ksp

import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ContributionTypeId
import org.quicklauncher.contracts.domain.SlotTypeId
import org.quicklauncher.contracts.contribution.ScrollAxis

data class RawRegistryFragment(
    val declarationName: String,
    val fragmentId: String,
    val entries: List<RawRegistryFragmentEntry>,
)

data class RawRegistryFragmentEntry(
    val index: Int,
    val contributionId: String,
    val configTypeId: String,
    val categoryTypeId: String,
    val contractMajor: Int = 1,
    val providedCapabilities: List<String>,
    val requiredCapabilities: List<String>,
    val compatibleSlotTypes: List<String>,
    val occupiedScrollAxes: List<String> = emptyList(),
    val childSlots: List<RawRegistryFragmentSlot>,
    val requiredContributions: List<String> = emptyList(),
)

data class RawRegistryFragmentSlot(
    val type: String,
    val acceptedBlocks: List<String>,
    val requiredCapabilities: List<String> = emptyList(),
    val maximumChildren: Int = 1,
    val allowedScrollAxes: List<String> = emptyList(),
)

data class AggregatedRegistryEntry(
    val fragmentName: String,
    val index: Int,
    val contributionId: ContributionId,
    val configTypeId: ConfigTypeId,
    val categoryTypeId: ContributionTypeId,
)

sealed interface AggregationValidationResult {
    data class Valid(val entries: List<AggregatedRegistryEntry>) : AggregationValidationResult
    data class Invalid(val issues: List<String>) : AggregationValidationResult
}

object RegistryAggregationValidator {
    private const val HOST_OWNED_SAFE_LAYOUT_ID = "org.quicklauncher.core/safe-layout"

    fun validate(
        categories: List<CategoryDefinition>,
        fragments: List<RawRegistryFragment>,
    ): AggregationValidationResult {
        val issues = mutableListOf<String>()
        val categoriesById = categories.associateBy { it.persistedTypeId }
        val supportedCategories = categoriesById.keys
        val validated = mutableListOf<AggregatedRegistryEntry>()

        fragments.groupBy { it.fragmentId }.filterValues { it.size > 1 }.keys.forEach { id ->
            issues += "[registry.duplicate-fragment-id] Fragment ID '$id' is aggregated more than once"
        }
        fragments.forEach { fragment ->
            parse(fragment.fragmentId, ContributionId::parse) ?: run {
                issues += "[registry.invalid-fragment-id] Fragment '${fragment.declarationName}' has invalid ID " +
                    "'${fragment.fragmentId}'"
            }
            fragment.entries.groupBy { it.index }.filterValues { it.size > 1 }.keys.forEach { index ->
                issues += "[registry.duplicate-fragment-index] Fragment '${fragment.fragmentId}' has duplicate " +
                    "entry index $index"
            }
            val expectedIndices = fragment.entries.indices.toSet()
            val actualIndices = fragment.entries.map { it.index }.toSet()
            if (actualIndices != expectedIndices) {
                issues += "[registry.invalid-fragment-index] Fragment '${fragment.fragmentId}' entry indices must " +
                    "be exactly ${expectedIndices.sorted().joinToString()}"
            }
            fragment.entries.forEach { entry ->
                if (entry.index < 0) {
                    issues += "[registry.invalid-fragment-index] Fragment '${fragment.fragmentId}' has negative " +
                        "entry index ${entry.index}"
                }
                val contributionId = parse(entry.contributionId, ContributionId::parse)
                val configTypeId = parse(entry.configTypeId, ConfigTypeId::parse)
                val categoryTypeId = parse(entry.categoryTypeId, ContributionTypeId::parse)
                if (contributionId == null) {
                    issues += "[registry.invalid-contribution-id] Fragment '${fragment.fragmentId}' has invalid " +
                        "contribution ID '${entry.contributionId}'"
                }
                if (configTypeId == null) {
                    issues += "[registry.invalid-config-type] Contribution '${entry.contributionId}' has invalid " +
                        "configuration type '${entry.configTypeId}'"
                }
                if (categoryTypeId == null || entry.categoryTypeId !in supportedCategories) {
                    issues += "[registry.unsupported-category] Contribution '${entry.contributionId}' declares " +
                        "unsupported category '${entry.categoryTypeId}'"
                }
                if (entry.contractMajor !in categoriesById[entry.categoryTypeId]?.supportedMajors.orEmpty()) {
                    issues += "[registry.unsupported-contract-major] Contribution '${entry.contributionId}' " +
                        "declares unsupported contract major ${entry.contractMajor}"
                }
                if (entry.contributionId == HOST_OWNED_SAFE_LAYOUT_ID ||
                    entry.configTypeId == HOST_OWNED_SAFE_LAYOUT_ID
                ) {
                    issues += "[registry.reserved-safe-layout-identity] Contribution '${entry.contributionId}' " +
                        "claims the host-owned safe layout identity"
                }
                entry.providedCapabilities.forEach { value ->
                    if (parse(value, CapabilityId::parse) == null) {
                        issues += "[registry.invalid-capability-id] Contribution '${entry.contributionId}' has " +
                            "invalid provided capability '$value'"
                    }
                }
                entry.requiredCapabilities.forEach { value ->
                    if (parse(value, CapabilityId::parse) == null) {
                        issues += "[registry.invalid-capability-id] Contribution '${entry.contributionId}' has " +
                            "invalid required capability '$value'"
                    }
                }
                (entry.compatibleSlotTypes + entry.childSlots.map { it.type }).forEach { value ->
                    if (parse(value, SlotTypeId::parse) == null) {
                        issues += "[registry.invalid-slot-type] Contribution '${entry.contributionId}' has invalid " +
                            "slot type '$value'"
                    }
                }
                entry.occupiedScrollAxes.forEach { axis ->
                    if (enumValueOrNull<ScrollAxis>(axis) == null) {
                        issues += "[registry.invalid-scroll-axis] Contribution '${entry.contributionId}' has " +
                            "invalid occupied scroll axis '$axis'"
                    }
                }
                entry.childSlots.forEach { slot ->
                    slot.requiredCapabilities.forEach { value ->
                        if (parse(value, CapabilityId::parse) == null) {
                            issues += "[registry.invalid-capability-id] Contribution '${entry.contributionId}' " +
                                "slot '${slot.type}' has invalid required capability '$value'"
                        }
                    }
                    if (slot.maximumChildren <= 0) {
                        issues += "[registry.invalid-slot-capacity] Contribution '${entry.contributionId}' " +
                            "slot '${slot.type}' has non-positive capacity"
                    }
                    slot.allowedScrollAxes.forEach { axis ->
                        if (enumValueOrNull<ScrollAxis>(axis) == null) {
                            issues += "[registry.invalid-scroll-axis] Contribution '${entry.contributionId}' " +
                                "slot '${slot.type}' has invalid allowed scroll axis '$axis'"
                        }
                    }
                }
                entry.childSlots.flatMap { it.acceptedBlocks }.forEach { value ->
                    if (parse(value, ContributionId::parse) == null) {
                        issues += "[registry.invalid-accepted-block] Contribution '${entry.contributionId}' accepts " +
                            "invalid block ID '$value'"
                    }
                }
                entry.requiredContributions.forEach { value ->
                    if (parse(value, ContributionId::parse) == null) {
                        issues += "[registry.invalid-required-contribution] Contribution " +
                            "'${entry.contributionId}' requires invalid contribution '$value'"
                    }
                }
                if (contributionId != null && configTypeId != null && categoryTypeId != null &&
                    entry.categoryTypeId in supportedCategories && entry.index >= 0
                ) {
                    validated += AggregatedRegistryEntry(
                        fragment.declarationName,
                        entry.index,
                        contributionId,
                        configTypeId,
                        categoryTypeId,
                    )
                }
            }
        }

        duplicates(validated, { it.contributionId.value }).forEach { id ->
            issues += "[registry.duplicate-contribution-id] Contribution '$id' is registered across multiple fragments"
        }
        duplicates(validated, { it.configTypeId.value }).forEach { id ->
            issues += "[registry.duplicate-config-type] Persisted configuration type '$id' is registered across " +
                "multiple fragments"
        }

        val rawEntries = fragments.flatMap { it.entries }
        val rawById = rawEntries.associateBy { it.contributionId }
        rawEntries.forEach { owner ->
            owner.childSlots.forEach { slot ->
                slot.acceptedBlocks.forEach { acceptedId ->
                    val accepted = rawById[acceptedId]
                    when {
                        accepted == null -> issues +=
                            "[registry.missing-accepted-block] Contribution '${owner.contributionId}' accepts " +
                                "unregistered block '$acceptedId'"
                        accepted.categoryTypeId != RegistrationKind.BLOCK.persistedTypeId -> issues +=
                            "[registry.accepted-target-not-block] Contribution '${owner.contributionId}' accepts " +
                                "'$acceptedId', which is not a block"
                        slot.type !in accepted.compatibleSlotTypes -> issues +=
                            "[registry.slot-compatibility] Block '$acceptedId' does not declare compatibility " +
                                "with slot type '${slot.type}' exposed by '${owner.contributionId}'"
                        !accepted.providedCapabilities.containsAll(slot.requiredCapabilities) -> issues +=
                            "[registry.slot-capability] Block '$acceptedId' does not provide every capability " +
                                "required by '${owner.contributionId}'"
                        !slot.allowedScrollAxes.containsAll(accepted.occupiedScrollAxes) -> issues +=
                            "[registry.slot-scroll-axis] Block '$acceptedId' occupies a scroll axis disallowed " +
                                "by '${owner.contributionId}'"
                    }
                }
            }
            owner.requiredContributions.forEach { requiredId ->
                if (requiredId !in rawById) {
                    issues += "[registry.missing-required-contribution] Contribution " +
                        "'${owner.contributionId}' requires unregistered contribution '$requiredId'"
                }
            }
        }
        findCycle(
            buildMap<String, Set<String>> {
                rawEntries.forEach { entry ->
                    entry.providedCapabilities.forEach { provided ->
                        put(provided, get(provided).orEmpty() + entry.requiredCapabilities)
                    }
                }
            },
        )?.let { cycle ->
            val owners = rawEntries.filter { entry ->
                entry.providedCapabilities.any { it in cycle }
            }.map { it.contributionId }.sorted()
            issues += "[registry.capability-cycle] Capability dependency cycle: " +
                "${cycle.joinToString(" -> ")} (contributions: ${owners.joinToString()})"
        }
        findCycle(
            rawEntries.filter { it.categoryTypeId == RegistrationKind.BLOCK.persistedTypeId }.associate { entry ->
                entry.contributionId to entry.childSlots.flatMap { it.acceptedBlocks }
                    .filter { rawById[it]?.categoryTypeId == RegistrationKind.BLOCK.persistedTypeId }
                    .toSet()
            },
        )?.let { cycle ->
            issues += "[registry.block-nesting-cycle] Block nesting cycle: ${cycle.joinToString(" -> ")}"
        }

        if (issues.isNotEmpty()) return AggregationValidationResult.Invalid(issues.distinct().sorted())
        return AggregationValidationResult.Valid(
            validated.sortedWith(
                compareBy<AggregatedRegistryEntry>(
                    { it.categoryTypeId.value },
                    { it.contributionId.value },
                    { it.fragmentName },
                    { it.index },
                ),
            ),
        )
    }

    private fun <T> duplicates(values: List<T>, selector: (T) -> String): Set<String> = values
        .groupBy(selector)
        .filterValues { it.size > 1 }
        .keys

    private fun findCycle(graph: Map<String, Set<String>>): List<String>? {
        val visited = mutableSetOf<String>()
        val active = linkedSetOf<String>()
        fun visit(node: String): List<String>? {
            if (node in active) {
                val path = active.toList()
                val start = path.indexOf(node)
                return path.drop(start) + node
            }
            if (!visited.add(node)) return null
            active += node
            graph[node].orEmpty().sorted().forEach { next -> visit(next)?.let { return it } }
            active -= node
            return null
        }
        graph.keys.sorted().forEach { node -> visit(node)?.let { return it } }
        return null
    }

    private fun <T> parse(value: String, parser: (String) -> T): T? = try {
        parser(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }
}

object AggregatedRegistrySourceGenerator {
    fun generate(
        packageName: String,
        registryName: String,
        validation: AggregationValidationResult.Valid,
    ): String = buildString {
        appendLine("package $packageName")
        appendLine()
        appendLine("object $registryName : org.quicklauncher.contracts.contribution.ContributionRegistry {")
        appendLine("    override val categoryIds = org.quicklauncher.contracts.contribution.contributionTypeIdsOf(")
        appendLine("        listOf(")
        CategoryCatalog.firstRelease().forEach { category ->
            appendLine("            org.quicklauncher.contracts.domain.ContributionTypeId.parse(\"${category.persistedTypeId}\"),")
        }
        appendLine("        ),")
        appendLine("    )")
        appendLine()
        appendLine("    override val entries = org.quicklauncher.contracts.contribution.contributionEntriesOf(")
        appendLine("        listOf(")
        validation.entries.forEach { entry ->
            appendLine("            ${entry.fragmentName}.entries[${entry.index}],")
        }
        appendLine("        ),")
        appendLine("    )")
        appendLine("}")
    }
}
