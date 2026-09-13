package org.quicklauncher.host.runtime.folders

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.ContentItemKind
import org.quicklauncher.host.data.store.ContentItemRecord
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.StoreRejectionCode

class StoredFolder(
    val id: ContentItemId,
    val name: String,
    members: Collection<ContentItemId>,
) {
    val members: List<ContentItemId> = immutableList(members)

    init {
        require(name.isNotBlank()) { "Folder name must not be blank" }
        require(this.members.distinct().size == this.members.size) {
            "Folder membership must not contain duplicates"
        }
    }

    fun copy(
        id: ContentItemId = this.id,
        name: String = this.name,
        members: Collection<ContentItemId> = this.members,
    ): StoredFolder = StoredFolder(id, name, members)
}

sealed interface FolderMutationResult {
    data object Committed : FolderMutationResult
    data object Missing : FolderMutationResult
    data object Duplicate : FolderMutationResult
    data object InvalidReference : FolderMutationResult
    data object Stale : FolderMutationResult
    data object Failed : FolderMutationResult
}

interface FolderRepository {
    suspend fun read(): List<StoredFolder>

    suspend fun create(folder: StoredFolder): FolderMutationResult

    suspend fun rename(folderId: ContentItemId, name: String): FolderMutationResult

    suspend fun setMembers(folderId: ContentItemId, members: List<ContentItemId>): FolderMutationResult

    suspend fun delete(folderId: ContentItemId): FolderMutationResult
}

/** Maps host folder tasks to revisioned, atomic LauncherStore edits. */
class LauncherStoreFolderRepository(private val store: LauncherStore) : FolderRepository {
    override suspend fun read(): List<StoredFolder> {
        val snapshot = store.read()
        val members = snapshot.folderMembers.groupBy { it.folderId }
        return snapshot.contentItems
            .asSequence()
            .filter { it.kind == ContentItemKind.FOLDER }
            .map { folder ->
                StoredFolder(
                    id = folder.id,
                    name = folder.encoded,
                    members = members[folder.id]
                        .orEmpty()
                        .sortedBy { it.index }
                        .map { it.memberId },
                )
            }
            .toList()
    }

    override suspend fun create(folder: StoredFolder): FolderMutationResult = commit { revision ->
        LauncherTransaction(
            revision,
            listOf(
                LauncherEdit.CreateFolder(
                    ContentItemRecord(folder.id, ContentItemKind.FOLDER, folder.name),
                    folder.members,
                ),
            ),
        )
    }

    override suspend fun rename(folderId: ContentItemId, name: String): FolderMutationResult =
        commit { revision ->
            LauncherTransaction(revision, listOf(LauncherEdit.RenameFolder(folderId, name)))
        }

    override suspend fun setMembers(
        folderId: ContentItemId,
        members: List<ContentItemId>,
    ): FolderMutationResult = commit { revision ->
        LauncherTransaction(revision, listOf(LauncherEdit.SetFolderMembers(folderId, members)))
    }

    override suspend fun delete(folderId: ContentItemId): FolderMutationResult = commit { revision ->
        LauncherTransaction(revision, listOf(LauncherEdit.DeleteFolder(folderId, confirmed = true)))
    }

    private suspend fun commit(
        transaction: (org.quicklauncher.host.data.store.StoreRevision) -> LauncherTransaction,
    ): FolderMutationResult {
        val before = store.read()
        return when (val result = store.commit(transaction(before.revision))) {
            is CommitResult.Committed -> FolderMutationResult.Committed
            is CommitResult.Rejected -> when (result.reason.code) {
                StoreRejectionCode.NOT_FOUND -> FolderMutationResult.Missing
                StoreRejectionCode.DUPLICATE_ID -> FolderMutationResult.Duplicate
                StoreRejectionCode.INVALID_REFERENCE -> FolderMutationResult.InvalidReference
                StoreRejectionCode.STALE_REVISION -> FolderMutationResult.Stale
                else -> FolderMutationResult.Failed
            }
        }
    }
}

enum class FolderMemberKind {
    APP,
    SHORTCUT,
    FOLDER,
    OTHER,
}

data class FolderMemberPresentation(
    val id: ContentItemId,
    val label: String,
    val kind: FolderMemberKind,
    val workBadge: Boolean,
    val available: Boolean,
    /** Position in durable membership, used by non-drag reordering after policy filtering. */
    val durableIndex: Int = UNASSIGNED_DURABLE_INDEX,
) {
    init {
        require(label.isNotBlank()) { "Visible folder member label must not be blank" }
        require(durableIndex >= UNASSIGNED_DURABLE_INDEX) { "Durable index must be -1 or nonnegative" }
    }

    private companion object {
        const val UNASSIGNED_DURABLE_INDEX = -1
    }
}

interface FolderMemberResolver {
    /** Profile, package, visibility, and lock changes that can change sanitized membership. */
    val invalidations: Flow<Unit>

    /** Returns null when current policy forbids exposing the durable member. */
    suspend fun resolve(id: ContentItemId): FolderMemberPresentation?
}

sealed interface FolderOverlayState {
    data object Closed : FolderOverlayState

    class Open(
        val id: ContentItemId,
        val name: String,
        members: Collection<FolderMemberPresentation>,
        val durableMemberCount: Int,
    ) : FolderOverlayState {
        val members: List<FolderMemberPresentation> = immutableList(members)

        init {
            require(name.isNotBlank()) { "Open folder name must not be blank" }
            require(durableMemberCount >= this.members.size) {
                "Visible folder members cannot exceed durable membership"
            }
        }
    }
}

enum class FolderRejection {
    MISSING_FOLDER,
    MISSING_MEMBER,
    DUPLICATE_MEMBER,
    STALE_REVISION,
    PERSISTENCE_FAILURE,
    CLOSED,
}

sealed interface FolderOperationResult {
    data object Applied : FolderOperationResult
    data object ConfirmationRequired : FolderOperationResult
    data class Rejected(val reason: FolderRejection) : FolderOperationResult
}

interface FolderOverlayController : AutoCloseable {
    val state: StateFlow<FolderOverlayState>

    suspend fun open(folderId: ContentItemId): FolderOperationResult

    suspend fun restore(folderId: ContentItemId?): FolderOperationResult

    suspend fun create(folderId: ContentItemId, name: String): FolderOperationResult

    suspend fun rename(folderId: ContentItemId, name: String): FolderOperationResult

    suspend fun setMembers(folderId: ContentItemId, members: List<ContentItemId>): FolderOperationResult

    suspend fun addMember(folderId: ContentItemId, memberId: ContentItemId): FolderOperationResult

    suspend fun removeMember(folderId: ContentItemId, memberId: ContentItemId): FolderOperationResult

    suspend fun moveMember(
        folderId: ContentItemId,
        memberId: ContentItemId,
        targetIndex: Int,
    ): FolderOperationResult

    suspend fun delete(folderId: ContentItemId, confirmed: Boolean): FolderOperationResult

    /** Handles predictive Back or repeated Home. Returns whether an overlay was closed. */
    fun dismiss(): Boolean
}

class DefaultFolderOverlayController(
    private val repository: FolderRepository,
    private val resolver: FolderMemberResolver,
    parentScope: CoroutineScope,
) : FolderOverlayController {
    private val closed = AtomicBoolean(false)
    private val operationMutex = Mutex()
    private val ownedJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownedJob)
    private val mutableState = MutableStateFlow<FolderOverlayState>(FolderOverlayState.Closed)

    override val state: StateFlow<FolderOverlayState> = mutableState.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            resolver.invalidations.collect {
                val open = mutableState.value as? FolderOverlayState.Open ?: return@collect
                refreshOpen(open.id)
            }
        }
    }

    override suspend fun open(folderId: ContentItemId): FolderOperationResult = operationMutex.withLock {
        if (closed.get()) return@withLock rejected(FolderRejection.CLOSED)
        publish(folderId)
    }

    override suspend fun restore(folderId: ContentItemId?): FolderOperationResult {
        if (folderId == null) {
            dismiss()
            return FolderOperationResult.Applied
        }
        return open(folderId)
    }

    override suspend fun create(folderId: ContentItemId, name: String): FolderOperationResult =
        mutate { repository.create(StoredFolder(folderId, name, emptyList())) }

    override suspend fun rename(folderId: ContentItemId, name: String): FolderOperationResult {
        require(name.isNotBlank()) { "Folder name must not be blank" }
        return mutate(refreshId = folderId) { repository.rename(folderId, name) }
    }

    override suspend fun setMembers(
        folderId: ContentItemId,
        members: List<ContentItemId>,
    ): FolderOperationResult {
        if (members.distinct().size != members.size) {
            return rejected(FolderRejection.DUPLICATE_MEMBER)
        }
        return mutate(refreshId = folderId) {
            repository.setMembers(folderId, immutableList(members))
        }
    }

    override suspend fun addMember(
        folderId: ContentItemId,
        memberId: ContentItemId,
    ): FolderOperationResult {
        if (closed.get()) return rejected(FolderRejection.CLOSED)
        val folder = when (val loaded = loadFolder(folderId)) {
            is FolderLoad.Found -> loaded.folder
            FolderLoad.Missing -> return rejected(FolderRejection.MISSING_FOLDER)
            FolderLoad.Failed -> return rejected(FolderRejection.PERSISTENCE_FAILURE)
        }
        if (memberId in folder.members) return rejected(FolderRejection.DUPLICATE_MEMBER)
        return setMembers(folderId, folder.members + memberId)
    }

    override suspend fun removeMember(
        folderId: ContentItemId,
        memberId: ContentItemId,
    ): FolderOperationResult {
        if (closed.get()) return rejected(FolderRejection.CLOSED)
        val folder = when (val loaded = loadFolder(folderId)) {
            is FolderLoad.Found -> loaded.folder
            FolderLoad.Missing -> return rejected(FolderRejection.MISSING_FOLDER)
            FolderLoad.Failed -> return rejected(FolderRejection.PERSISTENCE_FAILURE)
        }
        if (memberId !in folder.members) return rejected(FolderRejection.MISSING_MEMBER)
        return setMembers(folderId, folder.members - memberId)
    }

    override suspend fun moveMember(
        folderId: ContentItemId,
        memberId: ContentItemId,
        targetIndex: Int,
    ): FolderOperationResult {
        if (closed.get()) return rejected(FolderRejection.CLOSED)
        val folder = when (val loaded = loadFolder(folderId)) {
            is FolderLoad.Found -> loaded.folder
            FolderLoad.Missing -> return rejected(FolderRejection.MISSING_FOLDER)
            FolderLoad.Failed -> return rejected(FolderRejection.PERSISTENCE_FAILURE)
        }
        val source = folder.members.indexOf(memberId)
        if (source < 0) return rejected(FolderRejection.MISSING_MEMBER)
        if (targetIndex !in folder.members.indices) return rejected(FolderRejection.MISSING_MEMBER)
        val reordered = folder.members.toMutableList().apply {
            removeAt(source)
            add(targetIndex, memberId)
        }
        return setMembers(folderId, reordered)
    }

    override suspend fun delete(
        folderId: ContentItemId,
        confirmed: Boolean,
    ): FolderOperationResult {
        if (closed.get()) return rejected(FolderRejection.CLOSED)
        if (!confirmed) return FolderOperationResult.ConfirmationRequired
        val result = mutate { repository.delete(folderId) }
        if (result == FolderOperationResult.Applied &&
            (mutableState.value as? FolderOverlayState.Open)?.id == folderId
        ) {
            mutableState.value = FolderOverlayState.Closed
        }
        return result
    }

    override fun dismiss(): Boolean {
        if (mutableState.value == FolderOverlayState.Closed) return false
        mutableState.value = FolderOverlayState.Closed
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mutableState.value = FolderOverlayState.Closed
        scope.cancel()
    }

    private suspend fun refreshOpen(folderId: ContentItemId) {
        operationMutex.withLock {
            if (!closed.get()) publish(folderId)
        }
    }

    private suspend fun publish(folderId: ContentItemId): FolderOperationResult {
        val folder = try {
            repository.read().singleOrNull { it.id == folderId }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            mutableState.value = FolderOverlayState.Closed
            return rejected(FolderRejection.PERSISTENCE_FAILURE)
        }
        if (folder == null) {
            mutableState.value = FolderOverlayState.Closed
            return rejected(FolderRejection.MISSING_FOLDER)
        }
        val visible = folder.members.mapIndexedNotNull { durableIndex, memberId ->
            resolveVisible(memberId)?.copy(durableIndex = durableIndex)
        }
        mutableState.value = FolderOverlayState.Open(
            id = folder.id,
            name = folder.name,
            members = visible,
            durableMemberCount = folder.members.size,
        )
        return FolderOperationResult.Applied
    }

    private suspend fun resolveVisible(memberId: ContentItemId): FolderMemberPresentation? = try {
        resolver.resolve(memberId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        // Profile and visibility resolution fails closed for this member. Durable state is retained.
        null
    }

    private suspend fun loadFolder(folderId: ContentItemId): FolderLoad = try {
        repository.read().singleOrNull { it.id == folderId }
            ?.let(FolderLoad::Found) ?: FolderLoad.Missing
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        FolderLoad.Failed
    }

    private sealed interface FolderLoad {
        data class Found(val folder: StoredFolder) : FolderLoad
        data object Missing : FolderLoad
        data object Failed : FolderLoad
    }

    private suspend fun mutate(
        refreshId: ContentItemId? = null,
        operation: suspend () -> FolderMutationResult,
    ): FolderOperationResult {
        if (closed.get()) return rejected(FolderRejection.CLOSED)
        val mutation = try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return rejected(FolderRejection.PERSISTENCE_FAILURE)
        }
        val result = when (mutation) {
            FolderMutationResult.Committed -> FolderOperationResult.Applied
            FolderMutationResult.Missing -> rejected(FolderRejection.MISSING_FOLDER)
            FolderMutationResult.Duplicate -> rejected(FolderRejection.DUPLICATE_MEMBER)
            FolderMutationResult.InvalidReference -> rejected(FolderRejection.MISSING_MEMBER)
            FolderMutationResult.Stale -> rejected(FolderRejection.STALE_REVISION)
            FolderMutationResult.Failed -> rejected(FolderRejection.PERSISTENCE_FAILURE)
        }
        if (result == FolderOperationResult.Applied && refreshId != null &&
            (mutableState.value as? FolderOverlayState.Open)?.id == refreshId
        ) {
            publish(refreshId)
        }
        return result
    }

    private fun rejected(reason: FolderRejection) = FolderOperationResult.Rejected(reason)
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
