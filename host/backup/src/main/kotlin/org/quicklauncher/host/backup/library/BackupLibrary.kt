package org.quicklauncher.host.backup.library

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.backup.archive.PortableBackupArchive
import org.quicklauncher.host.backup.archive.PortableBackupArchiveCodec
import org.quicklauncher.host.backup.archive.PortableBackupDecodeResult
import org.quicklauncher.host.backup.archive.PortableBackupEncodeResult
import org.quicklauncher.host.backup.archive.PortableBackupKind
import org.quicklauncher.host.backup.archive.PortableBackupSection
import org.quicklauncher.host.backup.archive.PortableBackupSectionType
import org.quicklauncher.host.backup.crypto.PassphraseArchiveProtection
import org.quicklauncher.host.backup.crypto.ProtectResult
import org.quicklauncher.host.backup.crypto.UnprotectResult
import org.quicklauncher.host.backup.payload.LauncherPayloadDecodeResult
import org.quicklauncher.host.backup.payload.LauncherPayloadEncodeResult
import org.quicklauncher.host.backup.payload.LauncherSnapshotPayloadCodec
import org.quicklauncher.host.backup.payload.SectionPayloadKind
import org.quicklauncher.host.backup.payload.SectionPayloadResult
import org.quicklauncher.host.backup.payload.VersionedSectionPayloadCodec
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceStatus
import org.quicklauncher.host.data.store.ModuleQuarantineOrigin

data class BackupDocument(
    val id: String,
    val displayName: String,
    val sizeBytes: Long?,
    val modifiedAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(sizeBytes == null || sizeBytes >= 0L)
        require(modifiedAtEpochMillis >= 0L)
    }
}

/** A user-authorized folder. Implementations must bound reads and complete writes before returning. */
interface BackupFolder {
    suspend fun list(): List<BackupDocument>
    suspend fun read(id: String, maxBytes: Int): ByteArray?
    suspend fun write(displayName: String, bytes: ByteArray): BackupDocument
    suspend fun delete(id: String): Boolean
}

interface BackupClock {
    fun nowEpochMillis(): Long
}

fun interface BackupSectionSource {
    suspend fun export(snapshot: LauncherSnapshot): List<PortableBackupSection>
}

object EmptyBackupSectionSource : BackupSectionSource {
    override suspend fun export(snapshot: LauncherSnapshot): List<PortableBackupSection> = emptyList()
}

fun interface BackupLauncherExtensionSource {
    /** Returns caller-owned copies of opaque launcher payload extensions. */
    suspend fun export(): Map<String, ByteArray>
}

object EmptyBackupLauncherExtensionSource : BackupLauncherExtensionSource {
    override suspend fun export(): Map<String, ByteArray> = emptyMap()
}

object SystemBackupClock : BackupClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

/** Allows imported private assets to be staged without exposing a filesystem to the coordinator. */
interface RestoreAuxiliaryPort {
    fun validate(snapshot: LauncherSnapshot, sections: List<PortableBackupSection>): Boolean = true
    fun validate(
        snapshot: LauncherSnapshot,
        sections: List<PortableBackupSection>,
        launcherExtensions: Map<String, ByteArray>,
    ): Boolean = validate(snapshot, sections)
    suspend fun stage(sections: List<PortableBackupSection>): AuxiliaryStage
    suspend fun stage(
        sections: List<PortableBackupSection>,
        selection: RestoreSelection,
    ): AuxiliaryStage = stage(sections)
    suspend fun stage(
        sections: List<PortableBackupSection>,
        selection: RestoreSelection,
        launcherExtensions: Map<String, ByteArray>,
    ): AuxiliaryStage = stage(sections, selection)
    suspend fun stage(
        snapshot: LauncherSnapshot,
        sections: List<PortableBackupSection>,
        selection: RestoreSelection,
        launcherExtensions: Map<String, ByteArray>,
    ): AuxiliaryStage = stage(sections, selection, launcherExtensions)
}

interface AuxiliaryStage {
    suspend fun commit()
    suspend fun discard()
}

object EmptyRestoreAuxiliaryPort : RestoreAuxiliaryPort {
    override suspend fun stage(sections: List<PortableBackupSection>): AuxiliaryStage = object : AuxiliaryStage {
        override suspend fun commit() = Unit
        override suspend fun discard() = Unit
    }
}

enum class BackupLibraryProblem {
    FOLDER_UNAVAILABLE,
    READ_FAILED,
    WRITE_FAILED,
    DELETE_FAILED,
    ARCHIVE_REJECTED,
    PASSPHRASE_REQUIRED,
    AUTHENTICATION_FAILED,
    PAYLOAD_REJECTED,
    SAFE_LAYOUT_MISSING,
    LIVE_STATE_CHANGED,
    STALE_PREVIEW,
    PRE_RESTORE_BACKUP_FAILED,
    AUXILIARY_IMPORT_FAILED,
    STORE_REJECTED,
}

sealed interface BackupOperationResult<out T> {
    data class Completed<T>(val value: T) : BackupOperationResult<T>
    data class Failed(val problem: BackupLibraryProblem) : BackupOperationResult<Nothing>
}

data class BackupInventory(
    val manual: List<BackupDocument>,
    val automatic: List<BackupDocument>,
    val preRestore: List<BackupDocument>,
)

data class RestoreReview(
    val archiveCreatedAtEpochMillis: Long,
    val destinationCount: Int,
    val moduleCount: Int,
    val widgetRebindCount: Int,
    val quarantinedInstanceCount: Int,
    val profilesToReview: Int,
    val profileSerials: List<ProfileSerial>,
    val permissionsRequireReauthorization: Boolean,
)

class StagedRestore internal constructor(
    internal val sourceId: String,
    internal val sourceDigest: ByteArray,
    internal val importedSnapshot: LauncherSnapshot,
    internal val archive: PortableBackupArchive,
    internal val passphraseRequired: Boolean,
    launcherExtensions: Map<String, ByteArray>,
    val token: String,
    val review: RestoreReview,
) {
    private val launcherExtensions = launcherExtensions.mapValues { it.value.copyOf() }
    internal fun digestCopy(): ByteArray = sourceDigest.copyOf()
    internal fun launcherExtensionsCopy(): Map<String, ByteArray> =
        launcherExtensions.mapValues { it.value.copyOf() }
}

data class RestoreSelection(
    val launcherMap: Boolean = true,
    val themes: Boolean = true,
    val webAdapters: Boolean = true,
    val assets: Boolean = true,
) {
    init {
        require(launcherMap || themes || webAdapters || assets) { "At least one restore category is required" }
        require(!themes || assets) { "Theme profiles require their imported assets" }
    }
}

data class RestoreReceipt(
    val preRestoreBackup: BackupDocument,
    val revision: Long,
    val widgetRebindCount: Int,
    val quarantinedInstanceCount: Int,
)

class PortableBackupLibrary(
    private val folder: BackupFolder,
    private val store: LauncherStore,
    private val knownContribution: (ContributionId) -> Boolean,
    private val sectionSource: BackupSectionSource = EmptyBackupSectionSource,
    private val launcherExtensionSource: BackupLauncherExtensionSource = EmptyBackupLauncherExtensionSource,
    private val auxiliaryPort: RestoreAuxiliaryPort = EmptyRestoreAuxiliaryPort,
    private val clock: BackupClock = SystemBackupClock,
    private val protection: PassphraseArchiveProtection = PassphraseArchiveProtection(),
) {
    suspend fun inventory(): BackupOperationResult<BackupInventory> {
        val documents = try {
            folder.list()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return BackupOperationResult.Failed(BackupLibraryProblem.FOLDER_UNAVAILABLE)
        }
        val archives = ArrayList<IndexedArchive>()
        for (document in documents) {
            coroutineContext.ensureActive()
            if (document.sizeBytes != null && document.sizeBytes !in 1..MAX_STORED_BYTES) continue
            val bytes = try {
                folder.read(document.id, MAX_STORED_BYTES)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return BackupOperationResult.Failed(BackupLibraryProblem.READ_FAILED)
            } ?: continue
            if (PassphraseArchiveProtection.isSupportedEnvelope(bytes)) {
                archives += IndexedArchive(
                    document = document,
                    kind = PortableBackupKind.MANUAL,
                    createdAtEpochMillis = document.modifiedAtEpochMillis,
                )
                bytes.fill(0)
                continue
            }
            val decoded = try {
                decodeArchive(bytes, null)
            } finally {
                bytes.fill(0)
            }
            if (decoded is BackupOperationResult.Completed) {
                archives += IndexedArchive(
                    document = document,
                    kind = decoded.value.kind,
                    createdAtEpochMillis = decoded.value.createdAtEpochMillis,
                )
            }
        }
        return BackupOperationResult.Completed(
            BackupInventory(
                manual = archives.documentsOfKind(PortableBackupKind.MANUAL),
                automatic = archives.documentsOfKind(PortableBackupKind.AUTOMATIC),
                preRestore = archives.documentsOfKind(PortableBackupKind.PRE_RESTORE),
            ),
        )
    }

    suspend fun createManual(passphrase: CharArray? = null): BackupOperationResult<BackupDocument> =
        create(PortableBackupKind.MANUAL, passphrase)

    suspend fun createAutomatic(): BackupOperationResult<BackupDocument> {
        val created = create(PortableBackupKind.AUTOMATIC, null)
        if (created !is BackupOperationResult.Completed) return created
        val current = when (val inventory = inventory()) {
            is BackupOperationResult.Completed -> inventory.value
            is BackupOperationResult.Failed -> {
                if (!rollbackAutomatic(created.value)) {
                    return BackupOperationResult.Failed(BackupLibraryProblem.DELETE_FAILED)
                }
                return BackupOperationResult.Failed(inventory.problem)
            }
        }
        for (expired in current.automatic.drop(AUTOMATIC_RETENTION)) {
            try {
                coroutineContext.ensureActive()
                if (!folder.delete(expired.id)) {
                    rollbackAutomatic(created.value)
                    return BackupOperationResult.Failed(BackupLibraryProblem.DELETE_FAILED)
                }
            } catch (cancelled: CancellationException) {
                rollbackAutomatic(created.value)
                throw cancelled
            } catch (_: Exception) {
                rollbackAutomatic(created.value)
                return BackupOperationResult.Failed(BackupLibraryProblem.DELETE_FAILED)
            }
        }
        return created
    }

    private suspend fun rollbackAutomatic(created: BackupDocument): Boolean = withContext(NonCancellable) {
        try {
            folder.delete(created.id)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun preview(
        documentId: String,
        passphrase: CharArray? = null,
    ): BackupOperationResult<StagedRestore> {
        val bytes = read(documentId) ?: return BackupOperationResult.Failed(BackupLibraryProblem.READ_FAILED)
        val digest = sha256(bytes)
        val protected = PassphraseArchiveProtection.isProtected(bytes)
        val decoded = decodeArchive(bytes, passphrase)
        bytes.fill(0)
        if (decoded !is BackupOperationResult.Completed) {
            return BackupOperationResult.Failed((decoded as BackupOperationResult.Failed).problem)
        }
        val archive = decoded.value
        if (!validateOptionalPayloads(archive)) {
            return BackupOperationResult.Failed(BackupLibraryProblem.PAYLOAD_REJECTED)
        }
        val payloadBytes = archive.sections.single { it.type == PortableBackupSectionType.LAUNCHER_MAP }
            .contentCopy()
        val payload = LauncherSnapshotPayloadCodec.decode(payloadBytes)
        payloadBytes.fill(0)
        if (payload !is LauncherPayloadDecodeResult.Decoded) {
            return BackupOperationResult.Failed(BackupLibraryProblem.PAYLOAD_REJECTED)
        }
        val launcherExtensions = payload.extensionNames.associateWith { name ->
            requireNotNull(payload.extensionCopy(name))
        }
        val auxiliaryValid = try {
            auxiliaryPort.validate(payload.snapshot, archive.sections, launcherExtensions)
        } catch (_: RuntimeException) {
            false
        }
        if (!auxiliaryValid) {
            return BackupOperationResult.Failed(BackupLibraryProblem.PAYLOAD_REJECTED)
        }
        val prepared = prepare(payload.snapshot)
            ?: return BackupOperationResult.Failed(BackupLibraryProblem.SAFE_LAYOUT_MISSING)
        if (!validInIsolation(prepared)) {
            return BackupOperationResult.Failed(BackupLibraryProblem.PAYLOAD_REJECTED)
        }
        val profileSerials = (prepared.appOverrides.map { it.profile } +
            prepared.widgetPlacements.map { it.profile } +
            prepared.contentItems.mapNotNull { it.profile }).distinct().sortedBy { it.value }
        val review = RestoreReview(
            archiveCreatedAtEpochMillis = archive.createdAtEpochMillis,
            destinationCount = prepared.destinations.size,
            moduleCount = prepared.moduleInstances.size,
            widgetRebindCount = prepared.widgetPlacements.size,
            quarantinedInstanceCount = prepared.moduleInstances.count {
                it.status is ModuleInstanceStatus.Quarantined
            },
            profilesToReview = profileSerials.size,
            profileSerials = profileSerials,
            permissionsRequireReauthorization = true,
        )
        return BackupOperationResult.Completed(
            StagedRestore(
                sourceId = documentId,
                sourceDigest = digest,
                importedSnapshot = prepared,
                archive = archive,
                passphraseRequired = protected,
                launcherExtensions = launcherExtensions,
                token = UUID.randomUUID().toString(),
                review = review,
            ),
        )
    }

    suspend fun restore(
        staged: StagedRestore,
        selection: RestoreSelection = RestoreSelection(),
        passphrase: CharArray? = null,
    ): BackupOperationResult<RestoreReceipt> {
        coroutineContext.ensureActive()
        val source = read(staged.sourceId) ?: return BackupOperationResult.Failed(BackupLibraryProblem.READ_FAILED)
        if (!MessageDigest.isEqual(staged.digestCopy(), sha256(source))) {
            source.fill(0)
            return BackupOperationResult.Failed(BackupLibraryProblem.STALE_PREVIEW)
        }
        val revalidated = decodeArchive(source, passphrase)
        source.fill(0)
        if (revalidated !is BackupOperationResult.Completed || revalidated.value != staged.archive) {
            return BackupOperationResult.Failed(
                if (staged.passphraseRequired && passphrase == null) {
                    BackupLibraryProblem.PASSPHRASE_REQUIRED
                } else {
                    BackupLibraryProblem.STALE_PREVIEW
                },
            )
        }
        coroutineContext.ensureActive()
        val before = store.read()
        val selected = selectState(before, staged.importedSnapshot, selection)
        if (!validInIsolation(selected)) {
            return BackupOperationResult.Failed(BackupLibraryProblem.SAFE_LAYOUT_MISSING)
        }
        val preRestore = create(PortableBackupKind.PRE_RESTORE, null)
        if (preRestore !is BackupOperationResult.Completed) {
            return BackupOperationResult.Failed(BackupLibraryProblem.PRE_RESTORE_BACKUP_FAILED)
        }
        val selectedAuxiliary = staged.archive.sections.filter { section ->
            when (section.type) {
                PortableBackupSectionType.LAUNCHER_MAP -> false
                PortableBackupSectionType.THEME_PROFILES -> selection.themes
                PortableBackupSectionType.WEB_ADAPTERS -> selection.webAdapters
                PortableBackupSectionType.ASSET -> selection.assets
            }
        }
        val auxiliary = try {
            auxiliaryPort.stage(
                staged.importedSnapshot,
                selectedAuxiliary,
                selection,
                if (selection.launcherMap) staged.launcherExtensionsCopy() else emptyMap(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return BackupOperationResult.Failed(BackupLibraryProblem.AUXILIARY_IMPORT_FAILED)
        }
        coroutineContext.ensureActive()
        try {
            auxiliary.commit()
        } catch (cancelled: CancellationException) {
            discardAuxiliary(auxiliary)
            throw cancelled
        } catch (_: Exception) {
            discardAuxiliary(auxiliary)
            return BackupOperationResult.Failed(BackupLibraryProblem.AUXILIARY_IMPORT_FAILED)
        }
        if (!selection.launcherMap && !selection.themes) {
            return BackupOperationResult.Completed(
                RestoreReceipt(
                    preRestore.value,
                    before.revision.value,
                    widgetRebindCount = 0,
                    quarantinedInstanceCount = 0,
                ),
            )
        }
        val committed = try {
            store.commit(
                LauncherTransaction(
                    before.revision,
                    listOf(LauncherEdit.RestoreSnapshot(selected, rebindWidgets = selection.launcherMap)),
                ),
            )
        } catch (cancelled: CancellationException) {
            discardAuxiliary(auxiliary)
            throw cancelled
        } catch (_: Exception) {
            discardAuxiliary(auxiliary)
            return BackupOperationResult.Failed(BackupLibraryProblem.STORE_REJECTED)
        }
        return when (committed) {
            is CommitResult.Committed ->
                BackupOperationResult.Completed(
                    RestoreReceipt(
                        preRestore.value,
                        committed.state.revision.value,
                        committed.state.widgetPlacements.size,
                        committed.state.moduleInstances.count {
                            it.status is ModuleInstanceStatus.Quarantined
                        },
                    ),
                )
            is CommitResult.Rejected -> {
                discardAuxiliary(auxiliary)
                BackupOperationResult.Failed(
                    if (committed.current.revision != before.revision) {
                        BackupLibraryProblem.LIVE_STATE_CHANGED
                    } else {
                        BackupLibraryProblem.STORE_REJECTED
                    },
                )
            }
        }
    }

    private suspend fun discardAuxiliary(auxiliary: AuxiliaryStage) {
        withContext(NonCancellable) {
            runCatching { auxiliary.discard() }
        }
    }

    private suspend fun create(
        kind: PortableBackupKind,
        passphrase: CharArray?,
    ): BackupOperationResult<BackupDocument> {
        coroutineContext.ensureActive()
        val snapshot = store.read()
        val launcherExtensions = try {
            launcherExtensionSource.export()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return BackupOperationResult.Failed(BackupLibraryProblem.WRITE_FAILED)
        }
        val encodedPayload = try {
            LauncherSnapshotPayloadCodec.encode(snapshot, launcherExtensions)
        } finally {
            launcherExtensions.values.forEach { it.fill(0) }
        }
        if (encodedPayload !is LauncherPayloadEncodeResult.Encoded) {
            return BackupOperationResult.Failed(BackupLibraryProblem.PAYLOAD_REJECTED)
        }
        val payload = encodedPayload.bytesCopy()
        val exportedSections = try {
            sectionSource.export(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return BackupOperationResult.Failed(BackupLibraryProblem.WRITE_FAILED)
        }
        val archive = PortableBackupArchive(
            createdAtEpochMillis = clock.nowEpochMillis().coerceAtLeast(0L),
            kind = kind,
            sections = listOf(
                PortableBackupSection(PortableBackupSectionType.LAUNCHER_MAP, "state", payload),
            ) + exportedSections,
        )
        payload.fill(0)
        val encodedArchive = PortableBackupArchiveCodec.encode(archive)
        if (encodedArchive !is PortableBackupEncodeResult.Encoded) {
            return BackupOperationResult.Failed(BackupLibraryProblem.ARCHIVE_REJECTED)
        }
        val plaintext = encodedArchive.bytesCopy()
        val stored = if (passphrase == null) {
            plaintext.copyOf()
        } else {
            when (val protected = protection.protect(plaintext, passphrase)) {
                is ProtectResult.Protected -> protected.bytesCopy()
                is ProtectResult.Rejected -> {
                    plaintext.fill(0)
                    return BackupOperationResult.Failed(BackupLibraryProblem.ARCHIVE_REJECTED)
                }
            }
        }
        plaintext.fill(0)
        coroutineContext.ensureActive()
        return try {
            val written = folder.write(fileName(kind, archive.createdAtEpochMillis), stored)
            BackupOperationResult.Completed(written)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            BackupOperationResult.Failed(BackupLibraryProblem.WRITE_FAILED)
        } finally {
            stored.fill(0)
        }
    }

    private suspend fun read(id: String): ByteArray? = try {
        folder.read(id, MAX_STORED_BYTES)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun decodeArchive(
        storedBytes: ByteArray,
        passphrase: CharArray?,
    ): BackupOperationResult<PortableBackupArchive> {
        val plaintext = if (PassphraseArchiveProtection.isProtected(storedBytes)) {
            if (passphrase == null) {
                return BackupOperationResult.Failed(BackupLibraryProblem.PASSPHRASE_REQUIRED)
            }
            when (val unprotected = protection.unprotect(storedBytes, passphrase)) {
                is UnprotectResult.Plaintext -> unprotected.bytesCopy()
                is UnprotectResult.Rejected -> return BackupOperationResult.Failed(
                    BackupLibraryProblem.AUTHENTICATION_FAILED,
                )
            }
        } else {
            storedBytes.copyOf()
        }
        return try {
            when (val decoded = PortableBackupArchiveCodec.decode(plaintext)) {
                is PortableBackupDecodeResult.Decoded -> BackupOperationResult.Completed(decoded.archive)
                is PortableBackupDecodeResult.Rejected -> BackupOperationResult.Failed(
                    BackupLibraryProblem.ARCHIVE_REJECTED,
                )
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun validateOptionalPayloads(archive: PortableBackupArchive): Boolean {
        return validateOptionalPayload(
            archive,
            PortableBackupSectionType.THEME_PROFILES,
            "profiles",
            SectionPayloadKind.THEME_PROFILES,
        ) && validateOptionalPayload(
            archive,
            PortableBackupSectionType.WEB_ADAPTERS,
            "adapters",
            SectionPayloadKind.WEB_ADAPTERS,
        ) && validateAssetSections(archive)
    }

    private fun validateAssetSections(archive: PortableBackupArchive): Boolean {
        val sections = archive.sections.filter { it.type == PortableBackupSectionType.ASSET }
        val assets = sections.filter { it.name != ASSET_MANIFEST_NAME }
        if (assets.any { !ASSET_SECTION_NAME.matches(it.name) }) return false
        val manifest = sections.singleOrNull { it.name == ASSET_MANIFEST_NAME } ?: return true
        val decoded = VersionedSectionPayloadCodec.decode(
            manifest.contentCopy(),
            SectionPayloadKind.ASSET_MANIFEST,
        )
        if (decoded !is SectionPayloadResult.Decoded || decoded.value.entries.isEmpty()) return false
        val entries = decoded.value.entries.associateBy { it.name }
        if (entries.keys != assets.mapTo(linkedSetOf()) { it.name }) return false
        return assets.all { asset ->
            val expected = entries.getValue(asset.name).contentCopy()
            val content = asset.contentCopy()
            val actual = sha256(content)
            content.fill(0)
            val valid = expected.size == SHA256_BYTES && MessageDigest.isEqual(expected, actual)
            expected.fill(0)
            actual.fill(0)
            valid
        }
    }

    private fun validateOptionalPayload(
        archive: PortableBackupArchive,
        type: PortableBackupSectionType,
        name: String,
        kind: SectionPayloadKind,
        allowOtherNames: Boolean = false,
    ): Boolean {
        val sections = archive.sections.filter { it.type == type }
        if (!allowOtherNames && sections.any { it.name != name }) return false
        val payload = sections.singleOrNull { it.name == name } ?: return true
        return VersionedSectionPayloadCodec.decode(payload.contentCopy(), kind) is
            SectionPayloadResult.Decoded
    }

    private fun prepare(imported: LauncherSnapshot): LauncherSnapshot? {
        if (!hasSafeLayout(imported)) return null
        val instances = imported.moduleInstances.map { instance ->
            if (knownContribution(instance.contributionId) ||
                instance.contributionId.value == SAFE_LAYOUT_CONTRIBUTION
            ) {
                instance
            } else {
                instance.copy(
                    status = ModuleInstanceStatus.Quarantined(
                        code = "restore.contribution-unavailable",
                        message = "Contribution is unavailable after restore",
                        origin = ModuleQuarantineOrigin.RESTORE,
                    ),
                )
            }
        }
        return copySnapshot(imported, moduleInstances = instances)
    }

    private fun selectState(
        current: LauncherSnapshot,
        imported: LauncherSnapshot,
        selection: RestoreSelection,
    ): LauncherSnapshot {
        if (!selection.launcherMap) {
            return if (selection.themes) {
                copySnapshot(
                    current,
                    themeProfiles = imported.themeProfiles,
                    destinationBackgrounds = imported.destinationBackgrounds.filter { background ->
                        current.destinations.any { it.id == background.destinationId }
                    },
                )
            } else {
                current
            }
        }
        return if (selection.themes) {
            imported
        } else {
            copySnapshot(
                imported,
                themeProfiles = current.themeProfiles,
                destinationBackgrounds = current.destinationBackgrounds.filter { background ->
                    imported.destinations.any { it.id == background.destinationId }
                },
            )
        }
    }

    private suspend fun validInIsolation(snapshot: LauncherSnapshot): Boolean {
        val validator = InMemoryLauncherStore(snapshot)
        val result = validator.commit(
            LauncherTransaction(snapshot.revision, listOf(LauncherEdit.RestoreSnapshot(snapshot))),
        )
        return result is CommitResult.Committed
    }

    private fun hasSafeLayout(snapshot: LauncherSnapshot): Boolean =
        snapshot.destinations.isNotEmpty() && snapshot.destinations.all { destination ->
            snapshot.destinationLayouts.any { layout ->
                layout.destinationId == destination.id &&
                    layout.layoutContributionId.value == SAFE_LAYOUT_CONTRIBUTION
            }
        }

    private fun copySnapshot(
        source: LauncherSnapshot,
        moduleInstances: Collection<org.quicklauncher.host.data.store.ModuleInstanceRecord> = source.moduleInstances,
        themeProfiles: Collection<org.quicklauncher.host.data.store.ThemeProfileRecord> = source.themeProfiles,
        destinationBackgrounds: Collection<org.quicklauncher.host.data.store.DestinationBackgroundRecord> = source.destinationBackgrounds,
    ): LauncherSnapshot = LauncherSnapshot.restored(
        source.startDestinationId,
        source.destinations,
        source.destinationLayouts,
        moduleInstances,
        source.configurationDocuments,
        source.placements,
        source.contentItems,
        source.folderMembers,
        source.appOverrides,
        source.widgetPlacements,
        themeProfiles,
        destinationBackgrounds,
    )

    private fun fileName(kind: PortableBackupKind, timestamp: Long): String {
        val prefix = when (kind) {
            PortableBackupKind.MANUAL -> MANUAL_PREFIX
            PortableBackupKind.AUTOMATIC -> AUTOMATIC_PREFIX
            PortableBackupKind.PRE_RESTORE -> PRE_RESTORE_PREFIX
        }
        val instant = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(timestamp))
        return "$prefix$instant-${UUID.randomUUID().toString().take(8)}$EXTENSION"
    }

    private data class IndexedArchive(
        val document: BackupDocument,
        val kind: PortableBackupKind,
        val createdAtEpochMillis: Long,
    )

    private fun List<IndexedArchive>.documentsOfKind(kind: PortableBackupKind): List<BackupDocument> =
        asSequence()
            .filter { it.kind == kind }
            .sortedWith(compareByDescending<IndexedArchive> { it.createdAtEpochMillis }.thenBy { it.document.id })
            .map { it.document }
            .toList()

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        const val AUTOMATIC_RETENTION = 7
        private const val ASSET_MANIFEST_NAME = "manifest"
        private const val SHA256_BYTES = 32
        private val ASSET_SECTION_NAME = Regex(
            "(?:font|image|preview)\\.[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*",
        )
        private const val SAFE_LAYOUT_CONTRIBUTION = "org.quicklauncher.core/safe-layout"
        private const val EXTENSION = ".qlbackup"
        private const val MANUAL_PREFIX = "ql-manual-"
        private const val AUTOMATIC_PREFIX = "ql-auto-"
        private const val PRE_RESTORE_PREFIX = "ql-pre-restore-"
        private const val MAX_STORED_BYTES = PortableBackupArchiveCodec.MAX_ARCHIVE_BYTES + 1_024
    }
}
