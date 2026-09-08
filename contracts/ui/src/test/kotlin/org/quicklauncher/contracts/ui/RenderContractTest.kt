package org.quicklauncher.contracts.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.SlotTypeId
import org.quicklauncher.contracts.domain.StableKey

class RenderContractTest {
    @Test
    fun `render state snapshots caller collections`() {
        val placements = mutableListOf(
            PlacedChild(
                ModuleInstanceId.parse("org.quicklauncher.instance/child"),
                ContributionId.parse("org.quicklauncher.samples/child"),
                0,
                PlacementRenderData(
                    schemaVersion = 3,
                    encoded = EncodedPlacement.of("{\"column\":2}"),
                ),
            ),
        )
        val source = mutableListOf(
            SlotRenderState(
                id = StableKey.parse("main"),
                type = SlotTypeId.parse("org.quicklauncher.slot/content"),
                status = RenderStatus.Ready,
                placements = placements,
            ),
        )
        val state = LayoutRenderState(
            theme = theme(),
            window = WindowInfo(360, 800, WindowOrientation.PORTRAIT),
            backgroundContrast = BackgroundContrast(7f, 4.5f, prefersLightForeground = true),
            status = RenderStatus.Ready,
            editorMode = EditorMode.BROWSING,
            composition = CompositionState(CompositionRole.CURRENT, isInteractive = true),
            placement = PlacementState.root(),
            slots = source,
        )

        source.clear()
        placements.clear()

        assertEquals(listOf("main"), state.slots.map { it.id.value })
        assertEquals(1, state.slots.single().placements.size)
        assertEquals(3, state.slots.single().placements.single().placement.schemaVersion)
        assertEquals(
            "{\"column\":2}",
            state.slots.single().placements.single().placement.encoded.value,
        )
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (state.slots as MutableList<SlotRenderState>).clear()
        }
    }

    @Test
    fun `placement render data requires a positive schema version`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlacementRenderData(0, EncodedPlacement.of("{}"))
        }
    }

    @Test
    fun `prepared content snapshots items and exposes no mutable host state`() {
        val items = mutableListOf(
            PreparedContentItem(
                id = ContentItemId.parse("org.quicklauncher.content/sample"),
                label = "Sample",
                supportingText = null,
                kind = PreparedContentKind.APP,
                enabled = true,
            ),
        )

        val content = PreparedHostContent(items, emptyList())
        items.clear()

        assertEquals(1, content.items.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (content.items as MutableList<PreparedContentItem>).clear()
        }
    }

    @Test
    fun `typed action sink reports whether the host accepted an action`() {
        val accepted = ActionSink<LayoutAction> { ActionDispatchResult.Accepted }
        val rejected = ActionSink<LayoutAction> {
            ActionDispatchResult.Rejected(StableKey.parse("disposed"), "Instance is disposed")
        }
        val action = LayoutAction.OpenSettings(
            ModuleInstanceId.parse("org.quicklauncher.instance/layout-one"),
        )

        assertEquals(ActionDispatchResult.Accepted, accepted.emit(action))
        assertTrue(rejected.emit(action) is ActionDispatchResult.Rejected)
    }

    @Test
    fun `accessibility validation identifies duplicate focus order and missing semantics`() {
        val duplicate = StableKey.parse("primary")
        val declaration = AccessibilityDeclaration(
            semantics = listOf(
                AccessibilitySemantics(
                    id = duplicate,
                    label = "",
                    role = AccessibilityRole.BUTTON,
                    stateDescription = null,
                    actions = emptyList(),
                ),
            ),
            focusOrder = listOf(duplicate, duplicate),
            supportsLargeText = false,
            supportsKeyboardNavigation = true,
            supportsReducedMotion = true,
        )

        val errors = AccessibilityDeclarationValidator.validate(declaration)

        assertEquals(
            setOf("accessibility.blank-label", "accessibility.duplicate-focus", "accessibility.large-text"),
            errors.map { it.code }.toSet(),
        )
    }

    @Test
    fun `accessibility declarations are immutable value snapshots`() {
        val actions = mutableListOf(
            AccessibilityActionDeclaration(StableKey.parse("activate"), "Activate"),
        )
        val semantics = mutableListOf(
            AccessibilitySemantics(
                StableKey.parse("primary"),
                "Primary",
                AccessibilityRole.BUTTON,
                null,
                actions,
            ),
        )
        val focus = mutableListOf(StableKey.parse("primary"))
        val first = AccessibilityDeclaration(semantics, focus, true, true, true)
        val second = AccessibilityDeclaration(
            listOf(
                AccessibilitySemantics(
                    StableKey.parse("primary"),
                    "Primary",
                    AccessibilityRole.BUTTON,
                    null,
                    listOf(AccessibilityActionDeclaration(StableKey.parse("activate"), "Activate")),
                ),
            ),
            listOf(StableKey.parse("primary")),
            true,
            true,
            true,
        )

        actions.clear()
        semantics.clear()
        focus.clear()

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(1, first.semantics.single().actions.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (first.semantics as MutableList<AccessibilitySemantics>).clear()
        }
    }

    @Test
    fun `accessibility validation rejects duplicate semantics and action identifiers`() {
        val duplicateSemantics = StableKey.parse("primary")
        val duplicateAction = StableKey.parse("activate")
        val declaration = AccessibilityDeclaration(
            semantics = listOf(
                AccessibilitySemantics(
                    duplicateSemantics,
                    "Primary",
                    AccessibilityRole.BUTTON,
                    null,
                    listOf(
                        AccessibilityActionDeclaration(duplicateAction, "Activate"),
                        AccessibilityActionDeclaration(duplicateAction, "Open"),
                    ),
                ),
                AccessibilitySemantics(
                    duplicateSemantics,
                    "Duplicate",
                    AccessibilityRole.TEXT,
                    null,
                    emptyList(),
                ),
            ),
            focusOrder = listOf(duplicateSemantics),
            supportsLargeText = true,
            supportsKeyboardNavigation = true,
            supportsReducedMotion = true,
        )

        assertEquals(
            setOf("accessibility.duplicate-semantics", "accessibility.duplicate-action"),
            AccessibilityDeclarationValidator.validate(declaration).map { it.code }.toSet(),
        )
    }

    private fun theme() = LauncherTheme(
        mode = ThemeMode.DARK,
        foreground = ArgbColor.of(0xffeeeeee),
        background = ArgbColor.of(0xff111111),
        accent = ArgbColor.of(0xff4488ff),
        textScale = 1f,
        reducedMotion = false,
    )
}
