package org.quicklauncher.testing.samples

import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.ConfigurationMigration
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.contribution.MigrationResult
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.testing.contracts.ConfigurationContractCases
import org.quicklauncher.registry.annotations.ConfigurationCodecSpec

data class SampleConfiguration(
    val enabled: Boolean,
    val label: String,
)

abstract class SampleConfigurationCodec(
    final override val configType: ConfigTypeId,
) : ConfigurationCodec<SampleConfiguration> {
    final override val currentSchemaVersion: SchemaVersion = SchemaVersion.of(3)
    final override val default: SampleConfiguration = SampleConfiguration(true, "sample")
    final override val migrations: List<ConfigurationMigration> =
        sampleMigrations(configType, failAtSecondStep = false)

    final override fun encode(value: SampleConfiguration): EncodedConfiguration =
        EncodedConfiguration.of("enabled=${value.enabled};label=${value.label}")

    final override fun decode(encoded: EncodedConfiguration): CodecResult<SampleConfiguration> {
        val values = encoded.value.split(';').mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size == 2) pieces[0] to pieces[1] else null
        }.toMap()
        val enabled = values["enabled"]?.toBooleanStrictOrNull()
            ?: return CodecResult.Failed("enabled must be true or false")
        val label = values["label"]?.takeIf(String::isNotBlank)
            ?: return CodecResult.Failed("label must not be blank")
        return CodecResult.Decoded(SampleConfiguration(enabled, label))
    }
}

@ConfigurationCodecSpec(configTypeId = "org.quicklauncher.samples/layout-config")
object LayoutConfigurationCodec : SampleConfigurationCodec(
    ConfigTypeId.parse("org.quicklauncher.samples/layout-config"),
)

@ConfigurationCodecSpec(configTypeId = "org.quicklauncher.samples/block-config")
object BlockConfigurationCodec : SampleConfigurationCodec(
    ConfigTypeId.parse("org.quicklauncher.samples/block-config"),
)

@ConfigurationCodecSpec(configTypeId = "org.quicklauncher.samples/search-config")
object SearchConfigurationCodec : SampleConfigurationCodec(
    ConfigTypeId.parse("org.quicklauncher.samples/search-config"),
)

@ConfigurationCodecSpec(configTypeId = "org.quicklauncher.samples/command-config")
object CommandConfigurationCodec : SampleConfigurationCodec(
    ConfigTypeId.parse("org.quicklauncher.samples/command-config"),
)

@ConfigurationCodecSpec(configTypeId = "org.quicklauncher.samples/template-config")
object TemplateConfigurationCodec : SampleConfigurationCodec(
    ConfigTypeId.parse("org.quicklauncher.samples/template-config"),
)

fun sampleConfigurationCases(
    codec: ConfigurationCodec<SampleConfiguration>,
): ConfigurationContractCases<SampleConfiguration> {
    val migrations = codec.migrations
    val failingMigrations = sampleMigrations(codec.configType, failAtSecondStep = true)
    return ConfigurationContractCases(
        roundTripValue = SampleConfiguration(false, "round-trip"),
        migrationDocument = ConfigurationDocument(
            codec.configType,
            SchemaVersion.of(1),
            EncodedConfiguration.of("false"),
        ),
        migrations = migrations,
        expectedMigratedValue = SampleConfiguration(false, "migrated"),
        failingDocument = ConfigurationDocument(
            codec.configType,
            SchemaVersion.of(1),
            EncodedConfiguration.of("failure-original"),
        ),
        failingMigrations = failingMigrations,
        decodeFailureDocument = ConfigurationDocument(
            codec.configType,
            codec.currentSchemaVersion,
            EncodedConfiguration.of("not-a-sample-configuration"),
        ),
    )
}

private fun sampleMigrations(
    type: ConfigTypeId,
    failAtSecondStep: Boolean,
): List<ConfigurationMigration> = listOf(
    object : ConfigurationMigration {
        override val configType: ConfigTypeId = type
        override val fromVersion: SchemaVersion = SchemaVersion.of(1)
        override val toVersion: SchemaVersion = SchemaVersion.of(2)

        override fun migrate(encoded: EncodedConfiguration): MigrationResult =
            MigrationResult.Migrated(
                EncodedConfiguration.of(
                    if (encoded.value == "failure-original") "enabled=false;partial=true" else "enabled=${encoded.value}",
                ),
            )
    },
    object : ConfigurationMigration {
        override val configType: ConfigTypeId = type
        override val fromVersion: SchemaVersion = SchemaVersion.of(2)
        override val toVersion: SchemaVersion = SchemaVersion.of(3)

        override fun migrate(encoded: EncodedConfiguration): MigrationResult =
            if (failAtSecondStep && "partial=true" in encoded.value) {
                MigrationResult.Failed("deliberate sample migration failure")
            } else {
                MigrationResult.Migrated(EncodedConfiguration.of("${encoded.value};label=migrated"))
            }
    },
)
