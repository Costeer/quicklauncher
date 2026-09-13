package org.quicklauncher.host.runtime.actions

import java.util.Collections
import kotlinx.coroutines.CancellationException
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.host.data.store.AppOverrideRecord
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.ContentItemKind
import org.quicklauncher.host.data.store.ContentItemRecord
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.StoreRejectionCode

sealed interface ItemMutationTarget {
    data class App(val identity: AppActivityIdentity) : ItemMutationTarget
    data object Shortcut : ItemMutationTarget
}

fun interface ItemMutationTargetResolver {
    /** Returns null when the current profile and visibility policy suppresses the item. */
    suspend fun resolve(id: ContentItemId): ItemMutationTarget?
}

/**
 * Owns revisioned item edits. Each operation reads the current record, builds one task-level edit,
 * and lets LauncherStore reject a concurrent or invalid change without a partial mutation.
 */
class LauncherStoreItemActionMutation(
    private val store: LauncherStore,
    private val targetResolver: ItemMutationTargetResolver,
) : ItemActionMutation {
    override suspend fun setFavorite(id: ContentItemId, favorite: Boolean): ItemMutationResult =
        updateApp(id) { current -> current.copy(favorite = favorite) }

    override suspend fun setVisibility(
        id: ContentItemId,
        collectionVisible: Boolean,
        searchVisible: Boolean,
    ): ItemMutationResult = updateApp(id) { current ->
        current.copy(collectionVisible = collectionVisible, searchVisible = searchVisible)
    }

    override suspend fun rename(id: ContentItemId, name: String): ItemMutationResult {
        if (name.isBlank()) return ItemMutationResult.Rejected
        return updateApp(id) { current -> current.copy(customLabel = name) }
    }

    override suspend fun setFolderMembership(
        id: ContentItemId,
        folderId: ContentItemId,
        member: Boolean,
    ): ItemMutationResult {
        val target = resolveTarget(id) ?: return ItemMutationResult.Missing
        val snapshot = store.read()
        if (snapshot.contentItems.none { it.id == folderId && it.kind == ContentItemKind.FOLDER }) {
            return ItemMutationResult.Missing
        }
        val reference = when (target) {
            is ItemMutationTarget.App -> ContentItemRecord(
                id = id,
                kind = ContentItemKind.APP,
                encoded = target.identity.activityName.value,
                profile = target.identity.profile,
                packageName = target.identity.packageName,
            )
            ItemMutationTarget.Shortcut -> snapshot.contentItems.singleOrNull {
                it.id == id && it.kind == ContentItemKind.SHORTCUT
            } ?: return ItemMutationResult.Missing
        }
        return commit(snapshot, LauncherEdit.SetFolderMembership(folderId, reference, member))
    }

    override suspend fun removeShortcut(id: ContentItemId): ItemMutationResult {
        val snapshot = store.read()
        val item = snapshot.contentItems.singleOrNull { it.id == id }
            ?: return ItemMutationResult.Missing
        if (item.kind != ContentItemKind.SHORTCUT) return ItemMutationResult.Rejected
        return commit(snapshot, LauncherEdit.RemoveShortcutPlacement(id))
    }

    private suspend fun updateApp(
        id: ContentItemId,
        update: (AppOverrideRecord) -> AppOverrideRecord,
    ): ItemMutationResult {
        val identity = (resolveTarget(id) as? ItemMutationTarget.App)?.identity
            ?: return ItemMutationResult.Missing
        val snapshot = store.read()
        val current = snapshot.appOverrides.singleOrNull {
            it.profile == identity.profile &&
                it.packageName == identity.packageName &&
                it.activityName == identity.activityName.value
        } ?: AppOverrideRecord(
            profile = identity.profile,
            packageName = identity.packageName,
            activityName = identity.activityName.value,
            customLabel = null,
            encodedIcon = null,
            favorite = false,
            collectionVisible = true,
            searchVisible = true,
        )
        return commit(snapshot, LauncherEdit.PutAppOverride(update(current)))
    }

    private suspend fun resolveTarget(id: ContentItemId): ItemMutationTarget? = try {
        targetResolver.resolve(id)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        null
    }

    private suspend fun commit(
        snapshot: LauncherSnapshot,
        edit: LauncherEdit,
    ): ItemMutationResult = when (
        val result = store.commit(LauncherTransaction(snapshot.revision, listOf(edit)))
    ) {
        is CommitResult.Committed -> ItemMutationResult.Committed
        is CommitResult.Rejected -> when (result.reason.code) {
            StoreRejectionCode.STALE_REVISION -> ItemMutationResult.Stale
            StoreRejectionCode.NOT_FOUND -> ItemMutationResult.Missing
            else -> ItemMutationResult.Rejected
        }
    }
}

class ItemActionPolicyItem(
    val id: ContentItemId,
    val label: String,
    val kind: ActionableItemKind,
    val availability: ItemAvailability,
    val appIdentity: AppActivityIdentity?,
    val renameAllowed: Boolean,
    val detailsAllowed: Boolean,
    val uninstallAllowed: Boolean,
    val disableAllowed: Boolean,
    val profileAction: ProfileItemAction,
) {
    init {
        require(label.isNotBlank()) { "Visible action item label must not be blank" }
        require((kind == ActionableItemKind.APP) == (appIdentity != null))
    }
}

fun interface ItemActionPolicySource {
    /** Resolves only sanitized, currently visible policy state. */
    suspend fun resolve(id: ContentItemId): ItemActionPolicyItem?
}

/** Enriches sanitized current policy with durable overrides and ordered folder membership. */
class LauncherStoreItemActionResolver(
    private val store: LauncherStore,
    private val policy: ItemActionPolicySource,
) : ItemActionResolver {
    override suspend fun resolve(id: ContentItemId): ActionableItem? {
        val current = policy.resolve(id) ?: return null
        val snapshot = store.read()
        if (
            current.kind == ActionableItemKind.SHORTCUT &&
            snapshot.contentItems.none { it.id == id && it.kind == ContentItemKind.SHORTCUT }
        ) return null
        val override = current.appIdentity?.let { identity ->
            snapshot.appOverrides.singleOrNull {
                it.profile == identity.profile && it.packageName == identity.packageName &&
                    it.activityName == identity.activityName.value
            }
        }
        val folders = snapshot.contentItems.filter { it.kind == ContentItemKind.FOLDER }.map { it.id }
        val folderLabels = snapshot.contentItems
            .filter { it.kind == ContentItemKind.FOLDER }
            .associate { it.id to it.encoded }
        val memberships = snapshot.folderMembers.filter { it.memberId == id }.map { it.folderId }
        return ActionableItem(
            id = current.id,
            label = override?.customLabel ?: current.label,
            kind = current.kind,
            availability = current.availability,
            appIdentity = current.appIdentity,
            favorite = override?.favorite == true,
            collectionVisible = override?.collectionVisible != false,
            searchVisible = override?.searchVisible != false,
            renameAllowed = current.renameAllowed,
            detailsAllowed = current.detailsAllowed,
            uninstallAllowed = current.uninstallAllowed,
            disableAllowed = current.disableAllowed,
            profileAction = current.profileAction,
            folderIds = immutableSet(folders),
            memberOfFolderIds = immutableSet(memberships.filter { it in folders }),
            folderLabels = folderLabels,
        )
    }

}

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
