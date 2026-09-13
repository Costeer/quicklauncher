package org.quicklauncher.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.data.store.WidgetBindState
import org.quicklauncher.host.data.store.WidgetPlacementRecord
import org.quicklauncher.host.data.store.WidgetRestoreState
import org.quicklauncher.host.runtime.widgets.WidgetBindingRequest
import org.quicklauncher.host.runtime.widgets.WidgetCoordinator
import org.quicklauncher.host.runtime.widgets.WidgetFlowResult
import org.quicklauncher.host.runtime.widgets.WidgetReconciliationResult
import org.quicklauncher.host.runtime.widgets.WidgetRemovalResult
import org.quicklauncher.host.runtime.widgets.WidgetResizeResult
import org.quicklauncher.host.runtime.widgets.WidgetSize
import org.quicklauncher.host.runtime.widgets.WidgetSurfaceResult

class ProductionWidgetResizeTest {
    @Test
    fun keyboardWidthIncreaseUsesTheCoordinatorResizeSeam() = runBlocking {
        val coordinator = RecordingWidgetCoordinator()

        val result = resizeWidget(coordinator, placement(widthDp = 180), WidgetResizeCommand.WIDER)

        assertEquals(WidgetResizeResult.Resized, result)
        assertEquals(listOf(WIDGET_INSTANCE to WidgetSize(220, 120)), coordinator.requests)
    }

    @Test
    fun keyboardShrinkStopsAtTheMinimumUsableSize() = runBlocking {
        val coordinator = RecordingWidgetCoordinator()

        resizeWidget(coordinator, placement(widthDp = 60, heightDp = 40), WidgetResizeCommand.NARROWER)
        resizeWidget(coordinator, placement(widthDp = 40, heightDp = 60), WidgetResizeCommand.SHORTER)

        assertEquals(
            listOf(
                WIDGET_INSTANCE to WidgetSize(40, 40),
                WIDGET_INSTANCE to WidgetSize(40, 40),
            ),
            coordinator.requests,
        )
    }

    @Test
    fun heightIncreasePreservesTheDurableWidth() = runBlocking {
        val coordinator = RecordingWidgetCoordinator(WidgetResizeResult.Unavailable("profile_unavailable"))

        val result = resizeWidget(coordinator, placement(widthDp = 200), WidgetResizeCommand.TALLER)

        assertEquals(WidgetResizeResult.Unavailable("profile_unavailable"), result)
        assertEquals(listOf(WIDGET_INSTANCE to WidgetSize(200, 160)), coordinator.requests)
    }

    @Test
    fun widthIncreaseNeverShrinksAnExistingOversizedWidget() = runBlocking {
        val coordinator = RecordingWidgetCoordinator()

        resizeWidget(
            coordinator,
            placement(widthDp = 500),
            WidgetResizeCommand.WIDER,
            maximumSize = WidgetSize(360, 800),
        )

        assertEquals(listOf(WIDGET_INSTANCE to WidgetSize(500, 120)), coordinator.requests)
    }

    private fun placement(
        widthDp: Int = 180,
        heightDp: Int = 120,
    ) = WidgetPlacementRecord(
        moduleInstanceId = WIDGET_INSTANCE,
        appWidgetId = 41,
        providerPackage = PackageName.parse("org.quicklauncher.fixture.widget"),
        providerClassName = "org.quicklauncher.fixture.widget.Provider",
        profile = ProfileSerial.of(0),
        intendedWidthDp = widthDp,
        intendedHeightDp = heightDp,
        bindState = WidgetBindState.BOUND,
        restoreState = WidgetRestoreState.READY,
    )

    private class RecordingWidgetCoordinator(
        private val result: WidgetResizeResult = WidgetResizeResult.Resized,
    ) : WidgetCoordinator {
        val requests = mutableListOf<Pair<ModuleInstanceId, WidgetSize>>()

        override suspend fun resize(
            moduleInstanceId: ModuleInstanceId,
            size: WidgetSize,
        ): WidgetResizeResult {
            requests += moduleInstanceId to size
            return result
        }

        override suspend fun beginBinding(request: WidgetBindingRequest) = unsupported()
        override suspend fun beginRestoredBinding(moduleInstanceId: ModuleInstanceId) = unsupported()
        override suspend fun completePermission(moduleInstanceId: ModuleInstanceId, accepted: Boolean) = unsupported()
        override suspend fun completeConfiguration(moduleInstanceId: ModuleInstanceId, accepted: Boolean) = unsupported()
        override suspend fun cancelBinding(moduleInstanceId: ModuleInstanceId) = unsupported()
        override suspend fun show(moduleInstanceId: ModuleInstanceId): WidgetSurfaceResult = unsupported()
        override suspend fun hide(moduleInstanceId: ModuleInstanceId) = Unit
        override suspend fun remove(moduleInstanceId: ModuleInstanceId): WidgetRemovalResult = unsupported()
        override suspend fun reconcileAfterRecreation(): WidgetReconciliationResult = unsupported()
        override fun close() = Unit

        private fun unsupported(): Nothing = error("Not used by resize tests")
    }

    private companion object {
        val WIDGET_INSTANCE = ModuleInstanceId.parse("org.quicklauncher.test/widget-resize")
    }
}
