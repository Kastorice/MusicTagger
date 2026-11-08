package com.musictag.app.data.model

import android.net.Uri
import com.musictag.core.net.ArtworkPayload
import com.musictag.core.net.LyricsResult
import com.musictag.core.net.MusicSourceId
import com.musictag.core.net.RemoteSong
import com.musictag.core.tag.Artwork
import com.musictag.core.tag.TagDocument
import java.io.File

/** User preferences (theme + online providers). */
data class AppSettings(
    val dynamicColor: Boolean = false,
    val preferredSource: MusicSourceId = MusicSourceId.NETEASE,
    val neteaseApiBase: String = "",
)

/** A track in the user's in-app library (persisted as a SAF document URI). */
data class TrackItem(
    val uri: String,
    val displayName: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val formatName: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val location: String? = null,
    /** Bumped after a save so the list knows its cached artwork is stale. */
    val modifiedAt: Long = 0L,
) {
    val uriObject: Uri get() = Uri.parse(uri)

    val secondaryText: String
        get() = listOfNotNull(
            artist?.takeIf { it.isNotBlank() },
            album?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { formatName ?: "音频文件" }
}

/** Filesystem-ish details of an opened track plus its private cache copy. */
data class LoadedTrack(
    val uri: String,
    val cacheFile: File,
    val displayName: String,
    val mimeType: String?,
    val formatName: String?,
    val sizeBytes: Long,
    val durationMs: Long?,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val location: String? = null,
) {
    val uriObject: Uri get() = Uri.parse(uri)
}

/** Result of loading a track into the editor. */
data class OpenedTrack(
    val track: LoadedTrack,
    val document: TagDocument,
    val artwork: Artwork?,
)

data class LibraryUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val importing: Boolean = false,
    val items: List<TrackItem> = emptyList(),
)

/** All editable text fields, as raw strings for text inputs. */
data class EditableFields(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: String = "",
    val trackNumber: String = "",
    val discNumber: String = "",
    val genre: String = "",
    val comment: String = "",
    val lyrics: String = "",
) {
    fun toDocument(artwork: Artwork?): TagDocument = TagDocument(
        title = title.nilIfBlank(),
        artist = artist.nilIfBlank(),
        album = album.nilIfBlank(),
        albumArtist = albumArtist.nilIfBlank(),
        year = year.nilIfBlank(),
        trackNumber = trackNumber.nilIfBlank(),
        discNumber = discNumber.nilIfBlank(),
        genre = genre.nilIfBlank(),
        comment = comment.nilIfBlank(),
        lyrics = lyrics.nilIfBlank(),
        artwork = artwork,
    )

    private fun String.nilIfBlank(): String? = trim().takeIf { it.isNotEmpty() }

    companion object {
        fun from(document: TagDocument): EditableFields = EditableFields(
            title = document.title.orEmpty(),
            artist = document.artist.orEmpty(),
            album = document.album.orEmpty(),
            albumArtist = document.albumArtist.orEmpty(),
            year = document.year.orEmpty(),
            trackNumber = document.trackNumber.orEmpty(),
            discNumber = document.discNumber.orEmpty(),
            genre = document.genre.orEmpty(),
            comment = document.comment.orEmpty(),
            lyrics = document.lyrics.orEmpty(),
        )
    }
}

/** Outcome of writing tags back to the original document or to a new file. */
sealed interface SaveOutcome {
    data class Saved(val item: TrackItem, val artwork: Artwork?) : SaveOutcome

    /** The original file is read-only for us; the user has to pick a new destination. */
    data class NeedsSaveAs(
        val cacheFile: File,
        val suggestedName: String,
        val mimeType: String,
    ) : SaveOutcome

    data class Failed(val message: String) : SaveOutcome
}

data class AddTracksResult(
    val added: List<TrackItem> = emptyList(),
    val rejectedNames: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Online matching state
// ---------------------------------------------------------------------------

data class SearchUiState(
    val keyword: String = "",
    val sourceId: MusicSourceId = MusicSourceId.NETEASE,
    val searching: Boolean = false,
    val results: List<RemoteSong> = emptyList(),
    val error: String? = null,
    val selectedSong: RemoteSong? = null,
    val loadingMatch: Boolean = false,
    val match: MatchInfo? = null,
)

/** Resolved lyrics + cover preview for a selected search hit. */
data class MatchInfo(
    val song: RemoteSong,
    val lyrics: LyricsResult? = null,
    val cover: ArtworkPayload? = null,
    val error: String? = null,
) {
    val hasLyrics: Boolean get() = lyrics != null && !lyrics.isEmpty
    val hasCover: Boolean get() = cover != null
    val lyricsPlain: String get() = lyrics?.lrc?.lineSequence()?.take(8)?.joinToString("\n").orEmpty()
}

data class EditorUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val track: LoadedTrack? = null,
    val fields: EditableFields = EditableFields(),
    val baselineFields: EditableFields = EditableFields(),
    val artwork: Artwork? = null,
    val baselineArtwork: Artwork? = null,
    val saving: Boolean = false,
    val search: SearchUiState = SearchUiState(),
    val searchVisible: Boolean = false,
) {
    val hasChanges: Boolean
        get() = fields != baselineFields || !artwork.isSameAs(baselineArtwork)

    private fun Artwork?.isSameAs(other: Artwork?): Boolean = when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> isSameImage(other)
    }
}
