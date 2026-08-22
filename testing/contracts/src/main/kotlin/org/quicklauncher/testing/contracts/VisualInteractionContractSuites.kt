@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package org.quicklauncher.testing.contracts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.CancellationSignal
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.ActionSink
import org.quicklauncher.contracts.ui.BlockAction
import org.quicklauncher.contracts.ui.BlockRenderInput
import org.quicklauncher.contracts.ui.LayoutAction
import org.quicklauncher.contracts.ui.LayoutRenderInput
import org.quicklauncher.contracts.ui.PreparedContentItem
import org.quicklauncher.contracts.ui.PreparedContentRenderer
import org.quicklauncher.contracts.ui.PreparedHostSurface
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.contracts.ui.SlotRenderState
import org.quicklauncher.contracts.ui.SlotRenderer
import org.quicklauncher.contracts.domain.StableKey

abstract class LayoutVisualInteractionContractSuite<C : Any> {
    protected abstract val contract: LayoutContractDeclaration<C>
    abstract val compose: ComposeContentTestRule

    @Test
    fun `layout semantics dispatch rejected typed actions once render slots and dispose owned work`() {
        val fixture = contract.fixtures.single { it.scenario == PreviewScenario.NORMAL }
        val expectedLabel = contract.previewAccessibility.getValue(PreviewScenario.NORMAL)
            .semantics.single().label
        val lifecycle = InteractionLifecycle()
        val session = contract.target.open(
            ContributionContext(contract.instanceId, contract.codec.default, ActiveSignal, lifecycle.scope),
        )
        val slots = InteractionSlotRenderer()
        val attempts = mutableListOf<LayoutAction>()
        val show = mutableStateOf(true)

        compose.setContent {
            if (show.value) {
                session.Render(
                    LayoutRenderInput(
                        contract.instanceId,
                        fixture.state,
                        slots,
                        rejectingSink(attempts),
                    ),
                )
            }
        }

        val root = compose.onNodeWithContentDescription(expectedLabel)
            .assertIsDisplayed()
            .assertHasClickAction()
        root.performClick()
        assertEquals(fixture.expectedActions.size - 1, fixture.expectedCustomActionLabels.size)
        fixture.expectedCustomActionLabels.forEach(root::performCustomAccessibilityActionWithLabel)
        compose.waitForIdle()

        assertEquals(fixture.expectedActions, attempts)
        assertEquals(fixture.state.slots.map { it.id }, slots.rendered.map { it.id })
        assertTrue(lifecycle.hasActiveChildren)

        compose.runOnIdle { show.value = false }
        compose.waitForIdle()
        awaitNoActiveChildren(lifecycle)
        assertTrue(session.isClosed)
        lifecycle.close()
    }
}

abstract class BlockVisualInteractionContractSuite<C : Any> {
    protected abstract val contract: BlockContractDeclaration<C>
    abstract val compose: ComposeContentTestRule

    @Test
    fun `block semantics dispatch rejected typed actions once render host content and dispose owned work`() {
        val fixture = contract.fixtures.single { it.scenario == PreviewScenario.NORMAL }
        val expectedLabel = contract.previewAccessibility.getValue(PreviewScenario.NORMAL)
            .semantics.single().label
        val lifecycle = InteractionLifecycle()
        val session = contract.target.open(
            ContributionContext(contract.instanceId, contract.codec.default, ActiveSignal, lifecycle.scope),
        )
        val slots = InteractionSlotRenderer()
        val content = InteractionPreparedContentRenderer()
        val attempts = mutableListOf<BlockAction>()
        val show = mutableStateOf(true)

        compose.setContent {
            if (show.value) {
                session.Render(
                    BlockRenderInput(
                        contract.instanceId,
                        fixture.state,
                        slots,
                        content,
                        rejectingSink(attempts),
                    ),
                )
            }
        }

        val root = compose.onNodeWithContentDescription(expectedLabel)
            .assertIsDisplayed()
            .assertHasClickAction()
        root.performClick()
        assertEquals(fixture.expectedActions.size - 1, fixture.expectedCustomActionLabels.size)
        fixture.expectedCustomActionLabels.forEach(root::performCustomAccessibilityActionWithLabel)
        compose.waitForIdle()

        assertEquals(fixture.expectedActions, attempts)
        assertEquals(fixture.state.childSlots.map { it.id }, slots.rendered.map { it.id })
        assertEquals(fixture.state.content.items.map { it.id }, content.items.map { it.id })
        assertEquals(fixture.state.content.surfaces.map { it.key }, content.surfaces.map { it.key })
        assertTrue(lifecycle.hasActiveChildren)

        compose.runOnIdle { show.value = false }
        compose.waitForIdle()
        awaitNoActiveChildren(lifecycle)
        assertTrue(session.isClosed)
        lifecycle.close()
    }
}

private fun <A> rejectingSink(attempts: MutableList<A>): ActionSink<A> = ActionSink { action ->
    attempts += action
    ActionDispatchResult.Rejected(
        StableKey.parse("contract-test-rejection"),
        "The contract host deliberately rejected this action",
    )
}

private object ActiveSignal : CancellationSignal {
    override val isCancelled: Boolean = false
}

private class InteractionLifecycle : AutoCloseable {
    private val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Default)
    val hasActiveChildren: Boolean get() = job.children.any { it.isActive }

    override fun close() = scope.cancel()
}

private class InteractionSlotRenderer : SlotRenderer {
    val rendered = mutableListOf<SlotRenderState>()

    @Composable
    override fun Render(slot: SlotRenderState, modifier: Modifier) {
        rendered += slot
    }
}

private class InteractionPreparedContentRenderer : PreparedContentRenderer {
    val items = mutableListOf<PreparedContentItem>()
    val surfaces = mutableListOf<PreparedHostSurface>()

    @Composable
    override fun RenderItem(item: PreparedContentItem, modifier: Modifier) {
        items += item
    }

    @Composable
    override fun RenderSurface(surface: PreparedHostSurface, modifier: Modifier) {
        surfaces += surface
    }
}

private fun awaitNoActiveChildren(lifecycle: InteractionLifecycle) {
    try {
        runBlocking {
            withTimeout(1_000) {
                while (lifecycle.hasActiveChildren) yield()
            }
        }
    } catch (cancelled: CancellationException) {
        throw AssertionError("Owned contribution work did not terminate", cancelled)
    }
}
