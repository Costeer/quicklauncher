package org.quicklauncher.registry.ksp

import org.quicklauncher.contracts.contribution.CommandContext
import org.quicklauncher.contracts.contribution.CommandResultKind
import org.quicklauncher.contracts.contribution.FontRole
import org.quicklauncher.contracts.contribution.SearchResultKind
import org.quicklauncher.contracts.contribution.ScrollAxis
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ContributionTypeId
import org.quicklauncher.contracts.domain.SlotTypeId
import org.quicklauncher.contracts.domain.StableKey

object RegistryValidator {
    private const val HOST_OWNED_SAFE_LAYOUT_ID = "org.quicklauncher.core/safe-layout"
    private const val RESERVED_SAFE_LAYOUT_ISSUE = "registry.reserved-safe-layout-identity"

    fun validate(
        categories: List<CategoryDefinition>,
        registrations: List<RawRegistration>,
        allowExternalReferences: Boolean = false,
    ): RegistryValidationResult {
        val issues = mutableListOf<RegistryValidationIssue>()
        validateCategories(categories, issues)
        val categoryByKind = categories.associateBy { it.registrationKind }
        val validated = registrations.mapNotNull { raw ->
            validateRegistration(raw, categoryByKind[raw.kind], issues)
        }

        validated.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, duplicates) ->
            duplicates.forEach { duplicate ->
                issues += issue(
                    "registry.duplicate-contribution-id",
                    duplicate.raw,
                    "Contribution '${id.value}' is registered more than once",
                )
            }
        }
        validated.groupBy { it.configTypeId }.filterValues { it.size > 1 }.forEach { (id, duplicates) ->
            duplicates.forEach { duplicate ->
                issues += issue(
                    "registry.duplicate-config-type",
                    duplicate.raw,
                    "Persisted configuration type '${id.value}' is registered more than once",
                )
            }
        }
        findCapabilityCycle(validated)?.let { cycle ->
            val owner = validated.firstOrNull { registration ->
                registration.providedCapabilities.any { it.value == cycle.first() }
            }
            issues += RegistryValidationIssue(
                code = "registry.capability-cycle",
                targetName = owner?.raw?.targetName ?: "capability graph",
                contributionId = owner?.id?.value,
                message = "Capability dependency cycle: ${cycle.joinToString(" -> ")}",
            )
        }
        validateBlockNesting(validated, issues, allowExternalReferences)

        val sortedIssues = issues.distinct().sortedWith(
            compareBy(RegistryValidationIssue::targetName, RegistryValidationIssue::code, RegistryValidationIssue::message),
        )
        if (sortedIssues.isNotEmpty()) return RegistryValidationResult.Invalid(sortedIssues)

        val categoryIdByKind = categoryByKind.mapValues { it.value.persistedTypeId }
        val sortedRegistrations = validated.sortedWith(
            compareBy<ValidatedRegistration>(
                { categoryIdByKind.getValue(it.raw.kind) },
                { it.id.value },
                { it.raw.targetName },
            ),
        )
        return RegistryValidationResult.Valid(
            categories = categories.sortedBy { it.persistedTypeId },
            registrations = sortedRegistrations,
        )
    }

    private fun validateCategories(
        categories: List<CategoryDefinition>,
        issues: MutableList<RegistryValidationIssue>,
    ) {
        categories.forEach { category ->
            parse(category.persistedTypeId, ContributionTypeId::parse)?.let { return@forEach }
            issues += RegistryValidationIssue(
                "registry.invalid-persisted-type-id",
                category.declaredBy,
                null,
                "Category '${category.declaredBy}' has invalid persisted type ID '${category.persistedTypeId}'",
            )
        }
        categories.groupBy { it.persistedTypeId }.filterValues { it.size > 1 }.forEach { (id, values) ->
            values.forEach { category ->
                issues += RegistryValidationIssue(
                    "registry.duplicate-persisted-type-id",
                    category.declaredBy,
                    null,
                    "Persisted contribution type ID '$id' is declared more than once by " +
                        values.joinToString { it.declaredBy },
                )
            }
        }
    }

    private fun validateRegistration(
        raw: RawRegistration,
        category: CategoryDefinition?,
        issues: MutableList<RegistryValidationIssue>,
    ): ValidatedRegistration? {
        val id = parse(raw.id, ContributionId::parse)
        if (id == null) issues += issue(
            "registry.invalid-contribution-id",
            raw,
            "Contribution '${raw.targetName}' has invalid ID '${raw.id}'",
        )
        val configType = parse(raw.configTypeId, ConfigTypeId::parse)
        if (configType == null) issues += issue(
            "registry.invalid-config-type",
            raw,
            "Contribution '${raw.id}' has invalid config type '${raw.configTypeId}'",
        )
        validateReservedSafeLayoutIdentity(raw, issues)
        val categoryType = parse(raw.kind.persistedTypeId, ContributionTypeId::parse)

        if (category == null || raw.contractMajor !in category.supportedMajors) {
            issues += issue(
                "registry.unsupported-contract-major",
                raw,
                "Contribution '${raw.id}' declares unsupported contract major ${raw.contractMajor} " +
                    "for '${raw.kind.persistedTypeId}'",
            )
        }
        if (raw.displayName.isBlank()) issues += issue(
            "registry.blank-display-name",
            raw,
            "Contribution '${raw.id}' display name must not be blank",
        )
        if (raw.description.isBlank()) issues += issue(
            "registry.blank-description",
            raw,
            "Contribution '${raw.id}' description must not be blank",
        )

        val provided = parseCapabilities(raw, raw.providedCapabilities, "provided", issues)
        val required = parseCapabilities(raw, raw.requiredCapabilities, "required", issues)
        (provided intersect required).forEach { capability ->
            issues += issue(
                "registry.capability-self-dependency",
                raw,
                "Contribution '${raw.id}' both provides and requires '${capability.value}'",
            )
        }

        validateImplementationDeclarations(raw, issues)
        validateSettings(raw, provided + required, issues)
        validateSpecific(raw, issues)

        if (id == null || configType == null || categoryType == null) return null
        return ValidatedRegistration(raw, id, configType, categoryType, provided, required)
    }

    private fun validateReservedSafeLayoutIdentity(
        raw: RawRegistration,
        issues: MutableList<RegistryValidationIssue>,
    ) {
        if (raw.id == HOST_OWNED_SAFE_LAYOUT_ID) {
            issues += issue(
                RESERVED_SAFE_LAYOUT_ISSUE,
                raw,
                "Contribution '${raw.targetName}' uses contribution ID '$HOST_OWNED_SAFE_LAYOUT_ID', " +
                    "which is reserved for the host-owned safe layout",
            )
        }
        if (raw.configTypeId == HOST_OWNED_SAFE_LAYOUT_ID) {
            issues += issue(
                RESERVED_SAFE_LAYOUT_ISSUE,
                raw,
                "Contribution '${raw.targetName}' uses configuration type ID '$HOST_OWNED_SAFE_LAYOUT_ID', " +
                    "which is reserved for the host-owned safe layout",
            )
        }
    }

    private fun validateImplementationDeclarations(
        raw: RawRegistration,
        issues: MutableList<RegistryValidationIssue>,
    ) {
        when {
            raw.codecName == MissingNames.CODEC -> issues += issue(
                "registry.missing-codec",
                raw,
                "Contribution '${raw.id}' must declare a configuration codec",
            )
            !raw.codecImplementsContract -> issues += issue(
                "registry.codec-contract",
                raw,
                "Codec '${raw.codecName}' for contribution '${raw.id}' does not implement ConfigurationCodec",
            )
            !raw.codecIsObject -> issues += issue(
                "registry.codec-construction",
                raw,
                "Codec '${raw.codecName}' for contribution '${raw.id}' must be a Kotlin object",
            )
            !raw.codecHasManifest -> issues += issue(
                "registry.codec-manifest",
                raw,
                "Codec '${raw.codecName}' for contribution '${raw.id}' must declare @ConfigurationCodecSpec",
            )
            raw.codecManifestConfigTypeId != raw.configTypeId -> issues += issue(
                "registry.codec-config-type-mismatch",
                raw,
                "Codec '${raw.codecName}' declares config type '${raw.codecManifestConfigTypeId}' but " +
                    "descriptor '${raw.id}' declares '${raw.configTypeId}'",
            )
        }
        if (raw.codecName != MissingNames.CODEC && raw.codecImplementsContract) {
            if (raw.targetConfigurationType == null) issues += issue(
                "registry.target-configuration-type",
                raw,
                "Target '${raw.targetName}' must bind a concrete configuration type",
            )
            if (raw.codecConfigurationType == null) issues += issue(
                "registry.codec-configuration-type",
                raw,
                "Codec '${raw.codecName}' must bind a concrete configuration type",
            )
            if (raw.targetConfigurationType != null && raw.codecConfigurationType != null &&
                raw.targetConfigurationType != raw.codecConfigurationType
            ) issues += issue(
                "registry.configuration-type-mismatch",
                raw,
                "Target '${raw.targetName}' uses '${raw.targetConfigurationType}' but codec " +
                    "'${raw.codecName}' uses '${raw.codecConfigurationType}'",
            )
        }
        when {
            raw.contractTestsName == MissingNames.CONTRACT_TESTS -> issues += issue(
                "registry.missing-contract-tests",
                raw,
                "Contribution '${raw.id}' must declare its mandatory contract tests",
            )
            !raw.testsImplementContract -> issues += issue(
                "registry.contract-tests-contract",
                raw,
                "Contract tests '${raw.contractTestsName}' for contribution '${raw.id}' do not implement " +
                    "ContributionContractDeclaration",
            )
            !raw.testsAreObject -> issues += issue(
                "registry.contract-tests-construction",
                raw,
                "Contract tests '${raw.contractTestsName}' for contribution '${raw.id}' must be a Kotlin object",
            )
            !raw.contractTestsHaveManifest -> issues += issue(
                "registry.contract-tests-manifest",
                raw,
                "Contract tests '${raw.contractTestsName}' for contribution '${raw.id}' must declare " +
                    "@ContractTestSpec",
            )
            !raw.testsImplementCategoryContract || raw.contractTestsCategory != raw.kind.name -> issues += issue(
                "registry.contract-tests-category",
                raw,
                "Contract tests '${raw.contractTestsName}' must implement ${raw.kind.contractTestsType} " +
                    "and declare category ${raw.kind.name}",
            )
            raw.contractTestsContributionId != raw.id -> issues += issue(
                "registry.contract-tests-id-mismatch",
                raw,
                "Contract tests '${raw.contractTestsName}' declare contribution ID " +
                    "'${raw.contractTestsContributionId}' but descriptor declares '${raw.id}'",
            )
        }
        if (raw.contractTestsName != MissingNames.CONTRACT_TESTS && raw.testsImplementContract) {
            val requiredScenarios = REQUIRED_SCENARIOS
            if (raw.contractTestsScenarios.toSet() != requiredScenarios ||
                raw.contractTestsScenarios.size != requiredScenarios.size
            ) issues += issue(
                "registry.contract-tests-missing-scenarios",
                raw,
                "Contract tests '${raw.contractTestsName}' must declare exactly " +
                    requiredScenarios.sorted().joinToString(),
            )
            val declaredMetrics = raw.contractTestsPerformanceMetrics.toSet()
            if (!declaredMetrics.containsAll(raw.kind.requiredPerformanceMetrics)) issues += issue(
                "registry.contract-tests-missing-performance-hooks",
                raw,
                "Contract tests '${raw.contractTestsName}' are missing required performance hooks: " +
                    (raw.kind.requiredPerformanceMetrics - declaredMetrics).sorted().joinToString(),
            )
            if (raw.contractTestsConfigurationType == null && raw.testsImplementCategoryContract) {
                issues += issue(
                    "registry.contract-tests-configuration-type",
                    raw,
                    "Contract tests '${raw.contractTestsName}' must bind a concrete configuration type",
                )
            } else if (raw.targetConfigurationType != null &&
                raw.contractTestsConfigurationType != null &&
                raw.targetConfigurationType != raw.contractTestsConfigurationType
            ) issues += issue(
                "registry.contract-tests-configuration-type",
                raw,
                "Target '${raw.targetName}' uses '${raw.targetConfigurationType}' but contract tests " +
                    "use '${raw.contractTestsConfigurationType}'",
            )
        }
        if (!raw.targetImplementsContract) issues += issue(
            "registry.target-contract",
            raw,
            "Registration target '${raw.targetName}' for contribution '${raw.id}' does not implement " +
                raw.kind.contractType,
        )
        if (!raw.targetIsObject) issues += issue(
            "registry.target-construction",
            raw,
            "Registration target '${raw.targetName}' for contribution '${raw.id}' must be a Kotlin object",
        )
    }

    private fun validateSettings(
        raw: RawRegistration,
        declaredCapabilities: Set<CapabilityId>,
        issues: MutableList<RegistryValidationIssue>,
    ) {
        val settings = raw.settings ?: return
        if (!settings.hasSchemaAnnotation) {
            issues += issue(
                "registry.invalid-settings-schema",
                raw,
                "Settings declaration '${settings.declarationName}' for contribution '${raw.id}' is missing " +
                    "@SettingsSchemaSpec",
            )
            return
        }
        settings.fields.groupBy { it.key }.filterValues { it.size > 1 }.keys.forEach { key ->
            issues += issue(
                "registry.invalid-settings-schema",
                raw,
                "Contribution '${raw.id}' settings key '$key' is declared more than once",
            )
        }
        settings.fields.forEach { field ->
            if (parse(field.key, StableKey::parse) == null) issues += issue(
                "registry.invalid-settings-schema",
                raw,
                "Contribution '${raw.id}' has invalid setting key '${field.key}'",
            )
            if (field.label.isBlank()) issues += issue(
                "registry.invalid-settings-schema",
                raw,
                "Contribution '${raw.id}' setting '${field.key}' has a blank label",
            )
            if (field.enabledWhen.isNotEmpty()) {
                val capability = parse(field.enabledWhen, CapabilityId::parse)
                if (capability == null || capability !in declaredCapabilities) issues += issue(
                    "registry.invalid-settings-schema",
                    raw,
                    "Contribution '${raw.id}' setting '${field.key}' uses undeclared capability " +
                        "'${field.enabledWhen}'",
                )
            }
            validateSettingKind(raw, field, issues)
        }
    }

    private fun validateSettingKind(
        raw: RawRegistration,
        field: RawSetting,
        issues: MutableList<RegistryValidationIssue>,
    ) {
        fun invalid(reason: String) {
            issues += issue(
                "registry.invalid-settings-schema",
                raw,
                "Contribution '${raw.id}' setting '${field.key}' $reason",
            )
        }
        when (field.kind) {
            "BOOLEAN" -> if (field.defaultValue !in setOf("true", "false")) {
                invalid("must use true or false as its default")
            }
            "CHOICE" -> {
                val validOptions = field.options.mapNotNull { parse(it, StableKey::parse) }
                if (validOptions.size != field.options.size || validOptions.isEmpty()) {
                    invalid("must declare valid choice option keys")
                }
                if (field.options.distinct().size != field.options.size) invalid("has duplicate choice options")
                if (field.defaultValue !in field.options) invalid("default is not a declared choice option")
            }
            "NUMBER", "DIMENSION" -> {
                val default = field.defaultValue.toIntOrNull()
                if (field.minimum > field.maximum) invalid("minimum exceeds maximum")
                if (default == null || default !in field.minimum..field.maximum) invalid("default is outside its range")
            }
            "COLOR" -> {
                val color = parseColor(field.defaultValue)
                if (color == null || color !in 0..0xffffffffL) invalid("default is not a 32-bit ARGB value")
            }
            "FONT" -> {
                val roles = field.allowedFontRoles.mapNotNull { enumValueOrNull<FontRole>(it) }
                if (roles.size != field.allowedFontRoles.size || roles.isEmpty()) invalid("must allow valid font roles")
                if (enumValueOrNull<FontRole>(field.defaultValue) !in roles) invalid("default is not an allowed font role")
            }
            "APP_SELECTOR" -> if (field.defaultValue.isNotEmpty()) {
                invalid("must not encode app identities in descriptor defaults")
            }
            "CONTENT_SELECTOR" -> if (field.defaultValue.isNotEmpty()) {
                invalid("must not encode content identities in descriptor defaults")
            }
            else -> invalid("uses unknown kind '${field.kind}'")
        }
    }

    private fun validateSpecific(
        raw: RawRegistration,
        issues: MutableList<RegistryValidationIssue>,
    ) {
        when (val specific = raw.specific) {
            is RawSpecificDescriptor.Layout -> validateSlots(raw, "layout", specific.slots, issues)
            is RawSpecificDescriptor.Block -> {
                if (specific.compatibleSlotTypes.isEmpty()) issues += issue(
                    "registry.invalid-descriptor",
                    raw,
                    "Block contribution '${raw.id}' must accept at least one slot type",
                )
                specific.compatibleSlotTypes.forEach { slot ->
                    if (parse(slot, SlotTypeId::parse) == null) issues += issue(
                        "registry.invalid-slot-type",
                        raw,
                        "Block contribution '${raw.id}' has invalid slot type '$slot'",
                    )
                }
                validateSlots(raw, "block", specific.childSlots, issues)
                if (specific.occupiedScrollAxes.any { enumValueOrNull<ScrollAxis>(it) == null }) {
                    issues += issue(
                        "registry.invalid-descriptor",
                        raw,
                        "Block contribution '${raw.id}' has an invalid occupied scroll axis",
                    )
                }
            }
            is RawSpecificDescriptor.SearchProvider -> {
                if (specific.resultKinds.isEmpty() || specific.resultKinds.any {
                        enumValueOrNull<SearchResultKind>(it) == null
                    }
                ) issues += issue(
                    "registry.invalid-descriptor",
                    raw,
                    "Search contribution '${raw.id}' must declare valid result kinds",
                )
                if (specific.minimumQueryLength < 0) issues += issue(
                    "registry.invalid-descriptor",
                    raw,
                    "Search contribution '${raw.id}' minimum query length must not be negative",
                )
            }
            is RawSpecificDescriptor.LauncherCommand -> {
                if (specific.contexts.isEmpty() || specific.contexts.any {
                        enumValueOrNull<CommandContext>(it) == null
                    }
                ) issues += issue(
                    "registry.invalid-descriptor",
                    raw,
                    "Command contribution '${raw.id}' must declare valid contexts",
                )
                if (specific.resultKinds.isEmpty() || specific.resultKinds.any {
                        enumValueOrNull<CommandResultKind>(it) == null
                    }
                ) issues += issue(
                    "registry.invalid-descriptor",
                    raw,
                    "Command contribution '${raw.id}' must declare valid result kinds",
                )
            }
            is RawSpecificDescriptor.DestinationTemplate -> {
                if (specific.maximumBlocks < 0) issues += issue(
                    "registry.invalid-descriptor",
                    raw,
                    "Template contribution '${raw.id}' maximum blocks must not be negative",
                )
                specific.requiredContributions.forEach { required ->
                    if (parse(required, ContributionId::parse) == null) issues += issue(
                        "registry.invalid-descriptor",
                        raw,
                        "Template contribution '${raw.id}' requires invalid contribution ID '$required'",
                    )
                }
            }
        }
    }

    private fun validateSlots(
        raw: RawRegistration,
        prefix: String,
        slots: List<RawSlot>,
        issues: MutableList<RegistryValidationIssue>,
    ) {
        slots.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { id ->
            issues += issue(
                "registry.invalid-descriptor",
                raw,
                "${prefix.replaceFirstChar(Char::uppercase)} contribution '${raw.id}' duplicates slot '$id'",
            )
        }
        slots.forEach { slot ->
            if (parse(slot.id, StableKey::parse) == null) issues += issue(
                "registry.invalid-descriptor",
                raw,
                "Contribution '${raw.id}' has invalid slot key '${slot.id}'",
            )
            if (parse(slot.type, SlotTypeId::parse) == null) issues += issue(
                "registry.invalid-slot-type",
                raw,
                "Contribution '${raw.id}' has invalid slot type '${slot.type}'",
            )
            slot.acceptedBlocks.forEach { blockId ->
                if (parse(blockId, ContributionId::parse) == null) issues += issue(
                    "registry.invalid-descriptor",
                    raw,
                    "Contribution '${raw.id}' slot '${slot.id}' accepts invalid block ID '$blockId'",
                )
            }
            if (slot.maximumChildren <= 0) issues += issue(
                "registry.invalid-descriptor",
                raw,
                "Contribution '${raw.id}' slot '${slot.id}' maximum children must be positive",
            )
            if (slot.allowedScrollAxes.any { enumValueOrNull<ScrollAxis>(it) == null }) issues += issue(
                "registry.invalid-descriptor",
                raw,
                "Contribution '${raw.id}' slot '${slot.id}' has an invalid scroll axis",
            )
            slot.requiredCapabilities.forEach { capability ->
                if (parse(capability, CapabilityId::parse) == null) issues += issue(
                    "registry.invalid-capability-id",
                    raw,
                    "Contribution '${raw.id}' slot '${slot.id}' has invalid capability '$capability'",
                )
            }
        }
    }

    private fun parseCapabilities(
        raw: RawRegistration,
        values: List<String>,
        role: String,
        issues: MutableList<RegistryValidationIssue>,
    ): Set<CapabilityId> = values.mapNotNull { value ->
        parse(value, CapabilityId::parse) ?: run {
            issues += issue(
                "registry.invalid-capability-id",
                raw,
                "Contribution '${raw.id}' has invalid $role capability '$value'",
            )
            null
        }
    }.toSet()

    private fun findCapabilityCycle(registrations: List<ValidatedRegistration>): List<String>? {
        val graph = sortedMapOf<String, MutableSet<String>>()
        registrations.forEach { registration ->
            registration.providedCapabilities.forEach { provided ->
                val edges = graph.getOrPut(provided.value) { sortedSetOf() }
                registration.requiredCapabilities.forEach { required -> edges += required.value }
            }
        }
        val visited = mutableSetOf<String>()
        val active = mutableListOf<String>()
        val activeSet = mutableSetOf<String>()

        fun visit(node: String): List<String>? {
            if (node in activeSet) {
                val start = active.indexOf(node)
                return active.subList(start, active.size).toList() + node
            }
            if (!visited.add(node)) return null
            active += node
            activeSet += node
            graph[node].orEmpty().sorted().forEach { next ->
                visit(next)?.let { return it }
            }
            active.removeAt(active.lastIndex)
            activeSet -= node
            return null
        }

        graph.keys.forEach { node -> visit(node)?.let { return it } }
        return null
    }

    private fun validateBlockNesting(
        registrations: List<ValidatedRegistration>,
        issues: MutableList<RegistryValidationIssue>,
        allowExternalReferences: Boolean,
    ) {
        val blocksById = registrations
            .filter { it.raw.kind == RegistrationKind.BLOCK }
            .associateBy { it.id.value }
        val graph = sortedMapOf<String, MutableSet<String>>()

        registrations.forEach { parent ->
            val slots = when (val specific = parent.raw.specific) {
                is RawSpecificDescriptor.Layout -> specific.slots
                is RawSpecificDescriptor.Block -> specific.childSlots
                else -> emptyList()
            }
            slots.forEach { slot ->
                slot.acceptedBlocks.sorted().forEach { acceptedId ->
                    val child = blocksById[acceptedId]
                    if (child == null) {
                        if (!allowExternalReferences) {
                            issues += issue(
                                "registry.unsupported-slot-nesting",
                                parent.raw,
                                "Contribution '${parent.raw.id}' slot '${slot.id}' accepts unknown block " +
                                    "'$acceptedId'",
                            )
                        }
                        return@forEach
                    }
                    val childSpecific = child.raw.specific as RawSpecificDescriptor.Block
                    val compatible = slot.type in childSpecific.compatibleSlotTypes &&
                        slot.requiredCapabilities.all { it in child.raw.providedCapabilities } &&
                        childSpecific.occupiedScrollAxes.all { it in slot.allowedScrollAxes }
                    if (!compatible) {
                        issues += issue(
                            "registry.unsupported-slot-nesting",
                            parent.raw,
                            "Contribution '${parent.raw.id}' slot '${slot.id}' is incompatible with block '$acceptedId'",
                        )
                    } else if (parent.raw.kind == RegistrationKind.BLOCK) {
                        graph.getOrPut(parent.id.value) { sortedSetOf() } += child.id.value
                    }
                }
            }
        }

        findCycle(graph)?.let { cycle ->
            val owner = blocksById.getValue(cycle.first())
            issues += issue(
                "registry.block-nesting-cycle",
                owner.raw,
                "Block nesting cycle: ${cycle.joinToString(" -> ")}",
            )
        }
    }

    private fun findCycle(graph: Map<String, Set<String>>): List<String>? {
        val visited = mutableSetOf<String>()
        val active = mutableListOf<String>()
        val activeSet = mutableSetOf<String>()

        fun visit(node: String): List<String>? {
            if (node in activeSet) {
                val start = active.indexOf(node)
                return active.subList(start, active.size).toList() + node
            }
            if (!visited.add(node)) return null
            active += node
            activeSet += node
            graph[node].orEmpty().sorted().forEach { child -> visit(child)?.let { return it } }
            active.removeAt(active.lastIndex)
            activeSet -= node
            return null
        }

        graph.keys.sorted().forEach { node -> visit(node)?.let { return it } }
        return null
    }

    private fun issue(code: String, raw: RawRegistration, message: String) =
        RegistryValidationIssue(code, raw.targetName, raw.id, message)

    private fun <T> parse(value: String, parser: (String) -> T): T? = try {
        parser(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }

    private fun parseColor(value: String): Long? = try {
        if (value.startsWith("0x")) value.removePrefix("0x").toLong(16) else value.toLong()
    } catch (_: NumberFormatException) {
        null
    }

    private val REQUIRED_SCENARIOS = setOf(
        "EMPTY",
        "NORMAL",
        "LOADING",
        "PERMISSION_DENIED",
        "PROFILE_LOCKED",
        "LARGE_TEXT",
        "ERROR",
    )
}
