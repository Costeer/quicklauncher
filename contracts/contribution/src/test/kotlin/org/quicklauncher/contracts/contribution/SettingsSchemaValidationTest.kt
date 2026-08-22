package org.quicklauncher.contracts.contribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.CapabilityId
import org.quicklauncher.contracts.domain.StableKey

class SettingsSchemaValidationTest {
    @Test
    fun `nested settings collections are immutable snapshots`() {
        val options = mutableListOf(ChoiceOption(StableKey.parse("compact"), "Compact"))
        val roles = linkedSetOf(FontRole.BODY)
        val choice = ChoiceSetting(
            key = StableKey.parse("density"),
            label = "Density",
            options = options,
            default = StableKey.parse("compact"),
        )
        val font = FontSetting(
            key = StableKey.parse("font"),
            label = "Font",
            allowedRoles = roles,
            default = FontRole.BODY,
        )
        val schema = SettingsSchema(listOf(choice, font))

        options.clear()
        roles.clear()

        assertEquals(listOf("compact"), choice.options.map { it.key.value })
        assertEquals(setOf(FontRole.BODY), font.allowedRoles)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (choice.options as MutableList<ChoiceOption>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (font.allowedRoles as MutableSet<FontRole>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (schema.fields as MutableList<SettingField>).clear()
        }
    }

    @Test
    fun `a complete declarative settings schema is valid`() {
        val capability = CapabilityId.parse("org.quicklauncher.capability/app-data")
        val schema = SettingsSchema(
            fields = listOf(
                BooleanSetting(StableKey.parse("show-labels"), "Show labels", true),
                ChoiceSetting(
                    key = StableKey.parse("density"),
                    label = "Density",
                    options = listOf(
                        ChoiceOption(StableKey.parse("compact"), "Compact"),
                        ChoiceOption(StableKey.parse("comfortable"), "Comfortable"),
                    ),
                    default = StableKey.parse("comfortable"),
                ),
                NumberSetting(StableKey.parse("columns"), "Columns", 2, 12, 4),
                DimensionSetting(StableKey.parse("gap"), "Gap", 0, 48, 8),
                ColorSetting(StableKey.parse("accent"), "Accent", 0xff336699),
                FontSetting(
                    StableKey.parse("label-font"),
                    "Label font",
                    allowedRoles = setOf(FontRole.BODY, FontRole.LABEL),
                    default = FontRole.LABEL,
                ),
                AppSelectorSetting(
                    StableKey.parse("apps"),
                    "Apps",
                    allowMultiple = true,
                    enabledWhen = capability,
                ),
            ),
        )

        assertEquals(ValidationResult.Valid, SettingsSchemaValidator.validate(schema))
    }

    @Test
    fun `schema validation reports every actionable field error`() {
        val duplicateKey = StableKey.parse("density")
        val schema = SettingsSchema(
            fields = listOf(
                ChoiceSetting(
                    key = duplicateKey,
                    label = "",
                    options = listOf(ChoiceOption(StableKey.parse("compact"), "Compact")),
                    default = StableKey.parse("missing"),
                ),
                NumberSetting(duplicateKey, "Columns", minimum = 8, maximum = 2, default = 12),
                FontSetting(
                    StableKey.parse("font"),
                    "Font",
                    allowedRoles = emptySet(),
                    default = FontRole.BODY,
                ),
            ),
        )

        val result = SettingsSchemaValidator.validate(schema) as ValidationResult.Invalid

        assertEquals(
            setOf(
                "settings.duplicate-key",
                "settings.blank-label",
                "settings.choice-default",
                "settings.number-range",
                "settings.number-default",
                "settings.font-roles",
                "settings.font-default",
            ),
            result.errors.map { it.code }.toSet(),
        )
        assertTrue(result.errors.all { it.path.startsWith("settings.") })
        assertTrue(result.errors.all { it.message.isNotBlank() })
    }
}
