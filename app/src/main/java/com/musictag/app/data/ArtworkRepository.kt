package com.musictag.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.musictag.core.tag.Artwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Decodes artwork (embedded pictures or freshly picked images) with caching, so
 * long lists and previews never block the main thread.
 */
class ArtworkRepository(private val context: Context) {

    private val cache = object : LruCache<String, Bitmap>(24) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Loads a downsampled thumbnail from the file's embedded picture. */
    suspend fun loadThumbnail(uri: Uri, maxSizePx: Int = 320): Bitmap? = withContext(Dispatchers.IO) {
        val key = "thumb:${uri}:$maxSizePx"
        cache.get(key)?.let { return@withContext it }
        val bytes = readEmbeddedPicture(uri) ?: return@withContext null
        val bitmap = decodeSampled(bytes, maxSizePx)
        if (bitmap != null) cache.put(key, bitmap)
        bitmap
    }

    /** Decodes an arbitrary image blob for previews. */
    suspend fun decode(bytes: ByteArray, maxSizePx: Int = 1024): Bitmap? = withContext(Dispatchers.IO) {
        decodeSampled(bytes, maxSizePx)
    }

    suspend fun decode(file: File, maxSizePx: Int = 1024): Bitmap? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        decodeSampled(file.readBytes(), maxSizePx)
    }

    /** Stores the just-saved cover as the list thumbnail. */
    fun putThumbnail(uri: String, bitmap: Bitmap?) {
        if (bitmap == null) {
            cache.remove("thumb:$uri:320")
        } else {
            cache.put("thumb:$uri:320", bitmap)
        }
    }

    /** Drops the cached list thumbnail for [uri] after its cover was rewritten. */
    fun invalidateThumbnail(uri: String) {
        cache.remove("thumb:$uri:320")
    }

    fun clear() = cache.evictAll()

    /**
     * Loads an image picked by the user and normalizes it for embedding:
     * downscales to [maxSizePx] and re-encodes (JPEG for photos, PNG when alpha
     * is present) to keep tag payloads reasonable.
     */
    suspend fun loadPickedArtwork(uri: Uri, maxSizePx: Int = 1200): Artwork? = withContext(Dispatchers.IO) {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(24 * 1024 * 1024) }
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        normalizeImage(bytes, maxSizePx)
    }

    suspend fun normalizeImage(bytes: ByteArray, maxSizePx: Int = 1200): Artwork? = withContext(Dispatchers.IO) {
        val decoded = decodeSampled(bytes, maxSizePx) ?: return@withContext null
        val scaled = scaleDown(decoded, maxSizePx)
        val output = ByteArrayOutputStream()
        if (scaled.hasAlpha()) {
            scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
        } else {
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }
        if (scaled !== decoded) scaled.recycle()
        Artwork.fromBytes(output.toByteArray())
    }

    fun readEmbeddedPicture(uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "content") {
                retriever.setDataSource(context, uri)
            } else {
                retriever.setDataSource(uri.path ?: return null)
            }
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    fun decodeSampled(bytes: ByteArray, maxSizePx: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxSizePx && bounds.outHeight / (sampleSize * 2) >= maxSizePx) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxSizePx: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val largest = maxOf(width, height)
        if (largest <= maxSizePx) return bitmap
        val scale = maxSizePx.toFloat() / largest
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun java.io.InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IllegalStateException("图片文件过大")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
