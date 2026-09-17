package org.quicklauncher.host.data.store

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.host.data.spatial.DestinationCoordinate

class RestoreSnapshotStoreTest {
    @Test
    fun `restore is one validated revision and strips framework widget identity`() = runTest {
        val imported = validSnapshot("Imported", includeWidget = true)
        val store = InMemoryLauncherStore(validSnapshot("Current"))

        val result = store.commit(
            LauncherTransaction(store.read().revision, listOf(LauncherEdit.RestoreSnapshot(imported))),
        ) as CommitResult.Committed

        assertEquals(StoreRevision.of(1), result.state.revision)
        assertEquals("Imported", result.state.destinations.single().name)
        val widget = result.state.widgetPlacements.single()
        assertNull(widget.appWidgetId)
        assertEquals(WidgetBindState.PENDING, widget.bindState)
        assertEquals(WidgetRestoreState.REBIND_REQUIRED, widget.restoreState)
    }

    @Test
    fun `invalid restore is rejected without replacing any live row`() = runTest {
        val store = InMemoryLauncherStore(validSnapshot("Current"))
        val before = store.read()
        val invalid = LauncherSnapshot.restored(
            startDestinationId = DestinationId.parse("org.quicklauncher.test/missing"),
            destinations = before.destinations,
            destinationLayouts = before.destinationLayouts,
            moduleInstances = before.moduleInstances,
            configurationDocuments = before.configurationDocuments,
            placements = before.placements,
        )

        val result = store.commit(
            LauncherTransaction(before.revision, listOf(LauncherEdit.RestoreSnapshot(invalid))),
        )

        assertTrue(result is CommitResult.Rejected)
        assertSame(before, store.read())
    }

    @Test
    fun `theme-only replacement retains live widget identity`() = runTest {
        val current = validSnapshot("Current", includeWidget = true)
        val store = InMemoryLauncherStore(current)

        val result = store.commit(
            LauncherTransaction(
                current.revision,
                listOf(LauncherEdit.RestoreSnapshot(current, rebindWidgets = false)),
            ),
        ) as CommitResult.Committed

        assertEquals(42, result.state.widgetPlacements.single().appWidgetId)
        assertEquals(WidgetRestoreState.READY, result.state.widgetPlacements.single().restoreState)
    }

    private fun validSnapshot(name: String, includeWidget: Boolean = false): LauncherSnapshot {
        val destinationId = DestinationId.parse("org.quicklauncher.test/home")
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.test/safe-root")
        val configurationId = ConfigurationDocumentId.parse("org.quicklauncher.test/safe-config")
        val contributionId = ContributionId.parse("org.quicklauncher.core/safe-layout")
        return LauncherSnapshot.restored(
            destinationId,
            listOf(DestinationRecord(destinationId, name, DestinationCoordinate(0, 0))),
            listOf(DestinationLayoutRecord(destinationId, contributionId, instanceId, true)),
            listOf(ModuleInstanceRecord(instanceId, contributionId, configurationId)),
            listOf(
                StoredConfigurationDocument(
                    configurationId,
                    ConfigurationDocument(
                        ConfigTypeId.parse("org.quicklauncher.core/safe-layout"),
                        SchemaVersion.of(1),
                        EncodedConfiguration.of("{}"),
                    ),
                ),
            ),
            emptyList(),
            widgetPlacements = if (includeWidget) {
                listOf(
                    WidgetPlacementRecord(
                        instanceId,
                        42,
                        PackageName.parse("org.example.widget"),
                        "org.example.widget.Provider",
                        ProfileSerial.of(0),
                        100,
                        100,
                        WidgetBindState.BOUND,
                        WidgetRestoreState.READY,
                    ),
                )
            } else {
                emptyList()
            },
        )
    }
}
