package org.quicklauncher.app

import android.content.Context
import java.util.Base64
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.quicklauncher.host.backup.archive.PortableBackupSection
import org.quicklauncher.host.backup.archive.PortableBackupSectionType
import org.quicklauncher.host.backup.library.AuxiliaryStage
import org.quicklauncher.host.backup.library.BackupLauncherExtensionSource
import org.quicklauncher.host.backup.library.BackupSectionSource
import org.quicklauncher.host.backup.library.RestoreAuxiliaryPort
import org.quicklauncher.host.backup.library.RestoreAuxiliaryStageRequest
import org.quicklauncher.host.backup.library.RestoreAuxiliaryValidationRequest
import org.quicklauncher.host.backup.payload.SectionPayloadKind
import org.quicklauncher.host.backup.payload.SectionPayloadResult
import org.quicklauncher.host.backup.payload.VersionedSectionPayloadCodec
import org.quicklauncher.host.data.store.LauncherSnapshot

internal class CompositeBackupSectionSource(
    private val sources: List<BackupSectionSource>,
) : BackupSectionSource {
    override suspend fun export(snapshot: LauncherSnapshot): List<PortableBackupSection> {
        val sections = sources.flatMap { it.export(snapshot) }
        check(sections.map { it.type to it.name }.toSet().size == sections.size) {
            "Backup section sources emitted duplicate identities"
        }
        return sections
    }
}

internal class CompositeRestoreAuxiliaryPort(
    private val ports: List<RestoreAuxiliaryPort>,
) : RestoreAuxiliaryPort {
    override fun validate(request: RestoreAuxiliaryValidationRequest): Boolean =
        ports.all { it.validate(request) }

    override suspend fun stage(request: RestoreAuxiliaryStageRequest): AuxiliaryStage =
        acquireStages { it.stage(request) }

    private suspend fun acquireStages(acquire: suspend (RestoreAuxiliaryPort) -> AuxiliaryStage): AuxiliaryStage {
        val stages = mutableListOf<AuxiliaryStage>()
        try {
            ports.forEach { stages += acquire(it) }
        } catch (failure: Throwable) {
            discardStages(stages)
            throw failure
        }
        return compositeStage(stages)
    }

    private fun compositeStage(stages: List<AuxiliaryStage>): AuxiliaryStage {
        return object : AuxiliaryStage {
            override suspend fun commit() {
                try {
                    stages.forEach { it.commit() }
                } catch (failure: Throwable) {
                    discardStages(stages)
                    throw failure
                }
            }

            override suspend fun discard() = discardStages(stages)
        }
    }

    private suspend fun discardStages(stages: List<AuxiliaryStage>) {
        withContext(NonCancellable) {
            stages.asReversed().forEach { runCatching { it.discard() } }
        }
    }
}

/** Preserves declarative web adapters and opaque launcher extensions without executing either. */
internal class PersistedWebAdapterBackupPort(context: Context) :
    BackupSectionSource,
    BackupLauncherExtensionSource,
    RestoreAuxiliaryPort {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override suspend fun export(snapshot: LauncherSnapshot): List<PortableBackupSection> {
        val encoded = preferences.getString(PAYLOAD, null) ?: return emptyList()
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { error("Stored web-adapter payload is corrupt") }
        check(
            VersionedSectionPayloadCodec.decode(bytes, SectionPayloadKind.WEB_ADAPTERS) is
                SectionPayloadResult.Decoded,
        ) { "Stored web-adapter payload is invalid" }
        return listOf(
            PortableBackupSection(
                PortableBackupSectionType.WEB_ADAPTERS,
                SECTION_NAME,
                bytes,
            ),
        )
    }

    override suspend fun export(): Map<String, ByteArray> = preferences.all.entries
        .asSequence()
        .filter { it.key.startsWith(EXTENSION_PREFIX) }
        .associate { entry ->
            val encoded = entry.value as? String ?: error("Stored launcher extension is corrupt")
            entry.key.removePrefix(EXTENSION_PREFIX) to runCatching {
                Base64.getDecoder().decode(encoded)
            }.getOrElse { error("Stored launcher extension is corrupt") }
        }

    override fun validate(request: RestoreAuxiliaryValidationRequest): Boolean = true

    override suspend fun stage(request: RestoreAuxiliaryStageRequest): AuxiliaryStage {
        val sections = request.sections
        val selection = request.selection
        if (!selection.webAdapters && !selection.launcherMap) return NoOpAuxiliaryStage
        val launcherExtensions = request.launcherExtensionsCopy()
        val payload = if (selection.webAdapters) {
            sections.singleOrNull { it.type == PortableBackupSectionType.WEB_ADAPTERS }
                ?.also { check(it.name == SECTION_NAME) }
                ?.contentCopy()
        } else {
            null
        }
        if (selection.webAdapters && payload != null) {
            check(
                VersionedSectionPayloadCodec.decode(payload, SectionPayloadKind.WEB_ADAPTERS) is
                    SectionPayloadResult.Decoded,
            ) { "Imported web-adapter payload is invalid" }
        }
        val previous = preferences.all.entries
            .filter { it.key == PAYLOAD || it.key.startsWith(EXTENSION_PREFIX) }
            .associate { it.key to (it.value as? String ?: error("Stored backup metadata is corrupt")) }
        val nextWeb = if (selection.webAdapters) {
            payload?.let { Base64.getEncoder().encodeToString(it) }
        } else {
            previous[PAYLOAD]
        }
        val nextExtensions = if (selection.launcherMap) {
            launcherExtensions.mapKeys { EXTENSION_PREFIX + it.key }
                .mapValues { Base64.getEncoder().encodeToString(it.value) }
        } else {
            previous.filterKeys { it.startsWith(EXTENSION_PREFIX) }
        }
        payload?.fill(0)
        return object : AuxiliaryStage {
            override suspend fun commit() {
                val editor = preferences.edit()
                preferences.all.keys.filter { it.startsWith(EXTENSION_PREFIX) }.forEach(editor::remove)
                if (nextWeb == null) editor.remove(PAYLOAD) else editor.putString(PAYLOAD, nextWeb)
                nextExtensions.forEach { (key, value) -> editor.putString(key, value) }
                check(editor.commit()) { "Portable backup metadata could not be persisted" }
            }

            override suspend fun discard() {
                val editor = preferences.edit()
                editor.remove(PAYLOAD)
                preferences.all.keys.filter { it.startsWith(EXTENSION_PREFIX) }.forEach(editor::remove)
                previous.forEach { (key, value) -> editor.putString(key, value) }
                check(editor.commit()) { "Portable backup metadata rollback failed" }
            }
        }
    }

    private companion object {
        const val PREFERENCES = "portable-backup-web-adapters"
        const val PAYLOAD = "payload-v1"
        const val SECTION_NAME = "adapters"
        const val EXTENSION_PREFIX = "launcher-extension."
    }
}

private data object NoOpAuxiliaryStage : AuxiliaryStage {
    override suspend fun commit() = Unit
    override suspend fun discard() = Unit
}
