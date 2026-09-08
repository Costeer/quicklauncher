package org.quicklauncher.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.CrashMarkerId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.CrashMarkerRecord
import org.quicklauncher.host.data.store.DestinationInstall
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.ModuleInstanceStatus
import org.quicklauncher.host.data.store.ModuleQuarantineOrigin
import org.quicklauncher.host.data.store.StoredConfigurationDocument
import org.quicklauncher.host.platform.diagnostics.AndroidDiagnosticLogFactory
import org.quicklauncher.host.platform.persistence.AndroidLauncherStoreFactory
import org.quicklauncher.host.runtime.DefaultLauncherRuntime
import org.quicklauncher.host.runtime.LauncherEntry
import org.quicklauncher.host.runtime.RecoveryController
import org.quicklauncher.host.runtime.RendererRestoreResult
import org.quicklauncher.host.runtime.SafeLayoutIds
import org.quicklauncher.host.runtime.SafeLayoutProvisioner
import org.quicklauncher.host.runtime.SelectedLayoutRestorer
import org.quicklauncher.host.runtime.catalog.AppCatalog
import org.quicklauncher.host.runtime.catalog.AppCatalogSnapshot
import org.quicklauncher.host.runtime.catalog.AppCatalogStatus
import org.quicklauncher.host.runtime.catalog.AppLaunchResult
import org.quicklauncher.host.runtime.diagnostics.DiagnosticEventCode
import org.quicklauncher.host.runtime.permissions.HomeRoleGateway
import org.quicklauncher.host.runtime.permissions.HomeRoleRequestDispatch
import org.quicklauncher.host.runtime.permissions.HomeRoleRequestReason
import org.quicklauncher.host.runtime.permissions.HomeRoleState
import org.quicklauncher.host.runtime.permissions.HomeSettingsOpenResult

@RunWith(AndroidJUnit4::class)
class LauncherPersistenceAndRecoveryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storeFactory = AndroidLauncherStoreFactory(context)

    @Test
    fun safeLauncherStateSurvivesProductionStoreCloseAndReopen() = runBlocking {
        context.deleteDatabase(PERSISTENCE_DATABASE)
        try {
            storeFactory.open(PERSISTENCE_DATABASE).use { firstProcess ->
                val provisioned = SafeLayoutProvisioner(firstProcess).ensureAvailable()
                assertEquals(SafeLayoutIds.START_DESTINATION, provisioned.startDestinationId)
            }

            storeFactory.open(PERSISTENCE_DATABASE).use { recreatedProcess ->
                val restored = recreatedProcess.read()
                assertEquals(SafeLayoutIds.START_DESTINATION, restored.startDestinationId)
                assertEquals(1, restored.destinations.size)
                assertEquals(SafeLayoutIds.CONTRIBUTION, restored.destinationLayouts.single().layoutContributionId)
                assertTrue(restored.destinationLayouts.single().selected)
            }
        } finally {
            context.deleteDatabase(PERSISTENCE_DATABASE)
        }
    }

    @Test
    fun deliberateRendererCrashQuarantinesOnlyRendererAndSelectsRetainedSafeLayout() = runBlocking {
        context.deleteDatabase(RECOVERY_DATABASE)
        val diagnosticFile = "phase3-device-recovery.bin"
        context.noBackupFilesDir.resolve(diagnosticFile).delete()
        try {
            storeFactory.open(RECOVERY_DATABASE).use { store ->
                val installed = store.commit(
                    LauncherTransaction(
                        store.read().revision,
                        listOf(LauncherEdit.Bootstrap(crashingLayout())),
                    ),
                )
                require(installed is CommitResult.Committed) {
                    "Could not install the deliberate crashing renderer: $installed"
                }
                SafeLayoutProvisioner(store).ensureAvailable()
                val before = store.read()
                val instance = before.moduleInstances.single { it.id == CRASHING_INSTANCE }
                val originalConfiguration = before.configurationDocuments.single {
                    it.id == instance.configurationDocumentId
                }
                val diagnostics = AndroidDiagnosticLogFactory(context).open(diagnosticFile)
                val controller = RecoveryController(store, diagnostics, "phase3-device-test")

                val result = controller.restoreSelectedLayout(instance.id) {
                    throw DeliberateRendererFailure()
                }

                assertTrue(result is RendererRestoreResult.SafeFallback)
                val recovered = store.read()
                val quarantine = recovered.moduleInstances.single { it.id == instance.id }.status
                    as ModuleInstanceStatus.Quarantined
                assertEquals(ModuleQuarantineOrigin.RENDERER, quarantine.origin)
                assertEquals(
                    originalConfiguration,
                    recovered.configurationDocuments.single { it.id == originalConfiguration.id },
                )
                assertEquals(
                    SafeLayoutIds.CONTRIBUTION,
                    recovered.destinationLayouts.single { it.selected }.layoutContributionId,
                )
                assertEquals(
                    setOf(
                        DiagnosticEventCode.STARTUP_ATTEMPT,
                        DiagnosticEventCode.RENDERER_FAILED,
                        DiagnosticEventCode.INSTANCE_QUARANTINED,
                    ),
                    diagnostics.records().map { it.code }.toSet(),
                )
            }
        } finally {
            context.deleteDatabase(RECOVERY_DATABASE)
            context.noBackupFilesDir.resolve(diagnosticFile).delete()
        }
    }

    @Test
    fun unresolvedStartupMarkerSurvivesStoreReopenAndTriggersCrashLoopFallback() = runBlocking {
        context.deleteDatabase(CRASH_LOOP_DATABASE)
        val diagnosticFile = "phase3-device-crash-loop.bin"
        context.noBackupFilesDir.resolve(diagnosticFile).delete()
        try {
            storeFactory.open(CRASH_LOOP_DATABASE).use { firstProcess ->
                require(
                    firstProcess.commit(
                        LauncherTransaction(
                            firstProcess.read().revision,
                            listOf(LauncherEdit.Bootstrap(crashingLayout())),
                        ),
                    ) is CommitResult.Committed,
                )
                SafeLayoutProvisioner(firstProcess).ensureAvailable()
                val snapshot = firstProcess.read()
                require(
                    firstProcess.commit(
                        LauncherTransaction(
                            snapshot.revision,
                            listOf(
                                LauncherEdit.BeginStartupRestore(
                                    CrashMarkerRecord(
                                        id = CrashMarkerId.parse(
                                            "org.quicklauncher.device/unresolved-startup",
                                        ),
                                        moduleInstanceId = CRASHING_INSTANCE,
                                        appVersion = "phase3-device-test",
                                        startupAttempt = 2,
                                        timestampEpochMillis = 1_000L,
                                        outcome = null,
                                        firstObservedAtEpochMillis = 900L,
                                    ),
                                ),
                            ),
                        ),
                    ) is CommitResult.Committed,
                )
            }

            storeFactory.open(CRASH_LOOP_DATABASE).use { recreatedProcess ->
                val diagnostics = AndroidDiagnosticLogFactory(context).open(diagnosticFile)
                val controller = RecoveryController(
                    recreatedProcess,
                    diagnostics,
                    "phase3-device-test",
                )
                var rendererInvoked = false

                val result = controller.restoreSelectedLayout(CRASHING_INSTANCE) {
                    rendererInvoked = true
                }

                assertTrue(result is RendererRestoreResult.SafeFallback)
                assertTrue("Crash-loop detection must not invoke the renderer", !rendererInvoked)
                val recovered = recreatedProcess.read()
                assertEquals(
                    SafeLayoutIds.CONTRIBUTION,
                    recovered.destinationLayouts.single { it.selected }.layoutContributionId,
                )
                assertTrue(
                    diagnostics.records().any { it.code == DiagnosticEventCode.CRASH_LOOP_DETECTED },
                )
            }
        } finally {
            context.deleteDatabase(CRASH_LOOP_DATABASE)
            context.noBackupFilesDir.resolve(diagnosticFile).delete()
        }
    }

    @Test
    fun launcherStartupInvokesSelectedRendererRecoveryAndPublishesSafeFallback() = runBlocking {
        context.deleteDatabase(RUNTIME_RECOVERY_DATABASE)
        val diagnosticFile = "phase3-device-runtime-recovery.bin"
        context.noBackupFilesDir.resolve(diagnosticFile).delete()
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            storeFactory.open(RUNTIME_RECOVERY_DATABASE).use { store ->
                require(
                    store.commit(
                        LauncherTransaction(
                            store.read().revision,
                            listOf(LauncherEdit.Bootstrap(crashingLayout())),
                        ),
                    ) is CommitResult.Committed,
                )
                val diagnostics = AndroidDiagnosticLogFactory(context).open(diagnosticFile)
                var selectedRendererInvocations = 0
                val runtime = DefaultLauncherRuntime(
                    store = store,
                    catalog = EmptyCatalog,
                    roleGateway = UnavailableHomeRole,
                    diagnostics = diagnostics,
                    appVersion = "phase3-device-test",
                    parentScope = runtimeScope,
                    selectedLayoutRestorer = SelectedLayoutRestorer {
                        selectedRendererInvocations += 1
                        throw DeliberateRendererFailure()
                    },
                )

                try {
                    runtime.start(LauncherEntry.HOME)

                    assertEquals(1, selectedRendererInvocations)
                    val recovered = store.read()
                    assertEquals(
                        SafeLayoutIds.CONTRIBUTION,
                        recovered.destinationLayouts.single { it.selected }.layoutContributionId,
                    )
                    assertEquals(DEVICE_DESTINATION, runtime.state.value.currentDestinationId)
                } finally {
                    runtime.close()
                }
            }
        } finally {
            runtimeScope.cancel()
            context.deleteDatabase(RUNTIME_RECOVERY_DATABASE)
            context.noBackupFilesDir.resolve(diagnosticFile).delete()
        }
    }

    private fun crashingLayout(): DestinationInstall = DestinationInstall(
        destination = DestinationRecord(
            id = DEVICE_DESTINATION,
            name = "Device recovery",
            coordinate = DestinationCoordinate(0, 0),
        ),
        layout = DestinationLayoutRecord(
            destinationId = DEVICE_DESTINATION,
            layoutContributionId = ContributionId.parse("org.quicklauncher.device/crashing-layout"),
            layoutInstanceId = CRASHING_INSTANCE,
            selected = true,
        ),
        layoutInstance = ModuleInstanceRecord(
            id = CRASHING_INSTANCE,
            contributionId = ContributionId.parse("org.quicklauncher.device/crashing-layout"),
            configurationDocumentId = ConfigurationDocumentId.parse(
                "org.quicklauncher.device/crashing-configuration",
            ),
        ),
        configuration = StoredConfigurationDocument(
            id = ConfigurationDocumentId.parse("org.quicklauncher.device/crashing-configuration"),
            document = ConfigurationDocument(
                configType = ConfigTypeId.parse("org.quicklauncher.device/crashing-config"),
                schemaVersion = SchemaVersion.of(1),
                encoded = EncodedConfiguration.of("{\"deviceFixture\":true}"),
            ),
        ),
    )

    private class DeliberateRendererFailure : RuntimeException()

    private data object EmptyCatalog : AppCatalog {
        private val snapshot = MutableStateFlow(
            AppCatalogSnapshot(AppCatalogStatus.READY, emptyList()),
        )
        override val state: StateFlow<AppCatalogSnapshot> = snapshot

        override suspend fun refresh() = Unit

        override suspend fun launch(
            identity: org.quicklauncher.contracts.domain.AppActivityIdentity,
        ): AppLaunchResult = AppLaunchResult.ActivityUnavailable

        override fun close() = Unit
    }

    private data object UnavailableHomeRole : HomeRoleGateway {
        override fun currentState(): HomeRoleState = HomeRoleState.UNAVAILABLE

        override suspend fun requestHomeRole(
            reason: HomeRoleRequestReason,
        ): HomeRoleRequestDispatch = HomeRoleRequestDispatch.Unavailable

        override fun openHomeSettings(): HomeSettingsOpenResult = HomeSettingsOpenResult.Unavailable
    }

    private companion object {
        const val PERSISTENCE_DATABASE = "phase3-device-persistence.db"
        const val RECOVERY_DATABASE = "phase3-device-recovery.db"
        const val CRASH_LOOP_DATABASE = "phase3-device-crash-loop.db"
        const val RUNTIME_RECOVERY_DATABASE = "phase3-device-runtime-recovery.db"
        val DEVICE_DESTINATION: DestinationId =
            DestinationId.parse("org.quicklauncher.device/start")
        val CRASHING_INSTANCE: ModuleInstanceId =
            ModuleInstanceId.parse("org.quicklauncher.device/crashing-instance")
    }
}
