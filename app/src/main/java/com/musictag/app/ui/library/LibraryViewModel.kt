package com.musictag.app.ui.library

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musictag.app.data.ArtworkRepository
import com.musictag.app.data.TrackRepository
import com.musictag.app.data.model.LibraryUiState
import com.musictag.app.data.model.TrackItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(
    private val repository: TrackRepository,
    private val artworkRepository: ArtworkRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        val cached = repository.libraryItems()
        _state.value = LibraryUiState(items = cached, loading = cached.isEmpty())
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val cached = repository.libraryItems()
            _state.update { it.copy(items = cached, loading = cached.isEmpty(), refreshing = cached.isNotEmpty()) }
            if (cached.isEmpty()) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            val refreshed = cached.map { item ->
                withContext(Dispatchers.IO) { repository.refreshItem(item) }
            }
            _state.update { it.copy(items = refreshed, refreshing = false, loading = false) }
        }
    }

    fun addTracks(uris: List<Uri>, openSingle: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true) }
            try {
                val result = repository.addUris(uris)
                _state.update { state ->
                    val existing = state.items.filterNot { item -> result.added.any { it.uri == item.uri } }
                    val merged = (result.added + existing).distinctBy { it.uri }
                    state.copy(items = merged, importing = false, loading = false)
                }
                if (result.rejectedNames.isNotEmpty()) {
                    val names = result.rejectedNames.take(2).joinToString("、")
                    _events.send(LibraryEvent.Message("已跳过不支持的音频：$names"))
                }
                if (openSingle && result.added.size == 1) {
                    _events.send(LibraryEvent.OpenTrack(result.added.first().uri))
                } else if (result.added.size > 1) {
                    _events.send(LibraryEvent.Message("已添加 ${result.added.size} 首歌曲"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "addTracks failed", e)
                _state.update { it.copy(importing = false) }
                _events.send(LibraryEvent.Message("添加失败：${e.message ?: "无法访问文件"}"))
            }
        }
    }

    fun remove(uri: String) {
        repository.removeItem(uri)
        _state.update { state -> state.copy(items = state.items.filterNot { it.uri == uri }) }
        viewModelScope.launch { _events.send(LibraryEvent.Message("已从列表移除，原文件不会被删除")) }
    }

    fun onTrackOpened(uri: String) {
        viewModelScope.launch {
            val item = repository.libraryItems().firstOrNull { it.uri == uri } ?: return@launch
            val refreshed = withContext(Dispatchers.IO) { repository.refreshItem(item) }
            _state.update { state ->
                state.copy(items = state.items.map { if (it.uri == uri) refreshed else it })
            }
        }
    }

    suspend fun thumbnail(uri: Uri): android.graphics.Bitmap? = artworkRepository.loadThumbnail(uri)

    private companion object {
        const val TAG = "LibraryViewModel"
    }
}

sealed interface LibraryEvent {
    data class Message(val text: String) : LibraryEvent
    data class OpenTrack(val uri: String) : LibraryEvent
}
