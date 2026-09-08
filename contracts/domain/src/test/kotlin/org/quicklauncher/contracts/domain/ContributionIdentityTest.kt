package org.quicklauncher.contracts.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContributionIdentityTest {
    @Test
    fun `persisted contribution identities accept a lowercase namespace and local name`() {
        val encoded = "org.quicklauncher.samples/layout"

        assertEquals(encoded, ContributionId.parse(encoded).value)
        assertEquals(encoded, CapabilityId.parse(encoded).value)
        assertEquals(encoded, SlotTypeId.parse(encoded).value)
        assertEquals(encoded, ConfigTypeId.parse(encoded).value)
        assertEquals(encoded, ModuleInstanceId.parse(encoded).value)
        assertEquals(encoded, ContributionTypeId.parse(encoded).value)
        assertEquals(encoded, ConfigurationDocumentId.parse(encoded).value)
        assertEquals(encoded, PlacementId.parse(encoded).value)
        assertEquals(encoded, ThemeProfileId.parse(encoded).value)
        assertEquals(encoded, CrashMarkerId.parse(encoded).value)
    }

    @Test
    fun `persisted contribution identities reject values without a complete namespace`() {
        val invalidValues = listOf(
            "",
            "layout",
            "quicklauncher/layout",
            "org.quicklauncher/",
            "org.quicklauncher/Layout",
            "org.quicklauncher/layout value",
            "org..quicklauncher/layout",
            "org.quicklauncher//layout",
        )

        invalidValues.forEach { value ->
            assertThrows("Expected '$value' to be rejected", IllegalArgumentException::class.java) {
                ContributionId.parse(value)
            }
        }
    }

    @Test
    fun `contract majors start at one`() {
        assertEquals(1, ContractMajor.of(1).value)
        assertThrows(IllegalArgumentException::class.java) { ContractMajor.of(0) }
        assertThrows(IllegalArgumentException::class.java) { ContractMajor.of(-1) }
    }

    @Test
    fun `configuration schema versions start at one independently of contract majors`() {
        val contractMajor = ContractMajor.of(1)
        val schemaVersion = SchemaVersion.of(4)

        assertEquals(1, contractMajor.value)
        assertEquals(4, schemaVersion.value)
        assertThrows(IllegalArgumentException::class.java) { SchemaVersion.of(0) }
    }

    @Test
    fun `local keys reject whitespace and punctuation that cannot round trip`() {
        assertEquals("result-item", StableKey.parse("result-item").value)
        listOf("", "Result", "result item", "result/item", ".result").forEach { value ->
            assertThrows("Expected '$value' to be rejected", IllegalArgumentException::class.java) {
                StableKey.parse(value)
            }
        }
    }

    @Test
    fun `shortcut IDs preserve nonblank platform identifiers`() {
        val encoded = "published:compose/recent items"

        assertEquals(encoded, ShortcutId.parse(encoded).value)
        assertEquals(encoded, ShortcutId.parse(encoded).toString())
    }

    @Test
    fun `shortcut IDs reject empty and whitespace-only identifiers`() {
        listOf("", " ", "\t\n").forEach { value ->
            assertThrows("Expected '$value' to be rejected", IllegalArgumentException::class.java) {
                ShortcutId.parse(value)
            }
        }
    }
}
