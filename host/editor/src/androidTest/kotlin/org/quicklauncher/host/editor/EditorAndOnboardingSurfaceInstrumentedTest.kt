package org.quicklauncher.host.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.DestinationDraft
import org.quicklauncher.contracts.contribution.DisplayText
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.contribution.ModuleDraft
import org.quicklauncher.contracts.contribution.PositionedDestinationDraft
import org.quicklauncher.contracts.contribution.TemplateCoordinate
import org.quicklauncher.contracts.contribution.TemplatePlan
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.host.data.store.StoreRejection
import org.quicklauncher.host.data.store.StoreRejectionCode
import org.quicklauncher.host.data.store.StoreRevision
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.DestinationInstall
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.StoredConfigurationDocument

@RunWith(AndroidJUnit4::class)
@SdkSuppress(maxSdkVersion = 36) // Espresso 3.7 calls removed InputManager.getInstance on API 37.
class EditorAndOnboardingSurfaceInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mapEditorExposesExactCommandsForMovementAndConfirmedDeletionAtLargeText() {
        val actions = mutableListOf<EditorAction>()
        val state = mapState(
            StoreRejection(
                StoreRejectionCode.CONFIRMATION_REQUIRED,
                "Deleting this destination requires confirmation",
            ),
            EditorConfirmation.DeleteDestination(RIGHT),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                MapEditorSurface(state, actions::add)
            }
        }

        compose.onNodeWithText("Change rejected: Deleting this destination requires confirmation")
            .assertIsDisplayed()
        compose.onNodeWithTag("destination-list")
            .performScrollToNode(hasContentDescription("Drag Right destination"))
        compose.onNodeWithContentDescription("Drag Right destination", useUnmergedTree = true)
            .performTouchInput {
                down(center)
                advanceEventTime(1_000L)
                moveBy(Offset(10f, 0f))
                advanceEventTime(16L)
                moveBy(Offset(120f, 0f))
                advanceEventTime(16L)
                up()
            }
        compose.onNodeWithText("Right").performScrollTo().performClick()
        compose.onNodeWithText("Set as start").performScrollTo().performClick()
        compose.onNodeWithText("Confirm deletion").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(
                EditorAction.Move(setOf(RIGHT), EditorDirection.RIGHT),
                actions[0],
            )
            assertEquals(EditorAction.Move(setOf(RIGHT), EditorDirection.RIGHT), actions[1])
            assertEquals(EditorAction.SetStart(RIGHT), actions[2])
            assertEquals(EditorAction.Delete(RIGHT, confirmed = true), actions[3])
        }
    }

    @Test
    fun mapEditorUiCommitsAtomicallyAndPreservesTheStoreAfterARejectedEdit() = runBlocking {
        val store = seededEditorStore()
        val editorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val editor = DefaultLauncherEditor(store, editorScope)
        try {
            editor.start()
            editor.dispatch(EditorAction.OpenMap)
            val initialRevision = editor.state.value.revision
            compose.setContent {
                val state by editor.state.collectAsState()
                MapEditorSurface(
                    state,
                    onAction = { action -> editorScope.launch { editor.dispatch(action) } },
                )
            }

            compose.onNodeWithText("Include Center destination in move group")
                .performScrollTo()
                .performClick()
            compose.onNodeWithText("Include Right destination in move group")
                .performScrollTo()
                .performClick()
            compose.onNodeWithText("Move selected group down")
                .performScrollTo()
                .performClick()
            compose.waitUntil { editor.state.value.revision != initialRevision }

            val committed = store.read()
            assertEquals(
                setOf(DestinationCoordinate(0, 1), DestinationCoordinate(1, 1)),
                committed.destinations.mapTo(mutableSetOf()) { it.coordinate },
            )

            compose.onNodeWithText("Include Right destination in move group")
                .performScrollTo()
                .performClick()
            compose.onNodeWithText("Move selected group right")
                .performScrollTo()
                .performClick()
            compose.waitUntil { editor.state.value.rejection != null }

            assertEquals(StoreRejectionCode.DISCONNECTED_MAP, editor.state.value.rejection?.code)
            assertEquals(committed, store.read())
            assertEquals(committed.revision, editor.state.value.revision)
        } finally {
            editor.close()
            editorScope.cancel()
        }
    }

    @Test
    fun destinationEditorExposesMoveCopyRemoveAndQuarantineRecoveryCommands() {
        val actions = mutableListOf<EditorAction>()
        val state =
            LauncherEditorState(
                screen = EditorScreen.DESTINATION,
                revision = StoreRevision.ZERO,
                selectedDestinationId = CENTER,
                destinations = emptyList(),
                modules = listOf(
                    EditorModule(
                        id = BLOCK,
                        contributionId = ContributionId.parse("org.quicklauncher.block/apps"),
                        selectedLayout = false,
                        placedBlock = true,
                        quarantined = true,
                        configuration = org.quicklauncher.contracts.contribution.ConfigurationDocument(
                            org.quicklauncher.contracts.domain.ConfigTypeId.parse(
                                "org.quicklauncher.config/apps",
                            ),
                            org.quicklauncher.contracts.domain.SchemaVersion.of(1),
                            org.quicklauncher.contracts.contribution.EncodedConfiguration.of("{}"),
                        ),
                        placement = EditorPlacement(
                            layoutInstanceId = LAYOUT,
                            parentInstanceId = LAYOUT,
                            parentSlotId = org.quicklauncher.contracts.domain.StableKey.parse("content"),
                            index = 1,
                            encoded = "{\"column\":1}",
                            schemaVersion = 1,
                        ),
                    ),
                ),
                rejection = StoreRejection(StoreRejectionCode.CONFIRMATION_REQUIRED, "Confirm removal"),
            )
        var pendingConfirmation by mutableStateOf<EditorConfirmation?>(null)
        compose.setContent {
            DestinationEditorSurface(
                state.withPendingConfirmation(pendingConfirmation),
                onAction = { action ->
                    actions += action
                    pendingConfirmation = when (action) {
                        is EditorAction.RemovePlacedModule -> if (action.confirmed) {
                            null
                        } else {
                            EditorConfirmation.RemovePlacedModule(action.instanceId)
                        }
                        is EditorAction.RemoveQuarantinedModule -> if (action.confirmed) {
                            null
                        } else {
                            EditorConfirmation.RemoveQuarantinedModule(action.instanceId)
                        }
                        else -> pendingConfirmation
                    }
                },
            )
        }

        compose.onNodeWithText("Move earlier").performScrollTo().performClick()
        compose.onNodeWithText("Copy block").performScrollTo().performClick()
        compose.onNodeWithContentDescription(
            "Drag org.quicklauncher.block/apps block",
            useUnmergedTree = true,
        )
            .performScrollTo()
            .performTouchInput {
                down(center)
                advanceEventTime(1_000L)
                moveBy(Offset(0f, -10f))
                advanceEventTime(16L)
                moveBy(Offset(0f, -120f))
                advanceEventTime(16L)
                up()
            }
        compose.onNodeWithText("Remove block").performScrollTo().performClick()
        compose.onNodeWithText("Remove block").performScrollTo().performClick()
        compose.onNodeWithText("Delete unavailable module").performScrollTo().performClick()
        compose.onNodeWithText("Delete unavailable module").performScrollTo().performClick()

        compose.runOnIdle {
            assertTrue(actions[0] is EditorAction.MoveBlock)
            assertTrue(actions[1] is EditorAction.CopyBlock)
            assertEquals(0, (actions[2] as EditorAction.MoveBlock).index)
            assertEquals(EditorAction.RemovePlacedModule(BLOCK, confirmed = false), actions[3])
            assertEquals(EditorAction.RemovePlacedModule(BLOCK, confirmed = true), actions[4])
            assertEquals(EditorAction.RemoveQuarantinedModule(BLOCK, confirmed = false), actions[5])
            assertEquals(EditorAction.RemoveQuarantinedModule(BLOCK, confirmed = true), actions[6])
        }
    }

    @Test
    fun onboardingRequiresPreviewInstallationBeforeTheHomeRoleRequest() {
        var selected: OnboardingTemplate? = null
        var installRequested = false
        var homeRequested = false
        var preview: OnboardingPreview? by mutableStateOf(null)
        var installed by mutableStateOf(false)
        val template = OnboardingTemplate(
            TEMPLATE_ID,
            "Blank",
            "One empty destination",
            "{\"columns\":4}",
            listOf("Grid columns"),
        )
        compose.setContent {
            LauncherOnboardingSurface(
                templates = listOf(template),
                preview = preview,
                installed = installed,
                message = null,
                onPreview = { selectedTemplate, _ ->
                    selected = selectedTemplate
                    preview = OnboardingPreview(selectedTemplate.id, blankPlan(), emptyList())
                },
                onInstall = {
                    installRequested = true
                    installed = true
                },
                onRequestHomeRole = { homeRequested = true },
            )
        }

        compose.onNodeWithText("Use Quicklauncher as Home").assertIsNotEnabled()
        compose.onNodeWithText("Preview configured Blank").performScrollTo().performClick()
        compose.onNodeWithText("Plan preview").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use Quicklauncher as Home").assertIsNotEnabled()
        compose.onNodeWithText("Install this plan").performScrollTo().performClick()
        compose.onNodeWithText("Use Quicklauncher as Home").assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(template, selected)
            assertTrue(installRequested)
            assertTrue(homeRequested)
        }
    }

    private fun mapState(
        rejection: StoreRejection?,
        pendingConfirmation: EditorConfirmation? = null,
    ) = LauncherEditorState(
        screen = EditorScreen.MAP,
        revision = StoreRevision.ZERO,
        selectedDestinationId = null,
        destinations = listOf(EditorDestination(RIGHT, "Right destination", 1, 0, start = false)),
        modules = emptyList(),
        rejection = rejection,
        pendingConfirmation = pendingConfirmation,
    )

    private fun LauncherEditorState.withPendingConfirmation(
        confirmation: EditorConfirmation?,
    ) = LauncherEditorState(
        screen,
        revision,
        selectedDestinationId,
        destinations,
        modules,
        rejection,
        layoutOptions,
        blockOptions,
        dropTargets,
        confirmation,
    )

    private fun blankPlan(): TemplatePlan {
        val configuration = ConfigurationDocument(
            ConfigTypeId.parse("org.quicklauncher.layout/grid-config"),
            SchemaVersion.of(1),
            EncodedConfiguration.of("{\"columns\":4}"),
        )
        return TemplatePlan(
            listOf(
                PositionedDestinationDraft(
                    TemplateCoordinate(0, 0),
                    DestinationDraft(
                        CENTER,
                        DisplayText.of("Center"),
                        ModuleDraft(
                            LAYOUT,
                            ContributionId.parse("org.quicklauncher.layout/grid"),
                            ConfigurationDocumentId.parse("org.quicklauncher.configuration/layout"),
                            configuration,
                        ),
                        blocks = emptyList(),
                        placements = emptyList(),
                    ),
                    isStart = true,
                ),
            ),
        )
    }

    private suspend fun seededEditorStore(): InMemoryLauncherStore = InMemoryLauncherStore().also { store ->
        check(
            store.commit(
                LauncherTransaction(
                    store.read().revision,
                    listOf(LauncherEdit.Bootstrap(editorInstall(CENTER, "Center destination", 0))),
                ),
            ) is CommitResult.Committed,
        )
        check(
            store.commit(
                LauncherTransaction(
                    store.read().revision,
                    listOf(LauncherEdit.InstallDestination(editorInstall(RIGHT, "Right destination", 1))),
                ),
            ) is CommitResult.Committed,
        )
    }

    private fun editorInstall(id: DestinationId, name: String, x: Long): DestinationInstall {
        val suffix = id.value.substringAfterLast('/')
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/$suffix-editor-layout")
        val documentId = ConfigurationDocumentId.parse("org.quicklauncher.configuration/$suffix-editor-layout")
        val contributionId = ContributionId.parse("org.quicklauncher.test/editor-layout")
        return DestinationInstall(
            DestinationRecord(id, name, DestinationCoordinate(x, 0)),
            DestinationLayoutRecord(id, contributionId, instanceId, selected = true),
            ModuleInstanceRecord(instanceId, contributionId, documentId),
            StoredConfigurationDocument(
                documentId,
                ConfigurationDocument(
                    ConfigTypeId.parse("org.quicklauncher.test/editor-layout-config"),
                    SchemaVersion.of(1),
                    EncodedConfiguration.of("{}"),
                ),
            ),
        )
    }

    private companion object {
        val CENTER = DestinationId.parse("org.quicklauncher.destination/center")
        val RIGHT = DestinationId.parse("org.quicklauncher.destination/right")
        val LAYOUT = ModuleInstanceId.parse("org.quicklauncher.instance/layout")
        val BLOCK = ModuleInstanceId.parse("org.quicklauncher.instance/block")
        val TEMPLATE_ID = ContributionId.parse("org.quicklauncher.template/blank")
    }
}
