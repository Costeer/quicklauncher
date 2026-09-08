package org.quicklauncher.registry.ksp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryValidatorTest {
    @Test
    fun `valid registrations are sorted by persisted category ID then contribution ID`() {
        val registrations = listOf(
            valid(RegistrationKind.SEARCH_PROVIDER, "org.quicklauncher.samples/search"),
            valid(RegistrationKind.BLOCK, "org.quicklauncher.samples/block"),
            valid(RegistrationKind.LAYOUT, "org.quicklauncher.samples/layout"),
        )

        val result = RegistryValidator.validate(CategoryCatalog.firstRelease(), registrations)
            as RegistryValidationResult.Valid

        assertEquals(
            listOf(
                "org.quicklauncher.samples/block",
                "org.quicklauncher.samples/layout",
                "org.quicklauncher.samples/search",
            ),
            result.registrations.map { it.id.value },
        )
    }

    @Test
    fun `validation reports duplicate identities missing declarations and wrong contracts`() {
        val first = valid(RegistrationKind.LAYOUT, "org.quicklauncher.samples/duplicate")
        val second = valid(RegistrationKind.BLOCK, "org.quicklauncher.samples/duplicate").copy(
            configTypeId = first.configTypeId,
            codecName = MissingNames.CODEC,
            contractTestsName = MissingNames.CONTRACT_TESTS,
            targetImplementsContract = false,
        )

        val invalid = RegistryValidator.validate(CategoryCatalog.firstRelease(), listOf(first, second))
            as RegistryValidationResult.Invalid

        assertEquals(
            setOf(
                "registry.duplicate-contribution-id",
                "registry.duplicate-config-type",
                "registry.missing-codec",
                "registry.missing-contract-tests",
                "registry.target-contract",
            ),
            invalid.issues.map { it.code }.toSet(),
        )
        assertTrue(invalid.issues.all { it.message.contains("org.quicklauncher.samples") })
    }

    @Test
    fun `capability prerequisites reject a canonical two node cycle`() {
        val first = valid(RegistrationKind.LAYOUT, "org.quicklauncher.samples/first").copy(
            providedCapabilities = listOf("org.quicklauncher.capability/first"),
            requiredCapabilities = listOf("org.quicklauncher.capability/second"),
        )
        val second = valid(RegistrationKind.BLOCK, "org.quicklauncher.samples/second").copy(
            providedCapabilities = listOf("org.quicklauncher.capability/second"),
            requiredCapabilities = listOf("org.quicklauncher.capability/first"),
        )

        val invalid = RegistryValidator.validate(CategoryCatalog.firstRelease(), listOf(first, second))
            as RegistryValidationResult.Invalid

        val cycle = invalid.issues.single { it.code == "registry.capability-cycle" }
        assertTrue(cycle.message.contains("first -> org.quicklauncher.capability/second"))
        assertTrue(cycle.message.contains("second -> org.quicklauncher.capability/first"))
    }

    @Test
    fun `host owned safe layout identity cannot be registered as a contribution`() {
        val registration = valid(
            RegistrationKind.LAYOUT,
            "org.quicklauncher.core/safe-layout",
        )

        val invalid = RegistryValidator.validate(CategoryCatalog.firstRelease(), listOf(registration))
            as RegistryValidationResult.Invalid

        val issue = invalid.issues.single { it.code == "registry.reserved-safe-layout-identity" }
        assertEquals(registration.targetName, issue.targetName)
        assertTrue(issue.message.contains("contribution ID 'org.quicklauncher.core/safe-layout'"))
        assertTrue(issue.message.contains("reserved for the host-owned safe layout"))
    }

    @Test
    fun `host owned safe layout identity cannot be registered as a configuration type`() {
        val registration = valid(
            RegistrationKind.LAYOUT,
            "org.quicklauncher.samples/reserved-config",
        ).copy(
            configTypeId = "org.quicklauncher.core/safe-layout",
            codecManifestConfigTypeId = "org.quicklauncher.core/safe-layout",
        )

        val invalid = RegistryValidator.validate(CategoryCatalog.firstRelease(), listOf(registration))
            as RegistryValidationResult.Invalid

        val issue = invalid.issues.single { it.code == "registry.reserved-safe-layout-identity" }
        assertEquals(registration.targetName, issue.targetName)
        assertTrue(issue.message.contains("configuration type ID 'org.quicklauncher.core/safe-layout'"))
        assertTrue(issue.message.contains("reserved for the host-owned safe layout"))
    }

    private fun valid(kind: RegistrationKind, id: String): RawRegistration = RawRegistration(
        targetName = "org.quicklauncher.samples.${id.substringAfter('/')}.Target",
        kind = kind,
        id = id,
        contractMajor = 1,
        configTypeId = "$id-config",
        displayName = id.substringAfter('/'),
        description = "Valid sample contribution",
        providedCapabilities = emptyList(),
        requiredCapabilities = emptyList(),
        settings = null,
        codecName = "org.quicklauncher.samples.${id.substringAfter('/')}.Codec",
        contractTestsName = "org.quicklauncher.samples.${id.substringAfter('/')}.ContractTests",
        targetImplementsContract = true,
        targetIsObject = true,
        codecImplementsContract = true,
        codecIsObject = true,
        testsImplementContract = true,
        testsAreObject = true,
        testsImplementCategoryContract = true,
        specific = when (kind) {
            RegistrationKind.LAYOUT -> RawSpecificDescriptor.Layout(
                listOf(
                    RawSlot(
                        id = "main",
                        type = "org.quicklauncher.slot/content",
                        acceptedBlocks = emptyList(),
                        requiredCapabilities = emptyList(),
                        maximumChildren = 1,
                        allowedScrollAxes = emptyList(),
                    ),
                ),
            )
            RegistrationKind.BLOCK -> RawSpecificDescriptor.Block(
                compatibleSlotTypes = listOf("org.quicklauncher.slot/content"),
                childSlots = emptyList(),
            )
            RegistrationKind.SEARCH_PROVIDER -> RawSpecificDescriptor.SearchProvider(
                resultKinds = listOf("APP"),
                minimumQueryLength = 0,
            )
            RegistrationKind.LAUNCHER_COMMAND -> RawSpecificDescriptor.LauncherCommand(
                contexts = listOf("SEARCH"),
                resultKinds = listOf("HOST_ACTION"),
            )
            RegistrationKind.DESTINATION_TEMPLATE -> RawSpecificDescriptor.DestinationTemplate(
                requiredContributions = emptyList(),
                maximumBlocks = 0,
            )
        },
        targetConfigurationType = "org.quicklauncher.samples.SampleConfiguration",
        codecConfigurationType = "org.quicklauncher.samples.SampleConfiguration",
        codecManifestConfigTypeId = "$id-config",
        codecHasManifest = true,
        contractTestsConfigurationType = "org.quicklauncher.samples.SampleConfiguration",
        contractTestsCategory = kind.name,
        contractTestsContributionId = id,
        contractTestsScenarios = listOf(
            "EMPTY",
            "NORMAL",
            "LOADING",
            "PERMISSION_DENIED",
            "PROFILE_LOCKED",
            "LARGE_TEXT",
            "ERROR",
        ),
        contractTestsPerformanceMetrics = kind.requiredPerformanceMetrics.toList(),
        contractTestsHaveManifest = true,
    )
}
