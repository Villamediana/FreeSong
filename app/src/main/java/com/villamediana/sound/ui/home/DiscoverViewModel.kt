package com.villamediana.sound.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.discover.DiscoverMixes
import com.villamediana.sound.discover.ForYou
import com.villamediana.sound.discover.GenreCatalog
import com.villamediana.sound.discover.GenreDef
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.playback.PlaySource
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One "Rock pra você" row. */
data class Shelf(val genre: GenreDef, val tracks: List<RemoteTrack>)

data class DiscoverUiState(
    /** Settings read at least once. */
    val ready: Boolean = false,
    /** The picker was answered (or skipped). */
    val chosen: Boolean = false,
    val favorites: List<String> = emptyList(),
    /** Picker open again from "Editar gêneros". */
    val editing: Boolean = false,
    /** Genres ticked in the picker. */
    val selection: Set<String> = emptySet(),
    /** Song covers per genre for the picker cards. */
    val covers: Map<String, List<String>> = emptyMap(),
    /** "Descobertas pra você": the day's new songs in the listener's style. */
    val discoveries: List<RemoteTrack> = emptyList(),
    /** "Porque você curte <artista>" rows (fixed for the 6-hour slot). */
    val because: List<ForYou.BecauseRow> = emptyList(),
    val playlists: List<RemoteCollection> = emptyList(),
    val shelves: List<Shelf> = emptyList(),
    val loading: Boolean = false,
    /** Pulled down to refresh, until the new lists are in. */
    val refreshing: Boolean = false,
) {
    val showPicker: Boolean get() = ready && (!chosen || editing)
    val hasContent: Boolean get() = discoveries.isNotEmpty() || because.isNotEmpty() || shelves.isNotEmpty()
}

class DiscoverViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = SoundApplication.graph
    private val repo = graph.discover
    private val forYou = graph.forYou

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            graph.settings.settings.map { it.genresChosen to it.favoriteGenres }.distinctUntilChanged().collect { (chosen, favs) ->
                val ordered = GenreCatalog.all.map { it.id }.filter { it in favs }
                _state.update { s -> s.copy(ready = true, chosen = chosen, favorites = ordered, selection = if (s.editing) s.selection else favs) }
                load(force = false)
            }
        }
        viewModelScope.launch {
            // Cached covers first (instant), then the stale ones from the network.
            _state.update { it.copy(covers = repo.covers(GenreCatalog.all, refresh = false)) }
            val fresh = runCatching { repo.covers(GenreCatalog.all) }.getOrNull()
            if (fresh != null) _state.update { it.copy(covers = fresh) }
        }
    }

    /**
     * Called whenever the Home shows (opening, coming back to it): lists follow their 6-hour slot
     * and what failed is asked again (cheap when nothing changed). A load under way carries on.
     */
    fun onShown() {
        if (loadJob?.isActive == true) return
        load(force = false)
    }

    /** Pull to refresh: searches again, reads the taste again and draws new shelves and playlists. */
    fun refresh() {
        repo.reshuffle()
        load(force = true, pulled = true)
    }

    /**
     * Everything on the Home made from the listener's taste: the day's discoveries, "Porque você
     * curte…" (fixed for the slot), and shelves of the genres they listen to and picked. Each section
     * lands on its own as soon as it is ready; a failed one keeps what was showing.
     */
    private fun load(force: Boolean, pulled: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, refreshing = pulled) }
            if (force) forYou.invalidate()
            val favorites = _state.value.favorites
            val genres = runCatching { forYou.genres(favorites) }.getOrElse { favorites.mapNotNull(GenreCatalog::byId) }
            coroutineScope {
                launch {
                    runCatching { forYou.discoveries() }.onSuccess { list -> _state.update { it.copy(discoveries = list) } }
                }
                launch {
                    runCatching { forYou.because() }.onSuccess { rows -> _state.update { it.copy(because = rows) } }
                }
                launch {
                    val list = genres.map { g -> async { Shelf(g, runCatching { forYou.shelf(g, force) }.getOrDefault(emptyList())) } }.awaitAll()
                        .filter { it.tracks.isNotEmpty() }
                    _state.update { it.copy(shelves = list) }
                }
                launch {
                    val lists = genres.take(PLAYLIST_GENRES).map { g -> async { runCatching { repo.playlists(g, 3, force) }.getOrDefault(emptyList()) } }.awaitAll()
                    _state.update { it.copy(playlists = DiscoverMixes.blendCollections(lists, 9)) }
                }
            }
            _state.update { it.copy(loading = false, refreshing = false) }
        }
    }

    fun toggle(id: String) = _state.update { s -> s.copy(selection = if (id in s.selection) s.selection - id else s.selection + id) }

    fun confirm() {
        val picked = _state.value.selection
        _state.update { it.copy(editing = false) }
        viewModelScope.launch { graph.settings.setFavoriteGenres(picked) }
    }

    fun skip() {
        _state.update { it.copy(editing = false) }
        viewModelScope.launch { graph.settings.setFavoriteGenres(_state.value.favorites.toSet()) }
    }

    fun edit() = _state.update { it.copy(editing = true, selection = it.favorites.toSet()) }

    private companion object {
        /** Playlists come from the genres listened most. */
        const val PLAYLIST_GENRES = 3
    }

    /**
     * Home lists play as smart queues: a genre shelf, then the radio of where it ends; the
     * discoveries and "Porque você curte…" are radios already.
     */
    fun play(tracks: List<RemoteTrack>, index: Int, title: String, id: String) {
        val type = if (id.startsWith("genre:")) PlaySource.Type.GENRE else PlaySource.Type.RADIO
        graph.player.playOnline(tracks, index, PlaySource(type, title, "discover:$id"))
    }
}
