package com.villamediana.sound.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.model.AlbumSummary
import com.villamediana.sound.data.model.ArtistSummary
import com.villamediana.sound.data.model.GenreSummary
import com.villamediana.sound.data.model.PlaylistSummary
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    private val graph = SoundApplication.graph
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

    private data class Built(val view: View, val sections: LibrarySections, val empty: Boolean, val likedCount: Int, val songCount: Int)

    /** Sorting/grouping only reruns when the library or the view changes — not on download progress. */
    private val built: Flow<Built> = combine(source, view) { src, v ->
        Built(
            view = v,
            sections = buildSections(src, v.filter, v.sort),
            empty = src.tracks.isEmpty() && src.playlists.isEmpty(),
            likedCount = src.tracks.count { it.liked },
            songCount = src.tracks.size,
        )
    }.flowOn(Dispatchers.Default)

    private val downloads: Flow<DownloadsRowState?> = graph.downloads.tasks.map { downloadsRow(it) }.distinctUntilChanged()

    val state: StateFlow<LibraryUiState> = combine(built, downloads) { b, dl ->
        val f = b.view.filter
        LibraryUiState(
            loaded = true,
            filter = f,
            sort = effectiveSort(b.view.sort, f),
            viewMode = b.view.mode,
            libraryEmpty = b.empty,
            likedCount = b.likedCount,
            showLiked = showsLiked(f) && !b.empty,
            downloads = if (showsDownloads(f)) dl else null,
            pinned = b.sections.pinned,
            items = b.sections.items,
            songCount = b.songCount,
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
