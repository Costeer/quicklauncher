package org.quicklauncher.host.runtime

import java.security.MessageDigest
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
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.StoredConfigurationDocument

object SafeLayoutIds {
    val CONTRIBUTION: ContributionId = ContributionId.parse("org.quicklauncher.core/safe-layout")
    val CONFIG_TYPE: ConfigTypeId = ConfigTypeId.parse("org.quicklauncher.core/safe-layout")
    val START_DESTINATION: DestinationId = DestinationId.parse("org.quicklauncher.core/start")
    val START_INSTANCE: ModuleInstanceId =
        ModuleInstanceId.parse("org.quicklauncher.core/safe-layout-root")
    val START_CONFIGURATION: ConfigurationDocumentId =
        ConfigurationDocumentId.parse("org.quicklauncher.core/safe-layout-root")

    fun instanceFor(destinationId: DestinationId): ModuleInstanceId =
        if (destinationId == START_DESTINATION) {
            START_INSTANCE
        } else {
            ModuleInstanceId.parse("org.quicklauncher.core/safe-${digest(destinationId.value)}")
        }

    fun configurationFor(destinationId: DestinationId): ConfigurationDocumentId =
        if (destinationId == START_DESTINATION) {
            START_CONFIGURATION
        } else {
            ConfigurationDocumentId.parse(
                "org.quicklauncher.core/safe-${digest(destinationId.value)}",
            )
        }

    fun recordsFor(destinationId: DestinationId, selected: Boolean): SafeLayoutRecords {
        val instanceId = instanceFor(destinationId)
        val configurationId = configurationFor(destinationId)
        return SafeLayoutRecords(
            DestinationLayoutRecord(destinationId, CONTRIBUTION, instanceId, selected),
            ModuleInstanceRecord(instanceId, CONTRIBUTION, configurationId),
            StoredConfigurationDocument(
                configurationId,
                ConfigurationDocument(
                    CONFIG_TYPE,
                    SchemaVersion.of(1),
                    EncodedConfiguration.of("{}"),
                ),
            ),
        )
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(10)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

data class SafeLayoutRecords(
    val layout: DestinationLayoutRecord,
    val instance: ModuleInstanceRecord,
    val configuration: StoredConfigurationDocument,
)

class SafeLayoutProvisioner(private val store: LauncherStore) {
    suspend fun ensureAvailable(): LauncherSnapshot {
        repeat(MAXIMUM_COMMIT_ATTEMPTS) {
            val current = store.read()
            validateExistingSafeLayouts(current)
            val edits = if (current.destinations.isEmpty()) {
                listOf(LauncherEdit.Bootstrap(startInstall()))
            } else {
                current.destinations
                    .filter { destination ->
                        current.destinationLayouts.none { retained ->
                            retained.destinationId == destination.id &&
                                retained.layoutContributionId == SafeLayoutIds.CONTRIBUTION
                        }
                    }
                    .map { destination ->
                        SafeLayoutIds.recordsFor(destination.id, selected = false).let { records ->
                            LauncherEdit.RetainLayout(records.layout, records.instance, records.configuration)
                        }
                    }
            }
            if (edits.isEmpty()) return current
            when (val result = store.commit(LauncherTransaction(current.revision, edits))) {
                is CommitResult.Committed -> return result.state
                is CommitResult.Rejected -> {
                    if (result.reason.code !=
                        org.quicklauncher.host.data.store.StoreRejectionCode.STALE_REVISION
                    ) {
                        error(
                            "Safe layout provisioning failed: ${result.reason.code} " +
                                result.reason.message,
                        )
                    }
                }
            }
        }
        error("Safe layout provisioning could not win a revision race")
    }

    private fun validateExistingSafeLayouts(snapshot: LauncherSnapshot) {
        snapshot.destinations.forEach { destination ->
            val retained = snapshot.destinationLayouts.filter { layout ->
                layout.destinationId == destination.id &&
                    layout.layoutContributionId == SafeLayoutIds.CONTRIBUTION
            }
            if (retained.isEmpty()) return@forEach
            check(retained.size == 1) {
                "Destination '${destination.id}' has multiple reserved safe-layout roots"
            }
            val expectedInstance = SafeLayoutIds.instanceFor(destination.id)
            val expectedConfiguration = SafeLayoutIds.configurationFor(destination.id)
            val layout = retained.single()
            check(layout.layoutInstanceId == expectedInstance) {
                "Destination '${destination.id}' has a non-host safe-layout instance identity"
            }
            val instance = snapshot.moduleInstances.singleOrNull { it.id == expectedInstance }
            check(
                instance != null &&
                    instance.contributionId == SafeLayoutIds.CONTRIBUTION &&
                    instance.configurationDocumentId == expectedConfiguration
            ) {
                "Destination '${destination.id}' has an invalid safe-layout instance record"
            }
            val configuration = snapshot.configurationDocuments.singleOrNull {
                it.id == expectedConfiguration
            }
            check(configuration?.document?.configType == SafeLayoutIds.CONFIG_TYPE) {
                "Destination '${destination.id}' has an invalid safe-layout configuration type"
            }
        }
    }

    private fun startInstall(): DestinationInstall {
        val records = SafeLayoutIds.recordsFor(SafeLayoutIds.START_DESTINATION, selected = true)
        return DestinationInstall(
            destination = DestinationRecord(
                id = SafeLayoutIds.START_DESTINATION,
                name = "Start",
                coordinate = DestinationCoordinate(0, 0),
            ),
            layout = records.layout,
            layoutInstance = records.instance,
            configuration = records.configuration,
        )
    }

    private companion object {
        const val MAXIMUM_COMMIT_ATTEMPTS = 3
    }
}
