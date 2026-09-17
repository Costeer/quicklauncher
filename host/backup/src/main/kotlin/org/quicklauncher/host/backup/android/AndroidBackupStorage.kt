package org.quicklauncher.host.backup.android

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.quicklauncher.host.backup.library.BackupDocument
import org.quicklauncher.host.backup.library.BackupFolder

/** SAF tree adapter. Document identities stay opaque outside this implementation. */
class AndroidSafBackupFolder(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : BackupFolder {
    override suspend fun list(): List<BackupDocument> = withContext(Dispatchers.IO) {
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = ArrayList<BackupDocument>()
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            var scanned = 0
            while (scanned < MAX_DOCUMENT_SCAN && cursor.moveToNext()) {
                scanned += 1
                coroutineContext.ensureActive()
                val documentId = cursor.getString(0) ?: continue
                val displayName = cursor.getString(1) ?: continue
                val mimeType = cursor.getString(2) ?: continue
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR ||
                    !displayName.endsWith(EXTENSION)
                ) continue
                val size = if (cursor.isNull(3)) null else cursor.getLong(3).coerceAtLeast(0L)
                val modified = if (cursor.isNull(4)) 0L else cursor.getLong(4).coerceAtLeast(0L)
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                result += BackupDocument(uri.toString(), displayName, size, modified)
            }
        }
        result
    }

    override suspend fun read(id: String, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        require(maxBytes > 0)
        val uri = validatedChild(id) ?: return@withContext null
        resolver.openInputStream(uri)?.use { input ->
            val sink = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) return@withContext null
                sink.write(buffer, 0, count)
            }
            sink.toByteArray()
        }
    }

    override suspend fun write(displayName: String, bytes: ByteArray): BackupDocument =
        withContext(Dispatchers.IO) {
            require(displayName.matches(FILE_NAME)) { "Invalid backup display name" }
            val parentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            val partialName = "$displayName.partial"
            val partial = checkNotNull(
                DocumentsContract.createDocument(resolver, parent, MIME_TYPE, partialName),
            ) { "The selected folder did not create the backup document" }
            try {
                resolver.openOutputStream(partial, "w")?.use { output ->
                    var offset = 0
                    while (offset < bytes.size) {
                        coroutineContext.ensureActive()
                        val count = minOf(DEFAULT_BUFFER_SIZE, bytes.size - offset)
                        output.write(bytes, offset, count)
                        offset += count
                    }
                    output.flush()
                } ?: error("The selected folder did not open the backup document")
                val completed = DocumentsContract.renameDocument(resolver, partial, displayName)
                    ?: error("The selected folder did not finalize the backup document")
                BackupDocument(completed.toString(), displayName, bytes.size.toLong(), System.currentTimeMillis())
            } catch (failure: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, partial) }
                throw failure
            }
        }

    override suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val uri = validatedChild(id) ?: return@withContext false
        DocumentsContract.deleteDocument(resolver, uri)
    }

    private fun validatedChild(id: String): Uri? {
        val uri = runCatching { Uri.parse(id) }.getOrNull() ?: return null
        val parent = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        }.getOrNull() ?: return null
        return if (runCatching { DocumentsContract.isChildDocument(resolver, parent, uri) }.getOrDefault(false)) {
            uri
        } else {
            null
        }
    }

    companion object {
        const val MIME_TYPE = "application/vnd.quicklauncher.backup"
        const val MAX_DOCUMENT_SCAN = 2_048
        private const val EXTENSION = ".qlbackup"
        private val FILE_NAME = Regex("ql-(manual|auto|pre-restore)-[A-Za-z0-9.-]+\\.qlbackup")
    }
}

data class BackupFolderAuthorization(
    val treeUri: Uri,
    val automaticEnabled: Boolean,
)

/** Persists only the user-granted tree URI and scheduling choice; passphrases are never stored. */
class AndroidBackupAuthorizationStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    fun current(): BackupFolderAuthorization? {
        val value = preferences.getString(KEY_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(value) }.getOrNull()?.let { uri ->
            BackupFolderAuthorization(uri, preferences.getBoolean(KEY_AUTOMATIC, false))
        }
    }

    fun authorize(uri: Uri): BackupFolderAuthorization {
        val previous = current()?.treeUri
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        if (!preferences.edit().putString(KEY_TREE_URI, uri.toString()).commit()) {
            if (previous != uri) {
                runCatching {
                    resolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
            error("Backup folder authorization could not be persisted")
        }
        if (previous != null && previous != uri) {
            runCatching {
                resolver.releasePersistableUriPermission(
                    previous,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        return BackupFolderAuthorization(uri, preferences.getBoolean(KEY_AUTOMATIC, false))
    }

    fun setAutomaticEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTOMATIC, enabled).apply()
    }

    fun clear() {
        val current = current()
        preferences.edit().clear().apply()
        current?.let { authorization ->
            runCatching {
                resolver.releasePersistableUriPermission(
                    authorization.treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    companion object {
        private const val PREFERENCES = "quicklauncher-backup-authorization"
        private const val KEY_TREE_URI = "tree-uri"
        private const val KEY_AUTOMATIC = "automatic-enabled"
    }
}

/** Registers the next persisted, charging-only job for the local nightly window. */
class AndroidChargingBackupScheduler(
    private val scheduler: JobScheduler,
    private val service: ComponentName,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun isInNightlyWindow(): Boolean = NightlyBackupSchedule.isInRunWindow(clock)

    fun schedule(): Boolean = scheduler.schedule(
        JobInfo.Builder(JOB_ID, service)
            .setRequiresCharging(true)
            .setPersisted(true)
            .setMinimumLatency(NightlyBackupSchedule.delayUntilNextRunMillis(clock))
            .build(),
    ) == JobScheduler.RESULT_SUCCESS

    fun cancel() = scheduler.cancel(JOB_ID)

    companion object {
        const val JOB_ID = 0x514c08
    }
}

/** Wall-clock policy for one backup opportunity per local night. */
internal object NightlyBackupSchedule {
    const val START_HOUR = 3
    const val END_HOUR = 6
    private val startTime = LocalTime.of(START_HOUR, 0)
    private val endTime = LocalTime.of(END_HOUR, 0)

    fun isInRunWindow(clock: Clock): Boolean {
        val time = ZonedDateTime.now(clock).toLocalTime()
        return !time.isBefore(startTime) && time.isBefore(endTime)
    }

    fun delayUntilNextRunMillis(clock: Clock): Long {
        val now = ZonedDateTime.now(clock)
        val todayStart = now.toLocalDate().atTime(startTime).atZone(now.zone)
        val nextStart = if (now.isBefore(todayStart)) todayStart else todayStart.plusDays(1)
        return Duration.between(now, nextStart).toMillis().coerceAtLeast(1L)
    }
}
