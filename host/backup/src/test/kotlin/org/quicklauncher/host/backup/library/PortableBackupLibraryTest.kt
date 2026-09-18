package org.quicklauncher.host.backup.library

import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.host.backup.archive.PortableBackupSection
import org.quicklauncher.host.backup.archive.PortableBackupSectionType
import org.quicklauncher.host.backup.payload.SectionPayloadEntry
import org.quicklauncher.host.backup.payload.SectionPayloadKind
import org.quicklauncher.host.backup.payload.SectionPayloadResult
import org.quicklauncher.host.backup.payload.VersionedSectionPayload
import org.quicklauncher.host.backup.payload.VersionedSectionPayloadCodec
import org.quicklauncher.host.backup.payload.snapshot
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.ConfigurationReconciliation
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction

class PortableBackupLibraryTest {
    @Test
    fun `theme restore selection cannot omit imported assets`() {
        assertThrows(IllegalArgumentException::class.java) {
            RestoreSelection(
                launcherMap = true,
                themes = true,
                webAdapters = false,
                assets = false,
            )
        }
    }

    @Test
    fun `auxiliary requests defensively snapshot sections and extension bytes`() {
        val section = PortableBackupSection(PortableBackupSectionType.ASSET, "image.fixture", byteArrayOf(1))
        val sections = mutableListOf(section)
        val extensionBytes = byteArrayOf(2, 3)
        val extensions = mutableMapOf("future.record" to extensionBytes)
        val imported = snapshot("Imported")
        val validation = RestoreAuxiliaryValidationRequest(imported, sections, extensions)
        val staging = RestoreAuxiliaryStageRequest(imported, sections, RestoreSelection(), extensions)

        sections.clear()
        extensionBytes.fill(9)
        extensions.clear()
        val validationCopy = requireNotNull(validation.launcherExtensionsCopy()["future.record"])
        validationCopy.fill(8)

        assertEquals(listOf(section), validation.sections)
        assertArrayEquals(byteArrayOf(2, 3), validation.launcherExtensionsCopy()["future.record"])
        assertEquals(listOf(section), staging.sections)
        assertArrayEquals(byteArrayOf(2, 3), staging.launcherExtensionsCopy()["future.record"])
    }

    @Test
    fun `manual encrypted export previews and atomically restores after a pre-restore backup`() = runTest {
        val folder = MemoryFolder()
        val clock = MutableClock()
        val source = PortableBackupLibrary(folder, InMemoryLauncherStore(snapshot("Backup")), { true }, clock = clock)
        val exported = source.createManual("passphrase".toCharArray()).completed()

        val targetStore = InMemoryLauncherStore(snapshot("Current"))
        val target = PortableBackupLibrary(folder, targetStore, { true }, clock = clock)
        assertEquals(listOf(exported.id), target.inventory().completed().manual.map { it.id })
        val staged = target.preview(exported.id, "passphrase".toCharArray()).completed()
        assertEquals("Backup", staged.importedSnapshot.destinations.single().name)
        assertTrue(staged.review.permissionsRequireReauthorization)

        val receipt = target.restore(staged, passphrase = "passphrase".toCharArray()).completed()

        assertEquals("Backup", targetStore.read().destinations.single().name)
        assertEquals(1L, receipt.revision)
        assertTrue(folder.documents.values.any { it.first.startsWith("ql-pre-restore-") })
    }

    @Test
    fun `empty optional categories are omitted instead of duplicating launcher state`() = runTest {
        val folder = MemoryFolder()
        val library = PortableBackupLibrary(folder, InMemoryLauncherStore(snapshot("Backup")), { true })

        val document = library.createManual().completed()
        val stored = requireNotNull(folder.documents[document.id]).second
        val decoded = org.quicklauncher.host.backup.archive.PortableBackupArchiveCodec.decode(stored) as
            org.quicklauncher.host.backup.archive.PortableBackupDecodeResult.Decoded

        assertEquals(
            listOf(org.quicklauncher.host.backup.archive.PortableBackupSectionType.LAUNCHER_MAP),
            decoded.archive.sections.map { it.type },
        )
        assertTrue(library.preview(document.id) is BackupOperationResult.Completed)
    }

    @Test
    fun `asset manifest is optional and exact manifest digests are accepted`() = runTest {
        val font = asset("font.primary", byteArrayOf(1, 2, 3))
        val image = asset("image.background", byteArrayOf(4, 5, 6))
        val variants = listOf(
            listOf(font, image),
            listOf(
                font,
                image,
                manifest(
                    "font.primary" to digest(byteArrayOf(1, 2, 3)),
                    "image.background" to digest(byteArrayOf(4, 5, 6)),
                ),
            ),
        )

        variants.forEach { sections ->
            val (library, document) = libraryWithSections(sections)
            assertTrue(library.preview(document.id) is BackupOperationResult.Completed)
        }
    }

    @Test
    fun `empty incomplete extra short and mismatched asset manifests are rejected`() = runTest {
        val font = asset("font.primary", byteArrayOf(1, 2, 3))
        val image = asset("image.background", byteArrayOf(4, 5, 6))
        val rejected = listOf(
            listOf(font, image, manifest()),
            listOf(font, image, manifest("font.primary" to digest(byteArrayOf(1, 2, 3)))),
            listOf(
                font,
                image,
                manifest(
                    "font.primary" to digest(byteArrayOf(1, 2, 3)),
                    "image.background" to digest(byteArrayOf(4, 5, 6)),
                    "preview.extra" to digest(byteArrayOf(7)),
                ),
            ),
            listOf(font, image, manifest("font.primary" to byteArrayOf(1), "image.background" to ByteArray(32))),
            listOf(
                font,
                image,
                manifest("font.primary" to ByteArray(32), "image.background" to ByteArray(32)),
            ),
        )

        rejected.forEach { sections ->
            val (library, document) = libraryWithSections(sections)
            val result = library.preview(document.id)
            assertEquals(BackupLibraryProblem.PAYLOAD_REJECTED, (result as BackupOperationResult.Failed).problem)
        }
    }

    @Test
    fun `malformed and unsupported asset identities are rejected before review`() = runTest {
        listOf("blob.alpha", "font.1alpha").forEach { name ->
            val (library, document) = libraryWithSections(listOf(asset(name, byteArrayOf(1))))

            val result = library.preview(document.id)

            assertEquals(BackupLibraryProblem.PAYLOAD_REJECTED, (result as BackupOperationResult.Failed).problem)
        }
    }

    @Test
    fun `preview auxiliary rejection cannot stage assets or write a pre-restore backup`() = runTest {
        val folder = MemoryFolder()
        val sourceSnapshot = snapshot("Backup")
        var exportedSnapshot: LauncherSnapshot? = null
        val source = PortableBackupLibrary(
            folder,
            InMemoryLauncherStore(sourceSnapshot),
            { true },
            sectionSource = BackupSectionSource { current ->
                exportedSnapshot = current
                listOf(asset("font.primary", byteArrayOf(1)))
            },
        )
        val document = source.createManual().completed()
        var staged = false
        val target = PortableBackupLibrary(
            folder,
            InMemoryLauncherStore(snapshot("Current")),
            { true },
            auxiliaryPort = object : RestoreAuxiliaryPort {
                override fun validate(request: RestoreAuxiliaryValidationRequest): Boolean = false

                override suspend fun stage(request: RestoreAuxiliaryStageRequest): AuxiliaryStage {
                    staged = true
                    error("validation must finish before staging")
                }
            },
        )
        val countBeforePreview = folder.documents.size

        val result = target.preview(document.id)

        assertSame(sourceSnapshot, exportedSnapshot)
        assertEquals(BackupLibraryProblem.PAYLOAD_REJECTED, (result as BackupOperationResult.Failed).problem)
        assertEquals(countBeforePreview, folder.documents.size)
        assertEquals(false, staged)
    }

    @Test
    fun `opaque launcher extensions flow through preview and selected restore`() = runTest {
        val folder = MemoryFolder()
        val source = PortableBackupLibrary(
            folder,
            InMemoryLauncherStore(snapshot("Backup")),
            { true },
            launcherExtensionSource = BackupLauncherExtensionSource {
                mapOf("future.record" to byteArrayOf(4, 5, 6))
            },
        )
        val document = source.createManual().completed()
        var validated: ByteArray? = null
        var restored: ByteArray? = null
        var validationSnapshot: LauncherSnapshot? = null
        var validationSections: List<PortableBackupSection>? = null
        var stagingSnapshot: LauncherSnapshot? = null
        var stagingSections: List<PortableBackupSection>? = null
        var stagingSelection: RestoreSelection? = null
        val target = PortableBackupLibrary(
            folder,
            InMemoryLauncherStore(snapshot("Current")),
            { true },
            auxiliaryPort = object : RestoreAuxiliaryPort {
                override fun validate(request: RestoreAuxiliaryValidationRequest): Boolean {
                    validationSnapshot = request.snapshot
                    validationSections = request.sections
                    validated = request.launcherExtensionsCopy()["future.record"]
                    return true
                }

                override suspend fun stage(request: RestoreAuxiliaryStageRequest): AuxiliaryStage {
                    stagingSnapshot = request.snapshot
                    stagingSections = request.sections
                    stagingSelection = request.selection
                    restored = request.launcherExtensionsCopy()["future.record"]
                    return object : AuxiliaryStage {
                        override suspend fun commit() = Unit
                        override suspend fun discard() = Unit
                    }
                }
            },
        )

        val selection = RestoreSelection(
            launcherMap = true,
            themes = false,
            webAdapters = false,
            assets = true,
        )
        val staged = target.preview(document.id).completed()
        target.restore(staged, selection).completed()

        assertArrayEquals(byteArrayOf(4, 5, 6), validated)
        assertArrayEquals(byteArrayOf(4, 5, 6), restored)
        assertEquals("Backup", validationSnapshot?.destinations?.single()?.name)
        assertEquals(listOf(PortableBackupSectionType.LAUNCHER_MAP), validationSections?.map { it.type })
        assertEquals("Backup", stagingSnapshot?.destinations?.single()?.name)
        assertTrue(stagingSections?.isEmpty() == true)
        assertEquals(selection, stagingSelection)
    }

    @Test
    fun `corrupt and stale archives never alter live state`() = runTest {
        val folder = MemoryFolder()
        val clock = MutableClock()
        val source = PortableBackupLibrary(folder, InMemoryLauncherStore(snapshot("Backup")), { true }, clock = clock)
        val exported = source.createManual().completed()
        val targetStore = InMemoryLauncherStore(snapshot("Current"))
        val target = PortableBackupLibrary(folder, targetStore, { true }, clock = clock)
        val before = targetStore.read()
        val staged = target.preview(exported.id).completed()
        folder.mutate(exported.id) { bytes -> bytes + 0 }

        val result = target.restore(staged)

        assertEquals(BackupLibraryProblem.STALE_PREVIEW, (result as BackupOperationResult.Failed).problem)
        assertSame(before, targetStore.read())
    }

    @Test
    fun `automatic retention keeps exactly seven and never removes manual archives`() = runTest {
        val folder = MemoryFolder()
        val clock = MutableClock()
        val library = PortableBackupLibrary(folder, InMemoryLauncherStore(snapshot("Home")), { true }, clock = clock)
        val manual = library.createManual().completed()
        folder.rename(manual.id, "ql-auto-renamed-manual.qlbackup")
        val corrupt = folder.putRaw("ql-auto-corrupt.qlbackup", byteArrayOf(1, 2, 3))
        repeat(9) {
            clock.time += 1_000
            library.createAutomatic().completed()
        }

        val inventory = library.inventory().completed()

        assertEquals(listOf(manual.id), inventory.manual.map { it.id })
        assertEquals(7, inventory.automatic.size)
        assertTrue(corrupt.id in folder.documents)
        assertEquals(9, folder.documents.size)
    }

    @Test
    fun `inventory classifies and orders decoded metadata instead of names and provider times`() = runTest {
        val folder = MemoryFolder()
        val clock = MutableClock(100L)
        val library = PortableBackupLibrary(folder, InMemoryLauncherStore(snapshot("Home")), { true }, clock = clock)
        val older = library.createAutomatic().completed()
        clock.time = 200L
        val newer = library.createAutomatic().completed()
        clock.time = 300L
        val manual = library.createManual().completed()
        folder.rename(older.id, "not-an-automatic-prefix.qlbackup")
        folder.rename(manual.id, "ql-auto-misleading.qlbackup")
        folder.setModified(older.id, 9_000L)
        folder.setModified(newer.id, 1L)

        val inventory = library.inventory().completed()

        assertEquals(listOf(newer.id, older.id), inventory.automatic.map { it.id })
        assertEquals(listOf(manual.id), inventory.manual.map { it.id })
    }

    @Test
    fun `inventory decodes an archive whose provider size is unknown and skips known oversized entries`() = runTest {
        val folder = MemoryFolder()
        val library = PortableBackupLibrary(folder, InMemoryLauncherStore(snapshot("Home")), { true })
        val unknown = library.createManual().completed()
        folder.setSize(unknown.id, null)
        val oversized = folder.putRaw("anything.qlbackup", byteArrayOf(1))
        folder.setSize(oversized.id, Long.MAX_VALUE)
        folder.putRaw("fake-protected.qlbackup", "QLCRYPT1".toByteArray())

        val inventory = library.inventory().completed()

        assertEquals(listOf(unknown.id), inventory.manual.map { it.id })
        assertEquals(0, folder.readCounts.getValue(oversized.id))
    }

    @Test
    fun `inventory and automatic creation expose folder read list write and delete failures`() = runTest {
        val store = InMemoryLauncherStore(snapshot("Home"))
        val listFailure = object : BackupFolder {
            override suspend fun list(): List<BackupDocument> = error("list")
            override suspend fun read(id: String, maxBytes: Int): ByteArray? = error("unused")
            override suspend fun write(displayName: String, bytes: ByteArray): BackupDocument = error("unused")
            override suspend fun delete(id: String): Boolean = error("unused")
        }
        assertEquals(
            BackupLibraryProblem.FOLDER_UNAVAILABLE,
            (PortableBackupLibrary(listFailure, store, { true }).inventory() as BackupOperationResult.Failed).problem,
        )

        val readable = MemoryFolder()
        val source = PortableBackupLibrary(readable, store, { true })
        val document = source.createManual().completed()
        val readFailure = forwardingFolder(readable, readAction = { _, _ -> error("read") })
        assertEquals(
            BackupLibraryProblem.READ_FAILED,
            (PortableBackupLibrary(readFailure, store, { true }).inventory() as BackupOperationResult.Failed).problem,
        )

        val writeFailure = forwardingFolder(readable, writeAction = { _, _ -> error("write") })
        val writeResult = PortableBackupLibrary(writeFailure, store, { true }).createManual()
        assertEquals(
            BackupLibraryProblem.WRITE_FAILED,
            (writeResult as BackupOperationResult.Failed).problem,
        )

        val retentionFolder = MemoryFolder()
        val clock = MutableClock()
        val retention = PortableBackupLibrary(retentionFolder, store, { true }, clock = clock)
        repeat(7) {
            clock.time += 1_000
            retention.createAutomatic().completed()
        }
        val deleteFailure = forwardingFolder(
            retentionFolder,
            deleteAction = { id -> if (id == "document-1") false else retentionFolder.delete(id) },
        )
        val result = PortableBackupLibrary(deleteFailure, store, { true }, clock = clock).createAutomatic()
        assertEquals(BackupLibraryProblem.DELETE_FAILED, (result as BackupOperationResult.Failed).problem)
        assertEquals(7, retention.inventory().completed().automatic.size)
        assertTrue("document-8" !in retentionFolder.documents)
        assertTrue(document.id in readable.documents)
    }

    @Test
    fun `archive without a safe layout is rejected during preview`() = runTest {
        val folder = MemoryFolder()
        val unsafe = snapshot("Unsafe")
        val unsafeSnapshot = org.quicklauncher.host.data.store.LauncherSnapshot.restored(
            unsafe.startDestinationId,
            unsafe.destinations,
            unsafe.destinationLayouts.map {
                it.copy(layoutContributionId = org.quicklauncher.contracts.domain.ContributionId.parse(
                    "org.quicklauncher.test/optional-layout",
                ))
            },
            unsafe.moduleInstances.map {
                it.copy(contributionId = org.quicklauncher.contracts.domain.ContributionId.parse(
                    "org.quicklauncher.test/optional-layout",
                ))
            },
            unsafe.configurationDocuments,
            unsafe.placements,
        )
        val source = PortableBackupLibrary(folder, InMemoryLauncherStore(unsafeSnapshot), { true })
        val document = source.createManual().completed()
        val target = PortableBackupLibrary(folder, InMemoryLauncherStore(snapshot("Current")), { true })

        val result = target.preview(document.id)

        assertEquals(BackupLibraryProblem.SAFE_LAYOUT_MISSING, (result as BackupOperationResult.Failed).problem)
    }

    @Test
    fun `cancellation at the atomic store boundary discards staged assets`() = runTest {
        val folder = MemoryFolder()
        val source = PortableBackupLibrary(folder, InMemoryLauncherStore(snapshot("Backup")), { true })
        val document = source.createManual().completed()
        val targetSnapshot = snapshot("Current")
        var committed = false
        var discarded = false
        val auxiliary = object : RestoreAuxiliaryPort {
            override fun validate(request: RestoreAuxiliaryValidationRequest): Boolean = true

            override suspend fun stage(request: RestoreAuxiliaryStageRequest) =
                object : AuxiliaryStage {
                    override suspend fun commit() { committed = true }
                    override suspend fun discard() { discarded = true }
                }
        }
        val library = PortableBackupLibrary(
            folder,
            CancellingStore(targetSnapshot),
            { true },
            auxiliaryPort = auxiliary,
        )
        val staged = library.preview(document.id).completed()

        val failure = runCatching { library.restore(staged) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(committed)
        assertTrue(discarded)
    }

    @Test
    fun `cancellation while committing staged assets rolls them back`() = runTest {
        val folder = MemoryFolder()
        val source = PortableBackupLibrary(folder, InMemoryLauncherStore(snapshot("Backup")), { true })
        val document = source.createManual().completed()
        val targetStore = InMemoryLauncherStore(snapshot("Current"))
        var discarded = false
        val library = PortableBackupLibrary(
            folder,
            targetStore,
            { true },
            auxiliaryPort = auxiliaryPort(
                commit = { throw CancellationException("injected") },
                discard = { discarded = true },
            ),
        )
        val staged = library.preview(document.id).completed()
        val before = targetStore.read()

        val failure = runCatching { library.restore(staged) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(discarded)
        assertSame(before, targetStore.read())
    }

    @Test
    fun `cancellation observed after staging discards staged assets`() = runTest {
        val folder = MemoryFolder()
        val source = PortableBackupLibrary(folder, InMemoryLauncherStore(snapshot("Backup")), { true })
        val document = source.createManual().completed()
        val targetStore = InMemoryLauncherStore(snapshot("Current"))
        var discarded = false
        val library = PortableBackupLibrary(
            folder,
            targetStore,
            { true },
            auxiliaryPort = object : RestoreAuxiliaryPort {
                override fun validate(request: RestoreAuxiliaryValidationRequest): Boolean = true

                override suspend fun stage(request: RestoreAuxiliaryStageRequest): AuxiliaryStage {
                    currentCoroutineContext().cancel(CancellationException("injected after staging"))
                    return object : AuxiliaryStage {
                        override suspend fun commit() = Unit
                        override suspend fun discard() { discarded = true }
                    }
                }
            },
        )
        val staged = library.preview(document.id).completed()
        val before = targetStore.read()

        val failure = runCatching {
            withContext(Job()) { library.restore(staged) }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(discarded)
        assertSame(before, targetStore.read())
    }

    @Test
    fun `auxiliary runtime failures are typed and retain live state`() = runTest {
        val folder = MemoryFolder()
        val source = PortableBackupLibrary(folder, InMemoryLauncherStore(snapshot("Backup")), { true })
        val document = source.createManual().completed()
        val targetStore = InMemoryLauncherStore(snapshot("Current"))
        var discarded = false
        val library = PortableBackupLibrary(
            folder,
            targetStore,
            { true },
            auxiliaryPort = auxiliaryPort(
                commit = { error("injected") },
                discard = {
                    discarded = true
                    error("rollback failure must not replace the typed result")
                },
            ),
        )
        val staged = library.preview(document.id).completed()
        val before = targetStore.read()

        val result = library.restore(staged)

        assertEquals(
            BackupLibraryProblem.AUXILIARY_IMPORT_FAILED,
            (result as BackupOperationResult.Failed).problem,
        )
        assertTrue(discarded)
        assertSame(before, targetStore.read())
    }

    @Test
    fun `store runtime failure rolls back staged assets and retains live state`() = runTest {
        val folder = MemoryFolder()
        val source = PortableBackupLibrary(folder, InMemoryLauncherStore(snapshot("Backup")), { true })
        val document = source.createManual().completed()
        val targetSnapshot = snapshot("Current")
        val targetStore = FailingStore(targetSnapshot)
        var committed = false
        var discarded = false
        val library = PortableBackupLibrary(
            folder,
            targetStore,
            { true },
            auxiliaryPort = auxiliaryPort(
                commit = { committed = true },
                discard = { discarded = true },
            ),
        )
        val staged = library.preview(document.id).completed()

        val result = library.restore(staged)

        assertEquals(BackupLibraryProblem.STORE_REJECTED, (result as BackupOperationResult.Failed).problem)
        assertTrue(committed)
        assertTrue(discarded)
        assertSame(targetSnapshot, targetStore.read())
    }

    private fun auxiliaryPort(
        commit: suspend () -> Unit,
        discard: suspend () -> Unit,
    ): RestoreAuxiliaryPort = object : RestoreAuxiliaryPort {
        override fun validate(request: RestoreAuxiliaryValidationRequest): Boolean = true

        override suspend fun stage(request: RestoreAuxiliaryStageRequest): AuxiliaryStage =
            object : AuxiliaryStage {
                override suspend fun commit() = commit.invoke()
                override suspend fun discard() = discard.invoke()
            }
    }

    private suspend fun libraryWithSections(
        sections: List<PortableBackupSection>,
    ): Pair<PortableBackupLibrary, BackupDocument> {
        val folder = MemoryFolder()
        val library = PortableBackupLibrary(
            folder,
            InMemoryLauncherStore(snapshot("Backup")),
            { true },
            sectionSource = BackupSectionSource { sections },
        )
        return library to library.createManual().completed()
    }

    private fun asset(name: String, bytes: ByteArray): PortableBackupSection =
        PortableBackupSection(PortableBackupSectionType.ASSET, name, bytes)

    private fun manifest(vararg entries: Pair<String, ByteArray>): PortableBackupSection {
        val encoded = VersionedSectionPayloadCodec.encode(
            VersionedSectionPayload(
                SectionPayloadKind.ASSET_MANIFEST,
                entries.map { (name, content) -> SectionPayloadEntry(name, content) },
            ),
        ) as SectionPayloadResult.Decoded
        return asset("manifest", encoded.value)
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun <T> BackupOperationResult<T>.completed(): T {
        assertTrue(this is BackupOperationResult.Completed)
        return (this as BackupOperationResult.Completed).value
    }

    private class MutableClock(var time: Long = 1_700_000_000_000L) : BackupClock {
        override fun nowEpochMillis(): Long = time
    }

    private fun forwardingFolder(
        delegate: BackupFolder,
        readAction: suspend (String, Int) -> ByteArray? = delegate::read,
        writeAction: suspend (String, ByteArray) -> BackupDocument = delegate::write,
        deleteAction: suspend (String) -> Boolean = delegate::delete,
    ): BackupFolder = object : BackupFolder {
        override suspend fun list(): List<BackupDocument> = delegate.list()
        override suspend fun read(id: String, maxBytes: Int): ByteArray? = readAction(id, maxBytes)
        override suspend fun write(displayName: String, bytes: ByteArray): BackupDocument =
            writeAction(displayName, bytes)
        override suspend fun delete(id: String): Boolean = deleteAction(id)
    }

    private class MemoryFolder : BackupFolder {
        val documents = LinkedHashMap<String, Pair<String, ByteArray>>()
        val readCounts = LinkedHashMap<String, Int>()
        private val sizes = LinkedHashMap<String, Long>()
        private val unknownSizes = LinkedHashSet<String>()
        private val modified = LinkedHashMap<String, Long>()
        private var nextId = 0

        override suspend fun list(): List<BackupDocument> = documents.map { (id, value) ->
            BackupDocument(
                id,
                value.first,
                if (id in unknownSizes) null else sizes[id] ?: value.second.size.toLong(),
                modified[id] ?: id.substringAfter('-').toLong(),
            )
        }

        override suspend fun read(id: String, maxBytes: Int): ByteArray? {
            readCounts[id] = readCounts.getOrDefault(id, 0) + 1
            return documents[id]?.second?.takeIf { it.size <= maxBytes }?.copyOf()
        }

        override suspend fun write(displayName: String, bytes: ByteArray): BackupDocument {
            val id = "document-${++nextId}"
            documents[id] = displayName to bytes.copyOf()
            sizes[id] = bytes.size.toLong()
            modified[id] = nextId.toLong()
            readCounts[id] = 0
            return BackupDocument(id, displayName, bytes.size.toLong(), nextId.toLong())
        }

        override suspend fun delete(id: String): Boolean {
            sizes.remove(id)
            unknownSizes.remove(id)
            modified.remove(id)
            readCounts.remove(id)
            return documents.remove(id) != null
        }

        fun putRaw(displayName: String, bytes: ByteArray): BackupDocument {
            val id = "document-${++nextId}"
            documents[id] = displayName to bytes.copyOf()
            sizes[id] = bytes.size.toLong()
            modified[id] = nextId.toLong()
            readCounts[id] = 0
            return BackupDocument(id, displayName, bytes.size.toLong(), nextId.toLong())
        }

        fun rename(id: String, displayName: String) {
            val existing = requireNotNull(documents[id])
            documents[id] = displayName to existing.second
        }

        fun setModified(id: String, modifiedAtEpochMillis: Long) {
            require(id in documents)
            modified[id] = modifiedAtEpochMillis
        }

        fun setSize(id: String, sizeBytes: Long?) {
            require(id in documents)
            if (sizeBytes == null) {
                sizes.remove(id)
                unknownSizes += id
            } else {
                sizes[id] = sizeBytes
                unknownSizes -= id
            }
        }

        fun mutate(id: String, block: (ByteArray) -> ByteArray) {
            val existing = requireNotNull(documents[id])
            documents[id] = existing.first to block(existing.second)
        }
    }

    private class CancellingStore(private val snapshot: LauncherSnapshot) : LauncherStore {
        override suspend fun read(): LauncherSnapshot = snapshot
        override suspend fun commit(transaction: LauncherTransaction): CommitResult {
            throw CancellationException("injected")
        }
        override suspend fun reconcileConfigurations(): ConfigurationReconciliation =
            ConfigurationReconciliation(snapshot, emptyList(), emptyList(), emptyList())
    }

    private class FailingStore(private val snapshot: LauncherSnapshot) : LauncherStore {
        override suspend fun read(): LauncherSnapshot = snapshot
        override suspend fun commit(transaction: LauncherTransaction): CommitResult {
            error("injected")
        }
        override suspend fun reconcileConfigurations(): ConfigurationReconciliation =
            ConfigurationReconciliation(snapshot, emptyList(), emptyList(), emptyList())
    }
}
