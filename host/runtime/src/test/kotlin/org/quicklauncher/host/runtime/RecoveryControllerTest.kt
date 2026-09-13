package org.quicklauncher.host.runtime

import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.CrashMarkerId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PlacementId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.CrashMarkerRecord
import org.quicklauncher.host.data.store.DestinationInstall
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.ModuleInstanceStatus
import org.quicklauncher.host.data.store.ModuleQuarantineOrigin
import org.quicklauncher.host.data.store.NewPlacedModule
import org.quicklauncher.host.data.store.PlacementRecord
import org.quicklauncher.host.data.store.PlacementPolicy
import org.quicklauncher.host.data.store.StoredConfigurationDocument
import org.quicklauncher.host.runtime.diagnostics.BoundedDiagnosticLog
import org.quicklauncher.host.runtime.diagnostics.DiagnosticEventCode
import org.quicklauncher.host.runtime.diagnostics.DiagnosticRecord
import org.quicklauncher.host.runtime.diagnostics.DiagnosticStorage

class RecoveryControllerTest {
    @Test
    fun `throwing child renderer quarantines only that child and keeps the selected layout`() = runTest {
        val store = unsafeStore()
        SafeLayoutProvisioner(store).ensureAvailable()
        val before = store.read()
        val selected = before.destinationLayouts.single { it.selected }
        val childId = ModuleInstanceId.parse("org.quicklauncher.instance/crashing-child")
        val childConfigurationId = ConfigurationDocumentId.parse(
            "org.quicklauncher.configuration/crashing-child",
        )
        store.commit(
            LauncherTransaction(
                before.revision,
                listOf(
                    LauncherEdit.CommitDrop(
                        NewPlacedModule(
                            ModuleInstanceRecord(
                                childId,
                                ContributionId.parse("org.quicklauncher.block/crashing-child"),
                                childConfigurationId,
                            ),
                            StoredConfigurationDocument(
                                childConfigurationId,
                                ConfigurationDocument(
                                    ConfigTypeId.parse("org.quicklauncher.config/crashing-child"),
                                    SchemaVersion.of(1),
                                    EncodedConfiguration.of("{}"),
                                ),
                            ),
                            PlacementRecord(
                                PlacementId.parse("org.quicklauncher.placement/crashing-child"),
                                selected.layoutInstanceId,
                                selected.layoutInstanceId,
                                StableKey.parse("content"),
                                childId,
                                0,
                            ),
                        ),
                    ),
                ),
            ),
        ) as CommitResult.Committed
        val storage = MemoryDiagnosticStorage()
        val controller = RecoveryController(
            store,
            BoundedDiagnosticLog(storage, { 1_000L }),
            "phase4-test",
            clock = { 1_000L },
        )

        assertEquals(
            null,
            controller.reportRendererFailure(childId, IllegalStateException("child failed")),
        )

        val recovered = store.read()
        assertEquals(selected.layoutInstanceId, recovered.destinationLayouts.single { it.selected }.layoutInstanceId)
        assertEquals(ModuleInstanceStatus.Active, recovered.moduleInstances.single { it.id == selected.layoutInstanceId }.status)
        val childStatus = recovered.moduleInstances.single { it.id == childId }.status
            as ModuleInstanceStatus.Quarantined
        assertEquals(ModuleQuarantineOrigin.RENDERER, childStatus.origin)
        assertTrue(storage.records.any { it.code == DiagnosticEventCode.RENDERER_FAILED })
    }

    @Test
    fun `throwing selected renderer is quarantined without changing its configuration`() = runTest {
        val store = unsafeStore()
        SafeLayoutProvisioner(store).ensureAvailable()
        val before = store.read()
        val unsafe = before.destinationLayouts.single { it.selected }
        val instance = before.moduleInstances.single { it.id == unsafe.layoutInstanceId }
        val original = before.configurationDocuments.single {
            it.id == instance.configurationDocumentId
        }
        val storage = MemoryDiagnosticStorage()
        val controller = RecoveryController(
            store = store,
            diagnostics = BoundedDiagnosticLog(storage, { 1_000L }),
            appVersion = "phase3-test",
            clock = { 1_000L },
        )

        val result = controller.restoreSelectedLayout(instance.id) {
            throw IllegalStateException("message must not be persisted")
        }

        assertTrue(result is RendererRestoreResult.SafeFallback)
        val recovered = store.read()
        val failed = recovered.moduleInstances.single { it.id == instance.id }
        val quarantine = failed.status as ModuleInstanceStatus.Quarantined
        assertEquals(ModuleQuarantineOrigin.RENDERER, quarantine.origin)
        assertEquals(original, recovered.configurationDocuments.single { it.id == original.id })
        val selected = recovered.destinationLayouts.single { it.selected }
        assertEquals(SafeLayoutIds.CONTRIBUTION, selected.layoutContributionId)
        assertEquals(
            setOf(
                DiagnosticEventCode.STARTUP_ATTEMPT,
                DiagnosticEventCode.RENDERER_FAILED,
                DiagnosticEventCode.INSTANCE_QUARANTINED,
            ),
            storage.records.map(DiagnosticRecord::code).toSet(),
        )
        assertTrue(storage.records.none { it.toString().contains("message must not be persisted") })
    }

    @Test
    fun `third startup quarantines an unresolved crash loop before invoking renderer`() = runTest {
        val store = unsafeStore()
        SafeLayoutProvisioner(store).ensureAvailable()
        val instanceId = store.read().destinationLayouts.single { it.selected }.layoutInstanceId
        val marker = CrashMarkerRecord(
            id = CrashMarkerId.parse("org.quicklauncher.crash/existing"),
            moduleInstanceId = instanceId,
            appVersion = "phase3-test",
            startupAttempt = 2,
            timestampEpochMillis = 900L,
            outcome = null,
        )
        store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(LauncherEdit.BeginStartupRestore(marker)),
            ),
        ) as CommitResult.Committed
        val storage = MemoryDiagnosticStorage()
        val controller = RecoveryController(
            store,
            BoundedDiagnosticLog(storage, { 1_000L }),
            "phase3-test",
            clock = { 1_000L },
        )
        var invoked = false

        val result = controller.restoreSelectedLayout(instanceId) { invoked = true }

        assertTrue(result is RendererRestoreResult.SafeFallback)
        assertFalse(invoked)
        val recovered = store.read()
        val quarantine = recovered.moduleInstances.single { it.id == instanceId }.status
            as ModuleInstanceStatus.Quarantined
        assertEquals(ModuleQuarantineOrigin.RENDERER, quarantine.origin)
        assertEquals(SafeLayoutIds.CONTRIBUTION, recovered.destinationLayouts.single { it.selected }.layoutContributionId)
        assertTrue(storage.records.any { it.code == DiagnosticEventCode.CRASH_LOOP_DETECTED })
    }

    @Test
    fun `successful restoration clears its durable startup marker`() = runTest {
        val store = unsafeStore()
        val instanceId = store.read().destinationLayouts.single { it.selected }.layoutInstanceId
        val controller = RecoveryController(
            store,
            BoundedDiagnosticLog(MemoryDiagnosticStorage(), { 1_000L }),
            "phase3-test",
            clock = { 1_000L },
        )

        assertEquals(
            RendererRestoreResult.Restored,
            controller.restoreSelectedLayout(instanceId) {},
        )
        assertTrue(store.read().crashMarkers.isEmpty())
    }

    @Test
    fun `cancelled restoration propagates and clears its startup marker`() = runTest {
        val store = unsafeStore()
        val instanceId = store.read().destinationLayouts.single { it.selected }.layoutInstanceId
        val controller = RecoveryController(
            store,
            BoundedDiagnosticLog(MemoryDiagnosticStorage(), { 1_000L }),
            "phase3-test",
            clock = { 1_000L },
        )

        var cancelled = false
        try {
            controller.restoreSelectedLayout(instanceId) {
                throw CancellationException("test cancellation")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertTrue(store.read().crashMarkers.isEmpty())
    }

    private suspend fun unsafeStore(): LauncherStore {
        val store: LauncherStore = InMemoryLauncherStore(placementPolicy = PlacementPolicy { null })
        val destinationId = DestinationId.parse("org.quicklauncher.destination/start")
        val contributionId = ContributionId.parse("org.quicklauncher.layout/crashing")
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/crashing")
        val configurationId = ConfigurationDocumentId.parse("org.quicklauncher.configuration/crashing")
        val install = DestinationInstall(
            destination = DestinationRecord(destinationId, "Start", DestinationCoordinate(0, 0)),
            layout = DestinationLayoutRecord(
                destinationId,
                contributionId,
                instanceId,
                selected = true,
            ),
            layoutInstance = ModuleInstanceRecord(instanceId, contributionId, configurationId),
            configuration = StoredConfigurationDocument(
                configurationId,
                ConfigurationDocument(
                    ConfigTypeId.parse("org.quicklauncher.config/crashing"),
                    SchemaVersion.of(1),
                    EncodedConfiguration.of("  { \"keep\": true }  "),
                ),
            ),
        )
        store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(LauncherEdit.Bootstrap(install)),
            ),
        ) as CommitResult.Committed
        return store
    }

    private class MemoryDiagnosticStorage : DiagnosticStorage {
        var records: List<DiagnosticRecord> = emptyList()

        override suspend fun read(): List<DiagnosticRecord> = records

        override suspend fun replace(records: List<DiagnosticRecord>) {
            this.records = records
        }
    }
}
