package dev.mono.storage.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import dev.mono.storage.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Exposes the app's internal Mono tree (projects/ and modules/) to the system
 * Files app and to any other editor through SAF. The root shows up as
 * "Mono" in the Files app side pane; users can browse, open, edit, copy,
 * move, rename and delete files there, and Mono picks the changes up live
 * through [dev.mono.storage.fs.StorageWatcher].
 */
class MonoProvider : DocumentsProvider() {

    private val rootDir: File
        get() = File(context!!.filesDir, "Mono")

    /** Dedicated thread for write-close callbacks (created lazily). */
    private var closeThread: HandlerThread? = null

    override fun onCreate(): Boolean {
        rootDir.mkdirs()
        File(rootDir, "projects").mkdirs()
        File(rootDir, "modules").mkdirs()
        return true
    }

    // --------------------------------------------------------------- queries

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(resolveRootProjection(projection))
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_AVAILABLE_BYTES, rootDir.usableSpace)
            add(Root.COLUMN_CAPACITY_BYTES, rootDir.totalSpace)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE
            )
            add(Root.COLUMN_ICON, R.drawable.ic_doc_root)
            add(Root.COLUMN_TITLE, "Mono")
            add(Root.COLUMN_SUMMARY, "Mono projects and modules")
            add(Root.COLUMN_DOCUMENT_ID, DOC_ROOT)
            add(Root.COLUMN_MIME_TYPES, "text/x-c++src text/x-chdr text/x-csrc text/plain")
        }
        return result
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        if (documentId == null) return false
        if (documentId == DOC_ROOT) return parentDocumentId == null
        if (parentDocumentId == null) return true
        return documentId.startsWith("$parentDocumentId/")
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(resolveDocumentProjection(projection))
        includeFile(result, documentId ?: DOC_ROOT, fileFor(documentId ?: DOC_ROOT))
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(resolveDocumentProjection(projection))
        val parent = fileFor(parentDocumentId ?: DOC_ROOT)
        val children = parent.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: emptyList()
        // Hidden files stay hidden — and so do *.mono-tmp scratch leftovers
        // from interrupted writes of earlier app versions.
        for (child in children.filterNot {
            it.name.startsWith(".") || it.name.endsWith(TMP_SUFFIX)
        }) {
            includeFile(result, docIdFor(child), child)
        }
        return result
    }

    // ------------------------------------------------------------------- io

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = fileFor(documentId ?: throw FileNotFoundException(documentId))
        val accessMode = ParcelFileDescriptor.parseMode(mode ?: "r")
        val isWrite = mode?.any { it == 'w' || it == 'a' || it == 't' || it == '+' } == true
        if (!isWrite) {
            return ParcelFileDescriptor.open(file, accessMode)
        }
        // Writes go straight to the file. The close listener is how we learn
        // that the remote writer has finished — the notifyChange is what
        // makes external edits appear live in Mono (the StorageWatcher
        // reacts on the filesystem side as well).
        return ParcelFileDescriptor.open(
            file,
            accessMode,
            closeHandler(),
            ParcelFileDescriptor.OnCloseListener { _ ->
                // Called with an IOException only when the writer died
                // without closing cleanly; the content may still have
                // changed, so notify either way.
                notifySelf(documentId)
            }
        )
    }

    // -------------------------------------------------------------- mutate

    override fun createDocument(
        parentDocumentId: String?,
        mimeType: String?,
        displayName: String?
    ): String? {
        val name = safeName(displayName) ?: return null
        val parent = fileFor(parentDocumentId ?: return null)
        if (!parent.isDirectory) return null
        val file = File(parent, name)
        if (mimeType == Document.MIME_TYPE_DIR) {
            if (!file.mkdirs() && !file.isDirectory) return null
        } else {
            file.parentFile?.mkdirs()
            if (!file.createNewFile() && !file.isFile) return null
        }
        notifySelf(docIdFor(file))
        return docIdFor(file)
    }

    override fun renameDocument(documentId: String?, displayName: String?): String? {
        if (documentId == null || documentId == DOC_ROOT) return null
        val name = safeName(displayName) ?: return null
        val file = fileFor(documentId)
        val parent = file.parentFile ?: return null
        val target = File(parent, name)
        if (target.exists()) return null
        if (!file.renameTo(target)) return null
        notifySelf(docIdFor(target))
        return docIdFor(target)
    }

    override fun copyDocument(
        sourceDocumentId: String?,
        targetParentDocumentId: String?
    ): String? {
        if (sourceDocumentId == null || targetParentDocumentId == null) return null
        val source = fileFor(sourceDocumentId)
        val targetParent = fileFor(targetParentDocumentId)
        if (!source.exists() || !targetParent.isDirectory) return null
        val name = source.name
        val target = File(targetParent, name)
        if (target.exists()) return null
        return try {
            if (source.isDirectory) {
                source.copyRecursively(target, overwrite = false)
            } else {
                source.copyTo(target, overwrite = false)
            }
            notifySelf(docIdFor(target))
            docIdFor(target)
        } catch (_: Exception) {
            // copyRecursively wraps I/O failures in FileTreeWalkException
            // (a RuntimeException); clean up any half-written copy.
            runCatching { target.deleteRecursively() }
            null
        }
    }

    override fun moveDocument(
        sourceDocumentId: String?,
        sourceParentDocumentId: String?,
        targetParentDocumentId: String?
    ): String? {
        if (sourceDocumentId == null || targetParentDocumentId == null) return null
        if (sourceDocumentId == DOC_ROOT) return null
        val source = fileFor(sourceDocumentId)
        val targetParent = fileFor(targetParentDocumentId)
        if (!source.exists() || !targetParent.isDirectory) return null
        val name = source.name
        val target = File(targetParent, name)
        if (target.exists()) return null
        if (!source.renameTo(target)) return null
        // The old location lost a child and the new one gained one.
        notifySelf(sourceDocumentId)
        notifySelf(docIdFor(target))
        return docIdFor(target)
    }

    override fun deleteDocument(documentId: String?) {
        if (documentId == null || documentId == DOC_ROOT) return
        val file = fileFor(documentId)
        file.deleteRecursively()
        notifySelf(documentId)
    }

    override fun getDocumentType(documentId: String?): String {
        val file = fileFor(documentId ?: return Document.MIME_TYPE_DIR)
        return typeFor(file)
    }

    // -------------------------------------------------------------- helpers

    /**
     * Resolves a document id to a file inside the exposed root. The check is
     * done on the canonical path (which resolves "..", "." and symlinks), so
     * ids like "projects/../../secrets" are rejected instead of walking out
     * of the Mono tree.
     */
    private fun fileFor(documentId: String): File {
        if (documentId == DOC_ROOT) return rootDir
        val rootPath = rootDir.canonicalPath
        val candidate = File(rootDir, documentId.trimStart('/'))
        val canonical = try {
            candidate.canonicalPath
        } catch (_: IOException) {
            throw FileNotFoundException(documentId)
        }
        if (canonical != rootPath && !canonical.startsWith(rootPath + File.separator)) {
            throw FileNotFoundException(documentId)
        }
        return candidate
    }

    private fun docIdFor(file: File): String {
        val rootPath = rootDir.canonicalPath
        val path = try {
            file.canonicalPath
        } catch (_: IOException) {
            file.absolutePath
        }
        return if (path == rootPath) DOC_ROOT
        else path.removePrefix(rootPath).trimStart('/')
    }

    /**
     * Validates a caller-supplied display name: non-empty, no path
     * separators, no traversal, no NUL bytes. [fileFor]'s canonical-path
     * check guards document ids; this guards names that have no id yet.
     */
    private fun safeName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        if (name == "." || name == "..") return null
        if ('/' in name || '\\' in name || '\u0000' in name) return null
        return name
    }

    @Synchronized
    private fun closeHandler(): Handler {
        val thread = closeThread ?: HandlerThread("mono-provider-close").also {
            it.start()
            closeThread = it
        }
        return Handler(thread.looper)
    }

    private fun includeFile(result: MatrixCursor, docId: String, file: File) {
        val flags = Document.FLAG_SUPPORTS_WRITE or
                Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_COPY or
                Document.FLAG_SUPPORTS_MOVE
        var actualFlags = flags
        if (file.isDirectory) {
            actualFlags = actualFlags or Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE
        }
        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_MIME_TYPE, typeFor(file))
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, actualFlags)
            if (file == rootDir) {
                add(Document.COLUMN_SUMMARY, null)
            }
        }
    }

    private fun typeFor(file: File): String = when {
        file.isDirectory -> Document.MIME_TYPE_DIR
        file.name.endsWith(".cpp", true) || file.name.endsWith(".cc", true) || file.name.endsWith(".cxx", true) -> "text/x-c++src"
        file.name.endsWith(".c", true) -> "text/x-csrc"
        file.name.endsWith(".h", true) || file.name.endsWith(".hpp", true) -> "text/x-chdr"
        file.name.endsWith(".txt", true) || file.name.endsWith(".md", true) -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun notifySelf(documentId: String?) {
        val uri = documentId?.let { uriFor(it) } ?: return
        context?.contentResolver?.notifyChange(uri, null)
    }

    private fun resolveRootProjection(projection: Array<out String>?): Array<out String> =
        projection ?: DEFAULT_ROOT_PROJECTION

    private fun resolveDocumentProjection(projection: Array<out String>?): Array<out String> =
        projection ?: DEFAULT_DOCUMENT_PROJECTION

    companion object {
        const val AUTHORITY = "dev.mono.app.documents"
        const val ROOT_ID = "mono"
        const val DOC_ROOT = "root:"

        /** Scratch names used for atomic writes; never listed by the provider. */
        const val TMP_SUFFIX = ".mono-tmp"

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_CAPACITY_BYTES,
            Root.COLUMN_MIME_TYPES
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS
        )

        fun uriFor(relPath: String): Uri =
            DocumentsContract.buildDocumentUri(AUTHORITY, relPath.trimStart('/'))
    }
}
