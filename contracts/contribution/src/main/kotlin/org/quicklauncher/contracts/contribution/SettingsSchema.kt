package org.quicklauncher.contracts.contribution

import java.util.Collections
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.StableKey

private fun <T> immutableSettingList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSettingSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

class SettingsSchema(fields: Collection<SettingField>) {
    val fields: List<SettingField> = immutableSettingList(fields)

    override fun equals(other: Any?): Boolean = other is SettingsSchema && fields == other.fields

    override fun hashCode(): Int = fields.hashCode()

    override fun toString(): String = "SettingsSchema(fields=$fields)"
}

sealed interface SettingField {
    val key: StableKey
    val label: String
    val enabledWhen: CapabilityId?
}

data class BooleanSetting(
    override val key: StableKey,
    override val label: String,
    val default: Boolean,
    override val enabledWhen: CapabilityId? = null,
) : SettingField

data class ChoiceOption(
    val key: StableKey,
    val label: String,
)

class ChoiceSetting(
    override val key: StableKey,
    override val label: String,
    options: Collection<ChoiceOption>,
    val default: StableKey,
    override val enabledWhen: CapabilityId? = null,
) : SettingField {
    val options: List<ChoiceOption> = immutableSettingList(options)

    override fun equals(other: Any?): Boolean = other is ChoiceSetting &&
        key == other.key && label == other.label && options == other.options &&
        default == other.default && enabledWhen == other.enabledWhen

    override fun hashCode(): Int = arrayOf(key, label, options, default, enabledWhen).contentHashCode()

    override fun toString(): String =
        "ChoiceSetting(key=$key, label=$label, options=$options, default=$default, enabledWhen=$enabledWhen)"
}

data class NumberSetting(
    override val key: StableKey,
    override val label: String,
    val minimum: Int,
    val maximum: Int,
    val default: Int,
    override val enabledWhen: CapabilityId? = null,
) : SettingField

data class DimensionSetting(
    override val key: StableKey,
    override val label: String,
    val minimumDp: Int,
    val maximumDp: Int,
    val defaultDp: Int,
    override val enabledWhen: CapabilityId? = null,
) : SettingField

data class ColorSetting(
    override val key: StableKey,
    override val label: String,
    val defaultArgb: Long,
    override val enabledWhen: CapabilityId? = null,
) : SettingField

enum class FontRole {
    DISPLAY,
    HEADLINE,
    TITLE,
    BODY,
    LABEL,
}

class FontSetting(
    override val key: StableKey,
    override val label: String,
    allowedRoles: Collection<FontRole>,
    val default: FontRole,
    override val enabledWhen: CapabilityId? = null,
) : SettingField {
    val allowedRoles: Set<FontRole> = immutableSettingSet(allowedRoles)

    override fun equals(other: Any?): Boolean = other is FontSetting &&
        key == other.key && label == other.label && allowedRoles == other.allowedRoles &&
        default == other.default && enabledWhen == other.enabledWhen

    override fun hashCode(): Int = arrayOf(key, label, allowedRoles, default, enabledWhen).contentHashCode()

    override fun toString(): String =
        "FontSetting(key=$key, label=$label, allowedRoles=$allowedRoles, " +
            "default=$default, enabledWhen=$enabledWhen)"
}

data class AppSelectorSetting(
    override val key: StableKey,
    override val label: String,
    val allowMultiple: Boolean,
    override val enabledWhen: CapabilityId? = null,
) : SettingField

object SettingsSchemaValidator {
    fun validate(schema: SettingsSchema): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        val duplicateKeys = schema.fields
            .groupingBy { it.key }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        duplicateKeys.sortedBy { it.value }.forEach { key ->
            errors += error(
                code = "settings.duplicate-key",
                path = "settings.${key.value}",
                message = "Setting key '${key.value}' is declared more than once",
            )
        }

        schema.fields.forEach { field ->
            val path = "settings.${field.key.value}"
            if (field.label.isBlank()) {
                errors += error(
                    code = "settings.blank-label",
                    path = path,
                    message = "Setting '${field.key.value}' must have a display label",
                )
            }

            when (field) {
                is BooleanSetting -> Unit
                is ChoiceSetting -> validateChoice(field, path, errors)
                is NumberSetting -> validateRange(
                    path = path,
                    minimum = field.minimum,
                    maximum = field.maximum,
                    default = field.default,
                    prefix = "number",
                    errors = errors,
                )
                is DimensionSetting -> validateRange(
                    path = path,
                    minimum = field.minimumDp,
                    maximum = field.maximumDp,
                    default = field.defaultDp,
                    prefix = "dimension",
                    errors = errors,
                )
                is ColorSetting -> if (field.defaultArgb !in 0..0xffffffffL) {
                    errors += error(
                        code = "settings.color-default",
                        path = path,
                        message = "Color default must be an unsigned 32-bit ARGB value",
                    )
                }
                is FontSetting -> validateFont(field, path, errors)
                is AppSelectorSetting -> Unit
            }
        }

        return ValidationResult.from(errors)
    }

    private fun validateChoice(
        field: ChoiceSetting,
        path: String,
        errors: MutableList<ValidationError>,
    ) {
        if (field.options.isEmpty()) {
            errors += error(
                code = "settings.choice-options",
                path = path,
                message = "Choice setting '${field.key.value}' must declare at least one option",
            )
        }
        val duplicateOptions = field.options.groupingBy { it.key }.eachCount().any { it.value > 1 }
        if (duplicateOptions) {
            errors += error(
                code = "settings.choice-duplicate-option",
                path = path,
                message = "Choice setting '${field.key.value}' declares a duplicate option key",
            )
        }
        if (field.options.none { it.key == field.default }) {
            errors += error(
                code = "settings.choice-default",
                path = path,
                message = "Choice default '${field.default.value}' is not a declared option",
            )
        }
        field.options.filter { it.label.isBlank() }.forEach { option ->
            errors += error(
                code = "settings.choice-option-label",
                path = "$path.${option.key.value}",
                message = "Choice option '${option.key.value}' must have a display label",
            )
        }
    }

    private fun validateRange(
        path: String,
        minimum: Int,
        maximum: Int,
        default: Int,
        prefix: String,
        errors: MutableList<ValidationError>,
    ) {
        if (minimum > maximum) {
            errors += error(
                code = "settings.$prefix-range",
                path = path,
                message = "Minimum $minimum must not exceed maximum $maximum",
            )
        }
        if (default !in minimum..maximum) {
            errors += error(
                code = "settings.$prefix-default",
                path = path,
                message = "Default $default must be between $minimum and $maximum",
            )
        }
    }

    private fun validateFont(
        field: FontSetting,
        path: String,
        errors: MutableList<ValidationError>,
    ) {
        if (field.allowedRoles.isEmpty()) {
            errors += error(
                code = "settings.font-roles",
                path = path,
                message = "Font setting '${field.key.value}' must allow at least one font role",
            )
        }
        if (field.default !in field.allowedRoles) {
            errors += error(
                code = "settings.font-default",
                path = path,
                message = "Font default ${field.default} is not an allowed role",
            )
        }
    }

    private fun error(code: String, path: String, message: String): ValidationError =
        ValidationError(code = code, path = path, message = message)
}
