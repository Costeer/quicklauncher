package org.quicklauncher.modules.templates.core

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.ActiveCancellationSignal
import org.quicklauncher.contracts.contribution.CancellationSignal
import org.quicklauncher.contracts.contribution.ModuleDraftIdentity
import org.quicklauncher.contracts.contribution.PositionedDestinationDraft
import org.quicklauncher.contracts.contribution.TemplateCoordinate
import org.quicklauncher.contracts.contribution.TemplateDestinationInput
import org.quicklauncher.contracts.contribution.TemplateInput
import org.quicklauncher.contracts.contribution.TemplateResult
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.modules.templates.core.generated.CoreTemplateRegistry
import org.quicklauncher.testing.contracts.RegisteredContributionContractSuite

class CoreTemplatesTest {
    @Test
    fun `every template satisfies the reusable public contribution contract suite`() {
        RegisteredContributionContractSuite.verify(CoreTemplateRegistry.entries)
    }

    @Test
    fun `modular plan positions center entry and apps as one connected map`() {
        val result = ModularTemplate.create(
            input(
                destinationNames = listOf("Center", "Entry", "Apps"),
                moduleCount = 7,
                configuration = PopulatedTemplateConfiguration(true, true),
            ),
        ) as TemplateResult.Created

        assertEquals(
            listOf(TemplateCoordinate(0, 0), TemplateCoordinate(0, -1), TemplateCoordinate(1, 0)),
            result.plan.destinations.map(PositionedDestinationDraft::coordinate),
        )
        assertEquals("Center", result.plan.startDestination.draft.name.value)
        assertEquals(
            listOf(2, 1, 1),
            result.plan.destinations.map { it.draft.blocks.size },
        )
        assertEquals(
            listOf("favorites", "clock-date"),
            result.plan.destinations[0].draft.blocks.map { it.contributionId.value.substringAfterLast('/') },
        )
        assertEquals(
            "search",
            result.plan.destinations[1].draft.blocks.single().contributionId.value.substringAfterLast('/'),
        )
        assertEquals(
            "alphabetical-apps",
            result.plan.destinations[2].draft.blocks.single().contributionId.value.substringAfterLast('/'),
        )
        assertTrue(result.plan.destinations.flatMap { it.draft.placements }.all { it.data.schemaVersion.value == 1 })
    }

    @Test
    fun `traditional and blank plans use supplied typed identities and explicit placements`() {
        val traditional = TraditionalTemplate.create(
            input(
                destinationNames = listOf("Home", "Apps"),
                moduleCount = 5,
                configuration = PopulatedTemplateConfiguration(true, true),
            ),
        ) as TemplateResult.Created
        val blank = BlankTemplate.create(
            input(
                destinationNames = listOf("Blank"),
                moduleCount = 1,
                configuration = BlankTemplateConfiguration(6),
            ),
        ) as TemplateResult.Created

        assertEquals(2, traditional.plan.destinations.size)
        assertEquals(3, traditional.plan.destinations.sumOf { it.draft.blocks.size })
        assertEquals(3, traditional.plan.destinations.sumOf { it.draft.placements.size })
        assertEquals(0, blank.plan.destinations.single().draft.blocks.size)
        assertEquals("{\"columns\":6}", blank.plan.destinations.single().draft.layout.configuration.encoded.value)
        val modules = traditional.plan.destinations.flatMap { listOf(it.draft.layout) + it.draft.blocks }
        assertEquals(modules.size, modules.map { it.configurationDocumentId }.toSet().size)
    }

    @Test
    fun `every template owns a distinct configuration type and round trips defaults`() {
        val codecs = listOf(ModularTemplateCodec, TraditionalTemplateCodec, BlankTemplateCodec)
        assertEquals(codecs.size, codecs.map { it.configType }.toSet().size)
        assertEquals(
            org.quicklauncher.contracts.contribution.CodecResult.Decoded(ModularTemplateCodec.default),
            ModularTemplateCodec.decode(ModularTemplateCodec.encode(ModularTemplateCodec.default)),
        )
        assertEquals(
            org.quicklauncher.contracts.contribution.CodecResult.Decoded(BlankTemplateCodec.default),
            BlankTemplateCodec.decode(BlankTemplateCodec.encode(BlankTemplateCodec.default)),
        )
    }

    @Test
    fun `insufficient identity pool is typed invalid and cancellation propagates`() {
        val invalid = ModularTemplate.create(
            input(
                destinationNames = listOf("Center", "Entry", "Apps"),
                moduleCount = 1,
                configuration = PopulatedTemplateConfiguration(true, true),
            ),
        )
        assertTrue(invalid is TemplateResult.Invalid)

        assertThrows(CancellationException::class.java) {
            ModularTemplate.create(
                input(
                    destinationNames = listOf("Center", "Entry", "Apps"),
                    moduleCount = 7,
                    configuration = PopulatedTemplateConfiguration(true, true),
                    cancellation = object : CancellationSignal {
                        override val isCancelled = true
                    },
                ),
            )
        }
    }

    private fun <C : Any> input(
        destinationNames: List<String>,
        moduleCount: Int,
        configuration: C,
        cancellation: CancellationSignal = ActiveCancellationSignal,
    ): TemplateInput<C> = TemplateInput(
        destinationNames.mapIndexed { index, name ->
            TemplateDestinationInput(
                DestinationId.parse("org.quicklauncher.templatetest/destination-$index"),
                org.quicklauncher.contracts.contribution.DisplayText.of(name),
            )
        },
        List(moduleCount) { index ->
            ModuleDraftIdentity(
                ModuleInstanceId.parse("org.quicklauncher.templatetest/module-$index"),
                ConfigurationDocumentId.parse("org.quicklauncher.templatetest/configuration-$index"),
            )
        },
        configuration,
        cancellation,
    )
}
