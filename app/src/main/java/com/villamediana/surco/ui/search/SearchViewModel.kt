package com.villamediana.surco.ui.search

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
import com.villamediana.surco.R
import com.villamediana.surco.SurcoApplication
import com.villamediana.surco.data.LibraryRepository
import com.villamediana.surco.data.db.DownloadTask
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.data.model.GenreSummary
import com.villamediana.surco.data.model.LocalSearchResult
import com.villamediana.surco.download.DownloadController
import com.villamediana.surco.online.OnlineException
import com.villamediana.surco.online.RemoteCollection
import com.villamediana.surco.online.RemoteTrack
import com.villamediana.surco.online.ResolvedLink
import com.villamediana.surco.online.SearchFilter
import com.villamediana.surco.online.SearchItem
import com.villamediana.surco.online.SearchPage
import com.villamediana.surco.online.YouTubeSource
import com.villamediana.surco.playback.PlaySource
import com.villamediana.surco.playback.PlayerConnection
import com.villamediana.surco.ui.downloads.DownloadGrouping
import com.villamediana.surco.ui.downloads.DownloadOverview
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
) {
    val hasMore: Boolean get() = page?.hasMore == true
}

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
) : ViewModel() {

    val online: StateFlow<Boolean> = onlineFlow.stateIn(viewModelScope, SharingStarted.Eagerly, initiallyOnline)

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
                .onFailure { e -> update(q, f) { ResultsState(q, f, error = e.toSearchError(online.value)) } }
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
                    .onSuccess { withTimeoutOrNull(5_000) { lookup.first { l -> ids.all(l::isKnown) } } }
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

    /** One tap on a song: download it, retry it, or play it when it is on the phone. */
    fun onSongTap(track: RemoteTrack, context: List<String>, sourceTitle: String) {
        when (val s = lookup.value.stateOf(track.videoId)) {
            ItemDownload.None -> download(listOf(track))
            is ItemDownload.Failed -> retry(s.taskId, track.videoId)
            ItemDownload.Done -> playDownloaded(track.videoId, context, sourceTitle)
            ItemDownload.Queued, is ItemDownload.Running -> Unit
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
        fun create(): SearchViewModel {
            val g = SurcoApplication.graph
            return SearchViewModel(
                youTube = g.youTube,
                library = g.library,
                downloads = g.downloads,
                player = g.player,
                recentStore = RecentSearchStore(g.appContext),
                onlineFlow = Connectivity.online(g.appContext),
                initiallyOnline = Connectivity.isOnline(g.appContext),
            )
        }
    }
}
