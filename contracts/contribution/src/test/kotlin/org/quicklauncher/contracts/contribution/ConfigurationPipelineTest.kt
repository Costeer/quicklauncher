package org.quicklauncher.contracts.contribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.SchemaVersion

class ConfigurationPipelineTest {
    private val configType = ConfigTypeId.parse("org.quicklauncher.samples/counter")

    @Test
    fun `defaults and codec round trips use the codec schema independently of contract versions`() {
        val codec = CounterCodec(currentVersion = 3)

        val defaultDocument = ConfigurationPipeline.defaultDocument(codec)
        val decoded = ConfigurationPipeline.load(defaultDocument, codec, migrations = emptyList())

        assertEquals(SchemaVersion.of(3), defaultDocument.schemaVersion)
        assertEquals(EncodedConfiguration.of("7"), defaultDocument.encoded)
        assertEquals(CounterConfig(7), (decoded as ConfigurationLoadResult.Loaded).value)
    }

    @Test
    fun `migrations run sequentially before typed decode`() {
        val original = ConfigurationDocument(
            configType = configType,
            schemaVersion = SchemaVersion.of(1),
            encoded = EncodedConfiguration.of("1"),
        )
        val migrations = listOf(
            migration(from = 1, to = 2) { encoded ->
                MigrationResult.Migrated(EncodedConfiguration.of((encoded.value.toInt() + 1).toString()))
            },
            migration(from = 2, to = 3) { encoded ->
                MigrationResult.Migrated(EncodedConfiguration.of((encoded.value.toInt() * 10).toString()))
            },
        )

        val result = ConfigurationPipeline.load(original, CounterCodec(3), migrations)
            as ConfigurationLoadResult.Loaded

        assertEquals(CounterConfig(20), result.value)
        assertEquals(SchemaVersion.of(3), result.document.schemaVersion)
        assertEquals(EncodedConfiguration.of("20"), result.document.encoded)
    }

    @Test
    fun `migration failure returns the byte-for-byte original document`() {
        val original = ConfigurationDocument(
            configType = configType,
            schemaVersion = SchemaVersion.of(1),
            encoded = EncodedConfiguration.of("{ deliberately-not-normalized: true }"),
        )
        val migrations = listOf(
            migration(from = 1, to = 2) {
                MigrationResult.Migrated(EncodedConfiguration.of("partially migrated"))
            },
            migration(from = 2, to = 3) {
                MigrationResult.Failed("sample migration refused the value")
            },
        )

        val failure = ConfigurationPipeline.load(original, CounterCodec(3), migrations)
            as ConfigurationLoadResult.Failed

        assertSame(original, failure.original)
        assertEquals("configuration.migration-failed", failure.error.code)
        assertTrue(failure.error.message.contains("2 to 3"))
        assertTrue(failure.error.message.contains("sample migration refused"))
    }

    @Test
    fun `missing sequential migration and future schema versions fail without decoding`() {
        val original = ConfigurationDocument(
            configType = configType,
            schemaVersion = SchemaVersion.of(2),
            encoded = EncodedConfiguration.of("9"),
        )
        val missing = ConfigurationPipeline.load(original, CounterCodec(4), migrations = emptyList())
            as ConfigurationLoadResult.Failed
        val future = ConfigurationPipeline.load(
            original.copy(schemaVersion = SchemaVersion.of(5)),
            CounterCodec(4),
            migrations = emptyList(),
        ) as ConfigurationLoadResult.Failed

        assertEquals("configuration.migration-missing", missing.error.code)
        assertEquals("configuration.future-schema", future.error.code)
    }

    @Test
    fun `codec failure preserves the original document`() {
        val original = ConfigurationDocument(
            configType = configType,
            schemaVersion = SchemaVersion.of(3),
            encoded = EncodedConfiguration.of("not-an-integer"),
        )

        val failure = ConfigurationPipeline.load(original, CounterCodec(3), emptyList())
            as ConfigurationLoadResult.Failed

        assertSame(original, failure.original)
        assertEquals("configuration.decode-failed", failure.error.code)
        assertTrue(failure.error.message.contains("integer"))
    }

    private fun migration(
        from: Int,
        to: Int,
        transform: (EncodedConfiguration) -> MigrationResult,
    ): ConfigurationMigration = object : ConfigurationMigration {
        override val configType: ConfigTypeId = this@ConfigurationPipelineTest.configType
        override val fromVersion: SchemaVersion = SchemaVersion.of(from)
        override val toVersion: SchemaVersion = SchemaVersion.of(to)

        override fun migrate(encoded: EncodedConfiguration): MigrationResult = transform(encoded)
    }

    private data class CounterConfig(val count: Int)

    private inner class CounterCodec(currentVersion: Int) : ConfigurationCodec<CounterConfig> {
        override val configType: ConfigTypeId = this@ConfigurationPipelineTest.configType
        override val currentSchemaVersion: SchemaVersion = SchemaVersion.of(currentVersion)
        override val default: CounterConfig = CounterConfig(7)

        override fun encode(value: CounterConfig): EncodedConfiguration =
            EncodedConfiguration.of(value.count.toString())

        override fun decode(encoded: EncodedConfiguration): CodecResult<CounterConfig> =
            encoded.value.toIntOrNull()
                ?.let { CodecResult.Decoded(CounterConfig(it)) }
                ?: CodecResult.Failed("value must be an integer")
    }
}
