package com.villamediana.sound.ui.home

import com.villamediana.sound.ui.nav.Chrome
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.download.DownloadSummary
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.playback.SmartSeed
import com.villamediana.sound.smart.Mix
import com.villamediana.sound.smart.SmartMixes
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
    val mixes: List<Mix> = emptyList(),
    val recentlyPlayed: List<HomeItem> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = SoundApplication.graph
    private val library = graph.library
    private val labels = homeMixLabels(app.resources)

    val playback: StateFlow<PlaybackState> = graph.player.state

    val downloads: StateFlow<DownloadSummary> =
        graph.downloads.summary.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadSummary())

    /** Listened this month (library + online): the way into the retrospective shows when > 0. */
    val monthListenedMs: StateFlow<Long> = run {
        val since = com.villamediana.sound.stats.Recaps.since(com.villamediana.sound.stats.RecapPeriod.MONTH, System.currentTimeMillis())
        combine(library.playEventsSince(since), library.onlinePlaysSince(since)) { local, online ->
            local.sumOf { it.listenedMs } + online.sumOf { it.listenedMs }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    }

    /** The staggered fade-in runs once per screen lifetime, not on every return to the tab. */
    var introPlayed: Boolean = false

    /** Mixes depend only on the songs (and play history, which moves the songs too). */
    private val mixes: Flow<List<Mix>> = library.tracks
        .mapLatest { tracks -> if (tracks.isEmpty()) emptyList() else buildMixes(tracks) }
        .flowOn(Dispatchers.Default)

    val state: StateFlow<HomeUiState> = combine(library.tracks, library.albums, mixes) { tracks, albums, mixes ->
        HomeUiState(
            loaded = true,
            empty = tracks.isEmpty(),
            mixes = mixes,
            recentlyPlayed = recentlyPlayedItems(tracks, albums.associateBy { it.key.normalized }),
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
        Chrome.openPlayer()
    }
}
