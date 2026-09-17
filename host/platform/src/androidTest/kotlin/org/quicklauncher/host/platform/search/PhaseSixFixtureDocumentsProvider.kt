package org.quicklauncher.host.platform.search

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.FileNotFoundException

/** Metadata-only disposable provider used to verify real persisted SAF grants. */
class PhaseSixFixtureDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_PROJECTION).apply {
            addRow(
                columnNames.map { column ->
                    when (column) {
                        DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT_ID
                        DocumentsContract.Root.COLUMN_DOCUMENT_ID -> DOCUMENT_ID
                        DocumentsContract.Root.COLUMN_TITLE -> "Phase 6 fixture"
                        DocumentsContract.Root.COLUMN_FLAGS -> DocumentsContract.Root.FLAG_SUPPORTS_SEARCH
                        DocumentsContract.Root.COLUMN_MIME_TYPES -> MIME_TYPE
                        else -> null
                    }
                },
            )
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        if (documentId != DOCUMENT_ID) throw FileNotFoundException()
        return documentCursor(projection)
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor = if (rootId == ROOT_ID && DISPLAY_NAME.contains(query, ignoreCase = true)) {
        documentCursor(projection)
    } else {
        MatrixCursor(projection ?: DOCUMENT_PROJECTION)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = throw FileNotFoundException("Metadata-only fixture")

    override fun getDocumentType(documentId: String): String {
        if (documentId != DOCUMENT_ID) throw FileNotFoundException()
        return MIME_TYPE
    }

    private fun documentCursor(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_PROJECTION).apply {
            addRow(
                columnNames.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> DOCUMENT_ID
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> DISPLAY_NAME
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> MIME_TYPE
                        DocumentsContract.Document.COLUMN_FLAGS -> 0
                        DocumentsContract.Document.COLUMN_SIZE -> 0L
                        else -> null
                    }
                },
            )
        }

    companion object {
        const val AUTHORITY = "org.quicklauncher.host.platform.test.phase6.documents"
        const val ROOT_ID = "phase6-root"
        const val DOCUMENT_ID = "phase6-document"
        const val DISPLAY_NAME = "phase6-fixture.txt"
        const val MIME_TYPE = "text/plain"

        private val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
        )
        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
