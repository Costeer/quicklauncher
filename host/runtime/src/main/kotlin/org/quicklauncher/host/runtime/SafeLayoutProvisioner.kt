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

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(10)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

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
                        val instanceId = SafeLayoutIds.instanceFor(destination.id)
                        val configurationId = SafeLayoutIds.configurationFor(destination.id)
                        LauncherEdit.RetainLayout(
                            layout = DestinationLayoutRecord(
                                destinationId = destination.id,
                                layoutContributionId = SafeLayoutIds.CONTRIBUTION,
                                layoutInstanceId = instanceId,
                                selected = false,
                            ),
                            layoutInstance = ModuleInstanceRecord(
                                id = instanceId,
                                contributionId = SafeLayoutIds.CONTRIBUTION,
                                configurationDocumentId = configurationId,
                            ),
                            configuration = safeConfiguration(configurationId),
                        )
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

    private fun startInstall(): DestinationInstall = DestinationInstall(
            destination = DestinationRecord(
                id = SafeLayoutIds.START_DESTINATION,
                name = "Start",
                coordinate = DestinationCoordinate(0, 0),
            ),
            layout = DestinationLayoutRecord(
                destinationId = SafeLayoutIds.START_DESTINATION,
                layoutContributionId = SafeLayoutIds.CONTRIBUTION,
                layoutInstanceId = SafeLayoutIds.START_INSTANCE,
                selected = true,
            ),
            layoutInstance = ModuleInstanceRecord(
                id = SafeLayoutIds.START_INSTANCE,
                contributionId = SafeLayoutIds.CONTRIBUTION,
                configurationDocumentId = SafeLayoutIds.START_CONFIGURATION,
            ),
            configuration = safeConfiguration(SafeLayoutIds.START_CONFIGURATION),
        )

    private fun safeConfiguration(id: ConfigurationDocumentId): StoredConfigurationDocument =
        StoredConfigurationDocument(
            id = id,
            document = ConfigurationDocument(
                configType = SafeLayoutIds.CONFIG_TYPE,
                schemaVersion = SchemaVersion.of(1),
                encoded = EncodedConfiguration.of("{}"),
            ),
        )

    private companion object {
        const val MAXIMUM_COMMIT_ATTEMPTS = 3
    }
}
