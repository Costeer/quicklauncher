package org.quicklauncher.app

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.host.backup.android.AndroidSafBackupFolder

@RunWith(AndroidJUnit4::class)
class BackupSafBoundaryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var provider: TestDocumentsProvider
    private lateinit var resolver: ContentResolver
    private lateinit var folder: AndroidSafBackupFolder

    @Before
    fun setUp() {
        provider = TestDocumentsProvider(context)
        provider.attachInfo(
            context,
            ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            },
        )
        resolver = ContentResolver.wrap(provider)
        folder = AndroidSafBackupFolder(resolver, DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID))
    }

    @Test
    fun listAndReadHonorUnknownSizeBoundAndTreeChildIdentity() = runBlocking {
        provider.documents["archive"] = StoredDocument("renamed.qlbackup", byteArrayOf(1, 2, 3, 4, 5), null)

        val listed = folder.list().single()

        assertNull(listed.sizeBytes)
        assertNull(folder.read(listed.id, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), folder.read(listed.id, 5))
        assertEquals(ROOT_ID, provider.lastParentId)

        val outside = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID),
            "outside",
        )
        val opensBefore = provider.openCount
        assertNull(folder.read(outside.toString(), 5))
        assertEquals(opensBefore, provider.openCount)
        assertFalse(folder.delete(outside.toString()))
    }

    @Test
    fun providerOpenCreateAndDeleteFailuresReachTheCaller() = runBlocking {
        provider.documents["archive"] = StoredDocument("backup.qlbackup", byteArrayOf(1), 1L)
        val id = folder.list().single().id

        provider.failOpen = true
        assertThrows(FileNotFoundException::class.java) { runBlocking { folder.read(id, 4) } }
        provider.failOpen = false

        provider.failCreate = true
        assertThrows(FileNotFoundException::class.java) {
            runBlocking { folder.write("ql-manual-20260917-000000-000-deadbeef.qlbackup", byteArrayOf(1)) }
        }

        provider.failDelete = true
        assertThrows(FileNotFoundException::class.java) { runBlocking { folder.delete(id) } }
        assertTrue("archive" in provider.documents)
    }

    @Test
    fun writeFinalizesTheDocumentAndRenameFailureDeletesThePartial() = runBlocking {
        val completedName = "ql-manual-20260917-000000-000-deadbeef.qlbackup"

        val completed = folder.write(completedName, byteArrayOf(1, 2, 3))

        assertEquals(completedName, completed.displayName)
        assertTrue(provider.documents.values.any { it.displayName == completedName })
        assertFalse(provider.documents.values.any { it.displayName.endsWith(".partial") })

        provider.failRename = true
        assertThrows(FileNotFoundException::class.java) {
            runBlocking {
                folder.write("ql-auto-20260917-000000-000-deadbeef.qlbackup", byteArrayOf(4, 5, 6))
            }
        }
        assertFalse(provider.documents.values.any { it.displayName.endsWith(".partial") })
    }

    @Test
    fun listingStopsAtThePublishedDocumentScanBound() = runBlocking {
        repeat(AndroidSafBackupFolder.MAX_DOCUMENT_SCAN + 2) { index ->
            provider.documents["archive-$index"] = StoredDocument(
                "archive-$index.qlbackup",
                byteArrayOf(1),
                1L,
            )
        }

        assertEquals(AndroidSafBackupFolder.MAX_DOCUMENT_SCAN, folder.list().size)
    }

    private class TestDocumentsProvider(private val testContext: Context) : DocumentsProvider() {
        val documents = linkedMapOf<String, StoredDocument>()
        var lastParentId: String? = null
        var openCount = 0
        var failOpen = false
        var failCreate = false
        var failDelete = false
        var failRename = false
        private var nextDocument = 0

        override fun onCreate(): Boolean = true

        override fun queryRoots(projection: Array<out String>?): Cursor =
            MatrixCursor(projection ?: ROOT_PROJECTION)

        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
            documentCursor(projection, documentId, documents[documentId])

        override fun queryChildDocuments(
            parentDocumentId: String,
            projection: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            check(parentDocumentId == ROOT_ID)
            val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
            documents.forEach { (id, document) -> addDocument(cursor, id, document) }
            return cursor
        }

        override fun openDocument(
            documentId: String,
            mode: String,
            signal: CancellationSignal?,
        ): ParcelFileDescriptor {
            openCount += 1
            if (failOpen) throw FileNotFoundException("injected open failure")
            val document = documents[documentId] ?: throw FileNotFoundException(documentId)
            val file = File.createTempFile("backup-saf-", ".tmp", testContext.cacheDir)
            file.writeBytes(document.bytes)
            file.deleteOnExit()
            val descriptorMode = if ('w' in mode) {
                ParcelFileDescriptor.MODE_WRITE_ONLY or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE
            } else {
                ParcelFileDescriptor.MODE_READ_ONLY
            }
            return ParcelFileDescriptor.open(file, descriptorMode)
        }

        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            if (failCreate) throw FileNotFoundException("injected create failure")
            val id = "created-${nextDocument++}"
            documents[id] = StoredDocument(displayName, byteArrayOf(), 0L)
            return id
        }

        override fun renameDocument(documentId: String, displayName: String): String {
            if (failRename) throw FileNotFoundException("injected rename failure")
            val existing = documents[documentId] ?: throw FileNotFoundException(documentId)
            documents[documentId] = existing.copy(displayName = displayName)
            return documentId
        }

        override fun deleteDocument(documentId: String) {
            if (failDelete) throw FileNotFoundException("injected delete failure")
            if (documents.remove(documentId) == null) throw FileNotFoundException(documentId)
        }

        override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
            lastParentId = parentDocumentId
            return parentDocumentId == ROOT_ID && documentId in documents
        }

        private fun documentCursor(
            projection: Array<out String>?,
            id: String,
            document: StoredDocument?,
        ): Cursor {
            val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
            if (document != null) addDocument(cursor, id, document)
            return cursor
        }

        private fun addDocument(cursor: MatrixCursor, id: String, document: StoredDocument) {
            val values = cursor.columnNames.associateWith { column ->
                when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> document.displayName
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> AndroidSafBackupFolder.MIME_TYPE
                    DocumentsContract.Document.COLUMN_SIZE -> document.size
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1L
                    else -> null
                }
            }
            cursor.addRow(cursor.columnNames.map(values::get))
        }
    }

    private data class StoredDocument(
        val displayName: String,
        val bytes: ByteArray,
        val size: Long?,
    )

    companion object {
        private const val AUTHORITY = "org.quicklauncher.test.documents"
        private const val ROOT_ID = "root"
        private val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        )
        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
