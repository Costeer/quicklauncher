package org.quicklauncher.contracts.contribution

import org.quicklauncher.contracts.domain.ContributionTypeId

object DescriptorValidator {
    fun validate(descriptor: ContributionDescriptor): ValidationResult {
        val metadata = descriptor.metadata
        val id = metadata.id.value
        val errors = mutableListOf<ValidationError>()
        val expectedType = expectedType(descriptor)

        if (metadata.typeId != expectedType) {
            errors += error(
                "descriptor.category",
                "descriptor.typeId",
                id,
                "type '${metadata.typeId}' does not match expected type '$expectedType'",
            )
        }
        if (!ContractCompatibility.isSupported(metadata.typeId, metadata.contractMajor)) {
            errors += error(
                "descriptor.unsupported-major",
                "descriptor.contractMajor",
                id,
                "contract major ${metadata.contractMajor} is not supported for '${metadata.typeId}'",
            )
        }

        val selfDependencies = metadata.providedCapabilities intersect metadata.requiredCapabilities
        selfDependencies.sortedBy { it.value }.forEach { capability ->
            errors += error(
                "descriptor.capability-self-dependency",
                "descriptor.capabilities.${capability.value}",
                id,
                "capability '${capability.value}' is both provided and required",
            )
        }

        metadata.settings?.let { settings ->
            when (val validation = SettingsSchemaValidator.validate(settings)) {
                ValidationResult.Valid -> Unit
                is ValidationResult.Invalid -> validation.errors.forEach { settingError ->
                    errors += settingError.copy(
                        message = "Contribution '$id': ${settingError.message}",
                    )
                }
            }
            val declaredCapabilities = metadata.providedCapabilities + metadata.requiredCapabilities
            settings.fields.mapNotNull { it.enabledWhen }.filterNot { it in declaredCapabilities }.forEach {
                errors += error(
                    "descriptor.setting-capability",
                    "descriptor.${it.value}",
                    id,
                    "setting guard capability '${it.value}' is not declared",
                )
            }
        }

        when (descriptor) {
            is LayoutDescriptor -> validateSlots("layout", descriptor.slots, id, errors)
            is BlockDescriptor -> {
                if (descriptor.compatibleSlotTypes.isEmpty()) {
                    errors += error(
                        "block.compatible-slots",
                        "descriptor.compatibleSlotTypes",
                        id,
                        "block must accept at least one slot type",
                    )
                }
                validateSlots("block", descriptor.childSlots, id, errors)
            }
            is SearchProviderDescriptor -> {
                if (descriptor.resultKinds.isEmpty()) {
                    errors += error(
                        "search.result-kinds",
                        "descriptor.resultKinds",
                        id,
                        "search provider must declare at least one result kind",
                    )
                }
                if (descriptor.minimumQueryLength < 0) {
                    errors += error(
                        "search.minimum-query-length",
                        "descriptor.minimumQueryLength",
                        id,
                        "minimum query length must not be negative",
                    )
                }
            }
            is LauncherCommandDescriptor -> {
                if (descriptor.contexts.isEmpty()) {
                    errors += error(
                        "command.contexts",
                        "descriptor.contexts",
                        id,
                        "launcher command must declare at least one context",
                    )
                }
                if (descriptor.resultKinds.isEmpty()) {
                    errors += error(
                        "command.result-kinds",
                        "descriptor.resultKinds",
                        id,
                        "launcher command must declare at least one result kind",
                    )
                }
            }
            is DestinationTemplateDescriptor -> if (descriptor.maximumBlocks < 0) {
                errors += error(
                    "template.maximum-blocks",
                    "descriptor.maximumBlocks",
                    id,
                    "maximum blocks must not be negative",
                )
            }
        }

        return ValidationResult.from(errors)
    }

    private fun expectedType(descriptor: ContributionDescriptor): ContributionTypeId = when (descriptor) {
        is LayoutDescriptor -> ContributionTypes.LAYOUT
        is BlockDescriptor -> ContributionTypes.BLOCK
        is SearchProviderDescriptor -> ContributionTypes.SEARCH_PROVIDER
        is LauncherCommandDescriptor -> ContributionTypes.LAUNCHER_COMMAND
        is DestinationTemplateDescriptor -> ContributionTypes.DESTINATION_TEMPLATE
        else -> error("Unknown contribution descriptor implementation: ${descriptor::class.qualifiedName}")
    }

    private fun validateSlots(
        prefix: String,
        slots: List<SlotDescriptor>,
        contributionId: String,
        errors: MutableList<ValidationError>,
    ) {
        slots.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.forEach { slotId ->
            errors += error(
                "$prefix.duplicate-slot",
                "descriptor.slots.${slotId.value}",
                contributionId,
                "slot '${slotId.value}' is declared more than once",
            )
        }
        slots.filter { it.maximumChildren <= 0 }.forEach { slot ->
            errors += error(
                "$prefix.slot-multiplicity",
                "descriptor.slots.${slot.id.value}.maximumChildren",
                contributionId,
                "slot '${slot.id.value}' maximum children must be greater than zero",
            )
        }
    }

    private fun error(
        code: String,
        path: String,
        contributionId: String,
        detail: String,
    ) = ValidationError(code, path, "Contribution '$contributionId': $detail")
}
