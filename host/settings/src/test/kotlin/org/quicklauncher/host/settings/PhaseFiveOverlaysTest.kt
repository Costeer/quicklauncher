package org.quicklauncher.host.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.host.runtime.actions.ItemAction
import org.quicklauncher.host.runtime.actions.ItemActionCommand
import org.quicklauncher.host.runtime.actions.ItemActionOverlayState
import org.quicklauncher.host.runtime.actions.ItemAvailability
import org.quicklauncher.host.runtime.folders.FolderMemberKind
import org.quicklauncher.host.runtime.folders.FolderMemberPresentation
import org.quicklauncher.host.runtime.folders.FolderOverlayState
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PhaseFiveOverlaysTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `folder exposes non-drag reorder and confirmed deletion`() {
        val moves = mutableListOf<Pair<ContentItemId, Int>>()
        var deletes = 0
        val folder = FolderOverlayState.Open(
            id("folder"),
            "Tools",
            listOf(
                FolderMemberPresentation(id("clock"), "Clock", FolderMemberKind.APP, false, true, 0),
                FolderMemberPresentation(id("mail"), "Mail", FolderMemberKind.APP, true, true, 1),
            ),
            2,
        )
        compose.setContent {
            MaterialTheme {
                FolderOverlaySurface(
                    state = folder,
                    onActivateMember = {},
                    onMoveMember = { member, index -> moves += member to index },
                    onRemoveMember = {},
                    onRename = {},
                    onDelete = { deletes++ },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Move Mail up").performScrollTo().performClick()
        assertEquals(listOf(id("mail") to 0), moves)
        compose.onNodeWithText("Delete folder").performClick()
        compose.onNodeWithText("Delete Tools?").assertIsDisplayed()
        compose.onNodeWithText("Delete", substring = false).performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun `item actions ask separate search visibility and destructive confirmation`() {
        val commands = mutableListOf<ItemActionCommand>()
        val state = ItemActionOverlayState.Open(
            id("mail"),
            "Mail",
            ItemAvailability.AVAILABLE,
            setOf(ItemAction.HIDE, ItemAction.UNINSTALL),
            emptySet(),
            emptySet(),
        )
        compose.setContent {
            MaterialTheme {
                ItemActionOverlaySurface(state, { commands += it }, {})
            }
        }

        compose.onNodeWithText("Hide").performClick()
        compose.onNodeWithText("Hide from search too").performClick()
        compose.onNodeWithText("Uninstall").performClick()
        compose.onNodeWithText("Uninstall Mail?").assertIsDisplayed()
        compose.onNodeWithText("Confirm uninstall").performClick()

        assertEquals(
            listOf(ItemActionCommand.Hide(false), ItemActionCommand.Uninstall(true)),
            commands,
        )
    }

    @Test
    fun `folder actions use folder-specific typed controls`() {
        val commands = mutableListOf<ItemActionCommand>()
        val tools = id("tools")
        val travel = id("travel")
        val state = ItemActionOverlayState.Open(
            id("mail"),
            "Mail",
            ItemAvailability.AVAILABLE,
            setOf(ItemAction.ADD_TO_FOLDER, ItemAction.REMOVE_FROM_FOLDER),
            setOf(tools, travel),
            setOf(tools),
            mapOf(tools to "Tools", travel to "Travel"),
        )
        compose.setContent {
            MaterialTheme {
                ItemActionOverlaySurface(state, { commands += it }, {})
            }
        }

        compose.onAllNodesWithText("Add to folder").assertCountEquals(0)
        compose.onAllNodesWithText("Remove from folder").assertCountEquals(0)
        compose.onNodeWithText("Remove from Tools").performClick()
        compose.onNodeWithText("Add to Travel").performClick()

        assertEquals(
            listOf(
                ItemActionCommand.RemoveFromFolder(tools),
                ItemActionCommand.AddToFolder(travel),
            ),
            commands,
        )
    }

    @Test
    fun `folder creation rejects a blank name and emits a trimmed name`() {
        val names = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                CreateFolderDialog(onCreate = { names += it }, onDismiss = {})
            }
        }

        compose.onNodeWithText("Create", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("Folder name").performTextReplacement("  Travel  ")
        compose.onNodeWithText("Create", substring = false).performClick()

        assertEquals(listOf("Travel"), names)
    }

    private fun id(value: String) = ContentItemId.parse("org.quicklauncher.content/$value")
}
