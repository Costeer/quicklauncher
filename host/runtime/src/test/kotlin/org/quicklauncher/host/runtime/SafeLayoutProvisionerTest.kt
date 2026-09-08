package org.quicklauncher.host.runtime

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.DestinationInstall
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.StoredConfigurationDocument

class SafeLayoutProvisionerTest {
    @Test
    fun `empty store becomes one start destination backed by the reserved safe layout`() = runTest {
        val store: LauncherStore = InMemoryLauncherStore()

        val snapshot = SafeLayoutProvisioner(store).ensureAvailable()

        assertEquals(SafeLayoutIds.START_DESTINATION, snapshot.startDestinationId)
        assertEquals(listOf("Start"), snapshot.destinations.map { it.name })
        val retained = snapshot.destinationLayouts.single()
        assertEquals(SafeLayoutIds.CONTRIBUTION, retained.layoutContributionId)
        assertTrue(retained.selected)
        val root = snapshot.moduleInstances.single()
        assertEquals(SafeLayoutIds.CONTRIBUTION, root.contributionId)
        val configuration = snapshot.configurationDocuments.single().document
        assertEquals(SafeLayoutIds.CONFIG_TYPE, configuration.configType)
        assertEquals("{}", configuration.encoded.value)
    }

    @Test
    fun `existing destinations retain one safe root without replacing their selected layout`() = runTest {
        val store: LauncherStore = InMemoryLauncherStore()
        val first = store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(LauncherEdit.Bootstrap(install("center", 0))),
            ),
        ) as CommitResult.Committed
        store.commit(
            LauncherTransaction(
                first.state.revision,
                listOf(LauncherEdit.InstallDestination(install("right", 1))),
            ),
        ) as CommitResult.Committed

        val snapshot = SafeLayoutProvisioner(store).ensureAvailable()

        snapshot.destinations.forEach { destination ->
            val retained = snapshot.destinationLayouts.filter {
                it.destinationId == destination.id
            }
            assertEquals(2, retained.size)
            assertEquals(1, retained.count { it.layoutContributionId == SafeLayoutIds.CONTRIBUTION })
            assertEquals(
                contributionId(destination.id.value.substringAfter('/')),
                retained.single { it.selected }.layoutContributionId,
            )
        }
    }

    @Test
    fun `a preexisting reserved ID cannot masquerade as the host safe root`() = runTest {
        val store: LauncherStore = InMemoryLauncherStore()
        val wrongInstance = ModuleInstanceId.parse("org.quicklauncher.instance/masquerading-safe")
        val wrongConfiguration = ConfigurationDocumentId.parse(
            "org.quicklauncher.configuration/masquerading-safe",
        )
        val install = DestinationInstall(
            destination = DestinationRecord(
                SafeLayoutIds.START_DESTINATION,
                "Start",
                DestinationCoordinate(0, 0),
            ),
            layout = DestinationLayoutRecord(
                SafeLayoutIds.START_DESTINATION,
                SafeLayoutIds.CONTRIBUTION,
                wrongInstance,
                selected = true,
            ),
            layoutInstance = ModuleInstanceRecord(
                wrongInstance,
                SafeLayoutIds.CONTRIBUTION,
                wrongConfiguration,
            ),
            configuration = StoredConfigurationDocument(
                wrongConfiguration,
                ConfigurationDocument(
                    SafeLayoutIds.CONFIG_TYPE,
                    SchemaVersion.of(1),
                    EncodedConfiguration.of("{}"),
                ),
            ),
        )
        store.commit(
            LauncherTransaction(
                store.read().revision,
                listOf(LauncherEdit.Bootstrap(install)),
            ),
        ) as CommitResult.Committed

        var rejected = false
        try {
            SafeLayoutProvisioner(store).ensureAvailable()
        } catch (_: IllegalStateException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(wrongInstance, store.read().destinationLayouts.single().layoutInstanceId)
    }

    private fun install(name: String, x: Long): DestinationInstall {
        val destinationId = DestinationId.parse("org.quicklauncher.destination/$name")
        val contributionId = contributionId(name)
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/$name")
        val configurationId = ConfigurationDocumentId.parse("org.quicklauncher.configuration/$name")
        return DestinationInstall(
            destination = DestinationRecord(destinationId, name, DestinationCoordinate(x, 0)),
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
                    ConfigTypeId.parse("org.quicklauncher.config/$name"),
                    SchemaVersion.of(1),
                    EncodedConfiguration.of("{\"name\":\"$name\"}"),
                ),
            ),
        )
    }

    private fun contributionId(name: String): ContributionId =
        ContributionId.parse("org.quicklauncher.layout/$name")
}
