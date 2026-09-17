package org.quicklauncher.registry.production

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.ContributionTypes
import org.quicklauncher.contracts.domain.CapabilityId

class ProductionRegistryTest {
    @Test
    fun `production fragments aggregate all reference contributions in stable order`() {
        assertEquals(ContributionTypes.firstRelease, productionRegistry.categoryIds)
        assertEquals(
            listOf(
                "org.quicklauncher.block/alphabetical-apps",
                "org.quicklauncher.block/app-grid",
                "org.quicklauncher.block/clock-date",
                "org.quicklauncher.block/favorites",
                "org.quicklauncher.block/folder",
                "org.quicklauncher.block/search",
                "org.quicklauncher.block/widget",
                "org.quicklauncher.template/blank",
                "org.quicklauncher.template/modular",
                "org.quicklauncher.template/traditional",
                "org.quicklauncher.command/open-search",
                "org.quicklauncher.command/open-settings",
                "org.quicklauncher.layout/grid",
                "org.quicklauncher.layout/single-block",
                "org.quicklauncher.search/apps",
                "org.quicklauncher.search/commands",
                "org.quicklauncher.search/contacts",
                "org.quicklauncher.search/files",
                "org.quicklauncher.search/graphene-settings",
                "org.quicklauncher.search/information",
                "org.quicklauncher.search/settings",
                "org.quicklauncher.search/shortcuts",
                "org.quicklauncher.search/web",
            ),
            productionRegistry.entries.map { it.descriptor.metadata.id.value },
        )
    }

    @Test
    fun `production registry keeps contribution and configuration identities unique and safe layout reserved`() {
        val metadata = productionRegistry.entries.map { it.descriptor.metadata }
        assertEquals(metadata.size, metadata.map { it.id }.toSet().size)
        assertEquals(metadata.size, metadata.map { it.configType }.toSet().size)
        assertFalse(metadata.any { it.id.value == "org.quicklauncher.core/safe-layout" })
        assertFalse(metadata.any { it.configType.value == "org.quicklauncher.core/safe-layout" })
        assertTrue(metadata.all { it.contractMajor.value == 1 })
    }

    @Test
    fun `theme accent settings expose only explicitly registered contributions`() {
        val capability = CapabilityId.parse("org.quicklauncher.capability/theme-accent")
        assertEquals(
            listOf(
                "org.quicklauncher.block/app-grid",
                "org.quicklauncher.block/search",
                "org.quicklauncher.layout/grid",
            ),
            productionRegistry.entries
                .filter { capability in it.descriptor.metadata.providedCapabilities }
                .map { it.descriptor.metadata.id.value },
        )
    }
}
