package org.quicklauncher.host.backup.payload

import java.util.Base64
import java.util.Collections
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class SectionPayloadKind {
    THEME_PROFILES,
    WEB_ADAPTERS,
    ASSET_MANIFEST,
}

class SectionPayloadEntry(
    val name: String,
    content: ByteArray,
) {
    private val content = content.copyOf()
    fun contentCopy(): ByteArray = content.copyOf()
    internal fun contentUnsafe(): ByteArray = content
}

class VersionedSectionPayload(
    val kind: SectionPayloadKind,
    entries: Collection<SectionPayloadEntry>,
) {
    val entries: List<SectionPayloadEntry> = Collections.unmodifiableList(entries.toList())
}

sealed interface SectionPayloadResult<out T> {
    data class Decoded<T>(val value: T) : SectionPayloadResult<T>
    data class Rejected(val problem: LauncherPayloadProblem) : SectionPayloadResult<Nothing>
}

/** Small typed envelope used by optional theme, web-adapter, and asset-manifest sections. */
object VersionedSectionPayloadCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_ENTRIES = 512
    const val MAX_ENTRY_BYTES = 16 * 1024 * 1024

    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    private val namePattern = Regex("[a-z0-9][a-z0-9._-]{0,127}")

    fun encode(payload: VersionedSectionPayload): SectionPayloadResult<ByteArray> = try {
        if (payload.entries.size > MAX_ENTRIES || payload.entries.map { it.name }.toSet().size != payload.entries.size ||
            payload.entries.any { !namePattern.matches(it.name) || it.contentUnsafe().size > MAX_ENTRY_BYTES }
        ) {
            SectionPayloadResult.Rejected(LauncherPayloadProblem.INVALID_STATE)
        } else {
            val wire = SectionWire(
                kind = payload.kind.name,
                entries = payload.entries.sortedBy { it.name }.map { entry ->
                    EntryWire(entry.name, Base64.getEncoder().encodeToString(entry.contentUnsafe()))
                },
            )
            val bytes = json.encodeToString(wire).toByteArray(Charsets.UTF_8)
            if (bytes.size > LauncherSnapshotPayloadCodec.MAX_PAYLOAD_BYTES) {
                SectionPayloadResult.Rejected(LauncherPayloadProblem.OVERSIZED)
            } else {
                SectionPayloadResult.Decoded(bytes)
            }
        }
    } catch (_: IllegalArgumentException) {
        SectionPayloadResult.Rejected(LauncherPayloadProblem.INVALID_STATE)
    }

    fun decode(bytes: ByteArray, expectedKind: SectionPayloadKind): SectionPayloadResult<VersionedSectionPayload> {
        if (bytes.size > LauncherSnapshotPayloadCodec.MAX_PAYLOAD_BYTES) {
            return SectionPayloadResult.Rejected(LauncherPayloadProblem.OVERSIZED)
        }
        return try {
            val wire = json.decodeFromString<SectionWire>(bytes.toString(Charsets.UTF_8))
            if (wire.schemaVersion != SCHEMA_VERSION) {
                return SectionPayloadResult.Rejected(LauncherPayloadProblem.UNSUPPORTED_VERSION)
            }
            if (wire.kind != expectedKind.name || wire.entries.size > MAX_ENTRIES ||
                wire.entries.map { it.name }.toSet().size != wire.entries.size
            ) {
                return SectionPayloadResult.Rejected(LauncherPayloadProblem.INVALID_STATE)
            }
            val entries = wire.entries.map { entry ->
                require(namePattern.matches(entry.name))
                val content = Base64.getDecoder().decode(entry.base64)
                require(content.size <= MAX_ENTRY_BYTES)
                SectionPayloadEntry(entry.name, content)
            }
            SectionPayloadResult.Decoded(VersionedSectionPayload(expectedKind, entries))
        } catch (_: SerializationException) {
            SectionPayloadResult.Rejected(LauncherPayloadProblem.MALFORMED)
        } catch (_: IllegalArgumentException) {
            SectionPayloadResult.Rejected(LauncherPayloadProblem.INVALID_STATE)
        }
    }
}

@Serializable
private data class SectionWire(
    val schemaVersion: Int = VersionedSectionPayloadCodec.SCHEMA_VERSION,
    val kind: String,
    val entries: List<EntryWire>,
)

@Serializable
private data class EntryWire(val name: String, val base64: String)
