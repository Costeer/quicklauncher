package org.quicklauncher.registry.ksp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryAggregationTest {
    @Test
    fun `platform neutral aggregation sorts entries independently of fragment order`() {
        val layout = fragment(
            name = "sample.LayoutFragment",
            fragmentId = "org.quicklauncher.fragment/layout",
            entry = entry(
                index = 0,
                id = "org.quicklauncher.samples/layout",
                config = "org.quicklauncher.samples/layout-config",
                category = RegistrationKind.LAYOUT.persistedTypeId,
            ),
        )
        val block = fragment(
            name = "sample.BlockFragment",
            fragmentId = "org.quicklauncher.fragment/block",
            entry = entry(
                index = 0,
                id = "org.quicklauncher.samples/block",
                config = "org.quicklauncher.samples/block-config",
                category = RegistrationKind.BLOCK.persistedTypeId,
                compatibleSlots = listOf("org.quicklauncher.slot/content"),
            ),
        )

        val forward = RegistryAggregationValidator.validate(CategoryCatalog.firstRelease(), listOf(layout, block))
            as AggregationValidationResult.Valid
        val reverse = RegistryAggregationValidator.validate(CategoryCatalog.firstRelease(), listOf(block, layout))
            as AggregationValidationResult.Valid

        assertEquals(forward, reverse)
        assertEquals(
            listOf("org.quicklauncher.samples/block", "org.quicklauncher.samples/layout"),
            forward.entries.map { it.contributionId.value },
        )
        assertEquals(listOf(0, 0), forward.entries.map { it.index })
    }

    @Test
    fun `platform neutral aggregation rejects persisted configuration duplicates across fragments`() {
        val first = fragment(
            "sample.First",
            "org.quicklauncher.fragment/first",
            entry(0, "org.quicklauncher.samples/first", "org.quicklauncher.samples/shared", RegistrationKind.LAYOUT.persistedTypeId),
        )
        val second = fragment(
            "sample.Second",
            "org.quicklauncher.fragment/second",
            entry(0, "org.quicklauncher.samples/second", "org.quicklauncher.samples/shared", RegistrationKind.BLOCK.persistedTypeId),
        )

        val invalid = RegistryAggregationValidator.validate(CategoryCatalog.firstRelease(), listOf(first, second))
            as AggregationValidationResult.Invalid

        assertTrue(invalid.issues.any { "[registry.duplicate-config-type]" in it && "shared" in it })
    }

    @Test
    fun `cross fragment accepted blocks must support their declared slot type`() {
        val parent = fragment(
            "sample.Parent",
            "org.quicklauncher.fragment/parent",
            entry(
                0,
                "org.quicklauncher.samples/parent",
                "org.quicklauncher.samples/parent-config",
                RegistrationKind.BLOCK.persistedTypeId,
                compatibleSlots = listOf("org.quicklauncher.slot/content"),
                childSlots = listOf(
                    RawRegistryFragmentSlot(
                        "org.quicklauncher.slot/nested",
                        listOf("org.quicklauncher.samples/child"),
                    ),
                ),
            ),
        )
        val child = fragment(
            "sample.Child",
            "org.quicklauncher.fragment/child",
            entry(
                0,
                "org.quicklauncher.samples/child",
                "org.quicklauncher.samples/child-config",
                RegistrationKind.BLOCK.persistedTypeId,
                compatibleSlots = listOf("org.quicklauncher.slot/content"),
            ),
        )

        val invalid = RegistryAggregationValidator.validate(CategoryCatalog.firstRelease(), listOf(parent, child))
            as AggregationValidationResult.Invalid

        assertTrue(invalid.issues.any { "[registry.slot-compatibility]" in it && "slot/nested" in it })
    }

    private fun fragment(
        name: String,
        fragmentId: String,
        entry: RawRegistryFragmentEntry,
    ) = RawRegistryFragment(name, fragmentId, listOf(entry))

    private fun entry(
        index: Int,
        id: String,
        config: String,
        category: String,
        compatibleSlots: List<String> = emptyList(),
        childSlots: List<RawRegistryFragmentSlot> = emptyList(),
    ) = RawRegistryFragmentEntry(
        index = index,
        contributionId = id,
        configTypeId = config,
        categoryTypeId = category,
        providedCapabilities = emptyList(),
        requiredCapabilities = emptyList(),
        compatibleSlotTypes = compatibleSlots,
        childSlots = childSlots,
    )
}
