package org.quicklauncher.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.PlacementId
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.EncodedPlacementData
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.PlacementRecord
import org.quicklauncher.host.data.store.StoredConfigurationDocument
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

class WidgetResizeControlsVisualTest {
    @get:Rule
    val paparazzi = Paparazzi(
        validateAccessibility = true,
        showSystemUi = false,
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            fontScale = 2f,
            nightMode = NightMode.NOTNIGHT,
        ),
    )

    @Test
    fun `launcher resize controls remain usable at large text`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            paparazzi.snapshot(name = "widget-resize-controls-large-text-light-portrait") {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize()) {
                            WidgetResizeControls(
                                snapshot = boundWidgetSnapshot,
                                current = DESTINATION,
                                coordinator = VisualWidgetCoordinator,
                                transientEvents = emptyFlow(),
                                maximumSize = WidgetSize(393, 851),
                                scope = scope,
                                onChanged = {},
                                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                                initiallyOpen = true,
                            )
                        }
                    }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    private object VisualWidgetCoordinator : WidgetCoordinator {
        override suspend fun beginBinding(request: WidgetBindingRequest): WidgetFlowResult = unused()
        override suspend fun beginRestoredBinding(moduleInstanceId: ModuleInstanceId): WidgetFlowResult = unused()
        override suspend fun completePermission(
            moduleInstanceId: ModuleInstanceId,
            accepted: Boolean,
        ): WidgetFlowResult = unused()
        override suspend fun completeConfiguration(
            moduleInstanceId: ModuleInstanceId,
            accepted: Boolean,
        ): WidgetFlowResult = unused()
        override suspend fun cancelBinding(moduleInstanceId: ModuleInstanceId): WidgetFlowResult = unused()
        override suspend fun show(moduleInstanceId: ModuleInstanceId): WidgetSurfaceResult = unused()
        override suspend fun hide(moduleInstanceId: ModuleInstanceId) = Unit
        override suspend fun resize(
            moduleInstanceId: ModuleInstanceId,
            size: WidgetSize,
        ): WidgetResizeResult = unused()
        override suspend fun remove(moduleInstanceId: ModuleInstanceId): WidgetRemovalResult = unused()
        override suspend fun reconcileAfterRecreation(): WidgetReconciliationResult = unused()
        override fun close() = Unit

        private fun unused(): Nothing = error("Visual test does not execute widget platform calls")
    }

    private companion object {
        val DESTINATION = DestinationId.parse("org.quicklauncher.destination/widget-visual")
        val ROOT = ModuleInstanceId.parse("org.quicklauncher.instance/widget-visual-root")
        val WIDGET = ModuleInstanceId.parse("org.quicklauncher.instance/widget-visual")
        val ROOT_CONFIGURATION = ConfigurationDocumentId.parse(
            "org.quicklauncher.configuration/widget-visual-root",
        )
        val WIDGET_CONFIGURATION = ConfigurationDocumentId.parse(
            "org.quicklauncher.configuration/widget-visual",
        )
        val LAYOUT_CONTRIBUTION = ContributionId.parse("org.quicklauncher.layout/single")
        val WIDGET_CONTRIBUTION = ContributionId.parse("org.quicklauncher.block/widget")

        val boundWidgetSnapshot = LauncherSnapshot.restored(
            startDestinationId = DESTINATION,
            destinations = listOf(
                DestinationRecord(DESTINATION, "Home", DestinationCoordinate(0, 0)),
            ),
            destinationLayouts = listOf(
                DestinationLayoutRecord(DESTINATION, LAYOUT_CONTRIBUTION, ROOT, true),
            ),
            moduleInstances = listOf(
                ModuleInstanceRecord(ROOT, LAYOUT_CONTRIBUTION, ROOT_CONFIGURATION),
                ModuleInstanceRecord(WIDGET, WIDGET_CONTRIBUTION, WIDGET_CONFIGURATION),
            ),
            configurationDocuments = listOf(
                configuration(ROOT_CONFIGURATION, "layout"),
                configuration(WIDGET_CONFIGURATION, "widget"),
            ),
            placements = listOf(
                PlacementRecord(
                    PlacementId.parse("org.quicklauncher.placement/widget-visual"),
                    ROOT,
                    ROOT,
                    StableKey.parse("content"),
                    WIDGET,
                    0,
                    EncodedPlacementData.of("{}"),
                ),
            ),
            widgetPlacements = listOf(
                WidgetPlacementRecord(
                    moduleInstanceId = WIDGET,
                    appWidgetId = 41,
                    providerPackage = PackageName.parse("org.quicklauncher.fixture.widget"),
                    providerClassName = "org.quicklauncher.fixture.widget.Provider",
                    profile = ProfileSerial.of(0),
                    intendedWidthDp = 180,
                    intendedHeightDp = 120,
                    bindState = WidgetBindState.BOUND,
                    restoreState = WidgetRestoreState.READY,
                ),
            ),
        )

        fun configuration(id: ConfigurationDocumentId, local: String) = StoredConfigurationDocument(
            id,
            ConfigurationDocument(
                ConfigTypeId.parse("org.quicklauncher.config/$local"),
                SchemaVersion.of(1),
                EncodedConfiguration.of("{}"),
            ),
        )
    }
}
