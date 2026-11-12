package com.villamediana.sound.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.discover.ForYou
import com.villamediana.sound.discover.GenreCatalog
import com.villamediana.sound.discover.GenreDef
import com.villamediana.sound.discover.HomeMixes
import com.villamediana.sound.R
import com.villamediana.sound.smart.Mix
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
    /** "Porque você curte": one mix per loved artist, a single row of cards (fixed for the 6-hour slot). */
    val because: List<Mix> = emptyList(),
    /** "Seus gêneros": one mix per genre listened to and picked ("Latino hoje"), a single row of cards. */
    val genres: List<Mix> = emptyList(),
    val loading: Boolean = false,
    /** Pulled down to refresh, until the new lists are in. */
    val refreshing: Boolean = false,
) {
    val showPicker: Boolean get() = ready && (!chosen || editing)
    val hasContent: Boolean get() = discoveries.isNotEmpty() || because.isNotEmpty() || genres.isNotEmpty()
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

    /** Pull to refresh: searches again, reads the taste again and draws new mixes. */
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
                    runCatching { forYou.because() }.onSuccess { rows ->
                        val mixes = rows.map(::becauseMix)
                        graph.homeMixes.replace(HomeMixes.BECAUSE, mixes)
                        _state.update { it.copy(because = mixes) }
                        // Each card in its own color (from its covers), like "Feitos pra você".
                        val tinted = tinted(mixes)
                        graph.homeMixes.replace(HomeMixes.BECAUSE, tinted)
                        _state.update { it.copy(because = tinted) }
                    }
                }
                launch {
                    val mixes = genres.map { g -> async { g to runCatching { forYou.shelf(g, force) }.getOrDefault(emptyList()) } }.awaitAll()
                        .filter { (_, tracks) -> tracks.size >= MIN_MIX }
                        .map { (g, tracks) -> genreMix(g, tracks) }
                    graph.homeMixes.replace(HomeMixes.GENRE, mixes)
                    _state.update { it.copy(genres = mixes) }
                    val tinted = tinted(mixes)
                    graph.homeMixes.replace(HomeMixes.GENRE, tinted)
                    _state.update { it.copy(genres = tinted) }
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

    private fun res() = getApplication<Application>().resources

    /**
     * The mixes with their color: the first cover that gives one (the thumbnails are usually in
     * Coil's cache already, the cards show them). A mix without one keeps the app's color.
     */
    private suspend fun tinted(mixes: List<Mix>): List<Mix> = coroutineScope {
        mixes.map { mix ->
            async {
                if (mix.tint != null) return@async mix
                val color = mix.covers.firstNotNullOfOrNull { url ->
                    runCatching { com.villamediana.sound.ui.search.RemoteCoverColors.extract(getApplication(), url) }.getOrNull()
                }
                if (color != null) mix.copy(tint = color) else mix
            }
        }.awaitAll()
    }

    /** "Latino hoje": the genre's mix of the day, with its main artists. */
    private fun genreMix(g: GenreDef, tracks: List<RemoteTrack>) = HomeMixes.build(
        id = HomeMixes.GENRE + g.id,
        title = res().getString(R.string.discover_genre_mix, res().getString(g.label)),
        tracks = tracks,
        kind = Mix.Kind.GENRE,
        subtitle = { artists -> res().getString(R.string.discover_mix_artists, artists) },
    )

    /** An artist's card: songs in their style, the other artists of the mix underneath. */
    private fun becauseMix(row: ForYou.BecauseRow) = HomeMixes.build(
        id = HomeMixes.BECAUSE + row.artistKey,
        title = row.artist,
        tracks = row.tracks,
        kind = Mix.Kind.ARTIST,
        subtitle = { artists -> if (artists.isBlank()) res().getString(R.string.discover_because_card_plain) else res().getString(R.string.discover_mix_artists, artists) },
        except = row.artist,
    )

    /** A mix card plays it from the start (or pauses/resumes it when it is what plays). */
    fun playMix(mix: Mix) {
        val player = graph.player
        if (player.state.value.isFromMix(mix)) {
            player.togglePlayPause()
            return
        }
        player.play(mix.tracks, 0, PlaySource(PlaySource.Type.MIX, mix.title, mix.id))
    }

    private companion object {
        /** A genre with fewer songs than this has no card (not a mix yet). */
        const val MIN_MIX = 8
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
