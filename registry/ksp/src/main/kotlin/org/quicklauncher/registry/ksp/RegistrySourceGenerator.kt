package org.quicklauncher.registry.ksp

object RegistrySourceGenerator {
    fun generate(
        registry: RegistryValidationResult.Valid,
        packageName: String = "org.quicklauncher.generated",
        objectName: String = "GeneratedContributionRegistry",
        fragmentId: String = "$packageName/$objectName",
    ): String = buildString {
        appendLine("package $packageName")
        appendLine()
        appendFragmentManifest(registry, fragmentId)
        appendLine("object $objectName : org.quicklauncher.contracts.contribution.ContributionRegistry {")
        appendLine("    override val categoryIds = org.quicklauncher.contracts.contribution.contributionTypeIdsOf(")
        appendLine("        listOf(")
        registry.categories.forEach { category ->
            appendLine(
                "            org.quicklauncher.contracts.domain.ContributionTypeId.parse(" +
                    "${category.persistedTypeId.quoted()}),",
            )
        }
        appendLine("        ),")
        appendLine("    )")
        appendLine()
        appendLine("    override val entries = org.quicklauncher.contracts.contribution.contributionEntriesOf(")
        appendLine("        listOf(")
        registry.registrations.forEach { registration -> appendRegistration(registration) }
        appendLine("        ),")
        appendLine("    )")
        appendLine("}")
    }

    private fun StringBuilder.appendFragmentManifest(
        registry: RegistryValidationResult.Valid,
        fragmentId: String,
    ) {
        appendLine("@org.quicklauncher.registry.annotations.ContributionRegistryFragmentManifest(")
        appendLine("    fragmentId = ${fragmentId.quoted()},")
        appendLine("    entries = [")
        registry.registrations.forEachIndexed { index, registration ->
            val raw = registration.raw
            val block = raw.specific as? RawSpecificDescriptor.Block
            val slots = when (val specific = raw.specific) {
                is RawSpecificDescriptor.Layout -> specific.slots
                is RawSpecificDescriptor.Block -> specific.childSlots
                else -> emptyList()
            }
            appendLine("        org.quicklauncher.registry.annotations.ContributionRegistryFragmentEntry(")
            appendLine("            index = $index,")
            appendLine("            contributionId = ${raw.id.quoted()},")
            appendLine("            configTypeId = ${raw.configTypeId.quoted()},")
            appendLine("            categoryTypeId = ${raw.kind.persistedTypeId.quoted()},")
            appendLine("            contractMajor = ${raw.contractMajor},")
            appendLine("            providedCapabilities = ${raw.providedCapabilities.stringArray()},")
            appendLine("            requiredCapabilities = ${raw.requiredCapabilities.stringArray()},")
            appendLine("            compatibleSlotTypes = ${block?.compatibleSlotTypes.orEmpty().stringArray()},")
            appendLine("            occupiedScrollAxes = ${block?.occupiedScrollAxes.orEmpty().stringArray()},")
            appendLine("            childSlots = [")
            slots.forEach { slot ->
                appendLine("                org.quicklauncher.registry.annotations.ContributionRegistryFragmentSlot(")
                appendLine("                    type = ${slot.type.quoted()},")
                appendLine("                    acceptedBlocks = ${slot.acceptedBlocks.stringArray()},")
                appendLine("                    requiredCapabilities = ${slot.requiredCapabilities.stringArray()},")
                appendLine("                    maximumChildren = ${slot.maximumChildren},")
                appendLine("                    allowedScrollAxes = ${slot.allowedScrollAxes.stringArray()},")
                appendLine("                ),")
            }
            appendLine("            ],")
            val template = raw.specific as? RawSpecificDescriptor.DestinationTemplate
            appendLine(
                "            requiredContributions = " +
                    "${template?.requiredContributions.orEmpty().stringArray()},",
            )
            appendLine("        ),")
        }
        appendLine("    ],")
        appendLine(")")
    }

    private fun StringBuilder.appendRegistration(registration: ValidatedRegistration) {
        val raw = registration.raw
        val registeredType = raw.kind.registeredType
        appendLine("            org.quicklauncher.contracts.contribution.$registeredType(")
        appendLine("                descriptor = ${descriptorExpression(registration).prependIndent("                ").trimStart()},")
        appendLine("                target = ${raw.targetName},")
        appendLine("                codec = ${raw.codecName},")
        appendLine("                contractTests = ${raw.contractTestsName},")
        appendLine("            ),")
    }

    private fun descriptorExpression(registration: ValidatedRegistration): String = buildString {
        val raw = registration.raw
        val descriptorType = raw.kind.descriptorType
        appendLine("org.quicklauncher.contracts.contribution.$descriptorType(")
        append(metadataExpression(registration).prependIndent("    "))
        when (val specific = raw.specific) {
            is RawSpecificDescriptor.Layout -> {
                appendLine("    slots = ${slotListExpression(specific.slots)},")
            }
            is RawSpecificDescriptor.Block -> {
                appendLine(
                    "    compatibleSlotTypes = ${specific.compatibleSlotTypes.typedSet("SlotTypeId")},",
                )
                appendLine("    childSlots = ${slotListExpression(specific.childSlots)},")
                appendLine("    occupiedScrollAxes = ${specific.occupiedScrollAxes.enumSet("ScrollAxis")},")
            }
            is RawSpecificDescriptor.SearchProvider -> {
                appendLine("    resultKinds = ${specific.resultKinds.enumSet("SearchResultKind")},")
                appendLine("    minimumQueryLength = ${specific.minimumQueryLength},")
            }
            is RawSpecificDescriptor.LauncherCommand -> {
                appendLine("    contexts = ${specific.contexts.enumSet("CommandContext")},")
                appendLine("    resultKinds = ${specific.resultKinds.enumSet("CommandResultKind")},")
            }
            is RawSpecificDescriptor.DestinationTemplate -> {
                appendLine(
                    "    requiredContributions = ${specific.requiredContributions.typedSet("ContributionId")},",
                )
                appendLine("    maximumBlocks = ${specific.maximumBlocks},")
            }
        }
        append(")")
    }

    private fun metadataExpression(registration: ValidatedRegistration): String = buildString {
        val raw = registration.raw
        appendLine("metadata = org.quicklauncher.contracts.contribution.ContributionMetadata(")
        appendLine("    id = org.quicklauncher.contracts.domain.ContributionId.parse(${raw.id.quoted()}),")
        appendLine(
            "    typeId = org.quicklauncher.contracts.domain.ContributionTypeId.parse(" +
                "${raw.kind.persistedTypeId.quoted()}),",
        )
        appendLine(
            "    contractMajor = org.quicklauncher.contracts.domain.ContractMajor.of(${raw.contractMajor}),",
        )
        appendLine(
            "    displayName = org.quicklauncher.contracts.contribution.DisplayText.of(" +
                "${raw.displayName.quoted()}),",
        )
        appendLine(
            "    description = org.quicklauncher.contracts.contribution.DisplayText.of(" +
                "${raw.description.quoted()}),",
        )
        appendLine(
            "    providedCapabilities = ${raw.providedCapabilities.typedSet("CapabilityId")},",
        )
        appendLine(
            "    requiredCapabilities = ${raw.requiredCapabilities.typedSet("CapabilityId")},",
        )
        appendLine("    settings = ${settingsExpression(raw.settings)},")
        appendLine(
            "    configType = org.quicklauncher.contracts.domain.ConfigTypeId.parse(" +
                "${raw.configTypeId.quoted()}),",
        )
        appendLine("),")
    }

    private fun settingsExpression(settings: RawSettings?): String {
        if (settings == null) return "null"
        val fields = settings.fields.joinToString(",\n", prefix = "listOf(\n", postfix = "\n        )") { field ->
            settingExpression(field).prependIndent("            ")
        }
        return "org.quicklauncher.contracts.contribution.SettingsSchema(\n        fields = $fields,\n    )"
    }

    private fun settingExpression(field: RawSetting): String {
        val prefix = "org.quicklauncher.contracts.contribution."
        val common = "key = org.quicklauncher.contracts.domain.StableKey.parse(${field.key.quoted()}), " +
            "label = ${field.label.quoted()}"
        val enabled = if (field.enabledWhen.isEmpty()) {
            "null"
        } else {
            "org.quicklauncher.contracts.domain.CapabilityId.parse(${field.enabledWhen.quoted()})"
        }
        return when (field.kind) {
            "BOOLEAN" -> "${prefix}BooleanSetting($common, default = ${field.defaultValue}, enabledWhen = $enabled)"
            "CHOICE" -> {
                val options = field.options.joinToString(", ") { option ->
                    val label = option.replace('-', ' ').replaceFirstChar(Char::uppercase)
                    "${prefix}ChoiceOption(org.quicklauncher.contracts.domain.StableKey.parse(" +
                        "${option.quoted()}), ${label.quoted()})"
                }
                "${prefix}ChoiceSetting($common, options = listOf($options), " +
                    "default = org.quicklauncher.contracts.domain.StableKey.parse(" +
                    "${field.defaultValue.quoted()}), enabledWhen = $enabled)"
            }
            "NUMBER" -> "${prefix}NumberSetting($common, minimum = ${field.minimum}, " +
                "maximum = ${field.maximum}, default = ${field.defaultValue}, enabledWhen = $enabled)"
            "DIMENSION" -> "${prefix}DimensionSetting($common, minimumDp = ${field.minimum}, " +
                "maximumDp = ${field.maximum}, defaultDp = ${field.defaultValue}, enabledWhen = $enabled)"
            "COLOR" -> "${prefix}ColorSetting($common, defaultArgb = ${parseColor(field.defaultValue)}L, " +
                "enabledWhen = $enabled)"
            "FONT" -> "${prefix}FontSetting($common, " +
                "allowedRoles = ${field.allowedFontRoles.enumSet("FontRole")}, " +
                "default = ${prefix}FontRole.${field.defaultValue}, enabledWhen = $enabled)"
            "APP_SELECTOR" -> "${prefix}AppSelectorSetting($common, allowMultiple = ${field.allowMultiple}, " +
                "enabledWhen = $enabled)"
            "CONTENT_SELECTOR" -> "${prefix}ContentSelectorSetting($common, allowMultiple = ${field.allowMultiple}, " +
                "enabledWhen = $enabled)"
            else -> error("Validated setting has unsupported kind '${field.kind}'")
        }
    }

    private fun slotListExpression(slots: List<RawSlot>): String = slots.joinToString(
        separator = ", ",
        prefix = "listOf(",
        postfix = ")",
    ) { slot ->
        "org.quicklauncher.contracts.contribution.SlotDescriptor(" +
            "id = org.quicklauncher.contracts.domain.StableKey.parse(${slot.id.quoted()}), " +
            "type = org.quicklauncher.contracts.domain.SlotTypeId.parse(${slot.type.quoted()}), " +
            "acceptedBlocks = ${slot.acceptedBlocks.typedSet("ContributionId")}, " +
            "requiredCapabilities = ${slot.requiredCapabilities.typedSet("CapabilityId")}, " +
            "maximumChildren = ${slot.maximumChildren}, " +
            "allowedScrollAxes = ${slot.allowedScrollAxes.enumSet("ScrollAxis")})"
    }

    private fun List<String>.typedSet(type: String): String = joinToString(
        separator = ", ",
        prefix = "setOf(",
        postfix = ")",
    ) { value -> "org.quicklauncher.contracts.domain.$type.parse(${value.quoted()})" }

    private fun List<String>.enumSet(type: String): String = joinToString(
        separator = ", ",
        prefix = "setOf(",
        postfix = ")",
    ) { value -> "org.quicklauncher.contracts.contribution.$type.$value" }

    private fun List<String>.stringArray(): String = joinToString(
        separator = ", ",
        prefix = "[",
        postfix = "]",
    ) { it.quoted() }

    private fun parseColor(value: String): Long =
        if (value.startsWith("0x")) value.removePrefix("0x").toLong(16) else value.toLong()

    private fun String.quoted(): String = buildString {
        append('"')
        this@quoted.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
}
