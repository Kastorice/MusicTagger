package com.musictag.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/**
 * All SAF / document I/O lives here: metadata queries, private cache copies and
 * writing edited files back through [ContentResolver] streams.
 */
class TrackFileStore(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val editDir: File = File(context.cacheDir, "edit").apply { mkdirs() }

    data class DocumentInfo(
        val displayName: String,
        val sizeBytes: Long?,
        val mimeType: String?,
    )

    class WriteBackException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Persist read/write access for a document picked with ACTION_OPEN_DOCUMENT. */
    fun takePersistablePermission(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        try {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some providers only grant temporary access; editing still works this session.
        }
    }

    fun queryInfo(uri: Uri): DocumentInfo {
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
        var size: Long? = null
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                            displayName = cursor.getString(nameIndex) ?: displayName
                        }
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (_: Exception) {
                // Keep the URI-derived name.
            }
        } else if (uri.scheme == "file") {
            uri.path?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    displayName = file.name
                    size = file.length()
                }
            }
        }

        val mime = try {
            resolver.getType(uri)
        } catch (_: Exception) {
            null
        } ?: MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(displayName.substringAfterLast('.', "").lowercase())

        return DocumentInfo(displayName = displayName, sizeBytes = size, mimeType = mime)
    }

    /** Copies a document into the private cache so the tag engine can rewrite it. */
    fun copyToCache(uri: Uri, displayName: String): File {
        val safeName = displayName
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .take(96)
            .ifBlank { "track" }
        val target = File(editDir, "${System.currentTimeMillis()}_$safeName")
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: throw IOException("无法读取文件")
        } catch (e: Exception) {
            target.delete()
            throw IOException("无法读取文件：${e.message}", e)
        }
        return target
    }

    /**
     * Overwrites the original document with [source].
     *
     * Tries truncating write first, then random-access truncating write. Providers
     * that deny writes (read-only documents, cloud providers) raise
     * [WriteBackException] so the UI can offer "save as a new file".
     */
    fun writeBack(uri: Uri, source: File) {
        var lastError: Exception? = null
        for (mode in listOf("wt", "rwt")) {
            try {
                val output = resolver.openOutputStream(uri, mode)
                    ?: throw IOException("无法打开输出流")
                output.use { out ->
                    source.inputStream().use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
                    out.flush()
                }
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw WriteBackException(
            "原文件无法写入（可能只读或由云端提供）",
            lastError,
        )
    }

    /** Writes [source] into a new document created via ACTION_CREATE_DOCUMENT. */
    fun writeToNewDocument(target: Uri, source: File) {
        try {
            val output = resolver.openOutputStream(target, "wt") ?: throw IOException("无法创建文件")
            output.use { out ->
                source.inputStream().use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
                out.flush()
            }
        } catch (e: Exception) {
            throw IOException("无法写入新文件：${e.message}", e)
        }
    }

    fun readBytes(uri: Uri, maxBytes: Long = 24L * 1024 * 1024): ByteArray? {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                val output = java.io.ByteArrayOutputStream()
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw IOException("图片文件过大")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun deleteCache(file: File?) {
        if (file == null) return
        try {
            if (file.parentFile == editDir) file.delete()
        } catch (_: Exception) {
        }
    }

    /** Removes cache copies older than [maxAgeMillis] (called on app start). */
    fun pruneCache(maxAgeMillis: Long = 3L * 24 * 60 * 60 * 1000) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        editDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}
