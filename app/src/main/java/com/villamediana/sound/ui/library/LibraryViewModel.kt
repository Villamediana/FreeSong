package com.villamediana.sound.ui.library

import com.villamediana.sound.ui.nav.Chrome
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.ui.search.DownloadLookup
import com.villamediana.sound.ui.search.toRemoteTrack

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
) {
    val isEmptyView: Boolean get() = if (filter == null) libraryEmpty else pinned.isEmpty() && items.isEmpty()
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = SoundApplication.graph
    private val library = graph.library
    private val prefs = LibraryPrefs(app)

    /** Selected pill (null = everything). Kept here so it survives tab switches. */
    val filter = MutableStateFlow<LibFilter?>(null)

    /** A tab of the main screen shows one kind, always (no toggling back to everything). */
    fun showOnly(f: LibFilter) {
        filter.value = f
    }

    val playback: StateFlow<PlaybackState> = graph.player.state

    private val index: Flow<LibraryIndex> =
        combine(library.tracks, graph.db.playlists().observeAllEntries()) { tracks, rows -> LibraryIndex.build(tracks, rows) }
            .flowOn(Dispatchers.Default)

    /** Músicas: the liked songs (on the phone or streamed), each with when it was liked. */
    private val likedSongs: Flow<List<LikedSong>> = combine(library.likedAll, library.likedOnline) { all, online ->
        val streamedAt = online.associate { it.videoId to it.likedAt }
        all.map { t -> LikedSong(t, if (t.isOnline) streamedAt[t.videoId] ?: 0L else t.likedAt ?: t.addedAt) }
    }

    private val source: Flow<LibrarySource> = combine(
        combine(library.tracks, library.albums, library.artists, library.genres, library.playlists) { t, a, r, g, p -> Partial(t, a, r, g, p) },
        library.pinned,
        index,
        likedSongs,
    ) { part, pinned, index, liked -> LibrarySource(part.tracks, part.albums, part.artists, part.genres, part.playlists, pinned, index, liked) }

    private data class Partial(
        val tracks: List<Track>,
        val albums: List<AlbumSummary>,
        val artists: List<ArtistSummary>,
        val genres: List<GenreSummary>,
        val playlists: List<PlaylistSummary>,
    )

    private data class View(val filter: LibFilter?, val sort: LibSort, val mode: LibViewMode)

    private val view: Flow<View> = combine(filter, prefs.sort, prefs.viewMode) { f, s, m -> View(f, s, m) }

    private data class Built(val view: View, val sections: LibrarySections, val empty: Boolean, val likedCount: Int)

    /** Sorting/grouping only reruns when the library or the view changes — not on download progress. */
    /** Curtidas counts streamed likes too. */
    private val likedCount: Flow<Int> = library.likedAll.map { it.size }.distinctUntilChanged()

    private val built: Flow<Built> = combine(source, view, likedCount) { src, v, liked ->
        Built(
            view = v,
            sections = buildSections(src, v.filter, v.sort),
            empty = src.tracks.isEmpty() && src.playlists.isEmpty() && liked == 0,
            likedCount = liked,
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

    fun playSongs(tracks: List<Track>, index: Int, source: PlaySource) {
        if (tracks.isEmpty()) return
        graph.player.play(tracks, index.coerceIn(0, tracks.lastIndex), source)
        Chrome.openPlayer()
    }

    // ------------------------------------------------------------------ downloads of liked songs

    /** Songs just asked for — hidden as queued until the queue has them. */
    private val requested = MutableStateFlow<Set<String>>(emptySet())

    /** Where each song stands: Músicas offers "Baixar" only to the ones not on the phone nor queued. */
    val downloadLookup: StateFlow<DownloadLookup> =
        combine(library.downloadedVideoIds, graph.downloads.tasks, requested) { done, tasks, asked -> DownloadLookup.build(done, tasks, asked) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadLookup.Empty)

    fun download(track: Track) {
        val remote = track.toRemoteTrack() ?: return
        val id = remote.videoId
        if (!canDownload(downloadLookup.value.stateOf(id))) return
        requested.update { it + id }
        viewModelScope.launch {
            try {
                runCatching { graph.downloads.download(listOf(remote)) }
                    .onSuccess { withTimeoutOrNull(5_000) { downloadLookup.first { it.isKnown(id) } } }
            } finally {
                requested.update { it - id }
            }
        }
    }
}
