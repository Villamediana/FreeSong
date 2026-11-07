package com.villamediana.sound.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.discover.DiscoverMixes
import com.villamediana.sound.discover.GenreCatalog
import com.villamediana.sound.discover.GenreDef
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.SearchFilter
import com.villamediana.sound.playback.PlaySource
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One "Rock pra você" row. */
data class Shelf(val genre: GenreDef, val tracks: List<RemoteTrack>)

data class ArtistPick(val name: String, val imageUrl: String?)

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
    /** "Porque você ouviu <title>". */
    val becauseTitle: String? = null,
    val because: List<RemoteTrack> = emptyList(),
    val artists: List<ArtistPick> = emptyList(),
    val playlists: List<RemoteCollection> = emptyList(),
    val shelves: List<Shelf> = emptyList(),
    val loading: Boolean = false,
) {
    val showPicker: Boolean get() = ready && (!chosen || editing)
    val hasContent: Boolean get() = because.isNotEmpty() || shelves.isNotEmpty()
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
class DiscoverViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = SoundApplication.graph
    private val repo = graph.discover

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state

    private var loadJob: Job? = null
    /** The song "Porque você ouviu…" was made from. */
    private var becauseFor: String? = null

    init {
        viewModelScope.launch {
            graph.settings.settings.map { it.genresChosen to it.favoriteGenres }.distinctUntilChanged().collect { (chosen, favs) ->
                val ordered = GenreCatalog.all.map { it.id }.filter { it in favs }
                _state.update { s -> s.copy(ready = true, chosen = chosen, favorites = ordered, selection = if (s.editing) s.selection else favs) }
                load(force = false)
            }
        }
        // "Porque você ouviu…" follows what is being heard (a song kept for a few seconds, not every skip).
        viewModelScope.launch {
            graph.player.state.map { it.current?.videoId?.takeIf(String::isNotBlank) }
                .distinctUntilChanged()
                .filterNotNull()
                .debounce(BECAUSE_AFTER_MS)
                .collect { if (_state.value.favorites.isNotEmpty() && loadJob?.isActive != true) loadBecause() }
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

    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        val genres = _state.value.favorites.mapNotNull(GenreCatalog::byId)
        loadJob?.cancel()
        if (genres.isEmpty()) {
            _state.update { it.copy(because = emptyList(), becauseTitle = null, artists = emptyList(), playlists = emptyList(), shelves = emptyList(), loading = false) }
            return
        }
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            // Each section lands on its own as soon as it is ready.
            coroutineScope {
            launch {
                val list = genres.map { g -> async { Shelf(g, runCatching { repo.shelf(g, force) }.getOrDefault(emptyList())) } }.awaitAll()
                    .filter { it.tracks.isNotEmpty() }
                _state.update { s ->
                    s.copy(
                        shelves = list,
                        artists = DiscoverMixes.topArtists(list.map { it.tracks }, 10).map { (n, img) -> ArtistPick(n, img) },
                    )
                }
            }
            launch {
                val lists = genres.map { g -> async { runCatching { repo.collections("playlists:${g.id}", "${g.term} playlist", SearchFilter.PLAYLISTS, 3) }.getOrDefault(emptyList()) } }.awaitAll()
                _state.update { it.copy(playlists = DiscoverMixes.blendCollections(lists, 9)) }
            }
            launch { loadBecause() }
            }
            _state.update { it.copy(loading = false) }
        }
    }

    /** Radio of the last song heard: the one playing, else the last one listened (yours or streamed). */
    private suspend fun loadBecause() {
        val (videoId, title) = lastHeard() ?: return
        if (videoId == becauseFor && _state.value.because.isNotEmpty()) return
        val list = runCatching { repo.related(videoId) }.getOrDefault(emptyList())
        if (list.isEmpty()) return // keep what shows; asked again next time
        becauseFor = videoId
        _state.update { it.copy(becauseTitle = title, because = list) }
    }

    private suspend fun lastHeard(): Pair<String, String>? {
        graph.player.state.value.current?.let { t -> t.videoId?.takeIf(String::isNotBlank)?.let { return it to t.title } }
        val mine = runCatching { graph.library.allTracks() }.getOrDefault(emptyList())
            .filter { !it.videoId.isNullOrBlank() && it.lastPlayedAt != null }
            .maxByOrNull { it.lastPlayedAt!! }
        val streamed = runCatching { graph.library.onlinePlaysListSince(System.currentTimeMillis() - HEARD_WITHIN_MS) }
            .getOrDefault(emptyList()).firstOrNull()
        return when {
            streamed != null && (mine == null || streamed.playedAt > mine.lastPlayedAt!!) -> streamed.videoId to streamed.title
            mine != null -> mine.videoId!! to mine.title
            else -> null
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
        const val BECAUSE_AFTER_MS = 8_000L
        const val HEARD_WITHIN_MS = 30L * 24 * 3_600_000L
    }

    /**
     * Home lists play as smart queues: a genre shelf, then the radio of where it ends;
     * "Porque você ouviu…" is a radio already.
     */
    fun play(tracks: List<RemoteTrack>, index: Int, title: String, id: String) {
        val type = if (id.startsWith("genre:")) PlaySource.Type.GENRE else PlaySource.Type.RADIO
        graph.player.playOnline(tracks, index, PlaySource(type, title, "discover:$id"))
    }

    /** Finds the artist's page (once) and hands it to [open]. */
    fun openArtist(name: String, open: (RemoteCollection) -> Unit) {
        viewModelScope.launch { repo.artist(name)?.let(open) }
    }
}
