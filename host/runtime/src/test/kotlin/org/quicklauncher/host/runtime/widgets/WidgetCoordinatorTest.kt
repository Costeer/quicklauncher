package org.quicklauncher.host.runtime.widgets

import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.SchemaVersion
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
import org.quicklauncher.host.data.store.WidgetBindState
import org.quicklauncher.host.data.store.WidgetRestoreState

class WidgetCoordinatorTest {
    @Test
    fun `cancelled bind deletes its allocated id and durable placement`() = runTest {
        val store = widgetStore()
        val platform = FakeWidgetPlatform(
            bindResult = WidgetBindResult.PermissionRequired(
                WidgetUserAction.BindPermission(71, widgetRequest().provider, widgetRequest().size),
            ),
        )
        val coordinator = DefaultWidgetCoordinator(store, platform)

        val begun = coordinator.beginBinding(widgetRequest())
        assertType<WidgetFlowResult.AwaitingPermission>(begun)

        assertEquals(WidgetFlowResult.Cancelled, coordinator.cancelBinding(WIDGET_INSTANCE))
        assertEquals(listOf(71), platform.deletedIds)
        assertEquals(emptyList<Any>(), store.read().widgetPlacements)
    }

    @Test
    fun `rejected begin commit deletes the new framework id`() = runTest {
        val delegate = widgetStore()
        val store = RejectingCommitStore(delegate)
        val platform = FakeWidgetPlatform(bindResult = WidgetBindResult.Bound)

        val result = DefaultWidgetCoordinator(store, platform).beginBinding(widgetRequest())

        assertType<WidgetFlowResult.Rejected>(result)
        assertEquals(listOf(71), platform.deletedIds)
        assertEquals(emptyList<Any>(), delegate.read().widgetPlacements)
    }

    @Test
    fun `rejected begin commit reports an orphan until framework deletion can retry`() = runTest {
        val delegate = widgetStore()
        val store = RejectingCommitStore(delegate)
        val platform = FakeWidgetPlatform(bindResult = WidgetBindResult.Bound).apply {
            deletionResults.add(WidgetDeletionResult.RetryRequired("host_busy"))
        }
        val coordinator = DefaultWidgetCoordinator(store, platform)

        assertEquals(
            WidgetFlowResult.CleanupRequired(71, "host_busy"),
            coordinator.beginBinding(widgetRequest()),
        )
        assertEquals(WidgetAllocatedIdsResult.Available(setOf(71)), platform.allocatedIds())

        assertEquals(WidgetReconciliationResult.Reconciled, coordinator.reconcileAfterRecreation())
        assertEquals(WidgetAllocatedIdsResult.Available(emptySet()), platform.allocatedIds())
    }

    @Test
    fun `rejected completion commit deletes the bound framework id`() = runTest {
        val delegate = widgetStore()
        val store = RejectingNthCommitStore(delegate, rejectAt = 2)
        val platform = FakeWidgetPlatform(bindResult = WidgetBindResult.Bound)

        val result = DefaultWidgetCoordinator(store, platform).beginBinding(widgetRequest())

        assertType<WidgetFlowResult.Rejected>(result)
        assertEquals(listOf(71), platform.deletedIds)
        assertTrue(delegate.read().widgetPlacements.none { it.appWidgetId == 71 })
    }

    @Test
    fun `configuration completes one durable binding`() = runTest {
        val store = widgetStore()
        val platform = FakeWidgetPlatform(
            bindResult = WidgetBindResult.Bound,
            configurationResult = WidgetConfigurationResult.Required(WidgetUserAction.Configure(71)),
        )
        val coordinator = DefaultWidgetCoordinator(store, platform)

        assertType<WidgetFlowResult.AwaitingConfiguration>(coordinator.beginBinding(widgetRequest()))
        assertEquals(WidgetFlowResult.Bound, coordinator.completeConfiguration(WIDGET_INSTANCE, accepted = true))
        val placement = store.read().widgetPlacements.single()
        assertEquals(71, placement.appWidgetId)
        assertEquals(WidgetBindState.BOUND, placement.bindState)
    }

    @Test
    fun `duplicate configuration result cannot delete an already durable binding`() = runTest {
        val store = widgetStore()
        val platform = FakeWidgetPlatform(
            bindResult = WidgetBindResult.Bound,
            configurationResult = WidgetConfigurationResult.Required(WidgetUserAction.Configure(71)),
        )
        val coordinator = DefaultWidgetCoordinator(store, platform)
        assertType<WidgetFlowResult.AwaitingConfiguration>(coordinator.beginBinding(widgetRequest()))
        assertEquals(WidgetFlowResult.Bound, coordinator.completeConfiguration(WIDGET_INSTANCE, accepted = true))

        val duplicate = coordinator.completeConfiguration(WIDGET_INSTANCE, accepted = true)

        assertEquals(WidgetFlowResult.Rejected("widget_flow_not_pending"), duplicate)
        assertEquals(emptyList<Int>(), platform.deletedIds)
        assertEquals(WidgetBindState.BOUND, store.read().widgetPlacements.single().bindState)
    }

    @Test
    fun `visible neighbors receive distinct surfaces and dormant placements release theirs`() = runTest {
        val store = widgetStore()
        val platform = FakeWidgetPlatform(bindResult = WidgetBindResult.Bound)
        val coordinator = DefaultWidgetCoordinator(store, platform)
        assertEquals(WidgetFlowResult.Bound, coordinator.beginBinding(widgetRequest(WIDGET_INSTANCE)))
        platform.nextId = 72
        assertEquals(WidgetFlowResult.Bound, coordinator.beginBinding(widgetRequest(NEIGHBOR_INSTANCE)))

        val first = assertType<WidgetSurfaceResult.Ready>(coordinator.show(WIDGET_INSTANCE))
        val second = assertType<WidgetSurfaceResult.Ready>(coordinator.show(NEIGHBOR_INSTANCE))
        coordinator.hide(WIDGET_INSTANCE)

        assertEquals(WidgetRenderToken.of("surface-71"), first.token)
        assertEquals(WidgetRenderToken.of("surface-72"), second.token)
        assertEquals(listOf(first.token), platform.releasedTokens)
        assertEquals(2, store.read().widgetPlacements.size)
    }

    @Test
    fun `failed platform resize leaves the exact durable snapshot unchanged`() = runTest {
        val store = widgetStore()
        val platform = FakeWidgetPlatform(bindResult = WidgetBindResult.Bound)
        val coordinator = DefaultWidgetCoordinator(store, platform)
        assertEquals(WidgetFlowResult.Bound, coordinator.beginBinding(widgetRequest()))
        val before = store.read()
        platform.updateResults += WidgetUpdateResult.Failed("provider_busy")

        assertEquals(
            WidgetResizeResult.Unavailable("provider_busy"),
            coordinator.resize(WIDGET_INSTANCE, WidgetSize(240, 160)),
        )

        assertEquals(before, store.read())
        assertEquals(listOf(WidgetSize(240, 160)), platform.sizeUpdates)
    }

    @Test
    fun `rejected resize commit restores the platform and leaves durable state unchanged`() = runTest {
        val delegate = widgetStore()
        val store = RejectingNthCommitStore(delegate, rejectAt = 3)
        val platform = FakeWidgetPlatform(bindResult = WidgetBindResult.Bound)
        val coordinator = DefaultWidgetCoordinator(store, platform)
        assertEquals(WidgetFlowResult.Bound, coordinator.beginBinding(widgetRequest()))
        val before = delegate.read()

        assertType<WidgetResizeResult.Rejected>(
            coordinator.resize(WIDGET_INSTANCE, WidgetSize(240, 160)),
        )

        assertEquals(before, delegate.read())
        assertEquals(listOf(WidgetSize(240, 160), widgetRequest().size), platform.sizeUpdates)
    }

    @Test
    fun `cancelled resize restores the platform and propagates cancellation`() = runTest {
        val store = widgetStore()
        val platform = FakeWidgetPlatform(bindResult = WidgetBindResult.Bound)
        val coordinator = DefaultWidgetCoordinator(store, platform)
        assertEquals(WidgetFlowResult.Bound, coordinator.beginBinding(widgetRequest()))
        val before = store.read()
        platform.cancelNextSizeUpdate = true

        val failure = runCatching {
            coordinator.resize(WIDGET_INSTANCE, WidgetSize(240, 160))
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(before, store.read())
        assertEquals(listOf(WidgetSize(240, 160), widgetRequest().size), platform.sizeUpdates)
    }

    @Test
    fun `process recreation deletes framework ids absent from durable state`() = runTest {
        val store = widgetStore()
        val platform = FakeWidgetPlatform(bindResult = WidgetBindResult.Bound).apply {
            allocatedIds = setOf(70, 71)
        }

        val result = DefaultWidgetCoordinator(store, platform).reconcileAfterRecreation()

        assertEquals(WidgetReconciliationResult.Reconciled, result)
        assertEquals(listOf(70, 71), platform.deletedIds)
    }

    @Test
    fun `process recreation requests retry when allocated id enumeration fails`() = runTest {
        val store = widgetStore()
        val platform = FakeWidgetPlatform(bindResult = WidgetBindResult.Bound).apply {
            allocatedIdsResult = WidgetAllocatedIdsResult.Failed("enumeration_unavailable")
        }

        val result = DefaultWidgetCoordinator(store, platform).reconcileAfterRecreation()

        assertEquals(WidgetReconciliationResult.RetryRequired("enumeration_unavailable"), result)
        assertEquals(emptyList<Int>(), platform.deletedIds)
    }

    @Test
    fun `process recreation resumes a pending bind through a typed permission action`() = runTest {
        val store = widgetStore()
        val platform = FakeWidgetPlatform(
            bindResult = WidgetBindResult.PermissionRequired(
                WidgetUserAction.BindPermission(71, widgetRequest().provider, widgetRequest().size),
            ),
        )
        val coordinator = DefaultWidgetCoordinator(store, platform)
        assertType<WidgetFlowResult.AwaitingPermission>(coordinator.beginBinding(widgetRequest()))
        platform.validationResult = WidgetValidationResult.NotBound

        assertEquals(
            WidgetReconciliationResult.UserActionsRequired(
                listOf(
                    WidgetPendingUserAction(
                        WIDGET_INSTANCE,
                        WidgetUserAction.BindPermission(71, widgetRequest().provider, widgetRequest().size),
                    ),
                ),
            ),
            coordinator.reconcileAfterRecreation(),
        )
    }

    @Test
    fun `process recreation completes a valid pending binding without configuration`() = runTest {
        val store = widgetStore()
        val platform = FakeWidgetPlatform(
            bindResult = WidgetBindResult.PermissionRequired(
                WidgetUserAction.BindPermission(71, widgetRequest().provider, widgetRequest().size),
            ),
        )
        val coordinator = DefaultWidgetCoordinator(store, platform)
        assertType<WidgetFlowResult.AwaitingPermission>(coordinator.beginBinding(widgetRequest()))
        platform.validationResult = WidgetValidationResult.Valid

        assertEquals(WidgetReconciliationResult.Reconciled, coordinator.reconcileAfterRecreation())
        assertEquals(WidgetBindState.BOUND, store.read().widgetPlacements.single().bindState)
    }

    @Test
    fun `provider removal deletes the invalid binding and retains a pending rebind placement`() = runTest {
        val store = widgetStore()
        val platform = FakeWidgetPlatform(bindResult = WidgetBindResult.Bound)
        val coordinator = DefaultWidgetCoordinator(store, platform)
        assertEquals(WidgetFlowResult.Bound, coordinator.beginBinding(widgetRequest()))
        platform.validationResult = WidgetValidationResult.ProviderUnavailable

        assertEquals(WidgetReconciliationResult.Reconciled, coordinator.reconcileAfterRecreation())

        val placement = store.read().widgetPlacements.single()
        assertEquals(null, placement.appWidgetId)
        assertEquals(WidgetBindState.PENDING, placement.bindState)
        assertEquals(WidgetRestoreState.REBIND_REQUIRED, placement.restoreState)
        assertEquals(listOf(71), platform.deletedIds)
    }

    private suspend fun widgetStore(): InMemoryLauncherStore {
        val store = InMemoryLauncherStore()
        val configuration = StoredConfigurationDocument(
            ConfigurationDocumentId.parse("org.quicklauncher.test/config-layout"),
            ConfigurationDocument(
                ConfigTypeId.parse("org.quicklauncher.test/layout-config"),
                SchemaVersion.of(1),
                EncodedConfiguration.of("{}"),
            ),
        )
        val layout = ModuleInstanceRecord(
            ModuleInstanceId.parse("org.quicklauncher.test/instance-layout"),
            ContributionId.parse("org.quicklauncher.test/layout"),
            configuration.id,
        )
        val result = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(
                    LauncherEdit.Bootstrap(
                        DestinationInstall(
                            DestinationRecord(
                                DestinationId.parse("org.quicklauncher.test/destination-home"),
                                "Home",
                                DestinationCoordinate(0, 0),
                            ),
                            DestinationLayoutRecord(
                                DestinationId.parse("org.quicklauncher.test/destination-home"),
                                layout.contributionId,
                                layout.id,
                                selected = true,
                            ),
                            layout,
                            configuration,
                        ),
                    ),
                ),
            ),
        )
        check(result is CommitResult.Committed)
        // Widget records attach to module instances. Retained layouts give this test two real owners.
        val secondConfiguration = configuration.copy(
            id = ConfigurationDocumentId.parse("org.quicklauncher.test/config-widget"),
        )
        val widgetInstance = ModuleInstanceRecord(
            WIDGET_INSTANCE,
            ContributionId.parse("org.quicklauncher.test/widget"),
            secondConfiguration.id,
        )
        val neighborConfiguration = configuration.copy(
            id = ConfigurationDocumentId.parse("org.quicklauncher.test/config-neighbor"),
        )
        val neighborInstance = ModuleInstanceRecord(
            NEIGHBOR_INSTANCE,
            ContributionId.parse("org.quicklauncher.test/widget-neighbor"),
            neighborConfiguration.id,
        )
        val state = store.read()
        val added = store.commit(
            LauncherTransaction(
                state.revision,
                listOf(
                    LauncherEdit.RetainLayout(
                        DestinationLayoutRecord(
                            DestinationId.parse("org.quicklauncher.test/destination-home"),
                            widgetInstance.contributionId,
                            widgetInstance.id,
                            selected = false,
                        ),
                        widgetInstance,
                        secondConfiguration,
                    ),
                    LauncherEdit.RetainLayout(
                        DestinationLayoutRecord(
                            DestinationId.parse("org.quicklauncher.test/destination-home"),
                            neighborInstance.contributionId,
                            neighborInstance.id,
                            selected = false,
                        ),
                        neighborInstance,
                        neighborConfiguration,
                    ),
                ),
            ),
        )
        check(added is CommitResult.Committed)
        return store
    }

    private fun widgetRequest(
        instanceId: ModuleInstanceId = WIDGET_INSTANCE,
    ) = WidgetBindingRequest(
        moduleInstanceId = instanceId,
        provider = WidgetProviderIdentity(
            ProfileSerial.of(0),
            PackageName.parse("org.example.widget"),
            "org.example.widget.Provider",
        ),
        size = WidgetSize(180, 120),
    )

    private class RejectingCommitStore(
        private val delegate: InMemoryLauncherStore,
    ) : org.quicklauncher.host.data.store.LauncherStore by delegate {
        override suspend fun commit(transaction: LauncherTransaction): CommitResult = CommitResult.Rejected(
            org.quicklauncher.host.data.store.StoreRejection(
                org.quicklauncher.host.data.store.StoreRejectionCode.STALE_REVISION,
                "injected rejection",
            ),
            delegate.read(),
        )
    }

    private class RejectingNthCommitStore(
        private val delegate: InMemoryLauncherStore,
        private val rejectAt: Int,
    ) : org.quicklauncher.host.data.store.LauncherStore by delegate {
        private var commits = 0

        override suspend fun commit(transaction: LauncherTransaction): CommitResult {
            commits += 1
            if (commits != rejectAt) return delegate.commit(transaction)
            return CommitResult.Rejected(
                org.quicklauncher.host.data.store.StoreRejection(
                    org.quicklauncher.host.data.store.StoreRejectionCode.STALE_REVISION,
                    "injected rejection",
                ),
                delegate.read(),
            )
        }
    }

    private class FakeWidgetPlatform(
        private val bindResult: WidgetBindResult,
        private val configurationResult: WidgetConfigurationResult = WidgetConfigurationResult.NotRequired,
    ) : WidgetPlatform {
        var nextId = 71
        var allocatedIds: Set<Int> = emptySet()
        val deletedIds = mutableListOf<Int>()
        val releasedTokens = mutableListOf<WidgetRenderToken>()
        val deletionResults = ArrayDeque<WidgetDeletionResult>()
        val updateResults = ArrayDeque<WidgetUpdateResult>()
        val sizeUpdates = mutableListOf<WidgetSize>()
        var validationResult: WidgetValidationResult = WidgetValidationResult.Valid
        var allocatedIdsResult: WidgetAllocatedIdsResult? = null
        var cancelNextSizeUpdate = false

        override suspend fun discoverProviders(profile: ProfileSerial): WidgetProviderDiscoveryResult =
            WidgetProviderDiscoveryResult.Available(emptyList())

        override suspend fun allocate(): WidgetAllocationResult {
            allocatedIds = allocatedIds + nextId
            return WidgetAllocationResult.Allocated(nextId)
        }

        override suspend fun bind(request: AllocatedWidgetBinding): WidgetBindResult = bindResult

        override suspend fun validateBinding(
            appWidgetId: Int,
            expectedProvider: WidgetProviderIdentity,
        ): WidgetValidationResult = validationResult

        override suspend fun configuration(
            appWidgetId: Int,
            expectedProvider: WidgetProviderIdentity,
        ): WidgetConfigurationResult =
            configurationResult

        override suspend fun createSurface(
            appWidgetId: Int,
            expectedProvider: WidgetProviderIdentity,
            size: WidgetSize,
        ): WidgetSurfaceResult =
            WidgetSurfaceResult.Ready(WidgetRenderToken.of("surface-$appWidgetId"))

        override suspend fun releaseSurface(token: WidgetRenderToken) {
            releasedTokens += token
        }

        override suspend fun updateSize(
            appWidgetId: Int,
            expectedProvider: WidgetProviderIdentity,
            size: WidgetSize,
        ): WidgetUpdateResult {
            sizeUpdates += size
            if (cancelNextSizeUpdate) {
                cancelNextSizeUpdate = false
                throw CancellationException("injected resize cancellation")
            }
            return updateResults.removeFirstOrNull() ?: WidgetUpdateResult.Updated
        }

        override suspend fun delete(appWidgetId: Int): WidgetDeletionResult {
            deletedIds += appWidgetId
            val result = deletionResults.removeFirstOrNull() ?: WidgetDeletionResult.Deleted
            if (result is WidgetDeletionResult.RetryRequired) return result
            allocatedIds = allocatedIds - appWidgetId
            return result
        }

        override suspend fun allocatedIds(): WidgetAllocatedIdsResult =
            allocatedIdsResult ?: WidgetAllocatedIdsResult.Available(allocatedIds)

        override fun close() = Unit
    }

    private companion object {
        val WIDGET_INSTANCE = ModuleInstanceId.parse("org.quicklauncher.test/instance-widget")
        val NEIGHBOR_INSTANCE = ModuleInstanceId.parse("org.quicklauncher.test/instance-neighbor")
    }
}

private inline fun <reified T> assertType(value: Any?): T {
    assertTrue("Expected ${T::class.java.simpleName}, received $value", value is T)
    return value as T
}
