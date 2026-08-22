package org.quicklauncher.testing.fakes

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.LayoutAction
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.contracts.ui.RenderStatus
import org.quicklauncher.contracts.ui.CompositionRole
import org.quicklauncher.contracts.ui.PlacementMode
import org.quicklauncher.contracts.ui.ThemeMode
import org.quicklauncher.contracts.ui.WindowOrientation

class FakeHostStateTest {
    @Test
    fun `fake host states deterministically cover every required scenario`() {
        val states = PreviewScenario.entries.associateWith(FakeHostStates::layout)

        assertEquals(PreviewScenario.entries.toSet(), states.keys)
        assertEquals(RenderStatus.Empty, states.getValue(PreviewScenario.EMPTY).status)
        assertEquals(RenderStatus.Ready, states.getValue(PreviewScenario.NORMAL).status)
        assertEquals(RenderStatus.Loading, states.getValue(PreviewScenario.LOADING).status)
        assertTrue(states.getValue(PreviewScenario.PERMISSION_DENIED).status is RenderStatus.PermissionDenied)
        assertTrue(states.getValue(PreviewScenario.PROFILE_LOCKED).status is RenderStatus.ProfileLocked)
        assertTrue(states.getValue(PreviewScenario.ERROR).status is RenderStatus.Error)
        assertEquals(2f, states.getValue(PreviewScenario.LARGE_TEXT).theme.textScale)
        assertEquals(CompositionRole.entries.toSet(), states.values.map { it.composition.role }.toSet())
        assertEquals(PlacementMode.entries.toSet(), states.values.map { it.placement.mode }.toSet())

        val independent = FakeHostStates.layout(
            PreviewScenario.NORMAL,
            WindowOrientation.LANDSCAPE,
            ThemeMode.LIGHT,
            reducedMotion = true,
        )
        assertEquals(WindowOrientation.LANDSCAPE, independent.window.orientation)
        assertEquals(ThemeMode.LIGHT, independent.theme.mode)
        assertTrue(independent.theme.reducedMotion)
        assertEquals(1f, independent.theme.textScale)
        assertTrue(FakeHostStates.block(PreviewScenario.NORMAL).content.surfaces.isNotEmpty())
    }

    @Test
    fun `fake cancellation and action sinks expose cancellation and disposal`() {
        val cancellation = FakeCancellationSignal()
        cancellation.cancel()
        assertThrows(java.util.concurrent.CancellationException::class.java) { cancellation.ensureActive() }

        val sink = RecordingActionSink<LayoutAction>()
        val action = LayoutAction.OpenSettings(
            ModuleInstanceId.parse("org.quicklauncher.instance/test-layout"),
        )
        assertEquals(ActionDispatchResult.Accepted, sink.emit(action))
        sink.dispose()
        assertTrue(sink.emit(action) is ActionDispatchResult.Rejected)
        assertEquals(listOf(action), sink.actions)
    }

    @Test
    fun `fake instance lifecycle cancels owned work`() = runBlocking {
        val lifecycle = FakeInstanceLifecycle()
        val owned = lifecycle.scope.launch { awaitCancellation() }

        assertTrue(lifecycle.hasActiveChildren)
        lifecycle.close()
        owned.join()

        assertTrue(owned.isCancelled)
        assertTrue(lifecycle.isClosed)
        assertTrue(!lifecycle.hasActiveChildren)
    }
}
