package com.villamediana.surco.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.villamediana.surco.SurcoApplication
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.data.model.AlbumSummary
import com.villamediana.surco.data.model.ArtistSummary
import com.villamediana.surco.data.model.GenreSummary
import com.villamediana.surco.data.model.PlaylistSummary
import com.villamediana.surco.playback.PlaySource
import com.villamediana.surco.playback.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val loaded: Boolean = false,
    val filter: LibFilter? = null,
    val sort: LibSort = LibSort.RECENT,
    val viewMode: LibViewMode = LibViewMode.LIST,
    /** Nothing at all yet: no songs, no playlists. */
    val libraryEmpty: Boolean = true,
    val likedCount: Int = 0,
    val showLiked: Boolean = false,
    val downloads: DownloadsRowState? = null,
    val pinned: List<LibItem> = emptyList(),
    val items: List<LibItem> = emptyList(),
    val songCount: Int = 0,
) {
    val isEmptyView: Boolean get() = if (filter == null) libraryEmpty else pinned.isEmpty() && items.isEmpty()
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = SurcoApplication.graph
    private val library = graph.library
    private val prefs = LibraryPrefs(app)

    /** Selected pill (null = everything). Kept here so it survives tab switches. */
    val filter = MutableStateFlow<LibFilter?>(null)

    val playback: StateFlow<PlaybackState> = graph.player.state

    private val index: Flow<LibraryIndex> =
        combine(library.tracks, graph.db.playlists().observeAllEntries()) { tracks, rows -> LibraryIndex.build(tracks, rows) }
            .flowOn(Dispatchers.Default)

    private val source: Flow<LibrarySource> = combine(
        combine(library.tracks, library.albums, library.artists, library.genres, library.playlists) { t, a, r, g, p -> Partial(t, a, r, g, p) },
        library.pinned,
        index,
    ) { part, pinned, index -> LibrarySource(part.tracks, part.albums, part.artists, part.genres, part.playlists, pinned, index) }

    private data class Partial(
        val tracks: List<Track>,
        val albums: List<AlbumSummary>,
        val artists: List<ArtistSummary>,
        val genres: List<GenreSummary>,
        val playlists: List<PlaylistSummary>,
    )

    private data class View(val filter: LibFilter?, val sort: LibSort, val mode: LibViewMode)

    private val view: Flow<View> = combine(filter, prefs.sort, prefs.viewMode) { f, s, m -> View(f, s, m) }

    val state: StateFlow<LibraryUiState> = combine(source, view, graph.downloads.tasks) { src, v, tasks ->
        val sections = buildSections(src, v.filter, v.sort)
        val empty = src.tracks.isEmpty() && src.playlists.isEmpty()
        LibraryUiState(
            loaded = true,
            filter = v.filter,
            sort = effectiveSort(v.sort, v.filter),
            viewMode = v.mode,
            libraryEmpty = empty,
            likedCount = src.tracks.count { it.liked },
            showLiked = showsLiked(v.filter) && !empty,
            downloads = if (showsDownloads(v.filter)) downloadsRow(tasks) else null,
            pinned = sections.pinned,
            items = sections.items,
            songCount = src.tracks.size,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** Tapping the selected pill again clears it. */
    fun selectFilter(f: LibFilter?) {
        filter.value = if (f != null && filter.value == f) null else f
    }

    fun setSort(sort: LibSort) {
        viewModelScope.launch { prefs.setSort(sort) }
    }

    fun toggleViewMode() {
        viewModelScope.launch {
            val now = prefs.viewMode.first()
            prefs.setViewMode(if (now == LibViewMode.LIST) LibViewMode.GRID else LibViewMode.LIST)
        }
    }

    fun createPlaylist(name: String, description: String?, onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(library.createPlaylist(name, description)) }
    }

    fun playSongs(tracks: List<Track>, index: Int, sourceTitle: String) {
        if (tracks.isEmpty()) return
        graph.player.play(tracks, index.coerceIn(0, tracks.lastIndex), PlaySource(PlaySource.Type.SONGS, sourceTitle))
    }
}
