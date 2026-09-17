package org.quicklauncher.host.platform.theme

import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.FileNotFoundException
import java.io.File
import org.quicklauncher.host.runtime.theme.MAX_IMAGE_BYTES

/** Synthetic generated-image provider; it never contains user or installed-app data. */
class PhaseSevenFixtureDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_PROJECTION).apply {
            addRow(row(columnNames, ROOT_DOCUMENT))
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_PROJECTION).apply {
            if (documentId !in setOf(
                    ROOT_DOCUMENT,
                    IMAGE_DOCUMENT,
                    MALFORMED_DOCUMENT,
                    REVOKED_DOCUMENT,
                    FONT_DOCUMENT,
                    ORIENTED_IMAGE_DOCUMENT,
                    MALFORMED_IMAGE_DOCUMENT,
                    OVERSIZED_IMAGE_DOCUMENT,
                    OVERSIZED_DIMENSION_IMAGE_DOCUMENT,
                    UNSUPPORTED_DOCUMENT,
                )
            ) {
                throw FileNotFoundException()
            }
            addRow(row(columnNames, documentId))
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION).apply {
        if (parentDocumentId == ROOT_DOCUMENT) {
            addRow(row(columnNames, IMAGE_DOCUMENT))
            addRow(row(columnNames, MALFORMED_DOCUMENT))
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val bytes = when (documentId) {
            IMAGE_DOCUMENT -> syntheticPng()
            ORIENTED_IMAGE_DOCUMENT -> orientedJpeg()
            MALFORMED_IMAGE_DOCUMENT -> ByteArray(256) { 0x41 }
            OVERSIZED_DIMENSION_IMAGE_DOCUMENT -> oversizedDimensionPng()
            OVERSIZED_IMAGE_DOCUMENT -> return oversizedPipe()
            UNSUPPORTED_DOCUMENT -> ByteArray(1)
            MALFORMED_DOCUMENT -> ByteArray(256) { 0x41 }
            FONT_DOCUMENT -> return ParcelFileDescriptor.open(
                File(SYSTEM_FONT_FIXTURE),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            else -> throw FileNotFoundException()
        }
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
        }.start()
        return pipe[0]
    }

    override fun getDocumentType(documentId: String): String = when (documentId) {
        IMAGE_DOCUMENT -> "image/png"
        ORIENTED_IMAGE_DOCUMENT -> "image/jpeg"
        MALFORMED_IMAGE_DOCUMENT -> "image/png"
        OVERSIZED_IMAGE_DOCUMENT -> "image/png"
        OVERSIZED_DIMENSION_IMAGE_DOCUMENT -> "image/png"
        UNSUPPORTED_DOCUMENT -> "application/octet-stream"
        MALFORMED_DOCUMENT -> "font/ttf"
        REVOKED_DOCUMENT -> "font/ttf"
        FONT_DOCUMENT -> "font/ttf"
        else -> DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun row(columns: Array<String>, documentId: String): Array<Any?> = columns.map { column ->
        when (column) {
            DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT_ID
            DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT_DOCUMENT
            DocumentsContract.Root.COLUMN_TITLE -> "Phase 7 synthetic fixtures"
            DocumentsContract.Root.COLUMN_MIME_TYPES -> "image/png\nfont/ttf"
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> "synthetic"
            DocumentsContract.Document.COLUMN_MIME_TYPE -> getDocumentType(documentId)
            DocumentsContract.Document.COLUMN_FLAGS -> 0
            else -> null
        }
    }.toTypedArray()

    private fun syntheticPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(20, 80, 140))
            java.io.ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun orientedJpeg(): ByteArray {
        val file = File(requireNotNull(context).cacheDir, "phase7-synthetic-oriented-image")
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(120, 40, 80))
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            }
            ExifInterface(file.absolutePath).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_ROTATE_90.toString(),
                )
                saveAttributes()
            }
            file.readBytes()
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    private fun oversizedDimensionPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(8_193, 1, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.BLACK)
            java.io.ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun oversizedPipe(): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    val chunk = ByteArray(16 * 1024)
                    var remaining = MAX_IMAGE_BYTES + 1
                    while (remaining > 0) {
                        val count = minOf(chunk.size, remaining)
                        output.write(chunk, 0, count)
                        remaining -= count
                    }
                }
            }
        }.start()
        return pipe[0]
    }

    companion object {
        const val AUTHORITY = "org.quicklauncher.host.platform.test.phase7.documents"
        const val ROOT_ID = "phase7-root"
        const val ROOT_DOCUMENT = "phase7-root-document"
        const val IMAGE_DOCUMENT = "phase7-image"
        const val MALFORMED_DOCUMENT = "phase7-malformed"
        const val REVOKED_DOCUMENT = "phase7-revoked"
        const val FONT_DOCUMENT = "phase7-font"
        const val ORIENTED_IMAGE_DOCUMENT = "phase7-oriented-image"
        const val MALFORMED_IMAGE_DOCUMENT = "phase7-malformed-image"
        const val OVERSIZED_IMAGE_DOCUMENT = "phase7-oversized-image"
        const val OVERSIZED_DIMENSION_IMAGE_DOCUMENT = "phase7-oversized-dimension-image"
        const val UNSUPPORTED_DOCUMENT = "phase7-unsupported"
        private const val SYSTEM_FONT_FIXTURE = "/system/fonts/RobotoStatic-Regular.ttf"

        private val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
        )
        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
