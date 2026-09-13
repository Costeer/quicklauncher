package org.quicklauncher.host.runtime.actions

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.ShortcutId
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.ContentItemKind
import org.quicklauncher.host.data.store.ContentItemRecord
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ShortcutTarget

class LauncherStoreItemActionsTest {
    @Test
    fun `app mutations preserve unrelated override fields and folder order`() = runTest {
        val store = InMemoryLauncherStore()
        val folderId = id("folder")
        val siblingId = shortcutId("sibling")
        val appId = id("mail")
        val composeId = shortcutId("compose")
        commit(
            store,
            LauncherEdit.CreateShortcutPlacement(shortcut(siblingId, "sibling")),
            LauncherEdit.CreateShortcutPlacement(shortcut(composeId, "compose")),
            LauncherEdit.CreateFolder(
                ContentItemRecord(folderId, ContentItemKind.FOLDER, "Tools"),
                listOf(siblingId),
            ),
        )
        val target = MutableItemMutationTargetResolver(
            mapOf(
                appId to ItemMutationTarget.App(appIdentity()),
                composeId to ItemMutationTarget.Shortcut,
            ),
        )
        val mutation = LauncherStoreItemActionMutation(store, target)

        assertEquals(ItemMutationResult.Committed, mutation.setFavorite(appId, true))
        assertEquals(ItemMutationResult.Committed, mutation.setVisibility(appId, false, true))
        assertEquals(ItemMutationResult.Committed, mutation.rename(appId, "Work mail"))
        assertEquals(ItemMutationResult.Committed, mutation.setFolderMembership(composeId, folderId, true))
        assertEquals(ItemMutationResult.Committed, mutation.setFolderMembership(appId, folderId, true))

        val snapshot = store.read()
        val override = snapshot.appOverrides.single()
        assertTrue(override.favorite)
        assertFalse(override.collectionVisible)
        assertTrue(override.searchVisible)
        assertEquals("Work mail", override.customLabel)
        assertEquals(
            listOf(siblingId, composeId, appId),
            snapshot.folderMembers.sortedBy { it.index }.map { it.memberId },
        )
        val appReference = snapshot.contentItems.single { it.id == appId }
        assertEquals(ContentItemKind.APP, appReference.kind)
        assertEquals(appIdentity().profile, appReference.profile)
    }

    @Test
    fun `shortcut removal clears durable membership and missing targets are rejected`() = runTest {
        val store = InMemoryLauncherStore()
        val shortcutId = shortcutId("compose")
        val folderId = id("folder")
        commit(
            store,
            LauncherEdit.CreateShortcutPlacement(shortcut(shortcutId, "compose")),
            LauncherEdit.CreateFolder(
                ContentItemRecord(folderId, ContentItemKind.FOLDER, "Tools"),
                listOf(shortcutId),
            ),
        )
        val mutation = LauncherStoreItemActionMutation(
            store,
            MutableItemMutationTargetResolver(emptyMap()),
        )

        assertEquals(ItemMutationResult.Committed, mutation.removeShortcut(shortcutId))
        assertEquals(ItemMutationResult.Missing, mutation.removeShortcut(shortcutId))
        assertTrue(store.read().folderMembers.isEmpty())
    }

    @Test
    fun `resolver merges current store policy without exposing a policy-suppressed item`() = runTest {
        val store = InMemoryLauncherStore()
        val folderId = id("folder")
        val appId = id("mail")
        commit(
            store,
            LauncherEdit.CreateFolder(ContentItemRecord(folderId, ContentItemKind.FOLDER, "Tools")),
        )
        val policy = MutableItemActionPolicySource(
            mapOf(
                appId to ItemActionPolicyItem(
                    id = appId,
                    label = "Mail",
                    kind = ActionableItemKind.APP,
                    availability = ItemAvailability.AVAILABLE,
                    appIdentity = appIdentity(),
                    renameAllowed = true,
                    detailsAllowed = true,
                    uninstallAllowed = false,
                    disableAllowed = true,
                    profileAction = ProfileItemAction.PAUSE,
                ),
            ),
        )
        val resolver = LauncherStoreItemActionResolver(store, policy)

        val resolved = requireNotNull(resolver.resolve(appId))
        assertEquals(setOf(folderId), resolved.folderIds)
        assertEquals(mapOf(folderId to "Tools"), resolved.folderLabels)
        assertFalse(resolved.favorite)
        policy.items[appId] = null
        assertEquals(null, resolver.resolve(appId))
    }

    private suspend fun commit(store: InMemoryLauncherStore, vararg edits: LauncherEdit) {
        val before = store.read()
        check(store.commit(LauncherTransaction(before.revision, edits.toList())) is CommitResult.Committed)
    }

    private fun appIdentity() = AppActivityIdentity(
        ProfileSerial.of(10),
        PackageName.parse("org.example.mail"),
        ActivityName.parse("org.example.mail.MainActivity"),
    )

    private fun shortcut(id: ContentItemId, name: String) = ContentItemRecord.shortcut(
        id,
        name,
        ShortcutTarget(
            ProfileSerial.of(10),
            PackageName.parse("org.example.mail"),
            ShortcutId.parse(name),
        ),
    )

    private fun id(value: String) = ContentItemId.parse("org.quicklauncher.content/$value")
    private fun shortcutId(value: String) = id("shortcut-$value")

    private class MutableItemMutationTargetResolver(
        values: Map<ContentItemId, ItemMutationTarget>,
    ) : ItemMutationTargetResolver {
        val targets = values.toMutableMap()
        override suspend fun resolve(id: ContentItemId): ItemMutationTarget? = targets[id]
    }

    private class MutableItemActionPolicySource(
        values: Map<ContentItemId, ItemActionPolicyItem?>,
    ) : ItemActionPolicySource {
        val items = values.toMutableMap()
        override suspend fun resolve(id: ContentItemId): ItemActionPolicyItem? = items[id]
    }
}
