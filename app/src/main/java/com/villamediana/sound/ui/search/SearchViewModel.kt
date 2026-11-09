package com.villamediana.sound.ui.search

import kotlinx.coroutines.delay
import com.villamediana.sound.online.CoverMatch
import com.villamediana.sound.recognition.SongMatch
import com.villamediana.sound.recognition.Recognition
import com.villamediana.sound.recognition.MicSongListener
import com.villamediana.sound.recognition.SongListener
import com.villamediana.sound.download.DownloadWait
import com.villamediana.sound.playback.SmartSeed
import com.villamediana.sound.ui.nav.Chrome
import androidx.annotation.StringRes
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.DownloadTask
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.model.GenreSummary
import com.villamediana.sound.data.model.LocalSearchResult
import com.villamediana.sound.download.DownloadController
import com.villamediana.sound.online.OnlineException
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.ResolvedLink
import com.villamediana.sound.online.SearchFilter
import com.villamediana.sound.online.SearchItem
import com.villamediana.sound.online.SearchPage
import com.villamediana.sound.online.YouTubeSource
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.PlayerConnection
import com.villamediana.sound.ui.downloads.DownloadGrouping
import com.villamediana.sound.ui.downloads.DownloadOverview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class SearchMode { Idle, Typing, Results }

sealed interface SearchError {
    data object Offline : SearchError
    data object RateLimited : SearchError
    data object Generic : SearchError
}

/** Online results of one (query, filter). */
data class ResultsState(
    val query: String,
    val filter: SearchFilter,
    val items: List<SearchItem> = emptyList(),
    val page: SearchPage? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: SearchError? = null,
    val moreError: Boolean = false,
    /** Technical cause of [error] (exception chain), shown only on demand. */
    val errorDetail: String? = null,
) {
    val hasMore: Boolean get() = page?.hasMore == true
}

/** "OnlineException: … ← IOException: …" — short, for the error details line. */
internal fun Throwable.describeChain(max: Int = 400): String =
    generateSequence(this) { it.cause?.takeIf { c -> c !== it } }.take(4)
        .joinToString(" ← ") { "${it::class.java.simpleName}: ${it.message.orEmpty().take(140)}" }
        .take(max)

/** The card on top of the Search screen for a pasted/shared song link. */
sealed interface LinkCard {
    data object Resolving : LinkCard
    data class Found(val track: RemoteTrack) : LinkCard
}

sealed interface SearchEvent {
    data class OpenRemote(val collection: RemoteCollection) : SearchEvent
    data class Message(@StringRes val text: Int) : SearchEvent
}

/** Stable LazyColumn key of a result. */
val SearchItem.key: String
    get() = when (this) {
        is SearchItem.Song -> "s:" + track.videoId
        is SearchItem.Collection -> "c:" + collection.kind.name + ":" + collection.url
    }

internal fun Throwable.toSearchError(online: Boolean): SearchError = when {
    !online -> SearchError.Offline
    (this as? OnlineException)?.kind == OnlineException.Kind.NO_NETWORK -> SearchError.Offline
    (this as? OnlineException)?.kind == OnlineException.Kind.RATE_LIMITED -> SearchError.RateLimited
    else -> SearchError.Generic
}

@StringRes
internal fun Throwable.linkMessage(online: Boolean): Int = if (!online) R.string.search_link_offline else when ((this as? OnlineException)?.kind) {
    OnlineException.Kind.NO_NETWORK -> R.string.search_link_offline
    OnlineException.Kind.NOT_FOUND -> R.string.search_link_not_found
    OnlineException.Kind.AGE_RESTRICTED -> R.string.search_link_age
    OnlineException.Kind.GEO_BLOCKED -> R.string.search_link_geo
    OnlineException.Kind.UNAVAILABLE -> R.string.search_link_unavailable
    OnlineException.Kind.RATE_LIMITED -> R.string.search_error_rate_limited
    else -> R.string.search_link_error
}

/** Runs [block], turning failures into a Result but letting cancellation through. */
internal inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val youTube: YouTubeSource,
    private val library: LibraryRepository,
    private val downloads: DownloadController,
    private val player: PlayerConnection,
    private val recentStore: RecentSearchStore,
    onlineFlow: Flow<Boolean>,
    initiallyOnline: Boolean,
    /** Settings.playOnlineByDefault: a tap streams the song (true) or downloads it (false). */
    playOnlineFlow: Flow<Boolean> = flowOf(true),
    /** "Rádio de %s": what the player says a tapped search result plays from. */
    private val radioTitle: (String) -> String = { it },
    /** "Curtir já baixa" (Settings): rows show the heart alone. */
    likeDownloadsFlow: Flow<Boolean> = flowOf(false),
    /** The heart of a row (AppGraph.likeOnline: Curtidas, and the download rule). */
    private val onLike: (RemoteTrack, Boolean) -> Unit = { _, _ -> },
    /** The download button of a song on the phone or on its way (AppGraph.removeDownload). */
    private val onRemoveDownload: (String) -> Unit = {},
    /** The microphone button: names the song playing nearby. Null = no microphone search. */
    private val listener: SongListener? = null,
) : ViewModel() {

    val online: StateFlow<Boolean> = onlineFlow.stateIn(viewModelScope, SharingStarted.Eagerly, initiallyOnline)

    val playOnline: StateFlow<Boolean> = playOnlineFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The song playing now — its row in the results gets the live bars. */
    val nowPlaying: StateFlow<NowPlaying?> = player.state.nowPlaying()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ------------------------------------------------------------------ query

    var query by mutableStateOf(TextFieldValue(""))
        private set
    var filter by mutableStateOf(SearchFilter.SONGS)
        private set
    /** Query of the results on screen (null = no search submitted). */
    var submitted by mutableStateOf<String?>(null)
        private set

    val mode: SearchMode by derivedStateOf {
        val text = query.text.trim()
        when {
            text.isEmpty() -> SearchMode.Idle
            submitted == text -> SearchMode.Results
            else -> SearchMode.Typing
        }
    }

    private val typed: Flow<String> = snapshotFlow { query.text.trim() }.distinctUntilChanged()

    val suggestions: StateFlow<List<String>> = typed
        .debounce(200)
        .mapLatest { q ->
            if (q.isEmpty() || YouTubeLinks.contains(q) || !online.value) emptyList()
            else attempt { youTube.suggestions(q) }.getOrDefault(emptyList())
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(q, ignoreCase = true) }
                .distinctBy { it.lowercase() }
                .take(7)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Local matches of what is typed (or of the submitted query). */
    val local: StateFlow<LocalSearchResult?> = typed
        .debounce(120)
        .flatMapLatest { q -> if (q.isEmpty()) flowOf(null) else library.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recents: StateFlow<List<String>> = recentStore.recents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val genres: StateFlow<List<GenreSummary>> = library.genres
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ------------------------------------------------------------------ "what's playing?" (microphone)

    /** The listening screen: idle, listening (seconds heard), found, not found, failed. */
    sealed interface Hearing {
        data object Idle : Hearing
        data class Listening(val seconds: Float) : Hearing
        data class Found(val match: SongMatch) : Hearing
        data object NotFound : Hearing
        data class Failed(val noMicrophone: Boolean) : Hearing
    }

    private val _hearing = MutableStateFlow<Hearing>(Hearing.Idle)
    val hearing: StateFlow<Hearing> = _hearing

    /** Loudness heard right now, 0..1 (the rings breathe with it). */
    val hearingLevel = MutableStateFlow(0f)

    private var hearJob: Job? = null

    val canListen: Boolean get() = listener != null

    /**
     * Listens to the room and names the song: the music of the app pauses (it would hear itself),
     * the found song goes into the search with its results, and plays — as a radio, with the
     * player open.
     */
    fun listen() {
        val l = listener ?: return
        hearJob?.cancel()
        player.pauseHere()
        hearJob = viewModelScope.launch {
            _hearing.value = Hearing.Listening(0f)
            val outcome = l.recognize(
                onLevel = { hearingLevel.value = it },
                onSeconds = { sec -> if (_hearing.value is Hearing.Listening) _hearing.value = Hearing.Listening(sec) },
            )
            hearingLevel.value = 0f
            when (outcome) {
                is Recognition.Found -> {
                    _hearing.value = Hearing.Found(outcome.match)
                    playFound(outcome.match)
                }
                Recognition.NotFound -> _hearing.value = Hearing.NotFound
                is Recognition.Failed -> _hearing.value = Hearing.Failed(outcome.noMicrophone)
            }
        }
    }

    fun stopListening() {
        hearJob?.cancel()
        hearJob = null
        hearingLevel.value = 0f
        _hearing.value = Hearing.Idle
    }

    private suspend fun playFound(match: SongMatch) {
        val q = "${match.artist} ${match.title}".trim()
        submit(q)
        val songs = attempt { youTube.search(q, SearchFilter.SONGS).items.filterIsInstance<SearchItem.Song>().map { it.track } }.getOrNull().orEmpty()
        val song = songs.firstOrNull { CoverMatch.same(match.title, match.artist, it.title, it.artist) } ?: songs.firstOrNull()
        delay(FOUND_SHOWN_MS) // the found card stays a moment
        if (song != null) {
            player.playOnline(listOf(song), 0, PlaySource(PlaySource.Type.RADIO, radioTitle(song.title), song.videoId))
            Chrome.openPlayer()
        }
        _hearing.value = Hearing.Idle
    }

    fun onFieldChange(value: TextFieldValue) {
        query = value
        if (value.text.isBlank()) {
            submitted = null
            selection = emptyMap()
        }
    }

    fun clearField() {
        query = TextFieldValue("")
        submitted = null
        selection = emptyMap()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        states.clear()
        _results.value = null
    }

    /** The ↖ arrow of a suggestion: puts it in the field to keep typing. */
    fun fillWith(text: String) {
        val t = "$text "
        query = TextFieldValue(t, TextRange(t.length))
    }

    fun submit(text: String = query.text) {
        val q = text.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return
        val link = YouTubeLinks.find(q)
        if (link != null) {
            clearField()
            resolveLink(link)
            return
        }
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        states.clear()
        query = TextFieldValue(q, TextRange(q.length))
        submitted = q
        selection = emptyMap()
        viewModelScope.launch { attempt { recentStore.add(q) } }
        load(filter, force = true)
    }

    fun removeRecent(query: String) {
        viewModelScope.launch { attempt { recentStore.remove(query) } }
    }

    fun clearRecents() {
        viewModelScope.launch { attempt { recentStore.clear() } }
    }

    // ------------------------------------------------------------------ results

    private val states = HashMap<SearchFilter, ResultsState>()
    private val jobs = HashMap<SearchFilter, Job>()
    private val _results = MutableStateFlow<ResultsState?>(null)
    val results: StateFlow<ResultsState?> = _results

    fun selectFilter(f: SearchFilter) {
        if (f == filter) return
        filter = f
        selection = emptyMap()
        if (submitted != null) load(f) else _results.value = null
    }

    private fun update(q: String, f: SearchFilter, transform: (ResultsState) -> ResultsState) {
        if (q != submitted) return
        val next = transform(states[f] ?: ResultsState(q, f))
        states[f] = next
        if (f == filter) _results.value = next
    }

    private fun load(f: SearchFilter, force: Boolean = false) {
        val q = submitted ?: return
        val existing = states[f]
        if (!force && existing != null && existing.error == null) {
            _results.value = existing
            return
        }
        jobs[f]?.cancel()
        if (!online.value) {
            update(q, f) { ResultsState(q, f, error = SearchError.Offline) }
            return
        }
        update(q, f) { ResultsState(q, f, loading = true) }
        jobs[f] = viewModelScope.launch {
            attempt { youTube.search(q, f) }
                .onSuccess { page -> update(q, f) { ResultsState(q, f, items = page.items.distinctBy { it.key }, page = page) } }
                .onFailure { e ->
                    android.util.Log.w("Sound", "search failed: $q / $f", e)
                    update(q, f) { ResultsState(q, f, error = e.toSearchError(online.value), errorDetail = e.describeChain()) }
                }
        }
    }

    /** Infinite scroll: next page of the visible filter. */
    fun loadMore() {
        val q = submitted ?: return
        val f = filter
        val s = states[f] ?: return
        val page = s.page ?: return
        if (s.loading || s.loadingMore || s.moreError || !page.hasMore || jobs[f]?.isActive == true) return
        update(q, f) { it.copy(loadingMore = true) }
        jobs[f] = viewModelScope.launch {
            attempt { youTube.more(page) }
                .onSuccess { next ->
                    update(q, f) { cur ->
                        val seen = cur.items.mapTo(HashSet()) { it.key }
                        cur.copy(items = cur.items + next.items.filter { seen.add(it.key) }, page = next, loadingMore = false)
                    }
                }
                .onFailure { update(q, f) { it.copy(loadingMore = false, moreError = true) } }
        }
    }

    fun retry() {
        val q = submitted ?: return
        val s = states[filter]
        if (s != null && s.moreError) {
            update(q, filter) { it.copy(moreError = false) }
            loadMore()
        } else {
            load(filter, force = true)
        }
    }

    // ------------------------------------------------------------------ downloads

    private val tasks: Flow<List<DownloadTask>> = downloads.tasks
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** Songs the user just asked for — shown as queued until the queue has them. */
    private val requested = MutableStateFlow<Set<String>>(emptySet())

    /** Video ids in Curtidas: the hearts of the rows. */
    val likedIds: StateFlow<Set<String>> = library.likedVideoIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** "Curtir já baixa": rows show the heart alone. */
    val likeDownloads: StateFlow<Boolean> = likeDownloadsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** What queued downloads wait for: the rows' buttons and the message after a tap say it. */
    val downloadWait: StateFlow<DownloadWait> = downloads.wait.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadWait.NONE)

    fun like(track: RemoteTrack, liked: Boolean) {
        onLike(track, liked)
        // With "Curtir já baixa" the like is a download too: offline, say when it will happen.
        if (liked && likeDownloads.value) waitMessage()?.let { _events.trySend(SearchEvent.Message(it)) }
    }

    /** "Baixa quando conectar" / "Baixa quando estiver no Wi-Fi", or null when downloads run now. */
    private fun waitMessage(): Int? = when (downloadWait.value) {
        DownloadWait.NONE -> null
        DownloadWait.NETWORK -> R.string.download_wait_network
        DownloadWait.WIFI -> R.string.download_wait_wifi
    }

    val lookup: StateFlow<DownloadLookup> = combine(library.downloadedVideoIds, tasks, requested) { done, all, asked ->
        DownloadLookup.build(done, all, asked)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadLookup.Empty)

    val overview: StateFlow<DownloadOverview> = tasks.map { DownloadGrouping.overview(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadOverview.Empty)

    fun download(tracks: List<RemoteTrack>) {
        val current = lookup.value
        val fresh = tracks.distinctBy { it.videoId }.filter {
            val s = current.stateOf(it.videoId)
            s is ItemDownload.None || s is ItemDownload.Failed
        }
        if (fresh.isEmpty()) return
        val ids = fresh.map { it.videoId }.toSet()
        requested.update { it + ids }
        viewModelScope.launch {
            try {
                attempt { downloads.download(fresh) }
                    .onSuccess {
                        waitMessage()?.let { _events.send(SearchEvent.Message(it)) }
                        withTimeoutOrNull(5_000) { lookup.first { l -> ids.all(l::isKnown) } }
                    }
                    .onFailure { _events.send(SearchEvent.Message(R.string.search_download_error)) }
            } finally {
                requested.update { it - ids }
            }
        }
    }

    fun retry(taskId: Long, videoId: String) {
        requested.update { it + videoId }
        viewModelScope.launch {
            try {
                attempt { downloads.retry(taskId) }
                    .onSuccess { withTimeoutOrNull(5_000) { lookup.first { it.isKnown(videoId) } } }
                    .onFailure { _events.send(SearchEvent.Message(R.string.search_download_error)) }
            } finally {
                requested.update { it - videoId }
            }
        }
    }

    /**
     * One tap on a song: streams the visible songs from it (the default), or — with online play off
     * or no network — plays the downloaded copy, downloads it or retries it.
     */
    fun onSongTap(track: RemoteTrack, songs: List<RemoteTrack>, sourceTitle: String) {
        val state = lookup.value.stateOf(track.videoId)
        perform(SongTapAction.of(state, playOnline.value, online.value), track, songs, sourceTitle)
    }

    /** The trailing button keeps the song on the phone: download, retry, or play the local copy. */
    fun onIndicatorTap(track: RemoteTrack, songs: List<RemoteTrack>, sourceTitle: String) {
        perform(SongTapAction.ofIndicator(lookup.value.stateOf(track.videoId)), track, songs, sourceTitle)
    }

    /** "Ouvir agora" on the link card. */
    fun listenNow(track: RemoteTrack) {
        val action = if (online.value) SongTapAction.PlayOnline else SongTapAction.of(lookup.value.stateOf(track.videoId), true, false)
        perform(action, track, listOf(track), track.title)
    }

    private fun perform(action: SongTapAction, track: RemoteTrack, songs: List<RemoteTrack>, sourceTitle: String) {
        when (action) {
            // Results share a name, not a sound ("Perfect" by Simple Plan, then by Ed Sheeran): what
            // follows the tapped song is its radio — YouTube Music's up-next, same genre and style.
            SongTapAction.PlayOnline -> {
                player.playOnline(listOf(track), 0, PlaySource(PlaySource.Type.RADIO, radioTitle(track.title), track.videoId))
                Chrome.openPlayer()
            }
            // The copy on the phone, followed by what sounds like it in the library (no streaming).
            SongTapAction.PlayDownloaded -> {
                viewModelScope.launch {
                    val local = library.trackByVideoId(track.videoId) ?: return@launch
                    player.playSmart(SmartSeed.Radio(local))
                    Chrome.openPlayer()
                }
            }
            SongTapAction.Download -> download(listOf(track))
            is SongTapAction.Retry -> retry(action.taskId, track.videoId)
            SongTapAction.Offline -> _events.trySend(SearchEvent.Message(R.string.search_play_offline))
            SongTapAction.RemoveDownload -> {
                onRemoveDownload(track.videoId)
                _events.trySend(SearchEvent.Message(R.string.search_dl_removed))
            }
            SongTapAction.Busy -> Unit
        }
    }

    /** Plays the downloaded songs among [context] (in that order), starting at [videoId]. */
    fun playDownloaded(videoId: String, context: List<String>, sourceTitle: String) {
        viewModelScope.launch {
            val l = lookup.value
            val ids = context.distinct().filter(l::isDownloaded).takeIf { videoId in it } ?: listOf(videoId)
            val tracks = ids.mapNotNull { library.trackByVideoId(it) }
            val index = tracks.indexOfFirst { it.videoId == videoId }
            if (index >= 0) player.play(tracks, index, PlaySource(PlaySource.Type.SEARCH, sourceTitle))
        }
    }

    fun playLocal(tracks: List<Track>, index: Int, sourceTitle: String) {
        if (index in tracks.indices) player.play(tracks, index, PlaySource(PlaySource.Type.SEARCH, sourceTitle))
    }

    // ------------------------------------------------------------------ multi-select

    /** Selected songs, in the order they were picked. Empty = not selecting. */
    var selection by mutableStateOf<Map<String, RemoteTrack>>(emptyMap())
        private set

    fun toggleSelection(track: RemoteTrack) {
        selection = if (track.videoId in selection) selection - track.videoId else selection + (track.videoId to track)
    }

    fun clearSelection() {
        selection = emptyMap()
    }

    fun downloadSelection() {
        val picked = selection.values.toList()
        selection = emptyMap()
        download(picked)
    }

    // ------------------------------------------------------------------ links

    var link by mutableStateOf<LinkCard?>(null)
        private set
    /** Clipboard link the user already used or dismissed — its chip stays hidden. */
    var handledClip by mutableStateOf<String?>(null)
        private set
    private var linkJob: Job? = null

    private val _events = Channel<SearchEvent>(Channel.BUFFERED)
    val events: Flow<SearchEvent> = _events.receiveAsFlow()

    fun resolveLink(text: String) {
        val url = YouTubeLinks.find(text) ?: text.trim()
        handledClip = url
        linkJob?.cancel()
        if (!online.value) {
            link = null
            _events.trySend(SearchEvent.Message(R.string.search_link_offline))
            return
        }
        link = LinkCard.Resolving
        linkJob = viewModelScope.launch {
            attempt { youTube.resolve(url) }
                .onSuccess { resolved ->
                    when (resolved) {
                        is ResolvedLink.Track -> link = LinkCard.Found(resolved.track)
                        is ResolvedLink.Collection -> {
                            link = null
                            _events.send(SearchEvent.OpenRemote(resolved.collection))
                        }
                        ResolvedLink.Unsupported -> {
                            link = null
                            _events.send(SearchEvent.Message(R.string.search_link_unsupported))
                        }
                    }
                }
                .onFailure { e ->
                    link = null
                    _events.send(SearchEvent.Message(e.linkMessage(online.value)))
                }
        }
    }

    fun dismissLink() {
        linkJob?.cancel()
        link = null
    }

    fun dismissClip(url: String) {
        handledClip = url
    }

    init {
        // Back online: redo a search that failed for lack of network.
        viewModelScope.launch {
            online.collect { isOnline ->
                if (isOnline && states[filter]?.error == SearchError.Offline) load(filter, force = true)
            }
        }
    }

    companion object {
        /** How long the found song is shown before it plays. */
        const val FOUND_SHOWN_MS = 1_400L

        fun create(): SearchViewModel {
            val g = SoundApplication.graph
            return SearchViewModel(
                youTube = g.youTube,
                library = g.library,
                downloads = g.downloads,
                player = g.player,
                recentStore = RecentSearchStore(g.appContext),
                onlineFlow = Connectivity.online(g.appContext),
                initiallyOnline = Connectivity.isOnline(g.appContext),
                playOnlineFlow = g.settings.settings.map { it.playOnlineByDefault }.distinctUntilChanged(),
                radioTitle = { title -> g.appContext.getString(R.string.playback_source_radio, title) },
                likeDownloadsFlow = g.settings.settings.map { it.likeDownloads }.distinctUntilChanged(),
                onLike = g::likeOnline,
                onRemoveDownload = g::removeDownload,
                listener = MicSongListener(),
            )
        }
    }
}
