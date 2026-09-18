@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package org.quicklauncher.app

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.EncodedPlacementData
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherPlanInstallation
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.PlacementPolicy
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

@RunWith(AndroidJUnit4::class)
@SdkSuppress(maxSdkVersion = 36) // Espresso 3.7 calls removed InputManager.getInstance on API 37.
class WidgetResizeControlsInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `real resize controls focus and key activate every non-drag request`() {
        val snapshot = boundWidgetSnapshot()
        val coordinator = RecordingWidgetCoordinator()
        var changed = 0
        compose.setContent {
            WidgetResizeControls(
                snapshot = snapshot,
                current = DESTINATION,
                coordinator = coordinator,
                transientEvents = emptyFlow(),
                maximumSize = WidgetSize(240, 200),
                scope = rememberCoroutineScope(),
                onChanged = { changed += 1 },
            )
        }

        compose.onNodeWithText("Resize widgets")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("Widget 1: 180 by 120 dp").assertIsDisplayed()

        activate("Decrease width", Key.Enter, coordinator, 1)
        activate("Increase width", Key.DirectionCenter, coordinator, 2)
        activate("Decrease height", Key.Enter, coordinator, 3)
        activate("Increase height", Key.DirectionCenter, coordinator, 4)

        assertEquals(
            listOf(
                WIDGET to WidgetSize(140, 120),
                WIDGET to WidgetSize(180, 120),
                WIDGET to WidgetSize(180, 80),
                WIDGET to WidgetSize(180, 120),
            ),
            coordinator.requests,
        )
        assertEquals(4, changed)
    }

    private fun activate(
        label: String,
        key: Key,
        coordinator: RecordingWidgetCoordinator,
        expectedRequests: Int,
    ) {
        compose.onNodeWithContentDescription(label)
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performKeyInput { pressKey(key) }
        compose.waitUntil(timeoutMillis = 5_000) {
            coordinator.requests.size == expectedRequests
        }
    }

    private fun boundWidgetSnapshot(): LauncherSnapshot = runBlocking {
        val root = ModuleInstanceRecord(ROOT, LAYOUT_CONTRIBUTION, ROOT_CONFIGURATION)
        val widget = ModuleInstanceRecord(WIDGET, WIDGET_CONTRIBUTION, WIDGET_CONFIGURATION)
        val store = InMemoryLauncherStore(placementPolicy = PlacementPolicy { null })
        val installed = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(
                    LauncherEdit.InstallLauncherPlan(
                        LauncherPlanInstallation(
                            startDestinationId = DESTINATION,
                            destinations = listOf(
                                DestinationRecord(DESTINATION, "Home", DestinationCoordinate(0, 0)),
                            ),
                            layouts = listOf(
                                DestinationLayoutRecord(DESTINATION, LAYOUT_CONTRIBUTION, ROOT, true),
                            ),
                            moduleInstances = listOf(root, widget),
                            configurations = listOf(
                                configuration(ROOT_CONFIGURATION, "layout"),
                                configuration(WIDGET_CONFIGURATION, "widget"),
                            ),
                            placements = listOf(
                                PlacementRecord(
                                    PLACEMENT,
                                    ROOT,
                                    ROOT,
                                    StableKey.parse("content"),
                                    WIDGET,
                                    0,
                                    EncodedPlacementData.of("{}"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(installed is CommitResult.Committed)
        val placement = WidgetPlacementRecord(
            moduleInstanceId = WIDGET,
            appWidgetId = 41,
            providerPackage = PackageName.parse("org.quicklauncher.fixture.widget"),
            providerClassName = "org.quicklauncher.fixture.widget.Provider",
            profile = ProfileSerial.of(0),
            intendedWidthDp = 180,
            intendedHeightDp = 120,
            bindState = WidgetBindState.PENDING,
            restoreState = WidgetRestoreState.READY,
        )
        val binding = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(LauncherEdit.BeginWidgetBinding(placement)),
            ),
        )
        assertTrue(binding is CommitResult.Committed)
        val bound = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(LauncherEdit.CompleteWidgetBinding(WIDGET)),
            ),
        )
        assertTrue(bound is CommitResult.Committed)
        store.read()
    }

    private fun configuration(id: ConfigurationDocumentId, local: String) = StoredConfigurationDocument(
        id,
        ConfigurationDocument(
            ConfigTypeId.parse("org.quicklauncher.config/$local"),
            SchemaVersion.of(1),
            EncodedConfiguration.of("{}"),
        ),
    )

    private class RecordingWidgetCoordinator : WidgetCoordinator {
        val requests = mutableListOf<Pair<ModuleInstanceId, WidgetSize>>()

        override suspend fun resize(
            moduleInstanceId: ModuleInstanceId,
            size: WidgetSize,
        ): WidgetResizeResult {
            requests += moduleInstanceId to size
            return WidgetResizeResult.Resized
        }

        override suspend fun beginBinding(request: WidgetBindingRequest): WidgetFlowResult = unsupported()
        override suspend fun beginRestoredBinding(moduleInstanceId: ModuleInstanceId): WidgetFlowResult = unsupported()
        override suspend fun completePermission(
            moduleInstanceId: ModuleInstanceId,
            accepted: Boolean,
        ): WidgetFlowResult = unsupported()
        override suspend fun completeConfiguration(
            moduleInstanceId: ModuleInstanceId,
            accepted: Boolean,
        ): WidgetFlowResult = unsupported()
        override suspend fun cancelBinding(moduleInstanceId: ModuleInstanceId): WidgetFlowResult = unsupported()
        override suspend fun show(moduleInstanceId: ModuleInstanceId): WidgetSurfaceResult = unsupported()
        override suspend fun hide(moduleInstanceId: ModuleInstanceId) = Unit
        override suspend fun remove(moduleInstanceId: ModuleInstanceId): WidgetRemovalResult = unsupported()
        override suspend fun reconcileAfterRecreation(): WidgetReconciliationResult = unsupported()
        override fun close() = Unit

        private fun unsupported(): Nothing = error("Not used by resize control test")
    }

    private companion object {
        val DESTINATION = DestinationId.parse("org.quicklauncher.destination/widget-focus")
        val ROOT = ModuleInstanceId.parse("org.quicklauncher.instance/widget-focus-root")
        val WIDGET = ModuleInstanceId.parse("org.quicklauncher.instance/widget-focus")
        val ROOT_CONFIGURATION = ConfigurationDocumentId.parse(
            "org.quicklauncher.configuration/widget-focus-root",
        )
        val WIDGET_CONFIGURATION = ConfigurationDocumentId.parse(
            "org.quicklauncher.configuration/widget-focus",
        )
        val PLACEMENT = PlacementId.parse("org.quicklauncher.placement/widget-focus")
        val LAYOUT_CONTRIBUTION = ContributionId.parse("org.quicklauncher.layout/single")
        val WIDGET_CONTRIBUTION = ContributionId.parse("org.quicklauncher.block/widget")
    }
}
