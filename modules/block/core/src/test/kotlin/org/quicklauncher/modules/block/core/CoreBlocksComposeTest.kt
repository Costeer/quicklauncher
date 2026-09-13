@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package org.quicklauncher.modules.block.core

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.contribution.ActiveCancellationSignal
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.ActionSink
import org.quicklauncher.contracts.ui.BlockAction
import org.quicklauncher.contracts.ui.BlockRenderInput
import org.quicklauncher.contracts.ui.BlockRenderState
import org.quicklauncher.contracts.ui.CompositionRole
import org.quicklauncher.contracts.ui.CompositionState
import org.quicklauncher.contracts.ui.PreparedContentItem
import org.quicklauncher.contracts.ui.PreparedContentKind
import org.quicklauncher.contracts.ui.PreparedContentRenderer
import org.quicklauncher.contracts.ui.PreparedHostContent
import org.quicklauncher.contracts.ui.PreparedHostSurface
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.contracts.ui.SlotRenderState
import org.quicklauncher.contracts.ui.SlotRenderer
import org.quicklauncher.testing.fakes.FakeHostStates
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CoreBlocksComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun closeScope() = scope.cancel()

    @Test
    fun `alphabetical block sorts prepared content and emits typed accessible actions`() {
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/alphabetical-compose-test")
        val items = listOf(item("zulu", "Zulu"), item("alpha", "alpha"), item("beta", "Beta"))
        val state = state(items, CompositionState(CompositionRole.CURRENT, isInteractive = true))
        val rendered = mutableListOf<String>()
        val actions = mutableListOf<BlockAction>()
        val session = AlphabeticalAppsBlock.open(
            ContributionContext(instanceId, AlphabeticalAppsCodec.default, ActiveCancellationSignal, scope),
        )

        compose.setContent {
            session.Render(
                BlockRenderInput(
                    instanceId,
                    state,
                    EmptySlotRenderer,
                    RecordingContentRenderer(rendered),
                    ActionSink { action -> actions += action; ActionDispatchResult.Accepted },
                ),
            )
        }
        compose.waitForIdle()

        assertEquals(listOf("alpha", "Beta", "Zulu"), rendered)
        compose.onNodeWithContentDescription("alpha").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("alpha")
            .performCustomAccessibilityActionWithLabel("Open item actions")
        compose.onNodeWithContentDescription("Alphabetical apps")
            .performCustomAccessibilityActionWithLabel("Open block settings")
        assertEquals(
            listOf(
                BlockAction.ActivateItem(items[1].id),
                BlockAction.OpenItemActions(items[1].id),
                BlockAction.OpenSettings(instanceId),
            ),
            actions,
        )
    }

    @Test
    fun `neighbor preview renders content without executable item semantics`() {
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/neighbor-block-test")
        val item = item("preview", "Preview app")
        val session = AppGridBlock.open(
            ContributionContext(instanceId, AppGridCodec.default, ActiveCancellationSignal, scope),
        )
        compose.setContent {
            session.Render(
                BlockRenderInput(
                    instanceId,
                    state(listOf(item), CompositionState(CompositionRole.NEIGHBOR_PREVIEW, false)),
                    EmptySlotRenderer,
                    RecordingContentRenderer(mutableListOf()),
                    ActionSink { throw AssertionError("Neighbor action must not be emitted: $it") },
                ),
            )
        }

        compose.onNodeWithContentDescription("Preview app").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun `search block renders a real query control and filters prepared apps`() {
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/search-compose-test")
        val session = SearchBlock.open(
            ContributionContext(instanceId, SearchCodec.default, ActiveCancellationSignal, scope),
        )
        compose.setContent {
            session.Render(
                BlockRenderInput(
                    instanceId,
                    state(
                        listOf(item("alpha-search", "Alpha"), item("beta-search", "Beta")),
                        CompositionState(CompositionRole.CURRENT, isInteractive = true),
                    ),
                    EmptySlotRenderer,
                    RecordingContentRenderer(mutableListOf()),
                    ActionSink { ActionDispatchResult.Accepted },
                ),
            )
        }

        compose.onNodeWithText("Search apps").performTextInput("bet")
        compose.onNodeWithContentDescription("Beta").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Alpha").assertCountEquals(0)
    }

    @Test
    fun `folder block exposes only closed state and emits the standard typed activation action`() {
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/folder-compose-test")
        val folder = PreparedContentItem(
            ContentItemId.parse("org.quicklauncher.content/folder"),
            "Travel",
            "3 items",
            PreparedContentKind.FOLDER,
            enabled = true,
        )
        val actions = mutableListOf<BlockAction>()
        val renderedMembers = mutableListOf<String>()
        val session = FolderBlock.open(
            ContributionContext(instanceId, FolderCodec.default, ActiveCancellationSignal, scope),
        )
        compose.setContent {
            session.Render(
                BlockRenderInput(
                    instanceId,
                    state(listOf(folder), CompositionState(CompositionRole.CURRENT, true)),
                    EmptySlotRenderer,
                    RecordingContentRenderer(renderedMembers),
                    ActionSink { action -> actions += action; ActionDispatchResult.Accepted },
                ),
            )
        }

        compose.onNodeWithContentDescription("Travel, folder").performClick()

        assertEquals(listOf(BlockAction.ActivateItem(folder.id)), actions)
        assertEquals(emptyList<String>(), renderedMembers)
        compose.onNodeWithText("3 items").assertIsDisplayed()
    }

    @Test
    fun `folder block renders every host prepared folder without owning their identities`() {
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/folder-identity-test")
        val other = PreparedContentItem(
            ContentItemId.parse("org.quicklauncher.content/other-folder"),
            "Other",
            "2 items",
            PreparedContentKind.FOLDER,
            enabled = true,
        )
        val selected = PreparedContentItem(
            ContentItemId.parse("org.quicklauncher.content/selected-folder"),
            "Selected",
            "4 items",
            PreparedContentKind.FOLDER,
            enabled = true,
        )
        val actions = mutableListOf<BlockAction>()
        val session = FolderBlock.open(
            ContributionContext(
                instanceId,
                FolderConfiguration(columns = 2),
                ActiveCancellationSignal,
                scope,
            ),
        )
        compose.setContent {
            session.Render(
                BlockRenderInput(
                    instanceId,
                    state(listOf(other, selected), CompositionState(CompositionRole.CURRENT, true)),
                    EmptySlotRenderer,
                    RecordingContentRenderer(mutableListOf()),
                    ActionSink { action -> actions += action; ActionDispatchResult.Accepted },
                ),
            )
        }

        compose.onNodeWithContentDescription("Selected, folder").performClick()

        compose.onNodeWithContentDescription("Other, folder").assertIsDisplayed()
        assertEquals(listOf(BlockAction.ActivateItem(selected.id)), actions)
    }

    @Test
    fun `folder block reports unavailable when the host prepares no folders`() {
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/missing-folder-test")
        val session = FolderBlock.open(
            ContributionContext(
                instanceId,
                FolderCodec.default,
                ActiveCancellationSignal,
                scope,
            ),
        )
        compose.setContent {
            session.Render(
                BlockRenderInput(
                    instanceId,
                    state(emptyList(), CompositionState(CompositionRole.CURRENT, true)),
                    EmptySlotRenderer,
                    RecordingContentRenderer(mutableListOf()),
                    ActionSink { ActionDispatchResult.Accepted },
                ),
            )
        }

        compose.onNodeWithText("Folder unavailable").assertIsDisplayed()
    }

    private fun state(
        items: List<PreparedContentItem>,
        composition: CompositionState,
    ): BlockRenderState {
        val source = FakeHostStates.block(PreviewScenario.NORMAL)
        return BlockRenderState(
            source.theme,
            source.window,
            source.backgroundContrast,
            source.status,
            source.editorMode,
            composition,
            source.placement,
            PreparedHostContent(items, emptyList()),
            emptyList(),
        )
    }

    private fun item(id: String, label: String) = PreparedContentItem(
        ContentItemId.parse("org.quicklauncher.content/$id"),
        label,
        null,
        PreparedContentKind.APP,
        enabled = true,
    )
}

private object EmptySlotRenderer : SlotRenderer {
    @Composable
    override fun Render(slot: SlotRenderState, modifier: Modifier) = Unit
}

private class RecordingContentRenderer(
    private val rendered: MutableList<String>,
) : PreparedContentRenderer {
    @Composable
    override fun RenderItem(item: PreparedContentItem, modifier: Modifier) {
        rendered += item.label
        BasicText(item.label, modifier)
    }

    @Composable
    override fun RenderSurface(surface: PreparedHostSurface, modifier: Modifier) = Unit
}
