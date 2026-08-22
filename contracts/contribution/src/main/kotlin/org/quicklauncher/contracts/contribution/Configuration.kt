package org.quicklauncher.contracts.contribution

import java.util.concurrent.CancellationException
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.SchemaVersion

@JvmInline
value class EncodedConfiguration private constructor(val value: String) {
    companion object {
        /** Parses serialized configuration without normalizing it. */
        fun of(value: String): EncodedConfiguration = EncodedConfiguration(value)
    }
}

data class ConfigurationDocument(
    val configType: ConfigTypeId,
    val schemaVersion: SchemaVersion,
    val encoded: EncodedConfiguration,
)

sealed interface CodecResult<out T> {
    data class Decoded<T>(val value: T) : CodecResult<T>

    data class Failed(val reason: String) : CodecResult<Nothing> {
        init {
            require(reason.isNotBlank()) { "Codec failure reason must not be blank" }
        }
    }
}

interface ConfigurationCodec<T> {
    val configType: ConfigTypeId
    val currentSchemaVersion: SchemaVersion
    val default: T

    fun encode(value: T): EncodedConfiguration

    fun decode(encoded: EncodedConfiguration): CodecResult<T>
}

sealed interface MigrationResult {
    data class Migrated(val encoded: EncodedConfiguration) : MigrationResult

    data class Failed(val reason: String) : MigrationResult {
        init {
            require(reason.isNotBlank()) { "Migration failure reason must not be blank" }
        }
    }
}

interface ConfigurationMigration {
    val configType: ConfigTypeId
    val fromVersion: SchemaVersion
    val toVersion: SchemaVersion

    /** Must return the same output for the same input and must not mutate external state. */
    fun migrate(encoded: EncodedConfiguration): MigrationResult
}

data class ConfigurationError(
    val code: String,
    val message: String,
)

sealed interface ConfigurationLoadResult<out T> {
    data class Loaded<T>(
        val value: T,
        val document: ConfigurationDocument,
    ) : ConfigurationLoadResult<T>

    data class Failed(
        val original: ConfigurationDocument,
        val error: ConfigurationError,
    ) : ConfigurationLoadResult<Nothing>
}

/**
 * Owns the full configuration load path: version checks, sequential migrations, typed decode,
 * and preservation of the original document on every failure.
 */
object ConfigurationPipeline {
    fun <T> defaultDocument(codec: ConfigurationCodec<T>): ConfigurationDocument =
        ConfigurationDocument(
            configType = codec.configType,
            schemaVersion = codec.currentSchemaVersion,
            encoded = codec.encode(codec.default),
        )

    fun <T> encode(value: T, codec: ConfigurationCodec<T>): ConfigurationDocument =
        ConfigurationDocument(
            configType = codec.configType,
            schemaVersion = codec.currentSchemaVersion,
            encoded = codec.encode(value),
        )

    fun <T> load(
        document: ConfigurationDocument,
        codec: ConfigurationCodec<T>,
        migrations: Collection<ConfigurationMigration>,
    ): ConfigurationLoadResult<T> {
        if (document.configType != codec.configType) {
            return failed(
                document,
                "configuration.type-mismatch",
                "Document type '${document.configType}' does not match codec type '${codec.configType}'",
            )
        }
        if (document.schemaVersion.value > codec.currentSchemaVersion.value) {
            return failed(
                document,
                "configuration.future-schema",
                "Document schema ${document.schemaVersion} is newer than supported schema " +
                    "${codec.currentSchemaVersion}",
            )
        }

        var version = document.schemaVersion
        var encoded = document.encoded
        while (version.value < codec.currentSchemaVersion.value) {
            val candidates = migrations.filter {
                it.configType == codec.configType && it.fromVersion == version
            }
            if (candidates.size != 1) {
                val detail = if (candidates.isEmpty()) "No" else "More than one"
                return failed(
                    document,
                    if (candidates.isEmpty()) {
                        "configuration.migration-missing"
                    } else {
                        "configuration.migration-ambiguous"
                    },
                    "$detail migration is declared from schema $version for '${codec.configType}'",
                )
            }
            val migration = candidates.single()
            val expectedTo = version.value + 1
            if (migration.toVersion.value != expectedTo) {
                return failed(
                    document,
                    "configuration.migration-non-sequential",
                    "Migration from $version must advance to $expectedTo, not ${migration.toVersion}",
                )
            }

            val result = try {
                migration.migrate(encoded)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return failed(
                    document,
                    "configuration.migration-threw",
                    "Migration from $version to ${migration.toVersion} threw " +
                        "${failure::class.simpleName ?: "an exception"}",
                )
            }
            when (result) {
                is MigrationResult.Migrated -> {
                    encoded = result.encoded
                    version = migration.toVersion
                }
                is MigrationResult.Failed -> return failed(
                    document,
                    "configuration.migration-failed",
                    "Migration from $version to ${migration.toVersion} failed: ${result.reason}",
                )
            }
        }

        val decoded = try {
            codec.decode(encoded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return failed(
                document,
                "configuration.decode-threw",
                "Codec for '${codec.configType}' threw ${failure::class.simpleName ?: "an exception"}",
            )
        }

        return when (decoded) {
            is CodecResult.Decoded -> ConfigurationLoadResult.Loaded(
                value = decoded.value,
                document = ConfigurationDocument(codec.configType, version, encoded),
            )
            is CodecResult.Failed -> failed(
                document,
                "configuration.decode-failed",
                "Codec for '${codec.configType}' failed: ${decoded.reason}",
            )
        }
    }

    private fun failed(
        original: ConfigurationDocument,
        code: String,
        message: String,
    ): ConfigurationLoadResult.Failed = ConfigurationLoadResult.Failed(
        original = original,
        error = ConfigurationError(code = code, message = message),
    )
}
