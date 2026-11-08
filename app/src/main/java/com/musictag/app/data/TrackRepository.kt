package com.musictag.app.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.musictag.app.data.model.AddTracksResult
import com.musictag.app.data.model.LoadedTrack
import com.musictag.app.data.model.OpenedTrack
import com.musictag.app.data.model.SaveOutcome
import com.musictag.app.data.model.TrackItem
import com.musictag.core.tag.Artwork
import com.musictag.core.tag.TagDocument
import com.musictag.core.tag.TagEditor
import com.musictag.core.tag.TagException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Orchestrates library persistence, metadata extraction and tag editing by
 * combining SAF I/O ([TrackFileStore]) with the pure JVM tag engine.
 */
class TrackRepository(
    private val context: Context,
    private val fileStore: TrackFileStore,
    private val libraryStore: LibraryStore,
    private val artworkRepository: ArtworkRepository,
) {

    fun libraryItems(): List<TrackItem> = libraryStore.load()

    suspend fun addUris(uris: List<Uri>): AddTracksResult = withContext(Dispatchers.IO) {
        val added = mutableListOf<TrackItem>()
        val rejected = mutableListOf<String>()
        for (uri in uris) {
            fileStore.takePersistablePermission(uri)
            val info = fileStore.queryInfo(uri)
            val formatName = formatNameFor(info.displayName)
            if (formatName == null) {
                rejected += info.displayName
                continue
            }
            val described = try {
                describe(uri, info)
            } catch (_: Exception) {
                null
            }
            if (described == null) {
                rejected += info.displayName
                continue
            }
            added += described
        }

        val existing = libraryStore.load().filterNot { item -> added.any { it.uri == item.uri } }
        val merged = (added + existing).distinctBy { it.uri }
        libraryStore.save(merged)
        AddTracksResult(added = added, rejectedNames = rejected)
    }

    suspend fun describe(uri: Uri): TrackItem = withContext(Dispatchers.IO) {
        describe(uri, fileStore.queryInfo(uri))
    }

    /** Refreshes a single item; returns the existing item when the document is gone. */
    suspend fun refreshItem(item: TrackItem): TrackItem = withContext(Dispatchers.IO) {
        try {
            describe(item.uriObject).copy(modifiedAt = System.currentTimeMillis())
        } catch (_: Exception) {
            item
        }
    }

    suspend fun openForEdit(uri: Uri): OpenedTrack = withContext(Dispatchers.IO) {
        val info = fileStore.queryInfo(uri)
        val formatName = formatNameFor(info.displayName)
            ?: throw TagException("暂不支持该音频格式：${info.displayName}")
        val cacheFile = fileStore.copyToCache(uri, info.displayName)
        try {
            val document = TagEditor.read(cacheFile)
            val artwork = document.artwork ?: artworkRepository.readEmbeddedPicture(uri)?.let { Artwork.fromBytes(it) }
            val technical = readTechnicalInfo(uri)
            val loaded = LoadedTrack(
                uri = uri.toString(),
                cacheFile = cacheFile,
                displayName = info.displayName,
                mimeType = technical.mimeType ?: info.mimeType,
                formatName = formatName,
                sizeBytes = info.sizeBytes ?: cacheFile.length(),
                durationMs = technical.durationMs,
                bitrateKbps = technical.bitrateKbps,
                sampleRateHz = technical.sampleRateHz,
                channels = technical.channels,
                location = locationOf(uri),
            )
            OpenedTrack(track = loaded, document = document, artwork = artwork)
        } catch (e: Exception) {
            fileStore.deleteCache(cacheFile)
            throw e
        }
    }

    /**
     * Writes [document] into the cache copy, then persists it back to the original
     * document. Returns [SaveOutcome.NeedsSaveAs] when the provider denies writes.
     */
    suspend fun save(uri: Uri, cacheFile: File, document: TagDocument): SaveOutcome =
        withContext(Dispatchers.IO) {
            try {
                TagEditor.write(cacheFile, document)
            } catch (e: Exception) {
                return@withContext SaveOutcome.Failed(e.message ?: "写入标签失败")
            }

            try {
                fileStore.writeBack(uri, cacheFile)
            } catch (_: TrackFileStore.WriteBackException) {
                val info = fileStore.queryInfo(uri)
                return@withContext SaveOutcome.NeedsSaveAs(
                    cacheFile = cacheFile,
                    suggestedName = info.displayName,
                    mimeType = info.mimeType ?: "audio/*",
                )
            } catch (e: IOException) {
                return@withContext SaveOutcome.Failed(e.message ?: "保存文件失败")
            }

            val item = try {
                describe(uri)
            } catch (_: Exception) {
                TrackItem(uri = uri.toString(), displayName = cacheFile.name, formatName = null)
            }.copy(modifiedAt = System.currentTimeMillis())
            artworkRepository.invalidateThumbnail(uri.toString())
            updateLibraryItem(item)
            SaveOutcome.Saved(item = item, artwork = document.artwork)
        }

    /** Saves the already-edited cache copy to a new document picked by the user. */
    suspend fun saveAs(cacheFile: File, target: Uri): SaveOutcome = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext SaveOutcome.Failed("编辑缓存已失效，请重新保存")
        try {
            fileStore.writeToNewDocument(target, cacheFile)
        } catch (e: Exception) {
            return@withContext SaveOutcome.Failed(e.message ?: "无法写入新文件")
        }
        fileStore.takePersistablePermission(target)
        val item = try {
            describe(target)
        } catch (e: Exception) {
            return@withContext SaveOutcome.Failed("文件已写入，但读取新文件失败：${e.message}")
        }
        val existing = libraryStore.load().filterNot { it.uri == item.uri }
        libraryStore.save(listOf(item) + existing)
        SaveOutcome.Saved(item = item, artwork = null)
    }

    fun removeItem(uri: String) {
        libraryStore.save(libraryStore.load().filterNot { it.uri == uri })
    }

    fun updateLibraryItem(item: TrackItem) {
        val current = libraryStore.load()
        if (current.none { it.uri == item.uri }) {
            libraryStore.save(listOf(item) + current)
        } else {
            libraryStore.save(current.map { if (it.uri == item.uri) item else it })
        }
    }

    fun pruneCache() = fileStore.pruneCache()

    fun deleteCache(file: File?) = fileStore.deleteCache(file)

    // -- internals -----------------------------------------------------------

    private fun describe(uri: Uri, info: TrackFileStore.DocumentInfo): TrackItem {
        val technical = readTechnicalInfo(uri)
        val mimeType = technical.mimeType ?: info.mimeType
        val displayName = info.displayName.ifBlank { uri.lastPathSegment ?: "音频文件" }
        return TrackItem(
            uri = uri.toString(),
            displayName = displayName,
            title = technical.title ?: displayName.substringBeforeLast('.').takeIf { it.isNotBlank() },
            artist = technical.artist,
            album = technical.album,
            durationMs = technical.durationMs,
            mimeType = mimeType,
            sizeBytes = info.sizeBytes,
            formatName = formatNameFor(displayName),
            bitrateKbps = technical.bitrateKbps,
            sampleRateHz = technical.sampleRateHz,
            channels = technical.channels,
            location = locationOf(uri),
        )
    }

    private data class TechnicalInfo(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long? = null,
        val mimeType: String? = null,
        val bitrateKbps: Int? = null,
        val sampleRateHz: Int? = null,
        val channels: Int? = null,
    )

    /** Human readable SAF location, e.g. "内部存储/Music/sunny.mp3". */
    private fun locationOf(uri: Uri): String? = try {
        if (uri.scheme == "content") {
            val documentId = DocumentsContract.getDocumentId(uri)
            documentId
                .removePrefix("raw:")
                .replaceFirst("primary:", "内部存储/")
                .replace(":", "/")
        } else {
            uri.path
        }
    } catch (_: Exception) {
        uri.toString()
    }

    private fun readTechnicalInfo(uri: Uri): TechnicalInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "content") {
                retriever.setDataSource(context, uri)
            } else {
                retriever.setDataSource(uri.path ?: return TechnicalInfo())
            }
            TechnicalInfo(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.trim()?.takeIf { it.isNotEmpty() },
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.trim()?.takeIf { it.isNotEmpty() },
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.trim()?.takeIf { it.isNotEmpty() },
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull(),
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                bitrateKbps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toLongOrNull()?.let { (it / 1000).toInt() },
                sampleRateHz = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                    ?.toIntOrNull(),
                channels = readChannels(uri),
            )
        } catch (_: Exception) {
            TechnicalInfo()
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun readChannels(uri: Uri): Int? {
        val extractor = MediaExtractor()
        return try {
            if (uri.scheme == "content") {
                extractor.setDataSource(context, uri, null)
            } else {
                extractor.setDataSource(uri.path ?: return null)
            }
            (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrNull()
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun formatNameFor(displayName: String): String? {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return null
        return TagEditor.supportedFormats.firstOrNull { extension in it.extensions }?.displayName
    }
}
