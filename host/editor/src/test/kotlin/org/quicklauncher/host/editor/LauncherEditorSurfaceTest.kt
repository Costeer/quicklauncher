package org.quicklauncher.host.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.host.data.store.StoreRevision
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w600dp-h3000dp")
class LauncherEditorSurfaceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `map editor exposes commands for operations that also support dragging`() {
        val actions = mutableListOf<EditorAction>()
        compose.setContent {
            MaterialTheme {
                MapEditorSurface(mapState(), actions::add)
            }
        }

        compose.onNodeWithText("Edit destinations").assertIsDisplayed()
        compose.onNodeWithText("Include Home in move group").performClick()
        compose.onNodeWithText("Include Work in move group").performClick()
        compose.onNodeWithText("Move selected group down").performClick()
        compose.onAllNodesWithText("Left")[0].performClick()
        compose.onAllNodesWithText("Edit content")[0].performClick()
        compose.onNodeWithText("Set as start").performClick()
        compose.onAllNodesWithText("Delete destination")[0].performClick()

        assertTrue(
            actions.contains(EditorAction.Move(setOf(HOME, RIGHT), EditorDirection.DOWN)),
        )
        assertTrue(actions.any { it is EditorAction.OpenDestination })
        assertTrue(actions.any { it is EditorAction.SetStart })
        assertTrue(actions.any { it is EditorAction.Delete })
    }

    @Test
    fun `destination editor exposes accessible settings move copy and recovery commands`() {
        val actions = mutableListOf<EditorAction>()
        compose.setContent {
            MaterialTheme {
                DestinationEditorSurface(destinationState(), actions::add)
            }
        }

        compose.onAllNodesWithText("Reset settings", useUnmergedTree = true)[0].performClick()
        compose.onNodeWithText("Add Single layout", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Add Apps block", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Move earlier", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Copy block", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Remove block", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Delete unavailable module", useUnmergedTree = true).performClick()

        assertTrue(actions.any { it is EditorAction.ResetConfiguration })
        assertTrue(actions.any { it is EditorAction.RetainLayout })
        assertTrue(actions.any { it is EditorAction.AddBlock })
        assertTrue(actions.filterIsInstance<EditorAction.AddBlock>().single().parentInstanceId == BLOCK)
        assertTrue(actions.any { it is EditorAction.MoveBlock })
        assertTrue(actions.filterIsInstance<EditorAction.MoveBlock>().any { it.index == 0 })
        assertTrue(actions.any { it is EditorAction.CopyBlock })
        assertTrue(actions.any { it is EditorAction.RemovePlacedModule })
        assertTrue(actions.any { it is EditorAction.RemoveQuarantinedModule })
    }

    @Test
    fun `drag distance maps to one cardinal editor command after the threshold`() {
        assertEquals(EditorDirection.RIGHT, dominantEditorDirection(Offset(60f, 10f), 48f))
        assertEquals(EditorDirection.LEFT, dominantEditorDirection(Offset(-60f, 10f), 48f))
        assertEquals(EditorDirection.DOWN, dominantEditorDirection(Offset(10f, 60f), 48f))
        assertEquals(EditorDirection.UP, dominantEditorDirection(Offset(10f, -60f), 48f))
        assertEquals(null, dominantEditorDirection(Offset(47f, 47f), 48f))
    }

    @Test
    fun `onboarding renders the supplied module preview instead of a plan-only substitute`() {
        val preview = OnboardingPreview(
            TemplateOnboardingTest.TEMPLATE,
            TemplateOnboardingTest.plan(TemplateOnboardingTest.BLOCK),
            emptyList(),
        )
        compose.setContent {
            MaterialTheme {
                LauncherOnboardingSurface(
                    templates = emptyList(),
                    preview = preview,
                    installed = false,
                    message = null,
                    onPreview = { _, _ -> },
                    onInstall = {},
                    onRequestHomeRole = {},
                    previewContent = { Text("Rendered module preview") },
                )
            }
        }

        compose.onNodeWithText("Rendered module preview").assertIsDisplayed()
    }

}

internal fun mapState() = LauncherEditorState(
    EditorScreen.MAP,
    StoreRevision.of(4),
    selectedDestinationId = null,
    destinations = listOf(
        EditorDestination(HOME, "Home", 0, 0, start = true),
        EditorDestination(RIGHT, "Work", 1, 0, start = false),
    ),
    modules = emptyList(),
    rejection = null,
)

internal fun destinationState() = LauncherEditorState(
    EditorScreen.DESTINATION,
    StoreRevision.of(8),
    selectedDestinationId = HOME,
    destinations = listOf(EditorDestination(HOME, "Home", 0, 0, start = true)),
    modules = listOf(
        EditorModule(
            ROOT,
            ContributionId.parse("org.quicklauncher.layout/grid"),
            selectedLayout = true,
            placedBlock = false,
            quarantined = false,
            configuration = editorConfiguration("grid"),
        ),
        EditorModule(
            BLOCK,
            ContributionId.parse("org.quicklauncher.block/alphabetical-apps"),
            selectedLayout = false,
            placedBlock = true,
            quarantined = true,
            configuration = editorConfiguration("apps"),
            placement = EditorPlacement(ROOT, ROOT, StableKey.parse("content"), 1, "{\"span\":1}", 1, 2),
        ),
    ),
    rejection = null,
    layoutOptions = listOf(
        EditorContributionOption(
            ContributionId.parse("org.quicklauncher.layout/single"),
            "Single",
            editorConfiguration("single"),
        ),
    ),
    blockOptions = listOf(
        EditorContributionOption(
            ContributionId.parse("org.quicklauncher.block/apps"),
            "Apps",
            editorConfiguration("available-apps"),
        ),
    ),
    dropTargets = listOf(
        EditorDropTarget(
            ROOT,
            BLOCK,
            StableKey.parse("content"),
            2,
            setOf(ContributionId.parse("org.quicklauncher.block/apps")),
            6,
        ),
    ),
)

private val HOME = DestinationId.parse("org.quicklauncher.destination/home")
private val RIGHT = DestinationId.parse("org.quicklauncher.destination/work")
private val ROOT = ModuleInstanceId.parse("org.quicklauncher.instance/root")
private val BLOCK = ModuleInstanceId.parse("org.quicklauncher.instance/apps")

private fun editorConfiguration(name: String) = ConfigurationDocument(
    ConfigTypeId.parse("org.quicklauncher.config/$name"),
    SchemaVersion.of(1),
    EncodedConfiguration.of("{}"),
)
