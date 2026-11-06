package com.villamediana.surco.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.villamediana.surco.SurcoApplication
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.data.model.GenreSummary
import com.villamediana.surco.data.model.ArtistSummary
import com.villamediana.surco.download.DownloadSummary
import com.villamediana.surco.playback.PlaySource
import com.villamediana.surco.playback.PlaybackState
import com.villamediana.surco.playback.SmartSeed
import com.villamediana.surco.smart.Mix
import com.villamediana.surco.smart.SmartMixes
import com.villamediana.surco.ui.library.LibraryIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val loaded: Boolean = false,
    val empty: Boolean = true,
    val quick: List<QuickTile> = emptyList(),
    val heroCovers: List<String> = emptyList(),
    val mixes: List<Mix> = emptyList(),
    val recentlyPlayed: List<HomeItem> = emptyList(),
    val recentlyAdded: List<HomeItem> = emptyList(),
    val genres: List<GenreSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = SurcoApplication.graph
    private val library = graph.library
    private val labels = homeMixLabels(app.resources)

    val playback: StateFlow<PlaybackState> = graph.player.state

    val downloads: StateFlow<DownloadSummary> =
        graph.downloads.summary.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadSummary())

    /** The staggered fade-in runs once per screen lifetime, not on every return to the tab. */
    var introPlayed: Boolean = false

    private val index: Flow<LibraryIndex> =
        combine(library.tracks, graph.db.playlists().observeAllEntries()) { tracks, rows -> LibraryIndex.build(tracks, rows) }
            .flowOn(Dispatchers.Default)

    /** Mixes depend only on the songs (and play history, which moves the songs too). */
    private val mixes: Flow<List<Mix>> = library.tracks
        .mapLatest { tracks -> if (tracks.isEmpty()) emptyList() else buildMixes(tracks) }
        .flowOn(Dispatchers.Default)

    private data class Base(
        val tracks: List<Track>,
        val quick: List<QuickTile>,
        val recentlyPlayed: List<HomeItem>,
        val recentlyAdded: List<HomeItem>,
        val index: LibraryIndex,
    )

    private val base: Flow<Base> = combine(library.tracks, library.albums, library.playlists, library.pinned, index) { tracks, albums, playlists, pinned, index ->
        val byKey = albums.associateBy { it.key.normalized }
        Base(
            tracks = tracks,
            quick = if (tracks.isEmpty() && playlists.isEmpty()) emptyList()
            else buildQuickAccess(tracks.count { it.liked }, pinned, albums, playlists, index),
            recentlyPlayed = recentlyPlayedItems(tracks, byKey),
            recentlyAdded = recentlyAddedItems(tracks, byKey),
            index = index,
        )
    }

    val state: StateFlow<HomeUiState> = combine(base, library.genres, library.artists, mixes) { b, genres, artists, mixes ->
        HomeUiState(
            loaded = true,
            empty = b.tracks.isEmpty(),
            quick = b.quick,
            heroCovers = heroCovers(b.tracks),
            mixes = mixes,
            recentlyPlayed = b.recentlyPlayed,
            recentlyAdded = b.recentlyAdded,
            genres = genres.take(12),
            artists = topArtists(artists, b.index),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private suspend fun buildMixes(tracks: List<Track>): List<Mix> {
        val now = System.currentTimeMillis()
        val taste = buildTaste(library.playCountsSince(now - TASTE_WINDOW_MS), library.recentEvents(), now)
        // Ids key the carousel items: never let a duplicate reach the LazyRow.
        return SmartMixes.build(tracks, taste, labels).distinctBy { it.id }
    }

    // ------------------------------------------------------------------ actions

    fun playSmartLibrary() = graph.player.playSmart(SmartSeed.Library)
    fun playMix(id: String) = graph.player.playSmart(SmartSeed.Mix(id))
    fun togglePlayPause() = graph.player.togglePlayPause()

    fun playSongs(tracks: List<Track>, index: Int, sourceTitle: String) {
        if (tracks.isEmpty()) return
        graph.player.play(tracks, index.coerceIn(0, tracks.lastIndex), PlaySource(PlaySource.Type.SONGS, sourceTitle))
    }
}
