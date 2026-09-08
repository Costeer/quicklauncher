package org.quicklauncher.contracts.contribution

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.StableKey

class DestinationDraftTest {
    @Test
    fun `template plan is a connected immutable map with one start destination`() {
        val center = emptyDestination("center")
        val above = emptyDestination("above")
        val source = mutableListOf(
            PositionedDestinationDraft(TemplateCoordinate(0, 0), center, isStart = true),
            PositionedDestinationDraft(TemplateCoordinate(0, -1), above, isStart = false),
        )

        val plan = TemplatePlan(source)
        source.clear()

        assertEquals(listOf(center, above), plan.destinations.map { it.draft })
        assertEquals(center.id, plan.startDestination.draft.id)
        try {
            @Suppress("UNCHECKED_CAST")
            (plan.destinations as MutableList<PositionedDestinationDraft>).clear()
            fail("Expected immutable destinations")
        } catch (_: UnsupportedOperationException) {
            Unit
        }
    }

    @Test
    fun `template plan rejects invalid map and cross destination identities`() {
        val center = emptyDestination("center")
        val right = emptyDestination("right")

        assertThrows(IllegalArgumentException::class.java) { TemplatePlan(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            TemplatePlan(
                listOf(
                    PositionedDestinationDraft(TemplateCoordinate(0, 0), center, true),
                    PositionedDestinationDraft(TemplateCoordinate(0, 0), right, false),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemplatePlan(
                listOf(
                    PositionedDestinationDraft(TemplateCoordinate(0, 0), center, true),
                    PositionedDestinationDraft(TemplateCoordinate(2, 0), right, false),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemplatePlan(
                listOf(
                    PositionedDestinationDraft(TemplateCoordinate(0, 0), center, true),
                    PositionedDestinationDraft(TemplateCoordinate(1, 0), right, true),
                ),
            )
        }

        val reusedDocument = ModuleDraft(
            ModuleInstanceId.parse("org.quicklauncher.instance/unique"),
            ContributionId.parse("org.quicklauncher.samples/layout"),
            center.layout.configurationDocumentId,
            center.layout.configuration,
        )
        val colliding = DestinationDraft(
            DestinationId.parse("org.quicklauncher.destination/colliding"),
            DisplayText.of("colliding"),
            reusedDocument,
            emptyList(),
            emptyList(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TemplatePlan(
                listOf(
                    PositionedDestinationDraft(TemplateCoordinate(0, 0), center, true),
                    PositionedDestinationDraft(TemplateCoordinate(1, 0), colliding, false),
                ),
            )
        }
    }

    @Test
    fun `template input snapshots typed destination and module identity pools`() {
        val destination = TemplateDestinationInput(
            DestinationId.parse("org.quicklauncher.destination/input"),
            DisplayText.of("Input"),
        )
        val identity = ModuleDraftIdentity(
            ModuleInstanceId.parse("org.quicklauncher.instance/input"),
            ConfigurationDocumentId.parse("org.quicklauncher.configuration/input"),
        )
        val destinations = mutableListOf(destination)
        val identities = mutableListOf(identity)

        val input = TemplateInput(destinations, identities, "configuration", ActiveCancellationSignal)
        destinations.clear()
        identities.clear()

        assertEquals(listOf(destination), input.destinations)
        assertEquals(listOf(identity), input.availableModuleIdentities)
        assertThrows(IllegalArgumentException::class.java) {
            TemplateInput(
                listOf(destination),
                listOf(
                    identity,
                    ModuleDraftIdentity(
                        ModuleInstanceId.parse("org.quicklauncher.instance/second"),
                        identity.configurationDocumentId,
                    ),
                ),
                "configuration",
                ActiveCancellationSignal,
            )
        }
    }

    @Test
    fun `valid nested placement tree is an immutable typed snapshot`() {
        val layout = module("layout", "layout")
        val parent = module("parent", "block")
        val child = module("child", "block")
        val source = mutableListOf(
            placement(layout, parent, "main", 0, "{\"span\":2}"),
            placement(parent, child, "children", 0, "{\"weight\":1}"),
        )

        val draft = DestinationDraft(
            DestinationId.parse("org.quicklauncher.destination/test"),
            DisplayText.of("Test"),
            layout,
            listOf(parent, child),
            source,
        )
        source.clear()

        assertEquals(2, draft.placements.size)
        assertEquals("{\"span\":2}", draft.placements[0].data.encoded.value)
        assertEquals(SchemaVersion.of(1), draft.placements[0].data.schemaVersion)
        try {
            @Suppress("UNCHECKED_CAST")
            (draft.placements as MutableList<PlacementDraft>).clear()
            fail("Expected immutable placements")
        } catch (_: UnsupportedOperationException) {
            Unit
        }
    }

    @Test
    fun `child instance cannot appear in more than one placement`() {
        val layout = module("layout", "layout")
        val child = module("child", "block")

        val failure = assertThrows(IllegalArgumentException::class.java) {
            DestinationDraft(
                DestinationId.parse("org.quicklauncher.destination/test"),
                DisplayText.of("Test"),
                layout,
                listOf(child),
                listOf(
                    placement(layout, child, "main", 0),
                    placement(layout, child, "secondary", 0),
                ),
            )
        }

        assertEquals(
            "Each block instance must be the child of exactly one placement; duplicate " +
                "'org.quicklauncher.instance/child'",
            failure.message,
        )
    }

    @Test
    fun `placements reference only known draft instances and never place the layout root`() {
        val layout = module("layout", "layout")
        val child = module("child", "block")
        val unknown = module("unknown", "block")

        assertDraftRejected("Placement parent must be a known draft instance") {
            draft(layout, listOf(child), listOf(placement(unknown, child, "main", 0)))
        }
        assertDraftRejected("Placement child must be a declared block instance") {
            draft(layout, listOf(child), listOf(placement(layout, unknown, "main", 0)))
        }
        assertDraftRejected("Layout root must not be the child of a placement") {
            draft(layout, listOf(child), listOf(placement(child, layout, "main", 0)))
        }
    }

    @Test
    fun `every declared block is placed exactly once`() {
        val layout = module("layout", "layout")
        val placed = module("placed", "block")
        val missing = module("missing", "block")

        assertDraftRejected("Every declared block must be placed; missing") {
            draft(layout, listOf(placed, missing), listOf(placement(layout, placed, "main", 0)))
        }
    }

    @Test
    fun `module instance identities are unique across root and blocks`() {
        val layout = module("same", "layout")
        val duplicate = module("same", "block")

        assertDraftRejected("Module instance IDs must be unique") {
            draft(layout, listOf(duplicate), emptyList())
        }
    }

    @Test
    fun `placement graph rejects cycles and requires all blocks reachable from root`() {
        val layout = module("layout", "layout")
        val first = module("first", "block")
        val second = module("second", "block")

        assertDraftRejected("Placement graph must be acyclic") {
            draft(
                layout,
                listOf(first, second),
                listOf(
                    placement(first, second, "children", 0),
                    placement(second, first, "children", 0),
                ),
            )
        }
    }

    @Test
    fun `placement indexes are contiguous within each parent slot`() {
        val layout = module("layout", "layout")
        val first = module("first", "block")
        val second = module("second", "block")
        val otherSlot = module("other", "block")

        assertDraftRejected("Placement indexes must be contiguous from zero") {
            draft(
                layout,
                listOf(first, second, otherSlot),
                listOf(
                    placement(layout, first, "main", 0),
                    placement(layout, second, "main", 2),
                    placement(layout, otherSlot, "secondary", 0),
                ),
            )
        }
    }

    @Test
    fun `placement data schema is positive at its typed parsing boundary`() {
        assertThrows(IllegalArgumentException::class.java) { SchemaVersion.of(0) }
    }

    private fun draft(
        layout: ModuleDraft,
        blocks: List<ModuleDraft>,
        placements: List<PlacementDraft>,
    ) = DestinationDraft(
        DestinationId.parse("org.quicklauncher.destination/test"),
        DisplayText.of("Test"),
        layout,
        blocks,
        placements,
    )

    private fun assertDraftRejected(message: String, create: () -> DestinationDraft) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            create()
            Unit
        }
        if (failure.message?.contains(message) != true) {
            fail("Expected '$message' in '${failure.message}'")
        }
    }

    private fun module(instance: String, contribution: String) = ModuleDraft(
        ModuleInstanceId.parse("org.quicklauncher.instance/$instance"),
        ContributionId.parse("org.quicklauncher.samples/$contribution"),
        ConfigurationDocumentId.parse("org.quicklauncher.configuration/$instance"),
        ConfigurationDocument(
            ConfigTypeId.parse("org.quicklauncher.samples/$contribution-config"),
            SchemaVersion.of(1),
            EncodedConfiguration.of("{}"),
        ),
    )

    private fun emptyDestination(key: String) = DestinationDraft(
        DestinationId.parse("org.quicklauncher.destination/$key"),
        DisplayText.of(key),
        module("$key-layout", "layout"),
        emptyList(),
        emptyList(),
    )

    private fun placement(
        parent: ModuleDraft,
        child: ModuleDraft,
        slot: String,
        index: Int,
        data: String = "{}",
    ) = PlacementDraft(
        parent.instanceId,
        StableKey.parse(slot),
        child.instanceId,
        index,
        PlacementData(SchemaVersion.of(1), EncodedPlacementData.of(data)),
    )
}
