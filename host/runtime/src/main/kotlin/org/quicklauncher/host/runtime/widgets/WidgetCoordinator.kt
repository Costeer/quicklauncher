package org.quicklauncher.host.runtime.widgets

import java.util.Collections
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.WidgetBindState
import org.quicklauncher.host.data.store.WidgetCleanupState
import org.quicklauncher.host.data.store.WidgetPlacementRecord
import org.quicklauncher.host.data.store.WidgetRestoreState

sealed interface WidgetFlowResult {
    data object Bound : WidgetFlowResult
    data class AwaitingPermission(val action: WidgetUserAction.BindPermission) : WidgetFlowResult
    data class AwaitingConfiguration(val action: WidgetUserAction.Configure) : WidgetFlowResult
    data object Cancelled : WidgetFlowResult
    data class Unavailable(val code: String) : WidgetFlowResult
    data class Rejected(val code: String) : WidgetFlowResult
    data class CleanupRequired(val appWidgetId: Int, val code: String) : WidgetFlowResult
}

sealed interface WidgetResizeResult {
    data object Resized : WidgetResizeResult
    data class Unavailable(val code: String) : WidgetResizeResult
    data class Rejected(val code: String) : WidgetResizeResult
}

sealed interface WidgetRemovalResult {
    data object Removed : WidgetRemovalResult
    data class NotFound(val moduleInstanceId: ModuleInstanceId) : WidgetRemovalResult
    data class Rejected(val code: String) : WidgetRemovalResult
    data class CleanupRequired(val appWidgetId: Int, val code: String) : WidgetRemovalResult
}

sealed interface WidgetReconciliationResult {
    data object Reconciled : WidgetReconciliationResult
    data class RetryRequired(val code: String) : WidgetReconciliationResult
    class UserActionsRequired(actions: Collection<WidgetPendingUserAction>) : WidgetReconciliationResult {
        val actions: List<WidgetPendingUserAction> = Collections.unmodifiableList(ArrayList(actions))
        override fun equals(other: Any?) = other is UserActionsRequired && actions == other.actions
        override fun hashCode() = actions.hashCode()
        override fun toString() = "UserActionsRequired(actions=$actions)"
    }

    class CleanupRequired(appWidgetIds: Collection<Int>) : WidgetReconciliationResult {
        val appWidgetIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(appWidgetIds))
        override fun equals(other: Any?) = other is CleanupRequired && appWidgetIds == other.appWidgetIds
        override fun hashCode() = appWidgetIds.hashCode()
        override fun toString() = "CleanupRequired(appWidgetIds=$appWidgetIds)"
    }
}

data class WidgetPendingUserAction(
    val moduleInstanceId: ModuleInstanceId,
    val action: WidgetUserAction,
)

interface WidgetCoordinator : AutoCloseable {
    suspend fun beginBinding(request: WidgetBindingRequest): WidgetFlowResult

    /** Allocates and binds a fresh framework ID for a copied or restored durable placement. */
    suspend fun beginRestoredBinding(moduleInstanceId: ModuleInstanceId): WidgetFlowResult

    suspend fun completePermission(
        moduleInstanceId: ModuleInstanceId,
        accepted: Boolean,
    ): WidgetFlowResult

    suspend fun completeConfiguration(
        moduleInstanceId: ModuleInstanceId,
        accepted: Boolean,
    ): WidgetFlowResult

    suspend fun cancelBinding(moduleInstanceId: ModuleInstanceId): WidgetFlowResult

    suspend fun show(moduleInstanceId: ModuleInstanceId): WidgetSurfaceResult

    suspend fun hide(moduleInstanceId: ModuleInstanceId)

    suspend fun resize(moduleInstanceId: ModuleInstanceId, size: WidgetSize): WidgetResizeResult

    suspend fun remove(moduleInstanceId: ModuleInstanceId): WidgetRemovalResult

    suspend fun reconcileAfterRecreation(): WidgetReconciliationResult
}

class DefaultWidgetCoordinator(
    private val store: LauncherStore,
    private val platform: WidgetPlatform,
) : WidgetCoordinator {
    private val visibleSurfaces = linkedMapOf<ModuleInstanceId, WidgetRenderToken>()
    private var closed = false

    override suspend fun beginBinding(request: WidgetBindingRequest): WidgetFlowResult {
        checkOpen()
        val allocation = platform.allocate()
        val appWidgetId = when (allocation) {
            is WidgetAllocationResult.Allocated -> allocation.appWidgetId
            is WidgetAllocationResult.Failed -> return WidgetFlowResult.Unavailable(allocation.code)
            WidgetAllocationResult.Closed -> return WidgetFlowResult.Unavailable("widget_platform_closed")
        }
        var durable = false
        try {
            val placement = WidgetPlacementRecord(
                moduleInstanceId = request.moduleInstanceId,
                appWidgetId = appWidgetId,
                providerPackage = request.provider.packageName,
                providerClassName = request.provider.className,
                profile = request.provider.profile,
                intendedWidthDp = request.size.widthDp,
                intendedHeightDp = request.size.heightDp,
                bindState = WidgetBindState.PENDING,
                restoreState = WidgetRestoreState.READY,
            )
            when (val committed = commit(LauncherEdit.BeginWidgetBinding(placement))) {
                is CommitResult.Rejected -> {
                    return deleteUncommittedAllocation(appWidgetId, committed.reason.code.name)
                }
                is CommitResult.Committed -> Unit
            }
            durable = true
            return when (val bound = platform.bind(AllocatedWidgetBinding(appWidgetId, request.provider, request.size))) {
                WidgetBindResult.Bound -> continueAfterBind(request.moduleInstanceId, appWidgetId)
                is WidgetBindResult.PermissionRequired -> WidgetFlowResult.AwaitingPermission(bound.action)
                WidgetBindResult.ProfileUnavailable -> failBinding(request.moduleInstanceId, appWidgetId, "profile_unavailable")
                WidgetBindResult.ProviderUnavailable -> failBinding(request.moduleInstanceId, appWidgetId, "provider_unavailable")
                is WidgetBindResult.Failed -> failBinding(request.moduleInstanceId, appWidgetId, bound.code)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (durable) {
                    cleanupDurablePlacement(request.moduleInstanceId, appWidgetId, remove = true)
                } else {
                    platform.delete(appWidgetId)
                }
            }
            throw cancelled
        } catch (failure: RuntimeException) {
            return cleanupAfterFailure(request.moduleInstanceId, appWidgetId, "widget_binding_failed")
        }
    }

    override suspend fun beginRestoredBinding(moduleInstanceId: ModuleInstanceId): WidgetFlowResult {
        checkOpen()
        val placement = placement(moduleInstanceId)
            ?: return WidgetFlowResult.Rejected("widget_placement_missing")
        if (placement.restoreState != WidgetRestoreState.REBIND_REQUIRED || placement.appWidgetId != null) {
            return WidgetFlowResult.Rejected("widget_rebind_not_required")
        }
        val appWidgetId = when (val allocation = platform.allocate()) {
            is WidgetAllocationResult.Allocated -> allocation.appWidgetId
            is WidgetAllocationResult.Failed -> return WidgetFlowResult.Unavailable(allocation.code)
            WidgetAllocationResult.Closed -> return WidgetFlowResult.Unavailable("widget_platform_closed")
        }
        var durable = false
        try {
            when (val committed = commit(
                LauncherEdit.ReplaceRestoredWidgetId(moduleInstanceId, appWidgetId),
            )) {
                is CommitResult.Rejected -> {
                    return deleteUncommittedAllocation(appWidgetId, committed.reason.code.name)
                }
                is CommitResult.Committed -> durable = true
            }
            val request = AllocatedWidgetBinding(
                appWidgetId,
                WidgetProviderIdentity(
                    placement.profile,
                    placement.providerPackage,
                    placement.providerClassName,
                ),
                WidgetSize(placement.intendedWidthDp, placement.intendedHeightDp),
            )
            return when (val bound = platform.bind(request)) {
                WidgetBindResult.Bound -> continueAfterBind(moduleInstanceId, appWidgetId)
                is WidgetBindResult.PermissionRequired -> WidgetFlowResult.AwaitingPermission(bound.action)
                WidgetBindResult.ProfileUnavailable ->
                    failBinding(moduleInstanceId, appWidgetId, "profile_unavailable")
                WidgetBindResult.ProviderUnavailable ->
                    failBinding(moduleInstanceId, appWidgetId, "provider_unavailable")
                is WidgetBindResult.Failed -> failBinding(moduleInstanceId, appWidgetId, bound.code)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (durable) {
                    cleanupDurablePlacement(moduleInstanceId, appWidgetId, remove = true)
                } else {
                    platform.delete(appWidgetId)
                }
            }
            throw cancelled
        } catch (_: RuntimeException) {
            return if (durable) {
                cleanupAfterFailure(moduleInstanceId, appWidgetId, "widget_rebind_failed")
            } else {
                deleteUncommittedAllocation(appWidgetId, "widget_rebind_failed")
            }
        }
    }

    override suspend fun completePermission(
        moduleInstanceId: ModuleInstanceId,
        accepted: Boolean,
    ): WidgetFlowResult {
        checkOpen()
        if (!accepted) return cancelBinding(moduleInstanceId)
        val placement = placement(moduleInstanceId)
            ?: return WidgetFlowResult.Rejected("widget_placement_missing")
        if (!placement.isPendingFlow()) return WidgetFlowResult.Rejected("widget_flow_not_pending")
        val id = placement.appWidgetId
            ?: return WidgetFlowResult.Rejected("widget_id_missing")
        return when (platform.validateBinding(id, placement.providerIdentity())) {
            WidgetValidationResult.Valid -> continueAfterBind(moduleInstanceId, id)
            WidgetValidationResult.NotBound -> failBinding(moduleInstanceId, id, "widget_not_bound")
            WidgetValidationResult.ProviderUnavailable -> failBinding(moduleInstanceId, id, "provider_unavailable")
            WidgetValidationResult.ProfileUnavailable -> failBinding(moduleInstanceId, id, "profile_unavailable")
            is WidgetValidationResult.Failed -> failBinding(moduleInstanceId, id, "binding_validation_failed")
        }
    }

    override suspend fun completeConfiguration(
        moduleInstanceId: ModuleInstanceId,
        accepted: Boolean,
    ): WidgetFlowResult {
        checkOpen()
        if (!accepted) return cancelBinding(moduleInstanceId)
        val placement = placement(moduleInstanceId)
            ?: return WidgetFlowResult.Rejected("widget_placement_missing")
        if (!placement.isPendingFlow()) return WidgetFlowResult.Rejected("widget_flow_not_pending")
        val id = placement.appWidgetId
            ?: return WidgetFlowResult.Rejected("widget_id_missing")
        return when (platform.validateBinding(id, placement.providerIdentity())) {
            WidgetValidationResult.Valid -> completeBinding(moduleInstanceId, id)
            WidgetValidationResult.NotBound -> failBinding(moduleInstanceId, id, "widget_not_bound")
            WidgetValidationResult.ProviderUnavailable -> failBinding(moduleInstanceId, id, "provider_unavailable")
            WidgetValidationResult.ProfileUnavailable -> failBinding(moduleInstanceId, id, "profile_unavailable")
            is WidgetValidationResult.Failed -> failBinding(moduleInstanceId, id, "binding_validation_failed")
        }
    }

    override suspend fun cancelBinding(moduleInstanceId: ModuleInstanceId): WidgetFlowResult {
        checkOpen()
        val placement = placement(moduleInstanceId)
            ?: return WidgetFlowResult.Rejected("widget_placement_missing")
        if (!placement.isPendingFlow()) return WidgetFlowResult.Rejected("widget_flow_not_pending")
        val id = placement.appWidgetId
            ?: return WidgetFlowResult.Rejected("widget_id_missing")
        return when (val cleanup = cleanupDurablePlacement(moduleInstanceId, id, remove = true)) {
            null -> WidgetFlowResult.Cancelled
            is WidgetFlowResult -> cleanup
        }
    }

    override suspend fun show(moduleInstanceId: ModuleInstanceId): WidgetSurfaceResult {
        checkOpen()
        visibleSurfaces[moduleInstanceId]?.let { return WidgetSurfaceResult.Ready(it) }
        val placement = placement(moduleInstanceId)
            ?: return WidgetSurfaceResult.NotBound
        if (placement.bindState != WidgetBindState.BOUND) return WidgetSurfaceResult.NotBound
        val id = placement.appWidgetId ?: return WidgetSurfaceResult.NotBound
        val result = platform.createSurface(
            id,
            placement.providerIdentity(),
            WidgetSize(placement.intendedWidthDp, placement.intendedHeightDp),
        )
        if (result is WidgetSurfaceResult.Ready) visibleSurfaces[moduleInstanceId] = result.token
        return result
    }

    override suspend fun hide(moduleInstanceId: ModuleInstanceId) {
        visibleSurfaces.remove(moduleInstanceId)?.let { platform.releaseSurface(it) }
    }

    override suspend fun resize(
        moduleInstanceId: ModuleInstanceId,
        size: WidgetSize,
    ): WidgetResizeResult {
        checkOpen()
        val placement = placement(moduleInstanceId)
            ?: return WidgetResizeResult.Rejected("widget_placement_missing")
        val id = placement.appWidgetId
            ?: return WidgetResizeResult.Rejected("widget_id_missing")
        val provider = placement.providerIdentity()
        val previousSize = WidgetSize(placement.intendedWidthDp, placement.intendedHeightDp)
        try {
            when (val updated = platform.updateSize(id, provider, size)) {
                WidgetUpdateResult.Updated -> Unit
                WidgetUpdateResult.NotBound -> return WidgetResizeResult.Unavailable("widget_not_bound")
                WidgetUpdateResult.ProviderUnavailable -> return WidgetResizeResult.Unavailable("provider_unavailable")
                WidgetUpdateResult.ProfileUnavailable -> return WidgetResizeResult.Unavailable("profile_unavailable")
                is WidgetUpdateResult.Failed -> return WidgetResizeResult.Unavailable(updated.code)
            }
            return when (val committed = commit(
                LauncherEdit.UpdateWidgetSize(moduleInstanceId, size.widthDp, size.heightDp),
            )) {
                is CommitResult.Committed -> WidgetResizeResult.Resized
                is CommitResult.Rejected -> {
                    restorePlatformSize(id, provider, previousSize)
                    WidgetResizeResult.Rejected(committed.reason.code.name)
                }
            }
        } catch (cancelled: CancellationException) {
            restorePlatformSize(id, provider, previousSize)
            throw cancelled
        }
    }

    override suspend fun remove(moduleInstanceId: ModuleInstanceId): WidgetRemovalResult {
        checkOpen()
        val placement = placement(moduleInstanceId)
            ?: return WidgetRemovalResult.NotFound(moduleInstanceId)
        hide(moduleInstanceId)
        val id = placement.appWidgetId
        if (id == null) {
            return when (val committed = commit(LauncherEdit.DeleteWidgetPlacement(moduleInstanceId))) {
                is CommitResult.Committed -> WidgetRemovalResult.Removed
                is CommitResult.Rejected -> WidgetRemovalResult.Rejected(committed.reason.code.name)
            }
        }
        return when (val cleanup = cleanupDurablePlacement(moduleInstanceId, id, remove = true)) {
            null -> WidgetRemovalResult.Removed
            is WidgetFlowResult.Rejected -> WidgetRemovalResult.Rejected(cleanup.code)
            is WidgetFlowResult.CleanupRequired -> WidgetRemovalResult.CleanupRequired(id, cleanup.code)
            else -> WidgetRemovalResult.Rejected("widget_cleanup_failed")
        }
    }

    override suspend fun reconcileAfterRecreation(): WidgetReconciliationResult {
        checkOpen()
        val snapshot = store.read()
        val pendingCleanup = snapshot.widgetPlacements.filter {
            it.cleanupState != WidgetCleanupState.NONE && it.appWidgetId != null
        }
        val failedCleanup = linkedSetOf<Int>()
        pendingCleanup.forEach { placement ->
            val id = requireNotNull(placement.appWidgetId)
            when (val deleted = platform.delete(id)) {
                WidgetDeletionResult.Deleted,
                WidgetDeletionResult.AlreadyDeleted,
                -> if (commit(placement.cleanupCompletionEdit()) is CommitResult.Rejected) {
                    failedCleanup += id
                }
                is WidgetDeletionResult.RetryRequired -> failedCleanup += id
            }
        }
        store.read().widgetPlacements
            .filter { it.bindState == WidgetBindState.BOUND && it.appWidgetId != null }
            .forEach { placement ->
                val id = requireNotNull(placement.appWidgetId)
                when (platform.validateBinding(id, placement.providerIdentity())) {
                    WidgetValidationResult.Valid,
                    WidgetValidationResult.ProfileUnavailable,
                    is WidgetValidationResult.Failed,
                    -> Unit
                    WidgetValidationResult.NotBound,
                    WidgetValidationResult.ProviderUnavailable,
                    -> if (!invalidateBinding(placement, id)) failedCleanup += id
                }
            }
        val pendingActions = mutableListOf<WidgetPendingUserAction>()
        store.read().widgetPlacements
            .filter {
                it.bindState == WidgetBindState.PENDING &&
                    it.cleanupState == WidgetCleanupState.NONE &&
                    it.appWidgetId != null
            }
            .forEach { placement ->
                val id = requireNotNull(placement.appWidgetId)
                when (platform.validateBinding(id, placement.providerIdentity())) {
                    WidgetValidationResult.Valid -> when (val resumed = continueAfterBind(placement.moduleInstanceId, id)) {
                        is WidgetFlowResult.AwaitingConfiguration -> pendingActions += WidgetPendingUserAction(
                            placement.moduleInstanceId,
                            resumed.action,
                        )
                        is WidgetFlowResult.CleanupRequired -> failedCleanup += resumed.appWidgetId
                        else -> Unit
                    }
                    WidgetValidationResult.NotBound -> pendingActions += WidgetPendingUserAction(
                        placement.moduleInstanceId,
                        WidgetUserAction.BindPermission(
                            appWidgetId = id,
                            provider = placement.providerIdentity(),
                            size = WidgetSize(placement.intendedWidthDp, placement.intendedHeightDp),
                        ),
                    )
                    WidgetValidationResult.ProviderUnavailable -> {
                        val failed = failBinding(placement.moduleInstanceId, id, "provider_unavailable")
                        if (failed is WidgetFlowResult.CleanupRequired) failedCleanup += failed.appWidgetId
                    }
                    WidgetValidationResult.ProfileUnavailable,
                    is WidgetValidationResult.Failed,
                    -> Unit
                }
        }
        val durableIds = store.read().widgetPlacements.mapNotNull { it.appWidgetId }.toSet()
        val allocatedIds = when (val allocated = platform.allocatedIds()) {
            is WidgetAllocatedIdsResult.Available -> allocated.appWidgetIds
            is WidgetAllocatedIdsResult.Failed -> return WidgetReconciliationResult.RetryRequired(allocated.code)
            WidgetAllocatedIdsResult.Closed -> return WidgetReconciliationResult.RetryRequired("widget_platform_closed")
        }
        val orphans = allocatedIds - durableIds
        val remaining = linkedSetOf<Int>().apply { addAll(failedCleanup) }
        orphans.sorted().forEach { id ->
            if (platform.delete(id) is WidgetDeletionResult.RetryRequired) remaining += id
        }
        return if (remaining.isNotEmpty()) {
            WidgetReconciliationResult.CleanupRequired(remaining)
        } else if (pendingActions.isNotEmpty()) {
            WidgetReconciliationResult.UserActionsRequired(pendingActions.toList())
        } else {
            WidgetReconciliationResult.Reconciled
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Platform implementations release every retained view before stopping their host.
        platform.close()
        visibleSurfaces.clear()
    }

    private suspend fun continueAfterBind(
        moduleInstanceId: ModuleInstanceId,
        appWidgetId: Int,
    ): WidgetFlowResult {
        val provider = placement(moduleInstanceId)?.providerIdentity()
            ?: return WidgetFlowResult.Rejected("widget_placement_missing")
        return when (val configuration = platform.configuration(appWidgetId, provider)) {
            WidgetConfigurationResult.NotRequired -> completeBinding(moduleInstanceId, appWidgetId)
            is WidgetConfigurationResult.Required -> WidgetFlowResult.AwaitingConfiguration(configuration.action)
            WidgetConfigurationResult.ProviderUnavailable ->
                failBinding(moduleInstanceId, appWidgetId, "provider_unavailable")
            WidgetConfigurationResult.ProfileUnavailable ->
                failBinding(moduleInstanceId, appWidgetId, "profile_unavailable")
            is WidgetConfigurationResult.Failed ->
                failBinding(moduleInstanceId, appWidgetId, configuration.code)
        }
    }

    private suspend fun restorePlatformSize(
        appWidgetId: Int,
        provider: WidgetProviderIdentity,
        size: WidgetSize,
    ) {
        withContext(NonCancellable) {
            platform.updateSize(appWidgetId, provider, size)
        }
    }

    private suspend fun completeBinding(
        moduleInstanceId: ModuleInstanceId,
        appWidgetId: Int,
    ): WidgetFlowResult =
        when (val committed = commit(LauncherEdit.CompleteWidgetBinding(moduleInstanceId))) {
            is CommitResult.Committed -> WidgetFlowResult.Bound
            is CommitResult.Rejected -> {
                // The framework binding cannot be rolled back atomically with Room. Delete the
                // allocated ID even when the durable completion is rejected, then retain an
                // explicit pending record if the store is also unable to record cleanup.
                val cleanup = cleanupDurablePlacement(moduleInstanceId, appWidgetId, remove = true)
                cleanup ?: WidgetFlowResult.Rejected(committed.reason.code.name)
            }
        }

    private suspend fun failBinding(
        moduleInstanceId: ModuleInstanceId,
        appWidgetId: Int,
        code: String,
    ): WidgetFlowResult {
        when (val begun = commit(LauncherEdit.BeginWidgetDeletion(moduleInstanceId))) {
            is CommitResult.Rejected -> return WidgetFlowResult.Rejected(begun.reason.code.name)
            is CommitResult.Committed -> Unit
        }
        val deletion = platform.delete(appWidgetId)
        if (deletion is WidgetDeletionResult.RetryRequired) {
            return WidgetFlowResult.CleanupRequired(appWidgetId, deletion.code)
        }
        return when (val committed = commit(LauncherEdit.FailWidgetBinding(moduleInstanceId))) {
            is CommitResult.Committed -> WidgetFlowResult.Unavailable(code)
            is CommitResult.Rejected -> WidgetFlowResult.Rejected(committed.reason.code.name)
        }
    }

    private suspend fun cleanupAfterFailure(
        moduleInstanceId: ModuleInstanceId,
        appWidgetId: Int,
        code: String,
    ): WidgetFlowResult {
        val current = placement(moduleInstanceId)
        if (current?.bindState == WidgetBindState.PENDING) {
            return failBinding(moduleInstanceId, appWidgetId, code)
        }
        val deletion = platform.delete(appWidgetId)
        if (deletion is WidgetDeletionResult.RetryRequired) {
            return WidgetFlowResult.CleanupRequired(appWidgetId, deletion.code)
        }
        return WidgetFlowResult.Unavailable(code)
    }

    private suspend fun deleteUncommittedAllocation(
        appWidgetId: Int,
        code: String,
    ): WidgetFlowResult = when (val deleted = platform.delete(appWidgetId)) {
        WidgetDeletionResult.Deleted,
        WidgetDeletionResult.AlreadyDeleted,
        -> WidgetFlowResult.Rejected(code)
        is WidgetDeletionResult.RetryRequired ->
            WidgetFlowResult.CleanupRequired(appWidgetId, deleted.code)
    }

    private suspend fun invalidateBinding(
        placement: WidgetPlacementRecord,
        appWidgetId: Int,
    ): Boolean {
        if (commit(LauncherEdit.BeginWidgetInvalidation(placement.moduleInstanceId)) is CommitResult.Rejected) {
            return false
        }
        if (platform.delete(appWidgetId) is WidgetDeletionResult.RetryRequired) return false
        return commit(LauncherEdit.CompleteWidgetInvalidation(placement.moduleInstanceId)) is CommitResult.Committed
    }

    /** Returns null after durable removal, otherwise the typed cleanup failure. */
    private suspend fun cleanupDurablePlacement(
        moduleInstanceId: ModuleInstanceId,
        appWidgetId: Int,
        remove: Boolean,
    ): WidgetFlowResult? {
        val current = placement(moduleInstanceId)
            ?: return WidgetFlowResult.Rejected("widget_placement_missing")
        if (current.cleanupState != WidgetCleanupState.DELETE_PENDING) {
            when (val begun = commit(LauncherEdit.BeginWidgetDeletion(moduleInstanceId))) {
                is CommitResult.Rejected -> return WidgetFlowResult.Rejected(begun.reason.code.name)
                is CommitResult.Committed -> Unit
            }
        }
        when (val deleted = platform.delete(appWidgetId)) {
            is WidgetDeletionResult.RetryRequired ->
                return WidgetFlowResult.CleanupRequired(appWidgetId, deleted.code)
            WidgetDeletionResult.Deleted,
            WidgetDeletionResult.AlreadyDeleted,
            -> Unit
        }
        val edit = if (remove) {
            LauncherEdit.CompleteWidgetDeletion(moduleInstanceId)
        } else {
            LauncherEdit.FailWidgetBinding(moduleInstanceId)
        }
        return when (val completed = commit(edit)) {
            is CommitResult.Committed -> null
            is CommitResult.Rejected -> WidgetFlowResult.Rejected(completed.reason.code.name)
        }
    }

    private suspend fun placement(moduleInstanceId: ModuleInstanceId): WidgetPlacementRecord? =
        store.read().widgetPlacements.singleOrNull { it.moduleInstanceId == moduleInstanceId }

    private fun WidgetPlacementRecord.providerIdentity() = WidgetProviderIdentity(
        profile = profile,
        packageName = providerPackage,
        className = providerClassName,
    )

    private fun WidgetPlacementRecord.isPendingFlow(): Boolean =
        bindState == WidgetBindState.PENDING &&
            cleanupState == WidgetCleanupState.NONE &&
            appWidgetId != null

    private fun WidgetPlacementRecord.cleanupCompletionEdit(): LauncherEdit = when (cleanupState) {
        WidgetCleanupState.DELETE_PENDING -> LauncherEdit.CompleteWidgetDeletion(moduleInstanceId)
        WidgetCleanupState.REBIND_PENDING -> LauncherEdit.CompleteWidgetInvalidation(moduleInstanceId)
        WidgetCleanupState.NONE -> error("Widget placement has no pending cleanup")
    }

    private suspend fun commit(edit: LauncherEdit): CommitResult {
        val state = store.read()
        return store.commit(LauncherTransaction(state.revision, listOf(edit)))
    }

    private fun checkOpen() = check(!closed) { "Widget coordinator is closed" }
}
