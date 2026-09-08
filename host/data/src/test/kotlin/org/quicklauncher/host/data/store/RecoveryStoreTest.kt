package org.quicklauncher.host.data.store

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.CrashMarkerId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.host.data.spatial.DestinationCoordinate

class RecoveryStoreTest {
    @Test
    fun `beginning startup restore atomically records its unresolved marker`() = runTest {
        val store = bootstrappedStore()
        val before = store.read()
        val marker = crashMarker(attempt = 1)

        val result = store.commit(
            LauncherTransaction(
                expectedRevision = before.revision,
                edits = listOf(LauncherEdit.BeginStartupRestore(marker)),
            ),
        ) as CommitResult.Committed

        assertEquals(listOf(marker), result.state.crashMarkers)
        assertEquals(before.configurationDocuments, result.state.configurationDocuments)
        assertSame(result.state, store.read())
    }

    @Test
    fun `beginning another restore replaces the unresolved marker for that instance`() = runTest {
        val store = bootstrappedStore()
        val first = crashMarker(attempt = 1)
        commit(store, LauncherEdit.BeginStartupRestore(first))
        val beforeReplacement = store.read()
        val replacement = crashMarker(
            attempt = 2,
            id = CrashMarkerId.parse("org.quicklauncher.test/replacement-marker"),
        )

        val result = commit(store, LauncherEdit.BeginStartupRestore(replacement))

        assertEquals(listOf(replacement), result.state.crashMarkers)
        assertEquals(
            beforeReplacement.configurationDocuments,
            result.state.configurationDocuments,
        )
    }

    @Test
    fun `one transaction cannot leave two unresolved markers for one instance`() = runTest {
        val store = bootstrappedStore()
        val before = store.read()
        val replacement = crashMarker(
            attempt = 2,
            id = CrashMarkerId.parse("org.quicklauncher.test/transaction-marker"),
        )

        val result = store.commit(
            LauncherTransaction(
                expectedRevision = before.revision,
                edits = listOf(
                    LauncherEdit.BeginStartupRestore(crashMarker(attempt = 1)),
                    LauncherEdit.BeginStartupRestore(replacement),
                ),
            ),
        ) as CommitResult.Committed

        assertEquals(listOf(replacement), result.state.crashMarkers)
    }

    @Test
    fun `an unrelated edit cannot commit two unresolved markers for one instance`() = runTest {
        val first = crashMarker(attempt = 1)
        val second = crashMarker(
            attempt = 2,
            id = CrashMarkerId.parse("org.quicklauncher.test/duplicate-marker"),
        )
        val store = storeWithCrashMarkers(listOf(first, second))
        val before = store.read()

        val result = store.commit(
            LauncherTransaction(
                expectedRevision = before.revision,
                edits = listOf(
                    LauncherEdit.RenameDestination(
                        DestinationId.parse("org.quicklauncher.test/start"),
                        "Renamed",
                    ),
                ),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.DUPLICATE_ID, result.reason.code)
        assertSame(before, result.current)
        assertSame(before, store.read())
    }

    @Test
    fun `an unrelated edit cannot commit resolved and unresolved markers for one instance`() =
        runTest {
            val first = crashMarker(attempt = 1).copy(outcome = StableKey.parse("old-outcome"))
            val second = crashMarker(
                attempt = 2,
                id = CrashMarkerId.parse("org.quicklauncher.test/new-marker"),
            )
            val store = storeWithCrashMarkers(listOf(first, second))
            val before = store.read()

            val result = store.commit(
                LauncherTransaction(
                    expectedRevision = before.revision,
                    edits = listOf(
                        LauncherEdit.RenameDestination(
                            DestinationId.parse("org.quicklauncher.test/start"),
                            "Renamed",
                        ),
                    ),
                ),
            ) as CommitResult.Rejected

            assertEquals(StoreRejectionCode.DUPLICATE_ID, result.reason.code)
            assertSame(before, result.current)
            assertSame(before, store.read())
        }

    @Test
    fun `completing startup restore clears only its marker and preserves configuration`() = runTest {
        val store = bootstrappedStore()
        commit(store, LauncherEdit.BeginStartupRestore(crashMarker(attempt = 1)))
        val beforeCompletion = store.read()

        val result = commit(
            store,
            LauncherEdit.CompleteStartupRestore(moduleInstanceId()),
        )

        assertEquals(emptyList<CrashMarkerRecord>(), result.state.crashMarkers)
        assertEquals(beforeCompletion.configurationDocuments, result.state.configurationDocuments)
        assertEquals(beforeCompletion.moduleInstances, result.state.moduleInstances)
    }

    @Test
    fun `quarantining a crashing renderer records the outcome without changing configuration`() = runTest {
        val store = bootstrappedStore()
        val marker = crashMarker(attempt = 2)
        commit(store, LauncherEdit.BeginStartupRestore(marker))
        val beforeQuarantine = store.read()
        val outcome = StableKey.parse("renderer-quarantined")

        val result = commit(
            store,
            LauncherEdit.QuarantineRenderer(
                moduleInstanceId = moduleInstanceId(),
                code = "renderer.restore-crash-loop",
                message = "Renderer failed during consecutive startup restores",
                outcome = outcome,
            ),
        )

        assertEquals(
            ModuleInstanceStatus.Quarantined(
                code = "renderer.restore-crash-loop",
                message = "Renderer failed during consecutive startup restores",
                origin = ModuleQuarantineOrigin.RENDERER,
            ),
            result.state.moduleInstances.single().status,
        )
        assertEquals(listOf(marker.copy(outcome = outcome)), result.state.crashMarkers)
        assertEquals(beforeQuarantine.configurationDocuments, result.state.configurationDocuments)
    }

    @Test
    fun `retrying renderer quarantine reactivates only that instance and clears its marker`() = runTest {
        val store = bootstrappedStore()
        commit(store, LauncherEdit.BeginStartupRestore(crashMarker(attempt = 2)))
        commit(
            store,
            LauncherEdit.QuarantineRenderer(
                moduleInstanceId = moduleInstanceId(),
                code = "renderer.restore-crash-loop",
                message = "Renderer failed during consecutive startup restores",
                outcome = StableKey.parse("renderer-quarantined"),
            ),
        )
        val beforeRetry = store.read()

        val result = commit(store, LauncherEdit.RetryRenderer(moduleInstanceId()))

        assertEquals(ModuleInstanceStatus.Active, result.state.moduleInstances.single().status)
        assertEquals(emptyList<CrashMarkerRecord>(), result.state.crashMarkers)
        assertEquals(beforeRetry.configurationDocuments, result.state.configurationDocuments)
    }

    @Test
    fun `renderer retry rejects configuration quarantine without changing raw bytes`() = runTest {
        val store = storeWithQuarantine(ModuleQuarantineOrigin.CONFIGURATION)
        val before = store.read()

        val result = store.commit(
            LauncherTransaction(
                expectedRevision = before.revision,
                edits = listOf(LauncherEdit.RetryRenderer(moduleInstanceId())),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.RECOVERY_STATE_MISMATCH, result.reason.code)
        assertSame(before, result.current)
        assertSame(before, store.read())
        assertEquals("{\"original\":true}", before.configurationDocuments.single().document.encoded.value)
    }

    @Test
    fun `renderer retry rejects restore quarantine without changing raw bytes`() = runTest {
        val store = storeWithQuarantine(ModuleQuarantineOrigin.RESTORE)
        val before = store.read()

        val result = store.commit(
            LauncherTransaction(
                expectedRevision = before.revision,
                edits = listOf(LauncherEdit.RetryRenderer(moduleInstanceId())),
            ),
        ) as CommitResult.Rejected

        assertEquals(StoreRejectionCode.RECOVERY_STATE_MISMATCH, result.reason.code)
        assertSame(before, result.current)
        assertSame(before, store.read())
        assertEquals("{\"original\":true}", before.configurationDocuments.single().document.encoded.value)
    }

    private suspend fun bootstrappedStore(): LauncherStore {
        val store: LauncherStore = InMemoryLauncherStore()
        val initial = store.read()
        store.commit(
            LauncherTransaction(
                expectedRevision = initial.revision,
                edits = listOf(LauncherEdit.Bootstrap(sampleInstall())),
            ),
        ) as CommitResult.Committed
        return store
    }

    private suspend fun storeWithQuarantine(origin: ModuleQuarantineOrigin): LauncherStore {
        val base = bootstrappedStore().read()
        val quarantined = base.moduleInstances.single().copy(
            status = ModuleInstanceStatus.Quarantined(
                code = "${origin.name.lowercase()}.failure",
                message = "Recovery fixture",
                origin = origin,
            ),
        )
        return InMemoryLauncherStore(
            LauncherSnapshot(
                revision = base.revision,
                startDestinationId = base.startDestinationId,
                destinations = base.destinations,
                destinationLayouts = base.destinationLayouts,
                moduleInstances = listOf(quarantined),
                configurationDocuments = base.configurationDocuments,
                placements = base.placements,
            ),
        )
    }

    private suspend fun storeWithCrashMarkers(
        crashMarkers: Collection<CrashMarkerRecord>,
    ): LauncherStore {
        val base = bootstrappedStore().read()
        return InMemoryLauncherStore(
            LauncherSnapshot(
                revision = base.revision,
                startDestinationId = base.startDestinationId,
                destinations = base.destinations,
                destinationLayouts = base.destinationLayouts,
                moduleInstances = base.moduleInstances,
                configurationDocuments = base.configurationDocuments,
                placements = base.placements,
                crashMarkers = crashMarkers,
            ),
        )
    }

    private fun sampleInstall(): DestinationInstall {
        val destinationId = DestinationId.parse("org.quicklauncher.test/start")
        val instanceId = moduleInstanceId()
        val configurationId = ConfigurationDocumentId.parse("org.quicklauncher.test/safe-config")
        val contributionId = ContributionId.parse("org.quicklauncher.test/safe-layout")
        return DestinationInstall(
            destination = DestinationRecord(
                id = destinationId,
                name = "Start",
                coordinate = DestinationCoordinate(0, 0),
            ),
            layout = DestinationLayoutRecord(
                destinationId = destinationId,
                layoutContributionId = contributionId,
                layoutInstanceId = instanceId,
                selected = true,
            ),
            layoutInstance = ModuleInstanceRecord(
                id = instanceId,
                contributionId = contributionId,
                configurationDocumentId = configurationId,
            ),
            configuration = StoredConfigurationDocument(
                id = configurationId,
                document = ConfigurationDocument(
                    configType = ConfigTypeId.parse("org.quicklauncher.test/safe-config"),
                    schemaVersion = SchemaVersion.of(1),
                    encoded = EncodedConfiguration.of("{\"original\":true}"),
                ),
            ),
        )
    }

    private suspend fun commit(
        store: LauncherStore,
        edit: LauncherEdit,
    ): CommitResult.Committed {
        val before = store.read()
        return store.commit(
            LauncherTransaction(before.revision, listOf(edit)),
        ) as CommitResult.Committed
    }

    private fun crashMarker(
        attempt: Int,
        id: CrashMarkerId = CrashMarkerId.parse("org.quicklauncher.test/startup-marker"),
    ): CrashMarkerRecord = CrashMarkerRecord(
        id = id,
        moduleInstanceId = moduleInstanceId(),
        appVersion = "0.1.0",
        startupAttempt = attempt,
        timestampEpochMillis = 2_000L,
        outcome = null,
        firstObservedAtEpochMillis = 1_000L,
    )

    private fun moduleInstanceId(): ModuleInstanceId =
        ModuleInstanceId.parse("org.quicklauncher.test/safe-instance")
}
