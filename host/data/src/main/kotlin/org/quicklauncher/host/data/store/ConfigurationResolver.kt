package org.quicklauncher.host.data.store

import java.util.Collections
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.ConfigurationError
import org.quicklauncher.contracts.contribution.ConfigurationLoadResult
import org.quicklauncher.contracts.contribution.ConfigurationMigration
import org.quicklauncher.contracts.contribution.ConfigurationPipeline
import org.quicklauncher.contracts.contribution.ContributionRegistry
import org.quicklauncher.contracts.contribution.ContributionTypes
import org.quicklauncher.contracts.contribution.RegisteredContribution
import org.quicklauncher.contracts.contribution.find
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ContributionTypeId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.SchemaVersion

fun interface ConfigurationResolver {
    fun resolve(contributionId: ContributionId): ConfigurationLoader?
}

interface ConfigurationLoader {
    val contributionTypeId: ContributionTypeId
    val configType: ConfigTypeId
    val currentSchemaVersion: SchemaVersion

    fun load(document: ConfigurationDocument): ConfigurationLoadResult<*>
}

fun <T> configurationLoader(
    codec: ConfigurationCodec<T>,
    contributionTypeId: ContributionTypeId = ContributionTypes.LAYOUT,
    migrations: Collection<ConfigurationMigration> = codec.migrations,
): ConfigurationLoader {
    val immutableMigrations = Collections.unmodifiableList(ArrayList(migrations))
    return object : ConfigurationLoader {
        override val contributionTypeId: ContributionTypeId = contributionTypeId
        override val configType: ConfigTypeId = codec.configType
        override val currentSchemaVersion: SchemaVersion = codec.currentSchemaVersion

        override fun load(document: ConfigurationDocument): ConfigurationLoadResult<*> =
            ConfigurationPipeline.load(document, codec, immutableMigrations)
    }
}

fun registryConfigurationResolver(registry: ContributionRegistry): ConfigurationResolver =
    ConfigurationResolver { contributionId ->
        registry.find(contributionId)?.let { entry -> configurationLoader(entry) }
    }

private fun <T : Any> configurationLoader(entry: RegisteredContribution<T>): ConfigurationLoader =
    configurationLoader(
        codec = entry.codec,
        contributionTypeId = entry.descriptor.metadata.typeId,
    )

class ConfigurationFailure(
    val configurationDocumentId: ConfigurationDocumentId,
    affectedInstances: Collection<ModuleInstanceId>,
    val error: ConfigurationError,
) {
    val affectedInstances: List<ModuleInstanceId> =
        Collections.unmodifiableList(ArrayList(affectedInstances))
}

class ConfigurationReconciliation(
    val state: LauncherSnapshot,
    migrated: Collection<ConfigurationDocumentId>,
    unknown: Collection<ConfigurationDocumentId>,
    failures: Collection<ConfigurationFailure>,
) {
    val migrated: List<ConfigurationDocumentId> = Collections.unmodifiableList(ArrayList(migrated))
    val unknown: List<ConfigurationDocumentId> = Collections.unmodifiableList(ArrayList(unknown))
    val failures: List<ConfigurationFailure> = Collections.unmodifiableList(ArrayList(failures))
}

internal val EmptyConfigurationResolver = ConfigurationResolver { null }
