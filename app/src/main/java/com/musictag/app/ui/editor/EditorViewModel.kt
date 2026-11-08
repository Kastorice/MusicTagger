package com.musictag.app.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musictag.app.AppContainer
import com.musictag.app.data.model.EditableFields
import com.musictag.app.data.model.EditorUiState
import com.musictag.app.data.model.MatchInfo
import com.musictag.app.data.model.SaveOutcome
import com.musictag.app.data.model.SearchUiState
import com.musictag.app.data.model.TrackItem
import com.musictag.core.net.Lrc
import com.musictag.core.net.MusicSourceId
import com.musictag.core.net.RemoteSong
import com.musictag.core.tag.Artwork
import com.musictag.core.tag.TagException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Which text field an edit applies to. */
enum class EditorField {
    TITLE, ARTIST, ALBUM, ALBUM_ARTIST, YEAR, TRACK_NUMBER, DISC_NUMBER, GENRE, COMMENT, LYRICS
}

sealed interface EditorEvent {
    data class Message(val text: String) : EditorEvent
    data class RequestSaveAs(val suggestedName: String, val mimeType: String) : EditorEvent
    data class Saved(val item: TrackItem) : EditorEvent
    data class SaveAsCompleted(val item: TrackItem) : EditorEvent
}

class EditorViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.trackRepository
    private val artworkRepository = container.artworkRepository

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var matchJob: Job? = null
    private var pendingSaveAsFile: File? = null

    fun open(uri: Uri) {
        loadJob?.cancel()
        searchJob?.cancel()
        matchJob?.cancel()
        val previous = _state.value.track?.cacheFile
        _state.value = EditorUiState(loading = true)
        loadJob = viewModelScope.launch {
            try {
                val opened = withContext(Dispatchers.IO) { repository.openForEdit(uri) }
                repository.deleteCache(previous)
                val fields = EditableFields.from(opened.document)
                _state.value = EditorUiState(
                    loading = false,
                    track = opened.track,
                    fields = fields,
                    baselineFields = fields,
                    artwork = opened.artwork,
                    baselineArtwork = opened.artwork,
                    search = SearchUiState(
                        keyword = buildSearchKeyword(fields),
                        sourceId = container.settingsStore.settings.value.preferredSource,
                    ),
                )
            } catch (e: TagException) {
                _state.value = EditorUiState(loading = false, loadError = e.message ?: "无法读取该文件的标签")
            } catch (e: Exception) {
                Log.w(TAG, "open failed", e)
                _state.value = EditorUiState(
                    loading = false,
                    loadError = "无法打开文件：${e.message ?: "未知错误"}",
                )
            }
        }
    }

    fun close() {
        loadJob?.cancel()
        searchJob?.cancel()
        matchJob?.cancel()
        repository.deleteCache(_state.value.track?.cacheFile)
        repository.deleteCache(pendingSaveAsFile)
        pendingSaveAsFile = null
        _state.value = EditorUiState()
    }

    fun updateField(field: EditorField, value: String) {
        _state.update { state ->
            val fields = when (field) {
                EditorField.TITLE -> state.fields.copy(title = value)
                EditorField.ARTIST -> state.fields.copy(artist = value)
                EditorField.ALBUM -> state.fields.copy(album = value)
                EditorField.ALBUM_ARTIST -> state.fields.copy(albumArtist = value)
                EditorField.YEAR -> state.fields.copy(year = value)
                EditorField.TRACK_NUMBER -> state.fields.copy(trackNumber = value)
                EditorField.DISC_NUMBER -> state.fields.copy(discNumber = value)
                EditorField.GENRE -> state.fields.copy(genre = value)
                EditorField.COMMENT -> state.fields.copy(comment = value)
                EditorField.LYRICS -> state.fields.copy(lyrics = value)
            }
            state.copy(fields = fields)
        }
    }

    fun setArtworkFromLocal(uri: Uri) {
        viewModelScope.launch {
            val artwork = withContext(Dispatchers.IO) { artworkRepository.loadPickedArtwork(uri) }
            if (artwork == null) {
                _events.send(EditorEvent.Message("无法读取所选图片，请换一张试试"))
            } else {
                _state.update { it.copy(artwork = artwork) }
                _events.send(EditorEvent.Message("封面已更新，保存后写入文件"))
            }
        }
    }

    fun setArtworkFromBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        viewModelScope.launch {
            val artwork = withContext(Dispatchers.IO) { artworkRepository.normalizeImage(bytes, 900) }
                ?: Artwork.fromBytes(bytes)
            _state.update { it.copy(artwork = artwork) }
        }
    }

    fun removeArtwork() {
        _state.update { it.copy(artwork = null) }
    }

    fun isArtworkChanged(): Boolean = !_state.value.artwork.isSameAs(_state.value.baselineArtwork)

    private fun Artwork?.isSameAs(other: Artwork?): Boolean = when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> isSameImage(other)
    }

    fun save() {
        val current = _state.value
        val track = current.track ?: return
        if (current.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val document = current.fields.toDocument(current.artwork)
            val outcome = withContext(Dispatchers.IO) {
                repository.save(track.uriObject, track.cacheFile, document)
            }
            when (outcome) {
                is SaveOutcome.Saved -> {
                    _state.update {
                        it.copy(
                            saving = false,
                            baselineFields = it.fields,
                            baselineArtwork = it.artwork,
                            track = it.track?.copy(
                                displayName = outcome.item.displayName,
                                durationMs = outcome.item.durationMs ?: it.track?.durationMs,
                            ),
                        )
                    }
                    refreshThumbnail(outcome.item, outcome.artwork)
                    _events.send(EditorEvent.Saved(outcome.item))
                    _events.send(EditorEvent.Message("标签已保存到文件"))
                }

                is SaveOutcome.NeedsSaveAs -> {
                    pendingSaveAsFile = outcome.cacheFile
                    _state.update { it.copy(saving = false) }
                    _events.send(EditorEvent.RequestSaveAs(outcome.suggestedName, outcome.mimeType))
                }

                is SaveOutcome.Failed -> {
                    _state.update { it.copy(saving = false) }
                    _events.send(EditorEvent.Message(outcome.message))
                }
            }
        }
    }

    fun saveAs(target: Uri) {
        val cacheFile = pendingSaveAsFile ?: _state.value.track?.cacheFile ?: return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val outcome = withContext(Dispatchers.IO) { repository.saveAs(cacheFile, target) }
            _state.update { it.copy(saving = false) }
            when (outcome) {
                is SaveOutcome.Saved -> {
                    pendingSaveAsFile = null
                    _events.send(EditorEvent.SaveAsCompleted(outcome.item))
                    _events.send(EditorEvent.Message("已另存为新文件"))
                }

                is SaveOutcome.Failed -> _events.send(EditorEvent.Message(outcome.message))
                is SaveOutcome.NeedsSaveAs -> _events.send(EditorEvent.Message("无法写入目标文件"))
            }
        }
    }

    fun cancelSaveAs() {
        pendingSaveAsFile = null
    }

    /** Back from the match preview to the search results list. */
    fun clearMatchSelection() {
        matchJob?.cancel()
        _state.update {
            it.copy(search = it.search.copy(selectedSong = null, match = null, loadingMatch = false))
        }
    }

    // -- online matching -----------------------------------------------------

    fun openSearch() {
        _state.update {
            it.copy(
                searchVisible = true,
                search = it.search.copy(
                    sourceId = container.settingsStore.settings.value.preferredSource,
                    error = null,
                ),
            )
        }
    }

    fun closeSearch() {
        searchJob?.cancel()
        matchJob?.cancel()
        _state.update {
            it.copy(
                searchVisible = false,
                search = it.search.copy(selectedSong = null, match = null, loadingMatch = false),
            )
        }
    }

    fun setSearchKeyword(keyword: String) {
        _state.update { it.copy(search = it.search.copy(keyword = keyword)) }
    }

    fun setSearchSource(sourceId: MusicSourceId) {
        _state.update { it.copy(search = it.search.copy(sourceId = sourceId, results = emptyList(), error = null)) }
        if (_state.value.search.keyword.isNotBlank()) search()
    }

    fun search() {
        val keyword = _state.value.search.keyword.trim()
        if (keyword.isEmpty()) {
            _state.update { it.copy(search = it.search.copy(error = "请输入歌曲名或歌手")) }
            return
        }
        searchJob?.cancel()
        matchJob?.cancel()
        _state.update {
            it.copy(
                search = it.search.copy(
                    searching = true,
                    error = null,
                    results = emptyList(),
                    selectedSong = null,
                    match = null,
                ),
            )
        }
        searchJob = viewModelScope.launch {
            try {
                val sourceId = _state.value.search.sourceId
                val source = container.musicSources().firstOrNull { it.id == sourceId }
                    ?: throw IllegalStateException("在线数据源未就绪")
                val results = withContext(Dispatchers.IO) { source.search(keyword, SEARCH_LIMIT) }
                _state.update {
                    it.copy(
                        search = it.search.copy(
                            searching = false,
                            results = results,
                            error = if (results.isEmpty()) "没有找到相关歌曲，换个关键词试试" else null,
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "search failed", e)
                _state.update {
                    it.copy(
                        search = it.search.copy(
                            searching = false,
                            error = e.message ?: "搜索失败，请检查网络",
                        ),
                    )
                }
            }
        }
    }

    fun selectSong(song: RemoteSong) {
        matchJob?.cancel()
        _state.update { it.copy(search = it.search.copy(selectedSong = song, loadingMatch = true, match = null, error = null)) }
        matchJob = viewModelScope.launch {
            try {
                val source = container.musicSources().firstOrNull { it.id == song.source }
                    ?: throw IllegalStateException("在线数据源未就绪")
                val match = withContext(Dispatchers.IO) {
                    val resolved = runCatching { source.resolve(song) }.getOrDefault(song)
                    val lyrics = runCatching { source.fetchLyrics(resolved) }.getOrNull()
                    val cover = runCatching { source.fetchArtwork(resolved) }.getOrNull()
                    MatchInfo(
                        song = resolved,
                        lyrics = lyrics,
                        cover = cover,
                        error = if ((lyrics == null || lyrics.isEmpty) && cover == null) {
                            "该歌曲没有找到歌词或封面"
                        } else {
                            null
                        },
                    )
                }
                _state.update { it.copy(search = it.search.copy(loadingMatch = false, match = match)) }
            } catch (e: Exception) {
                Log.w(TAG, "match failed", e)
                _state.update {
                    it.copy(search = it.search.copy(loadingMatch = false, error = e.message ?: "获取歌词/封面失败"))
                }
            }
        }
    }

    fun applyMatch(applyMetadata: Boolean, applyLyrics: Boolean, applyCover: Boolean) {
        val match = _state.value.search.match ?: return
        val song = match.song
        viewModelScope.launch {
            var fields = _state.value.fields
            var artwork = _state.value.artwork
            if (applyMetadata) {
                fields = fields.copy(
                    title = song.title.ifBlank { fields.title },
                    artist = song.artists.ifBlank { fields.artist },
                    album = song.album?.takeIf { it.isNotBlank() } ?: fields.album,
                )
            }
            if (applyLyrics) {
                val lyrics = match.lyrics?.let { Lrc.mergeTranslation(it.lrc, it.translation) }
                if (!lyrics.isNullOrBlank()) fields = fields.copy(lyrics = lyrics)
            }
            if (applyCover) {
                val payload = match.cover
                if (payload != null) {
                    // Re-encode/downscale provider covers (often >500 KiB) so the
                    // embedded picture does not bloat the user's audio file.
                    artwork = withContext(Dispatchers.IO) {
                        artworkRepository.normalizeImage(payload.bytes, 900)
                    } ?: Artwork.fromBytes(payload.bytes)
                }
            }
            _state.update { state ->
                state.copy(
                    fields = fields,
                    artwork = artwork,
                    searchVisible = false,
                    search = state.search.copy(selectedSong = null, match = null, loadingMatch = false),
                )
            }
            _events.send(EditorEvent.Message("已应用到编辑内容，保存后写入文件"))
        }
    }

    private fun buildSearchKeyword(fields: EditableFields): String {
        val title = fields.title.trim()
        val artist = fields.artist.trim()
        return when {
            title.isNotEmpty() && artist.isNotEmpty() -> "$artist $title"
            title.isNotEmpty() -> title
            artist.isNotEmpty() -> artist
            else -> _state.value.track?.displayName?.substringBeforeLast('.')?.trim().orEmpty()
        }
    }

    suspend fun previewBitmap(bytes: ByteArray, maxSizePx: Int = 320): Bitmap? =
        artworkRepository.decode(bytes, maxSizePx)

    fun consumeMessage() = Unit

    private fun refreshThumbnail(item: TrackItem, artwork: Artwork?) {
        viewModelScope.launch {
            val bitmap = if (artwork != null) {
                withContext(Dispatchers.IO) { artworkRepository.decode(artwork.bytes, 320) }
            } else {
                null
            }
            artworkRepository.putThumbnail(item.uri, bitmap)
        }
    }

    private companion object {
        const val TAG = "EditorViewModel"
        const val SEARCH_LIMIT = 20
    }
}
