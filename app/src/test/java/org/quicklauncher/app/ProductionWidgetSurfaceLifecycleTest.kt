package org.quicklauncher.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.host.runtime.widgets.WidgetBindingRequest
import org.quicklauncher.host.runtime.widgets.WidgetCoordinator
import org.quicklauncher.host.runtime.widgets.WidgetFlowResult
import org.quicklauncher.host.runtime.widgets.WidgetReconciliationResult
import org.quicklauncher.host.runtime.widgets.WidgetRemovalResult
import org.quicklauncher.host.runtime.widgets.WidgetRenderToken
import org.quicklauncher.host.runtime.widgets.WidgetResizeResult
import org.quicklauncher.host.runtime.widgets.WidgetSize
import org.quicklauncher.host.runtime.widgets.WidgetSurfaceResult

class ProductionWidgetSurfaceLifecycleTest {
    @Test
    fun disposalHidesEveryAcquiredSurfaceExactlyOnce() = runBlocking {
        val coordinator = RecordingWidgetCoordinator()
        val lifecycle = WidgetSurfaceLifecycle(coordinator)

        lifecycle.update(linkedSetOf(FIRST, SECOND))
        lifecycle.release()
        lifecycle.release()

        assertEquals(listOf(FIRST, SECOND), coordinator.shown)
        assertEquals(listOf(FIRST, SECOND), coordinator.hidden)
    }

    @Test
    fun visibleSetChangesPreserveSharedSurfaceAndReleaseOnlyDepartedSurface() = runBlocking {
        val coordinator = RecordingWidgetCoordinator()
        val lifecycle = WidgetSurfaceLifecycle(coordinator)

        lifecycle.update(linkedSetOf(FIRST, SECOND))
        lifecycle.update(linkedSetOf(SECOND, THIRD))

        assertEquals(listOf(FIRST, SECOND, THIRD), coordinator.shown)
        assertEquals(listOf(FIRST), coordinator.hidden)
    }

    @Test
    fun cancelledUpdatePropagatesCancellationAfterReleasingAcquiredSurfaces() = runBlocking {
        val blocked = CompletableDeferred<Unit>()
        val coordinator = RecordingWidgetCoordinator(blockedOn = SECOND, blocked = blocked)
        val lifecycle = WidgetSurfaceLifecycle(coordinator)
        val update = async { lifecycle.update(linkedSetOf(FIRST, SECOND)) }
        coordinator.showStarted.await()

        update.cancelAndJoin()

        assertEquals(listOf(FIRST), coordinator.hidden)
    }

    @Test
    fun disposalAttemptsEveryReleaseWhenOneCoordinatorCallFails() = runBlocking {
        val coordinator = RecordingWidgetCoordinator(failingHide = FIRST)
        val lifecycle = WidgetSurfaceLifecycle(coordinator)
        lifecycle.update(linkedSetOf(FIRST, SECOND))

        runCatching { lifecycle.release() }

        assertEquals(listOf(FIRST, SECOND), coordinator.hidden)
    }

    private class RecordingWidgetCoordinator(
        private val blockedOn: ModuleInstanceId? = null,
        private val blocked: CompletableDeferred<Unit>? = null,
        private val failingHide: ModuleInstanceId? = null,
    ) : WidgetCoordinator {
        val shown = mutableListOf<ModuleInstanceId>()
        val hidden = mutableListOf<ModuleInstanceId>()
        val showStarted = CompletableDeferred<Unit>()

        override suspend fun show(moduleInstanceId: ModuleInstanceId): WidgetSurfaceResult {
            shown += moduleInstanceId
            if (moduleInstanceId == blockedOn) {
                showStarted.complete(Unit)
                blocked?.await()
            }
            return WidgetSurfaceResult.Ready(WidgetRenderToken.of("surface-${moduleInstanceId.value}"))
        }

        override suspend fun hide(moduleInstanceId: ModuleInstanceId) {
            hidden += moduleInstanceId
            if (moduleInstanceId == failingHide) error("release failed")
        }

        override suspend fun beginBinding(request: WidgetBindingRequest): WidgetFlowResult = unsupported()
        override suspend fun beginRestoredBinding(moduleInstanceId: ModuleInstanceId): WidgetFlowResult = unsupported()
        override suspend fun completePermission(moduleInstanceId: ModuleInstanceId, accepted: Boolean): WidgetFlowResult = unsupported()
        override suspend fun completeConfiguration(moduleInstanceId: ModuleInstanceId, accepted: Boolean): WidgetFlowResult = unsupported()
        override suspend fun cancelBinding(moduleInstanceId: ModuleInstanceId): WidgetFlowResult = unsupported()
        override suspend fun resize(moduleInstanceId: ModuleInstanceId, size: WidgetSize): WidgetResizeResult = unsupported()
        override suspend fun remove(moduleInstanceId: ModuleInstanceId): WidgetRemovalResult = unsupported()
        override suspend fun reconcileAfterRecreation(): WidgetReconciliationResult = unsupported()
        override fun close() = Unit

        private fun unsupported(): Nothing = error("Not used by widget surface lifecycle tests")
    }

    private companion object {
        val FIRST = ModuleInstanceId.parse("org.quicklauncher.test/widget-surface-first")
        val SECOND = ModuleInstanceId.parse("org.quicklauncher.test/widget-surface-second")
        val THIRD = ModuleInstanceId.parse("org.quicklauncher.test/widget-surface-third")
    }
}
