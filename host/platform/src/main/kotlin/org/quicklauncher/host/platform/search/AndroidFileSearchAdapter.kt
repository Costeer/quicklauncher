package org.quicklauncher.host.platform.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.contribution.SearchQuery
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.host.runtime.search.SearchExecutionResult
import org.quicklauncher.host.runtime.search.SearchExecutionTarget

data class FileSearchRecord(
    val authorizationId: StableKey,
    val documentId: StableKey,
    val displayName: String,
    val mimeType: String?,
) {
    override fun toString(): String = "FileSearchRecord(content=redacted)"
}

sealed interface FileSearchResult {
    class Available(records: Collection<FileSearchRecord>) : FileSearchResult {
        val records: List<FileSearchRecord> = java.util.Collections.unmodifiableList(ArrayList(records))
    }
    data object Denied : FileSearchResult
    data object Unavailable : FileSearchResult
}

interface FileSearchSource {
    suspend fun query(sessionId: Long, query: SearchQuery): FileSearchResult
    fun releaseSession(sessionId: Long)
}

class AndroidFileSearchAdapter(context: Context) : FileTargetLauncher, FileSearchSource {
    private val context = context.applicationContext
    private val targets = ConcurrentHashMap<TargetId, AuthorizedDocument>()

    override suspend fun query(
        sessionId: Long,
        query: SearchQuery,
    ): FileSearchResult = withContext(Dispatchers.IO) {
        require(sessionId > 0L) { "File search session identity must be positive" }
        val grants = context.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission && it.uri.scheme == "content" }
            .take(MAX_ROOTS)
            .toList()
        if (grants.isEmpty()) return@withContext FileSearchResult.Denied
        try {
            val nextTargets = LinkedHashMap<TargetId, AuthorizedDocument>()
            val values = buildList {
                grants.forEach { grant ->
                    if (size >= MAX_RESULTS) return@forEach
                    val records = if (DocumentsContract.isTreeUri(grant.uri)) {
                        queryTree(grant.uri, query)
                    } else {
                        queryDocument(grant.uri, query)?.let(::listOf).orEmpty()
                    }
                    records.forEach { document ->
                        if (size >= MAX_RESULTS) return@forEach
                        val id = targetId(sessionId, grant.uri, document.uri)
                        nextTargets[id] = AuthorizedDocument(grant.uri, document.uri, document.mimeType)
                        add(FileSearchRecord(id.authorizationId, id.documentId, document.name, document.mimeType))
                    }
                }
            }
            targets.keys.removeAll { it.sessionId == sessionId }
            targets.putAll(nextTargets)
            FileSearchResult.Available(values)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            targets.keys.removeAll { it.sessionId == sessionId }
            FileSearchResult.Denied
        } catch (_: RuntimeException) {
            targets.keys.removeAll { it.sessionId == sessionId }
            FileSearchResult.Unavailable
        }
    }

    override suspend fun launch(target: SearchExecutionTarget.File): SearchExecutionResult {
        val id = TargetId(target.sessionId, target.authorizationId, target.documentId)
        val document = targets[id] ?: return SearchExecutionResult.Unavailable
        val stillGranted = withContext(Dispatchers.IO) {
            context.contentResolver.persistedUriPermissions.any {
                it.isReadPermission && it.uri == document.root
            }
        }
        if (!stillGranted) {
            targets.remove(id)
            return SearchExecutionResult.MissingAccess
        }
        val current = try {
            withContext(Dispatchers.IO) {
                queryMetadata(document.uri)
            }
        } catch (_: SecurityException) {
            return SearchExecutionResult.MissingAccess
        } ?: return SearchExecutionResult.Unavailable
        if (current.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            return SearchExecutionResult.Rejected
        }
        if (targets[id] != document) return SearchExecutionResult.Unavailable
        return withContext(Dispatchers.Main.immediate) {
            val intent = Intent(Intent.ACTION_VIEW, document.uri).apply {
                setDataAndType(document.uri, current.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
                return@withContext SearchExecutionResult.Unavailable
            }
            try {
                context.startActivity(intent)
                SearchExecutionResult.Succeeded
            } catch (_: ActivityNotFoundException) {
                SearchExecutionResult.Unavailable
            } catch (_: SecurityException) {
                SearchExecutionResult.MissingAccess
            }
        }
    }

    override fun releaseSession(sessionId: Long) {
        targets.keys.removeAll { it.sessionId == sessionId }
    }

    private suspend fun queryTree(root: Uri, query: SearchQuery): List<DocumentMetadata> {
        val providerResults = try {
            queryProviderSearch(root, query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            null
        }
        if (providerResults != null) return providerResults

        val rootDocumentId = DocumentsContract.getTreeDocumentId(root)
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(root, rootDocumentId)
        val queue = ArrayDeque<TreeEntry>()
        queue.add(TreeEntry(rootDocument, 0))
        val visited = LinkedHashSet<String>()
        val results = mutableListOf<DocumentMetadata>()
        while (queue.isNotEmpty() && visited.size < MAX_VISITED && results.size < MAX_RESULTS) {
            currentCoroutineContext().ensureActive()
            val entry = queue.removeFirst()
            val documentId = DocumentsContract.getDocumentId(entry.uri)
            if (!visited.add(documentId)) continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(root, documentId)
            queryChildren(childrenUri).forEach { child ->
                if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (entry.depth < MAX_DEPTH) queue.add(TreeEntry(child.uri, entry.depth + 1))
                } else if (child.name.contains(query.value.trim(), ignoreCase = true)) {
                    results += child
                }
            }
        }
        return results
    }

    private suspend fun queryChildren(uri: Uri): List<DocumentMetadata> = queryCursor(uri) { cursor ->
        buildList {
            while (cursor.moveToNext() && size < MAX_CHILDREN_PER_DIRECTORY) {
                currentCoroutineContext().ensureActive()
                metadata(cursor, uri)?.let(::add)
            }
        }
    }

    private suspend fun queryProviderSearch(
        root: Uri,
        query: SearchQuery,
    ): List<DocumentMetadata>? {
        val authority = root.authority?.takeIf(String::isNotBlank) ?: return null
        val treeDocumentId = DocumentsContract.getTreeDocumentId(root)
        val rootsUri = DocumentsContract.buildRootsUri(authority)
        val rootId = queryCursor(rootsUri, ROOT_PROJECTION, MAX_ROOTS) { cursor ->
            val rootIdIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_ROOT_ID)
            val documentIdIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
            val flagsIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_FLAGS)
            var matchingRoot: String? = null
            while (cursor.moveToNext() && matchingRoot == null) {
                currentCoroutineContext().ensureActive()
                if (cursor.getString(documentIdIndex) == treeDocumentId &&
                    cursor.getInt(flagsIndex) and DocumentsContract.Root.FLAG_SUPPORTS_SEARCH != 0
                ) {
                    matchingRoot = cursor.getString(rootIdIndex)?.takeIf(String::isNotBlank)
                }
            }
            matchingRoot
        } ?: return null
        val searchUri = DocumentsContract.buildSearchDocumentsUri(authority, rootId, query.value.trim())
        return queryCursor(searchUri, PROJECTION, MAX_RESULTS) { cursor ->
            buildList {
                while (cursor.moveToNext() && size < MAX_RESULTS) {
                    currentCoroutineContext().ensureActive()
                    metadata(cursor, root)?.takeUnless {
                        it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                    }?.let(::add)
                }
            }
        }
    }

    private suspend fun queryDocument(uri: Uri, query: SearchQuery): DocumentMetadata? {
        val value = queryMetadata(uri) ?: return null
        return value.takeIf {
            it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR &&
                it.name.contains(query.value.trim(), ignoreCase = true)
        }
    }

    private suspend fun queryMetadata(uri: Uri): DocumentMetadata? =
        queryCursor(uri) { cursor -> if (cursor.moveToFirst()) metadata(cursor, uri) else null }

    private suspend fun <T> queryCursor(
        uri: Uri,
        projection: Array<String> = PROJECTION,
        limit: Int = MAX_CHILDREN_PER_DIRECTORY,
        read: suspend (Cursor) -> T,
    ): T {
        val signal = android.os.CancellationSignal()
        val job = currentCoroutineContext().job
        val registration = job.invokeOnCompletion { signal.cancel() }
        try {
            val args = Bundle().apply {
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
            }
            val cursor = context.contentResolver.query(uri, projection, args, signal)
                ?: throw IllegalStateException("Document provider returned no cursor")
            return cursor.use { read(it) }
        } finally {
            registration.dispose()
        }
    }

    private fun metadata(cursor: Cursor, parent: Uri): DocumentMetadata? {
        val id = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
            ?.takeIf(String::isNotBlank) ?: return null
        val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            ?.takeIf(String::isNotBlank)?.take(MAX_TEXT_LENGTH) ?: return null
        val mime = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
            ?.takeIf { it.isNotBlank() && it.length <= MAX_MIME_LENGTH }
        val documentUri = if (DocumentsContract.isTreeUri(parent)) {
            DocumentsContract.buildDocumentUriUsingTree(parent, id)
        } else {
            parent
        }
        return DocumentMetadata(documentUri, name, mime)
    }

    private fun targetId(sessionId: Long, root: Uri, document: Uri): TargetId = TargetId(
        sessionId,
        StableKey.parse("a-${digest(root.toString())}"),
        StableKey.parse("d-${digest(document.toString())}"),
    )

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class TargetId(
        val sessionId: Long,
        val authorizationId: StableKey,
        val documentId: StableKey,
    )
    private data class AuthorizedDocument(val root: Uri, val uri: Uri, val mimeType: String?)
    private data class DocumentMetadata(val uri: Uri, val name: String, val mimeType: String?)
    private data class TreeEntry(val uri: Uri, val depth: Int)

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
        )
        const val MAX_ROOTS = 16
        const val MAX_DEPTH = 3
        const val MAX_VISITED = 200
        const val MAX_RESULTS = 25
        const val MAX_CHILDREN_PER_DIRECTORY = 100
        const val MAX_TEXT_LENGTH = 500
        const val MAX_MIME_LENGTH = 200
    }
}
