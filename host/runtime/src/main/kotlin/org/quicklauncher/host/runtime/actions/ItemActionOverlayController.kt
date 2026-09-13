package org.quicklauncher.host.runtime.actions

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ProfileSerial

enum class ActionableItemKind {
    APP,
    SHORTCUT,
}

enum class ItemAvailability {
    AVAILABLE,
    PROFILE_LOCKED,
    PROFILE_UNAVAILABLE,
    PACKAGE_UNAVAILABLE,
    DISABLED,
}

enum class ProfileItemAction {
    NONE,
    PAUSE,
    RESUME,
}

class ActionableItem(
    val id: ContentItemId,
    val label: String,
    val kind: ActionableItemKind,
    val availability: ItemAvailability,
    val appIdentity: AppActivityIdentity?,
    val favorite: Boolean,
    val collectionVisible: Boolean,
    val searchVisible: Boolean,
    val renameAllowed: Boolean,
    val detailsAllowed: Boolean,
    val uninstallAllowed: Boolean,
    val disableAllowed: Boolean,
    val profileAction: ProfileItemAction,
    folderIds: Collection<ContentItemId>,
    memberOfFolderIds: Collection<ContentItemId>,
    folderLabels: Map<ContentItemId, String> = folderIds.associateWith { it.value.substringAfterLast('/') },
    val favoriteAllowed: Boolean = kind == ActionableItemKind.APP,
    val visibilityAllowed: Boolean = kind == ActionableItemKind.APP,
) {
    val folderIds: Set<ContentItemId> = immutableSet(folderIds)
    val memberOfFolderIds: Set<ContentItemId> = immutableSet(memberOfFolderIds)
    val folderLabels: Map<ContentItemId, String> = immutableMap(folderLabels)

    init {
        require(label.isNotBlank()) { "Actionable item label must not be blank" }
        require((kind == ActionableItemKind.APP) == (appIdentity != null)) {
            "Only app items may carry an app activity identity"
        }
        require(memberOfFolderIds.all { it in this.folderIds }) {
            "Current folder membership must refer to a visible folder choice"
        }
        require(this.folderLabels.keys == this.folderIds) {
            "Every folder choice must have one sanitized label"
        }
        require(this.folderLabels.values.all(String::isNotBlank)) {
            "Visible folder labels must not be blank"
        }
        require(!detailsAllowed || appIdentity != null) { "App details require an app identity" }
        require(!uninstallAllowed || appIdentity != null) { "Uninstall requires an app identity" }
        require(!disableAllowed || appIdentity != null) { "Disable requires an app identity" }
        require(profileAction == ProfileItemAction.NONE || appIdentity != null) {
            "Profile actions require an app identity"
        }
    }

    fun copy(
        id: ContentItemId = this.id,
        label: String = this.label,
        kind: ActionableItemKind = this.kind,
        availability: ItemAvailability = this.availability,
        appIdentity: AppActivityIdentity? = this.appIdentity,
        favorite: Boolean = this.favorite,
        collectionVisible: Boolean = this.collectionVisible,
        searchVisible: Boolean = this.searchVisible,
        renameAllowed: Boolean = this.renameAllowed,
        detailsAllowed: Boolean = this.detailsAllowed,
        uninstallAllowed: Boolean = this.uninstallAllowed,
        disableAllowed: Boolean = this.disableAllowed,
        profileAction: ProfileItemAction = this.profileAction,
        folderIds: Collection<ContentItemId> = this.folderIds,
        memberOfFolderIds: Collection<ContentItemId> = this.memberOfFolderIds,
        folderLabels: Map<ContentItemId, String> = this.folderLabels,
        favoriteAllowed: Boolean = this.favoriteAllowed,
        visibilityAllowed: Boolean = this.visibilityAllowed,
    ): ActionableItem = ActionableItem(
        id,
        label,
        kind,
        availability,
        appIdentity,
        favorite,
        collectionVisible,
        searchVisible,
        renameAllowed,
        detailsAllowed,
        uninstallAllowed,
        disableAllowed,
        profileAction,
        folderIds,
        memberOfFolderIds,
        folderLabels,
        favoriteAllowed,
        visibilityAllowed,
    )
}

fun interface ItemActionResolver {
    /** Resolves current package, profile, and policy state. Null means the identity is no longer safe to expose. */
    suspend fun resolve(id: ContentItemId): ActionableItem?
}

sealed interface ItemMutationResult {
    data object Committed : ItemMutationResult
    data object Stale : ItemMutationResult
    data object Missing : ItemMutationResult
    data object Rejected : ItemMutationResult
}

interface ItemActionMutation {
    suspend fun setFavorite(id: ContentItemId, favorite: Boolean): ItemMutationResult

    suspend fun setVisibility(
        id: ContentItemId,
        collectionVisible: Boolean,
        searchVisible: Boolean,
    ): ItemMutationResult

    suspend fun rename(id: ContentItemId, name: String): ItemMutationResult

    suspend fun setFolderMembership(
        id: ContentItemId,
        folderId: ContentItemId,
        member: Boolean,
    ): ItemMutationResult

    suspend fun removeShortcut(id: ContentItemId): ItemMutationResult
}

sealed interface ItemPlatformResult {
    data object Completed : ItemPlatformResult
    data object Denied : ItemPlatformResult
    data object Unavailable : ItemPlatformResult
    data object Failed : ItemPlatformResult
}

enum class ItemActionPermission {
    ALLOWED,
    DENIED,
    UNAVAILABLE,
}

data class ItemActionEligibility(
    val uninstall: ItemActionPermission,
    val disable: ItemActionPermission,
) {
    companion object {
        val None = ItemActionEligibility(
            uninstall = ItemActionPermission.UNAVAILABLE,
            disable = ItemActionPermission.UNAVAILABLE,
        )
    }
}

/** Android-free platform routes. The production adapter owns every Intent and ComponentName. */
interface ItemActionPlatform {
    /** Proves current destructive-action eligibility without exposing framework policy objects. */
    suspend fun eligibility(identity: AppActivityIdentity): ItemActionEligibility

    suspend fun openDetails(identity: AppActivityIdentity): ItemPlatformResult

    suspend fun uninstall(identity: AppActivityIdentity): ItemPlatformResult

    suspend fun disable(identity: AppActivityIdentity): ItemPlatformResult

    suspend fun changeProfileMode(
        profile: ProfileSerial,
        action: ProfileItemAction,
    ): ItemPlatformResult
}

enum class ItemAction {
    FAVORITE,
    UNFAVORITE,
    HIDE,
    UNHIDE,
    RENAME,
    ADD_TO_FOLDER,
    REMOVE_FROM_FOLDER,
    OPEN_DETAILS,
    UNINSTALL,
    DISABLE,
    REMOVE_SHORTCUT,
    PAUSE_PROFILE,
    RESUME_PROFILE,
}

sealed interface ItemActionCommand {
    data class SetFavorite(val favorite: Boolean) : ItemActionCommand
    data class Hide(val searchVisible: Boolean?) : ItemActionCommand
    data object Unhide : ItemActionCommand
    data class Rename(val name: String) : ItemActionCommand
    data class AddToFolder(val folderId: ContentItemId) : ItemActionCommand
    data class RemoveFromFolder(val folderId: ContentItemId) : ItemActionCommand
    data object OpenDetails : ItemActionCommand
    data class Uninstall(val confirmed: Boolean) : ItemActionCommand
    data class Disable(val confirmed: Boolean) : ItemActionCommand
    data class RemoveShortcut(val confirmed: Boolean) : ItemActionCommand
    data object PauseProfile : ItemActionCommand
    data object ResumeProfile : ItemActionCommand
}

sealed interface ItemActionOverlayState {
    data object Closed : ItemActionOverlayState

    class Open(
        val itemId: ContentItemId,
        val label: String,
        val availability: ItemAvailability,
        actions: Collection<ItemAction>,
        folderIds: Collection<ContentItemId>,
        memberOfFolderIds: Collection<ContentItemId>,
        folderLabels: Map<ContentItemId, String> = folderIds.associateWith {
            it.value.substringAfterLast('/')
        },
    ) : ItemActionOverlayState {
        val actions: Set<ItemAction> = immutableSet(actions)
        val folderIds: Set<ContentItemId> = immutableSet(folderIds)
        val memberOfFolderIds: Set<ContentItemId> = immutableSet(memberOfFolderIds)
        val folderLabels: Map<ContentItemId, String> = immutableMap(folderLabels)
    }
}

enum class ItemActionRejection {
    MISSING,
    UNAVAILABLE,
    ACTION_NOT_ALLOWED,
    INVALID_ARGUMENT,
    STALE_STATE,
    PLATFORM_DENIED,
    PLATFORM_FAILURE,
    PERSISTENCE_FAILURE,
    CLOSED,
}

sealed interface ItemActionResult {
    data object Applied : ItemActionResult
    data object SearchVisibilityChoiceRequired : ItemActionResult
    data class ConfirmationRequired(val action: ItemAction) : ItemActionResult
    data class Rejected(val reason: ItemActionRejection) : ItemActionResult
}

interface ItemActionOverlayController : AutoCloseable {
    val state: StateFlow<ItemActionOverlayState>

    suspend fun open(itemId: ContentItemId): ItemActionResult

    suspend fun execute(command: ItemActionCommand): ItemActionResult

    fun dismiss(): Boolean
}

/** Resolves and revalidates every action while hiding store and platform coordination from callers. */
class DefaultItemActionOverlayController(
    private val resolver: ItemActionResolver,
    private val mutation: ItemActionMutation,
    private val platform: ItemActionPlatform,
) : ItemActionOverlayController {
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<ItemActionOverlayState>(ItemActionOverlayState.Closed)

    override val state: StateFlow<ItemActionOverlayState> = mutableState.asStateFlow()

    override suspend fun open(itemId: ContentItemId): ItemActionResult {
        if (closed.get()) return rejected(ItemActionRejection.CLOSED)
        val item = when (val resolution = resolve(itemId)) {
            is Resolution.Found -> resolution.item
            Resolution.Missing -> return rejected(ItemActionRejection.MISSING)
            Resolution.Failed -> return rejected(ItemActionRejection.UNAVAILABLE)
        }
        mutableState.value = item.withPlatformEligibility().toOpenState()
        return ItemActionResult.Applied
    }

    override suspend fun execute(command: ItemActionCommand): ItemActionResult {
        if (closed.get()) return rejected(ItemActionRejection.CLOSED)
        val open = mutableState.value as? ItemActionOverlayState.Open
            ?: return rejected(ItemActionRejection.MISSING)
        val resolvedItem = when (val resolution = resolve(open.itemId)) {
            is Resolution.Found -> resolution.item
            Resolution.Failed -> return rejected(ItemActionRejection.UNAVAILABLE)
            Resolution.Missing -> {
                dismiss()
                return rejected(ItemActionRejection.MISSING)
            }
        }
        val item = resolvedItem.withPlatformEligibility()
        val required = command.action(item)
        val currentActions = item.validActions()
        if (required !in currentActions) return rejected(
            if (item.availability != ItemAvailability.AVAILABLE) {
                ItemActionRejection.UNAVAILABLE
            } else {
                ItemActionRejection.ACTION_NOT_ALLOWED
            },
        )
        return when (command) {
            is ItemActionCommand.SetFavorite -> mutate(
                item.id,
            ) { mutation.setFavorite(item.id, command.favorite) }
            is ItemActionCommand.Hide -> {
                if (command.searchVisible == null) {
                    ItemActionResult.SearchVisibilityChoiceRequired
                } else {
                    mutate(
                        item.id,
                    ) { mutation.setVisibility(item.id, false, command.searchVisible) }
                }
            }
            ItemActionCommand.Unhide -> mutate(
                item.id,
            ) { mutation.setVisibility(item.id, true, item.searchVisible) }
            is ItemActionCommand.Rename -> {
                if (command.name.isBlank()) return rejected(ItemActionRejection.INVALID_ARGUMENT)
                mutate(item.id) { mutation.rename(item.id, command.name) }
            }
            is ItemActionCommand.AddToFolder -> {
                if (command.folderId !in item.folderIds || command.folderId in item.memberOfFolderIds) {
                    rejected(ItemActionRejection.ACTION_NOT_ALLOWED)
                } else {
                    mutate(
                        item.id,
                    ) { mutation.setFolderMembership(item.id, command.folderId, true) }
                }
            }
            is ItemActionCommand.RemoveFromFolder -> {
                if (command.folderId !in item.memberOfFolderIds) {
                    rejected(ItemActionRejection.ACTION_NOT_ALLOWED)
                } else {
                    mutate(
                        item.id,
                    ) { mutation.setFolderMembership(item.id, command.folderId, false) }
                }
            }
            ItemActionCommand.OpenDetails -> usePlatform {
                platform.openDetails(requireNotNull(item.appIdentity))
            }
            is ItemActionCommand.Uninstall -> confirmed(
                command.confirmed,
                ItemAction.UNINSTALL,
            ) { platform.uninstall(requireNotNull(item.appIdentity)) }
            is ItemActionCommand.Disable -> confirmed(
                command.confirmed,
                ItemAction.DISABLE,
            ) { platform.disable(requireNotNull(item.appIdentity)) }
            is ItemActionCommand.RemoveShortcut -> {
                if (!command.confirmed) {
                    ItemActionResult.ConfirmationRequired(ItemAction.REMOVE_SHORTCUT)
                } else {
                    val result = mutate(refreshId = null) { mutation.removeShortcut(item.id) }
                    if (result == ItemActionResult.Applied) dismiss()
                    result
                }
            }
            ItemActionCommand.PauseProfile -> usePlatform {
                platform.changeProfileMode(
                    requireNotNull(item.appIdentity).profile,
                    ProfileItemAction.PAUSE,
                )
            }
            ItemActionCommand.ResumeProfile -> usePlatform {
                platform.changeProfileMode(
                    requireNotNull(item.appIdentity).profile,
                    ProfileItemAction.RESUME,
                )
            }
        }
    }

    override fun dismiss(): Boolean {
        if (mutableState.value == ItemActionOverlayState.Closed) return false
        mutableState.value = ItemActionOverlayState.Closed
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mutableState.value = ItemActionOverlayState.Closed
    }

    private suspend fun confirmed(
        confirmed: Boolean,
        action: ItemAction,
        execute: suspend () -> ItemPlatformResult,
    ): ItemActionResult = if (!confirmed) {
        ItemActionResult.ConfirmationRequired(action)
    } else {
        usePlatform(execute)
    }

    private suspend fun mutate(
        refreshId: ContentItemId?,
        execute: suspend () -> ItemMutationResult,
    ): ItemActionResult {
        val result = try {
            execute()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return rejected(ItemActionRejection.PERSISTENCE_FAILURE)
        }
        val mapped = when (result) {
            ItemMutationResult.Committed -> ItemActionResult.Applied
            ItemMutationResult.Stale -> rejected(ItemActionRejection.STALE_STATE)
            ItemMutationResult.Missing -> rejected(ItemActionRejection.MISSING)
            ItemMutationResult.Rejected -> rejected(ItemActionRejection.PERSISTENCE_FAILURE)
        }
        if (mapped == ItemActionResult.Applied && refreshId != null) open(refreshId)
        return mapped
    }

    private suspend fun usePlatform(execute: suspend () -> ItemPlatformResult): ItemActionResult =
        try {
            applyPlatform(execute())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            rejected(ItemActionRejection.PLATFORM_DENIED)
        } catch (_: RuntimeException) {
            rejected(ItemActionRejection.PLATFORM_FAILURE)
        }

    private suspend fun resolve(id: ContentItemId): Resolution = try {
        resolver.resolve(id)?.let(Resolution::Found) ?: Resolution.Missing
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        mutableState.value = ItemActionOverlayState.Closed
        Resolution.Failed
    }

    private suspend fun ActionableItem.withPlatformEligibility(): ActionableItem {
        val identity = appIdentity ?: return this
        if (!uninstallAllowed && !disableAllowed) return this
        val eligibility = try {
            platform.eligibility(identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            ItemActionEligibility.None
        }
        return copy(
            uninstallAllowed = uninstallAllowed &&
                eligibility.uninstall == ItemActionPermission.ALLOWED,
            disableAllowed = disableAllowed &&
                eligibility.disable == ItemActionPermission.ALLOWED,
        )
    }

    private sealed interface Resolution {
        data class Found(val item: ActionableItem) : Resolution
        data object Missing : Resolution
        data object Failed : Resolution
    }

    private fun applyPlatform(result: ItemPlatformResult): ItemActionResult = when (result) {
        ItemPlatformResult.Completed -> ItemActionResult.Applied
        ItemPlatformResult.Denied -> rejected(ItemActionRejection.PLATFORM_DENIED)
        ItemPlatformResult.Unavailable -> rejected(ItemActionRejection.UNAVAILABLE)
        ItemPlatformResult.Failed -> rejected(ItemActionRejection.PLATFORM_FAILURE)
    }

    private fun ActionableItem.toOpenState() = ItemActionOverlayState.Open(
        itemId = id,
        label = label,
        availability = availability,
        actions = validActions(),
        folderIds = folderIds,
        memberOfFolderIds = memberOfFolderIds,
        folderLabels = folderLabels,
    )

    private fun ActionableItem.validActions(): Set<ItemAction> = buildSet {
        if (availability == ItemAvailability.AVAILABLE) {
            if (favoriteAllowed) add(if (favorite) ItemAction.UNFAVORITE else ItemAction.FAVORITE)
            if (visibilityAllowed) add(if (collectionVisible) ItemAction.HIDE else ItemAction.UNHIDE)
            if (renameAllowed) add(ItemAction.RENAME)
            if (folderIds.any { it !in memberOfFolderIds }) add(ItemAction.ADD_TO_FOLDER)
            if (memberOfFolderIds.isNotEmpty()) add(ItemAction.REMOVE_FROM_FOLDER)
            if (detailsAllowed) add(ItemAction.OPEN_DETAILS)
            if (uninstallAllowed) add(ItemAction.UNINSTALL)
            if (disableAllowed) add(ItemAction.DISABLE)
            if (kind == ActionableItemKind.SHORTCUT) add(ItemAction.REMOVE_SHORTCUT)
        }
        when (profileAction) {
            ProfileItemAction.NONE -> Unit
            ProfileItemAction.PAUSE -> if (availability == ItemAvailability.AVAILABLE) {
                add(ItemAction.PAUSE_PROFILE)
            }
            ProfileItemAction.RESUME -> add(ItemAction.RESUME_PROFILE)
        }
    }

    private fun ItemActionCommand.action(item: ActionableItem): ItemAction = when (this) {
        is ItemActionCommand.SetFavorite -> if (favorite) ItemAction.FAVORITE else ItemAction.UNFAVORITE
        is ItemActionCommand.Hide -> ItemAction.HIDE
        ItemActionCommand.Unhide -> ItemAction.UNHIDE
        is ItemActionCommand.Rename -> ItemAction.RENAME
        is ItemActionCommand.AddToFolder -> ItemAction.ADD_TO_FOLDER
        is ItemActionCommand.RemoveFromFolder -> ItemAction.REMOVE_FROM_FOLDER
        ItemActionCommand.OpenDetails -> ItemAction.OPEN_DETAILS
        is ItemActionCommand.Uninstall -> ItemAction.UNINSTALL
        is ItemActionCommand.Disable -> ItemAction.DISABLE
        is ItemActionCommand.RemoveShortcut -> ItemAction.REMOVE_SHORTCUT
        ItemActionCommand.PauseProfile -> ItemAction.PAUSE_PROFILE
        ItemActionCommand.ResumeProfile -> ItemAction.RESUME_PROFILE
    }

    private fun rejected(reason: ItemActionRejection) = ItemActionResult.Rejected(reason)
}

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
