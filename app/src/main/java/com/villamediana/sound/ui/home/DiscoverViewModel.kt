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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One "Rock pra você" row. */
data class Shelf(val genre: GenreDef, val tracks: List<RemoteTrack>)

/** A mix card of the Home ("Seu mix", "Descobertas"…). */
data class DiscoverMix(val kind: MixKind, val tracks: List<RemoteTrack>, val covers: List<String>, val tint: Long)

enum class MixKind { YOURS, NEW, CLASSICS, CHILL }

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
    val mixes: List<DiscoverMix> = emptyList(),
    /** "Porque você ouviu <title>". */
    val becauseTitle: String? = null,
    val because: List<RemoteTrack> = emptyList(),
    val artists: List<ArtistPick> = emptyList(),
    val playlists: List<RemoteCollection> = emptyList(),
    val shelves: List<Shelf> = emptyList(),
    val loading: Boolean = false,
) {
    val showPicker: Boolean get() = ready && (!chosen || editing)
    val hasContent: Boolean get() = mixes.isNotEmpty() || because.isNotEmpty() || shelves.isNotEmpty()
}

class DiscoverViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = SoundApplication.graph
    private val repo = graph.discover

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

    /** Called whenever the Home shows: lists follow their 6-hour slot (cheap when nothing changed). */
    fun onShown() = load(force = false)

    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        val genres = _state.value.favorites.mapNotNull(GenreCatalog::byId)
        loadJob?.cancel()
        if (genres.isEmpty()) {
            _state.update { it.copy(mixes = emptyList(), because = emptyList(), becauseTitle = null, artists = emptyList(), playlists = emptyList(), shelves = emptyList(), loading = false) }
            return
        }
        val seed = GenreCatalog.slotOf(System.currentTimeMillis())
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            // Each section lands on its own as soon as it is ready.
            val shelves = async {
                genres.map { g -> async { Shelf(g, runCatching { repo.shelf(g, force) }.getOrDefault(emptyList())) } }.awaitAll()
                    .filter { it.tracks.isNotEmpty() }
                    .also { list ->
                        _state.update { s ->
                            s.copy(
                                shelves = list,
                                artists = DiscoverMixes.topArtists(list.map { it.tracks }, 10).map { (n, img) -> ArtistPick(n, img) },
                            )
                        }
                    }
            }
            val variants = MixKind.entries.filter { it != MixKind.YOURS }.map { kind ->
                async {
                    val lists = genres.map { g ->
                        async { runCatching { repo.songs("${kind.name.lowercase()}:${g.id}", queryFor(kind, g), 12, force) }.getOrDefault(emptyList()) }
                    }.awaitAll()
                    kind to lists
                }
            }
            launch {
                val lists = genres.map { g -> async { runCatching { repo.collections("playlists:${g.id}", "${g.term} playlist", SearchFilter.PLAYLISTS, 3) }.getOrDefault(emptyList()) } }.awaitAll()
                _state.update { it.copy(playlists = DiscoverMixes.blendCollections(lists, 9)) }
            }
            launch { loadBecause() }

            val shelfLists = shelves.await().map { it.tracks }
            val tint = genres.first().tint
            val mixes = mutableListOf<DiscoverMix>()
            DiscoverMixes.blend(shelfLists, 30, seed).takeIf { it.isNotEmpty() }?.let { mixes += DiscoverMix(MixKind.YOURS, it, DiscoverMixes.covers(it), tint) }
            _state.update { it.copy(mixes = mixes.toList()) }
            variants.awaitAll().forEach { (kind, lists) ->
                val tracks = DiscoverMixes.blend(lists, 30, seed)
                if (tracks.isNotEmpty()) mixes += DiscoverMix(kind, tracks, DiscoverMixes.covers(tracks), genres[(kind.ordinal) % genres.size].tint)
                _state.update { it.copy(mixes = mixes.toList()) }
            }
            _state.update { it.copy(loading = false) }
        }
    }

    /** Radio of the last song heard (now playing, else the library's last played). */
    private suspend fun loadBecause() {
        val current = graph.player.state.value.current?.takeIf { it.videoId != null }
        val last = current ?: runCatching { graph.library.allTracks() }.getOrDefault(emptyList())
            .filter { it.videoId != null && it.lastPlayedAt != null }.maxByOrNull { it.lastPlayedAt!! }
        val videoId = last?.videoId ?: return
        val list = runCatching { repo.related(videoId) }.getOrDefault(emptyList())
        _state.update { it.copy(becauseTitle = last.title, because = list) }
    }

    private fun queryFor(kind: MixKind, g: GenreDef): String = when (kind) {
        MixKind.NEW -> "${g.term} new releases"
        MixKind.CLASSICS -> "${g.term} classics"
        MixKind.CHILL -> "${g.term} acoustic chill"
        MixKind.YOURS -> g.query
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

    /**
     * Home lists play as smart queues: a mix or a genre shelf, then the radio of where it ends;
     * "Porque você ouviu…" is a radio already.
     */
    fun play(tracks: List<RemoteTrack>, index: Int, title: String, id: String) {
        val type = when {
            id.startsWith("mix:") -> PlaySource.Type.MIX
            id.startsWith("genre:") -> PlaySource.Type.GENRE
            else -> PlaySource.Type.RADIO
        }
        graph.player.playOnline(tracks, index, PlaySource(type, title, "discover:$id"))
    }

    /** Finds the artist's page (once) and hands it to [open]. */
    fun openArtist(name: String, open: (RemoteCollection) -> Unit) {
        viewModelScope.launch { repo.artist(name)?.let(open) }
    }
}
