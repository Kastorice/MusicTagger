package com.musictag.core.tag

import com.musictag.core.tag.flac.FlacFormat
import com.musictag.core.tag.mp3.Mp3Format
import com.musictag.core.tag.mp4.Mp4Format
import com.musictag.core.tag.ogg.OggFormat
import java.io.File

/**
 * Entry point for reading and writing tags. Pure JVM code: safe to run on a
 * background thread and easy to unit test without an emulator.
 */
object TagEditor {
    private val formats: List<TagFormat> = listOf(Mp3Format, FlacFormat, OggFormat, Mp4Format)

    /** All registered format implementations, in detection priority order. */
    val supportedFormats: List<TagFormat> get() = formats

    /** Lower-case extensions (no dot) that this library can edit. */
    val supportedExtensions: Set<String> = formats.flatMap { it.extensions }.toSet()

    fun detect(file: File): TagFormat? = formats.firstOrNull { it.supports(file) }

    @Throws(TagException::class)
    fun read(file: File): TagDocument {
        if (!file.exists() || !file.isFile) {
            throw TagException("文件不存在：${file.name}")
        }
        val format = detect(file) ?: throw TagException("不支持的音频格式：${file.name}")
        return try {
            format.read(file)
        } catch (e: TagException) {
            throw e
        } catch (e: Exception) {
            throw TagException("读取 ${file.name} 的标签失败：${e.message}", e)
        }
    }

    @Throws(TagException::class)
    fun write(file: File, document: TagDocument) {
        if (!file.exists() || !file.isFile) {
            throw TagException("文件不存在：${file.name}")
        }
        val format = detect(file) ?: throw TagException("不支持的音频格式：${file.name}")
        try {
            format.write(file, document.normalized())
        } catch (e: TagException) {
            throw e
        } catch (e: Exception) {
            throw TagException("写入 ${file.name} 的标签失败：${e.message}", e)
        }
    }

    /** Display name of the detected format, or null. */
    fun formatNameOf(file: File): String? = detect(file)?.displayName
}
