package org.quicklauncher.host.runtime.folders

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ContentItemId

@OptIn(ExperimentalCoroutinesApi::class)
class FolderOverlayControllerTest {
    @Test
    fun `open preserves durable order while filtering members through current policy`() = runTest {
        val folder = folder("org.quicklauncher.content/folder", "Tools", "one", "private", "two")
        val repository = FakeFolderRepository(listOf(folder))
        val resolver = FakeFolderMemberResolver(
            mapOf(
                id("one") to member("one", "Clock"),
                id("private") to null,
                id("two") to member("two", "Calculator"),
            ),
        )
        val controller = DefaultFolderOverlayController(repository, resolver, backgroundScope)

        val result = controller.open(folder.id)

        assertEquals(FolderOperationResult.Applied, result)
        val state = controller.state.value as FolderOverlayState.Open
        assertEquals("Tools", state.name)
        assertEquals(listOf("Clock", "Calculator"), state.members.map { it.label })
        assertEquals(3, state.durableMemberCount)
        assertFalse(state.members.any { it.id == id("private") })
        controller.close()
    }

    @Test
    fun `profile invalidation immediately removes a newly locked member from an open folder`() = runTest {
        val folder = folder("org.quicklauncher.content/folder", "Tools", "one", "private")
        val resolver = FakeFolderMemberResolver(
            mapOf(
                id("one") to member("one", "Clock"),
                id("private") to member("private", "Vault"),
            ),
        )
        val controller = DefaultFolderOverlayController(
            FakeFolderRepository(listOf(folder)),
            resolver,
            backgroundScope,
        )
        runCurrent()
        controller.open(folder.id)
        resolver.values[id("private")] = null

        resolver.invalidate()
        runCurrent()

        val state = controller.state.value as FolderOverlayState.Open
        assertEquals(listOf("Clock"), state.members.map { it.label })
        assertEquals(2, state.durableMemberCount)
        controller.close()
    }

    @Test
    fun `membership edits preserve explicit order and reject duplicates`() = runTest {
        val folder = folder("org.quicklauncher.content/folder", "Tools", "one", "two")
        val repository = FakeFolderRepository(listOf(folder))
        val resolver = FakeFolderMemberResolver(
            mapOf(id("one") to member("one", "One"), id("two") to member("two", "Two")),
        )
        val controller = DefaultFolderOverlayController(repository, resolver, backgroundScope)
        controller.open(folder.id)

        val reordered = controller.setMembers(folder.id, listOf(id("two"), id("one")))
        val duplicate = controller.setMembers(folder.id, listOf(id("one"), id("one")))

        assertEquals(FolderOperationResult.Applied, reordered)
        assertEquals(
            FolderOperationResult.Rejected(FolderRejection.DUPLICATE_MEMBER),
            duplicate,
        )
        assertEquals(listOf(id("two"), id("one")), repository.folders.single().members)
        controller.close()
    }

    @Test
    fun `visible reordering uses durable indexes when a locked member is filtered out`() = runTest {
        val folder = folder("org.quicklauncher.content/folder", "Tools", "private", "one", "two")
        val repository = FakeFolderRepository(listOf(folder))
        val controller = DefaultFolderOverlayController(
            repository,
            FakeFolderMemberResolver(
                mapOf(
                    id("private") to null,
                    id("one") to member("one", "One"),
                    id("two") to member("two", "Two"),
                ),
            ),
            backgroundScope,
        )

        controller.open(folder.id)
        val open = controller.state.value as FolderOverlayState.Open
        assertEquals(listOf(1, 2), open.members.map { it.durableIndex })
        controller.moveMember(folder.id, id("two"), open.members.first().durableIndex)

        assertEquals(listOf(id("private"), id("two"), id("one")), repository.folders.single().members)
        controller.close()
    }

    @Test
    fun `member resolution failure fails closed without exposing or closing healthy members`() = runTest {
        val folder = folder("org.quicklauncher.content/folder", "Tools", "one", "private")
        val resolver = FakeFolderMemberResolver(
            mapOf(id("one") to member("one", "One"), id("private") to member("private", "Vault")),
        )
        resolver.failures += id("private")
        val controller = DefaultFolderOverlayController(
            FakeFolderRepository(listOf(folder)),
            resolver,
            backgroundScope,
        )

        assertEquals(FolderOperationResult.Applied, controller.open(folder.id))
        assertEquals(listOf("One"), (controller.state.value as FolderOverlayState.Open).members.map { it.label })
        controller.close()
    }

    @Test
    fun `delete requires confirmation and closes the overlay after commit`() = runTest {
        val folder = folder("org.quicklauncher.content/folder", "Tools")
        val repository = FakeFolderRepository(listOf(folder))
        val controller = DefaultFolderOverlayController(
            repository,
            FakeFolderMemberResolver(emptyMap()),
            backgroundScope,
        )
        controller.open(folder.id)

        val rejected = controller.delete(folder.id, confirmed = false)
        val deleted = controller.delete(folder.id, confirmed = true)

        assertEquals(FolderOperationResult.ConfirmationRequired, rejected)
        assertEquals(FolderOperationResult.Applied, deleted)
        assertEquals(FolderOverlayState.Closed, controller.state.value)
        assertTrue(repository.folders.isEmpty())
        controller.close()
    }

    @Test
    fun `back home and recreation restore use the stable folder identity`() = runTest {
        val folder = folder("org.quicklauncher.content/folder", "Tools")
        val controller = DefaultFolderOverlayController(
            FakeFolderRepository(listOf(folder)),
            FakeFolderMemberResolver(emptyMap()),
            backgroundScope,
        )

        assertEquals(FolderOperationResult.Applied, controller.restore(folder.id))
        assertTrue(controller.dismiss())
        assertFalse(controller.dismiss())
        assertEquals(FolderOverlayState.Closed, controller.state.value)
        controller.close()
    }

    @Test
    fun `closed controller rejects mutation before touching persistence`() = runTest {
        val repository = FakeFolderRepository(emptyList())
        val controller = DefaultFolderOverlayController(
            repository,
            FakeFolderMemberResolver(emptyMap()),
            backgroundScope,
        )
        controller.close()

        assertEquals(
            FolderOperationResult.Rejected(FolderRejection.CLOSED),
            controller.create(id("folder"), "Tools"),
        )
        assertEquals(0, repository.mutationCount)
    }

    @Test
    fun `folder persistence cancellation propagates`() = runTest {
        val repository = object : FolderRepository {
            override suspend fun read(): List<StoredFolder> = throw CancellationException("cancelled")
            override suspend fun create(folder: StoredFolder) = FolderMutationResult.Failed
            override suspend fun rename(folderId: ContentItemId, name: String) = FolderMutationResult.Failed
            override suspend fun setMembers(folderId: ContentItemId, members: List<ContentItemId>) =
                FolderMutationResult.Failed
            override suspend fun delete(folderId: ContentItemId) = FolderMutationResult.Failed
        }
        val controller = DefaultFolderOverlayController(
            repository,
            FakeFolderMemberResolver(emptyMap()),
            backgroundScope,
        )

        assertTrue(runCatching { controller.open(id("folder")) }.exceptionOrNull() is CancellationException)
        controller.close()
    }

    private fun folder(path: String, name: String, vararg members: String) = StoredFolder(
        ContentItemId.parse(path),
        name,
        members.map(::id),
    )

    private fun id(name: String) = ContentItemId.parse("org.quicklauncher.content/$name")

    private fun member(name: String, label: String) = FolderMemberPresentation(
        id(name),
        label,
        FolderMemberKind.APP,
        workBadge = false,
        available = true,
    )

    private class FakeFolderRepository(folders: List<StoredFolder>) : FolderRepository {
        val folders = folders.toMutableList()
        var mutationCount = 0

        override suspend fun read(): List<StoredFolder> = folders.toList()

        override suspend fun create(folder: StoredFolder): FolderMutationResult {
            mutationCount++
            if (folders.any { it.id == folder.id }) return FolderMutationResult.Duplicate
            folders += folder
            return FolderMutationResult.Committed
        }

        override suspend fun rename(folderId: ContentItemId, name: String): FolderMutationResult {
            mutationCount++
            val index = folders.indexOfFirst { it.id == folderId }
            if (index < 0) return FolderMutationResult.Missing
            folders[index] = folders[index].copy(name = name)
            return FolderMutationResult.Committed
        }

        override suspend fun setMembers(
            folderId: ContentItemId,
            members: List<ContentItemId>,
        ): FolderMutationResult {
            mutationCount++
            if (members.distinct().size != members.size) return FolderMutationResult.Duplicate
            val index = folders.indexOfFirst { it.id == folderId }
            if (index < 0) return FolderMutationResult.Missing
            folders[index] = folders[index].copy(members = members)
            return FolderMutationResult.Committed
        }

        override suspend fun delete(folderId: ContentItemId): FolderMutationResult {
            mutationCount++
            return if (folders.removeAll { it.id == folderId }) {
                FolderMutationResult.Committed
            } else {
                FolderMutationResult.Missing
            }
        }
    }

    private class FakeFolderMemberResolver(
        values: Map<ContentItemId, FolderMemberPresentation?>,
    ) : FolderMemberResolver {
        private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val invalidations: Flow<Unit> = changes
        val values = values.toMutableMap()
        val failures = mutableSetOf<ContentItemId>()

        override suspend fun resolve(id: ContentItemId): FolderMemberPresentation? {
            if (id in failures) error("profile access disappeared")
            return values[id]
        }

        fun invalidate() {
            changes.tryEmit(Unit)
        }
    }
}
