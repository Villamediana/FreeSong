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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

    val playback: StateFlow<PlaybackState> = graph.player.state

    val downloads: StateFlow<DownloadSummary> =
        graph.downloads.summary.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadSummary())

    /** The retrospective's year (all through January: last year) and how long it was listened; null otherwise. */
    val recap: StateFlow<Pair<Int, Long>?> = run {
        val year = com.villamediana.sound.stats.Recaps.yearShown(System.currentTimeMillis())
            ?: return@run kotlinx.coroutines.flow.MutableStateFlow<Pair<Int, Long>?>(null)
        val span = com.villamediana.sound.stats.Recaps.span(year)
        combine(library.playEventsSince(span.first), library.onlinePlaysSince(span.first)) { local, online ->
            val ms = local.filter { it.playedAt in span }.sumOf { it.listenedMs } + online.filter { it.playedAt in span }.sumOf { it.listenedMs }
            year to ms
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    /** The staggered fade-in runs once per screen lifetime, not on every return to the tab. */
    var introPlayed: Boolean = false

    /** "Feitos pra você": the day's genre mixes, made once a day (see MadeForYouStore). */
    private val mixes: Flow<List<Mix>> = graph.madeForYou.mixes

    init {
        graph.madeForYou.refresh()
        // Songs coming in (or leaving) can change the mixes: a first song, a new genre.
        viewModelScope.launch {
            library.tracks
                .map { list -> list.size to list.mapNotNullTo(HashSet()) { it.genre?.trim()?.lowercase() } }
                .distinctUntilChanged()
                .drop(1)
                .collect { graph.madeForYou.refresh() }
        }
    }

    /** The Home shows: today's mixes (remade when the day turns). */
    fun refreshMixes() = graph.madeForYou.refresh()

    val state: StateFlow<HomeUiState> = combine(library.tracks, library.albums, mixes) { tracks, albums, mixes ->
        HomeUiState(
            loaded = true,
            empty = tracks.isEmpty(),
            mixes = mixes,
            recentlyPlayed = recentlyPlayedItems(tracks, albums.associateBy { it.key.normalized }),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    // ------------------------------------------------------------------ actions

    fun playSmartLibrary() = graph.player.playSmart(SmartSeed.Library)
    /** Plays the mix exactly as its card and page show it (your songs and the new ones). */
    fun playMix(id: String) {
        val mix = graph.madeForYou.byId(id) ?: return
        if (mix.tracks.isEmpty()) return
        graph.player.play(mix.tracks, 0, PlaySource(PlaySource.Type.MIX, mix.title, mix.id))
    }
    fun togglePlayPause() = graph.player.togglePlayPause()

    fun playSongs(tracks: List<Track>, index: Int, sourceTitle: String) {
        if (tracks.isEmpty()) return
        graph.player.play(tracks, index.coerceIn(0, tracks.lastIndex), PlaySource(PlaySource.Type.SONGS, sourceTitle))
        Chrome.openPlayer()
    }
}
