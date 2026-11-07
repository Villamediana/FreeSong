package com.villamediana.sound.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.discover.GenreCatalog
import com.villamediana.sound.discover.GenreDef
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.playback.PlaySource
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One "Pop pra você" row. */
data class Shelf(val genre: GenreDef, val tracks: List<RemoteTrack>)

data class DiscoverUiState(
    /** Settings read at least once. */
    val ready: Boolean = false,
    /** The picker was answered (or skipped). */
    val chosen: Boolean = false,
    val favorites: List<String> = emptyList(),
    /** Picker open again from "Editar". */
    val editing: Boolean = false,
    /** Genres ticked in the picker. */
    val selection: Set<String> = emptySet(),
    /** Song covers per genre for the picker cards. */
    val covers: Map<String, List<String>> = emptyMap(),
    val shelves: List<Shelf> = emptyList(),
    val loadingShelves: Boolean = false,
) {
    val showPicker: Boolean get() = ready && (!chosen || editing)
}

class DiscoverViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = SoundApplication.graph
    private val repo = graph.discover

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state

    private var shelvesJob: Job? = null

    init {
        viewModelScope.launch {
            graph.settings.settings.map { it.genresChosen to it.favoriteGenres }.distinctUntilChanged().collect { (chosen, favs) ->
                val ordered = GenreCatalog.all.map { it.id }.filter { it in favs }
                _state.update { s -> s.copy(ready = true, chosen = chosen, favorites = ordered, selection = if (s.editing) s.selection else favs) }
                loadShelves(force = false)
            }
        }
        viewModelScope.launch {
            // Cached covers first (instant), then the stale ones from the network.
            _state.update { it.copy(covers = repo.covers(GenreCatalog.all, refresh = false)) }
            val fresh = runCatching { repo.covers(GenreCatalog.all) }.getOrNull()
            if (fresh != null) _state.update { it.copy(covers = fresh) }
        }
    }

    /** Called whenever the Home shows: shelves follow their 6-hour slot (cheap when nothing changed). */
    fun onShown() = loadShelves(force = false)

    fun refresh() = loadShelves(force = true)

    private fun loadShelves(force: Boolean) {
        val genres = _state.value.favorites.mapNotNull(GenreCatalog::byId)
        shelvesJob?.cancel()
        if (genres.isEmpty()) {
            _state.update { it.copy(shelves = emptyList(), loadingShelves = false) }
            return
        }
        shelvesJob = viewModelScope.launch {
            // Last shelves on disk paint at once; the live ones replace them as they arrive.
            val cached = genres.map { g -> Shelf(g, repo.cachedShelf(g)) }.filter { it.tracks.isNotEmpty() }
            _state.update { it.copy(shelves = if (it.shelves.isEmpty()) cached else it.shelves, loadingShelves = true) }
            val live = genres.map { g -> async { Shelf(g, runCatching { repo.shelf(g, force) }.getOrDefault(emptyList())) } }.awaitAll()
            _state.update { it.copy(shelves = live.filter { s -> s.tracks.isNotEmpty() }, loadingShelves = false) }
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

    fun play(shelf: Shelf, index: Int, title: String) =
        graph.player.playOnline(shelf.tracks, index, PlaySource(PlaySource.Type.SEARCH, title, "discover:" + shelf.genre.id))
}
