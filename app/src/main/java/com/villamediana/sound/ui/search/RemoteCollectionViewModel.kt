package com.villamediana.sound.ui.search

import com.villamediana.sound.download.DownloadWait
import com.villamediana.sound.ui.nav.Chrome
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.AlbumKey
import com.villamediana.sound.download.DownloadController
import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.OnlineException
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.SpotifyLinks
import com.villamediana.sound.online.YouTubeSource
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.PlayerConnection
import com.villamediana.sound.ui.nav.RemoteCollectionRoute
import com.villamediana.sound.ui.nav.RemoteFind
import com.villamediana.sound.discover.DiscoverRepository
import com.villamediana.sound.online.RemoteCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

enum class RemoteError { Offline, NotFound, RateLimited, Generic }

sealed interface RemoteLoad {
    data object Loading : RemoteLoad
    data class Loaded(val detail: CollectionDetail) : RemoteLoad
    data class Failed(val error: RemoteError) : RemoteLoad
}

/** Where "Na sua biblioteca" leads. */
sealed interface LocalDestination {
    data class Playlist(val id: Long) : LocalDestination
    data class Album(val key: AlbumKey) : LocalDestination
    data class Artist(val name: String) : LocalDestination
}

/** The player is on this page: is it sounding (or about to: [loading]), is shuffle on. */
data class HerePlaying(val playing: Boolean, val shuffle: Boolean, val loading: Boolean = false)

sealed interface RemoteEvent {
    data class Open(val destination: LocalDestination) : RemoteEvent
    data class Message(@StringRes val text: Int) : RemoteEvent
    /** A song left the page (imported playlist): "Desfazer" puts it back. */
    data class Removed(val title: String, val undo: () -> Unit) : RemoteEvent
}

internal fun Throwable.toRemoteError(online: Boolean): RemoteError = when {
    !online -> RemoteError.Offline
    else -> when ((this as? OnlineException)?.kind) {
        OnlineException.Kind.NO_NETWORK -> RemoteError.Offline
        OnlineException.Kind.NOT_FOUND, OnlineException.Kind.UNAVAILABLE,
        OnlineException.Kind.AGE_RESTRICTED, OnlineException.Kind.GEO_BLOCKED,
        -> RemoteError.NotFound
        OnlineException.Kind.RATE_LIMITED -> RemoteError.RateLimited
        else -> RemoteError.Generic
    }
}

/** Online album / playlist / artist page: loads it, downloads it whole or song by song. */
class RemoteCollectionViewModel(
    val route: RemoteCollectionRoute,
    private val youTube: YouTubeSource,
    private val library: LibraryRepository,
    private val downloads: DownloadController,
    private val player: PlayerConnection,
    onlineFlow: Flow<Boolean>,
    initiallyOnline: Boolean,
    /** Settings.playOnlineByDefault: a tap on a song streams the page from it (true) or downloads it (false). */
    playOnlineFlow: Flow<Boolean> = flowOf(true),
    private val random: Random = Random.Default,
    /** "Curtir já baixa" (Settings): rows show the heart alone. */
    likeDownloadsFlow: Flow<Boolean> = flowOf(false),
    /** The heart of a row (AppGraph.likeOnline: Curtidas, and the download rule). */
    private val onLike: (RemoteTrack, Boolean) -> Unit = { _, _ -> },
    /** The download button of a song on the phone or on its way (AppGraph.removeDownload). */
    private val onRemoveDownload: (String) -> Unit = {},
    /** Where the page comes from: YouTube, or the saved songs of a playlist imported from Spotify. */
    private val loader: suspend (String, CollectionKind) -> CollectionDetail = { url, k -> youTube.collection(url, k) },
) : ViewModel() {

    val kind: CollectionKind = CollectionKind.entries.firstOrNull { it.name == route.kind } ?: CollectionKind.PLAYLIST

    val online: StateFlow<Boolean> = onlineFlow.stateIn(viewModelScope, SharingStarted.Eagerly, initiallyOnline)

    val playOnline: StateFlow<Boolean> = playOnlineFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The song playing now — its row gets the live bars. */
    val nowPlaying: StateFlow<NowPlaying?> = player.state.nowPlaying()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _load = MutableStateFlow<RemoteLoad>(RemoteLoad.Loading)
    val load: StateFlow<RemoteLoad> = _load

    /** The page's url: the one found when it was opened by name (artist / album of the player). */
    private fun pageUrl(load: RemoteLoad = _load.value): String =
        if (RemoteFind.isFind(route.url)) (load as? RemoteLoad.Loaded)?.detail?.collection?.url ?: route.url else route.url

    /** Whether the player is on this page (played online from here), and whether it sounds. */
    val here: StateFlow<HerePlaying?> = combine(player.state, _load) { s, load ->
        if (s.current != null && s.source?.id == pageUrl(load)) HerePlaying(s.isPlaying || s.isBuffering, s.shuffle, s.isBuffering) else null
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Waiting for the queue to take a "Baixar tudo". */
    var enqueuing by mutableStateOf(false)
        private set

    /** The local playlist this online playlist was (or is being) imported into. */
    var localPlaylistId by mutableStateOf<Long?>(null)
        private set

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
        if (liked && likeDownloads.value) waitMessage()?.let { _events.trySend(RemoteEvent.Message(it)) }
    }

    /** "Baixa quando conectar" / "Baixa quando estiver no Wi-Fi", or null when downloads run now. */
    private fun waitMessage(): Int? = when (downloadWait.value) {
        DownloadWait.NONE -> null
        DownloadWait.NETWORK -> R.string.download_wait_network
        DownloadWait.WIFI -> R.string.download_wait_wifi
    }

    val lookup: StateFlow<DownloadLookup> = combine(library.downloadedVideoIds, downloads.tasks, requested) { done, tasks, asked ->
        DownloadLookup.build(done, tasks, asked)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadLookup.Empty)

    val progress: StateFlow<BatchProgress> = combine(_load, lookup) { load, l ->
        val tracks = (load as? RemoteLoad.Loaded)?.detail?.tracks ?: return@combine BatchProgress.Empty
        BatchProgress.of(tracks.map { it.videoId }, l)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BatchProgress.Empty)

    private val _events = Channel<RemoteEvent>(Channel.BUFFERED)
    val events: Flow<RemoteEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    val detail: CollectionDetail? get() = (_load.value as? RemoteLoad.Loaded)?.detail

    init {
        load()
        viewModelScope.launch {
            online.collect { isOnline ->
                if (isOnline && (_load.value as? RemoteLoad.Failed)?.error == RemoteError.Offline) load()
            }
        }
    }

    fun load() {
        loadJob?.cancel()
        if (!online.value) {
            _load.value = RemoteLoad.Failed(RemoteError.Offline)
            return
        }
        _load.value = RemoteLoad.Loading
        loadJob = viewModelScope.launch {
            attempt { loader(route.url, kind) }
                .onSuccess { detail ->
                    if (kind == CollectionKind.PLAYLIST) {
                        localPlaylistId = attempt {
                            library.playlistBySourceUrl(detail.collection.url) ?: library.playlistBySourceUrl(route.url)
                        }.getOrNull()?.id
                    }
                    _load.value = RemoteLoad.Loaded(detail)
                    // Play is the likely next tap: have the first song's stream ready for it.
                    detail.tracks.firstOrNull()?.let { player.prefetch(listOf(it.videoId)) }
                }
                .onFailure { e -> _load.value = RemoteLoad.Failed(e.toRemoteError(online.value)) }
        }
    }

    /** A playlist imported from Spotify is yours: its songs come out with a swipe. */
    val canRemoveSongs: Boolean = SpotifyLinks.isImported(route.url)

    /** Takes the song at [index] out of the imported playlist; the message offers "Desfazer". */
    fun removeSong(index: Int, track: RemoteTrack) {
        val d = detail ?: return
        if (!canRemoveSongs || d.tracks.getOrNull(index)?.videoId != track.videoId) return
        _load.value = RemoteLoad.Loaded(d.copy(tracks = d.tracks.toMutableList().apply { removeAt(index) }))
        viewModelScope.launch(NonCancellable) { attempt { library.removeFromImported(route.url, index, track.videoId) } }
        _events.trySend(RemoteEvent.Removed(track.title) { putBack(index, track) })
    }

    private fun putBack(index: Int, track: RemoteTrack) {
        val d = detail ?: return
        if (d.tracks.any { it.videoId == track.videoId }) return
        _load.value = RemoteLoad.Loaded(d.copy(tracks = d.tracks.toMutableList().apply { add(index.coerceIn(0, size), track) }))
        viewModelScope.launch(NonCancellable) { attempt { library.putBackInImported(route.url, track, index) } }
    }

    /** "Baixar tudo" / "Baixar N restantes" / "Salvar playlist". */
    fun downloadAll() {
        val detail = detail ?: return
        if (enqueuing) return
        enqueuing = true
        val ids = detail.tracks.map { it.videoId }.toSet()
        val pending = ids.filterNot(lookup.value::isDownloaded).toSet()
        requested.update { it + pending }
        viewModelScope.launch {
            try {
                attempt {
                    if (kind == CollectionKind.ARTIST) {
                        downloads.download(detail.tracks)
                        null
                    } else {
                        downloads.downloadCollection(detail)
                    }
                }
                    .onSuccess { playlistId ->
                        if (playlistId != null) localPlaylistId = playlistId
                        if (pending.isNotEmpty()) waitMessage()?.let { _events.send(RemoteEvent.Message(it)) }
                        withTimeoutOrNull(5_000) { lookup.first { l -> pending.all(l::isKnown) } }
                    }
                    .onFailure { _events.send(RemoteEvent.Message(R.string.search_download_error)) }
            } finally {
                requested.update { it - pending }
                enqueuing = false
            }
        }
    }

    fun download(track: RemoteTrack) {
        val s = lookup.value.stateOf(track.videoId)
        if (s != ItemDownload.None && s !is ItemDownload.Failed) return
        requested.update { it + track.videoId }
        viewModelScope.launch {
            try {
                attempt { downloads.download(listOf(track)) }
                    .onSuccess {
                        waitMessage()?.let { _events.send(RemoteEvent.Message(it)) }
                        withTimeoutOrNull(5_000) { lookup.first { it.isKnown(track.videoId) } }
                    }
                    .onFailure { _events.send(RemoteEvent.Message(R.string.search_download_error)) }
            } finally {
                requested.update { it - track.videoId }
            }
        }
    }

    private fun retry(taskId: Long, videoId: String) {
        requested.update { it + videoId }
        viewModelScope.launch {
            try {
                attempt { downloads.retry(taskId) }
                    .onSuccess { withTimeoutOrNull(5_000) { lookup.first { it.isKnown(videoId) } } }
                    .onFailure { _events.send(RemoteEvent.Message(R.string.search_download_error)) }
            } finally {
                requested.update { it - videoId }
            }
        }
    }

    /** Retries every song of this page whose download failed. */
    fun retryFailed() {
        val detail = detail ?: return
        val l = lookup.value
        detail.tracks.distinctBy { it.videoId }.forEach { t ->
            (l.stateOf(t.videoId) as? ItemDownload.Failed)?.let { retry(it.taskId, t.videoId) }
        }
    }

    /**
     * A tap on a song: streams the page from it (the default), or — with online play off or no
     * network — plays the local copy, downloads it or retries it.
     */
    fun onSongTap(track: RemoteTrack) {
        when (val action = SongTapAction.of(lookup.value.stateOf(track.videoId), playOnline.value, online.value)) {
            // Keeps the page's shuffle when it is already the one playing.
            SongTapAction.PlayOnline -> {
                streamFrom(track.videoId, shuffle = here.value?.shuffle ?: false)
                Chrome.openPlayer()
            }
            else -> runIndicator(action, track)
        }
    }

    /** The trailing button keeps the song on the phone: download, retry, or play the local copy. */
    fun onIndicatorTap(track: RemoteTrack) = runIndicator(SongTapAction.ofIndicator(lookup.value.stateOf(track.videoId)), track)

    private fun runIndicator(action: SongTapAction, track: RemoteTrack) {
        when (action) {
            SongTapAction.Download -> download(track)
            is SongTapAction.Retry -> retry(action.taskId, track.videoId)
            SongTapAction.PlayDownloaded -> play(track.videoId, shuffle = false)
            SongTapAction.Offline -> _events.trySend(RemoteEvent.Message(R.string.search_play_offline))
            SongTapAction.PlayOnline -> {
                streamFrom(track.videoId, shuffle = here.value?.shuffle ?: false)
                Chrome.openPlayer()
            }
            SongTapAction.RemoveDownload -> {
                onRemoveDownload(track.videoId)
                _events.trySend(RemoteEvent.Message(R.string.search_dl_removed))
            }
            SongTapAction.Busy -> Unit
        }
    }

    /**
     * The big Play / Shuffle: streams the whole page from its first song (or a random one). Without
     * network the songs already on the phone play instead. Play on the page that is playing pauses.
     */
    fun playAll(shuffle: Boolean) {
        val detail = detail ?: return
        if (detail.tracks.isEmpty()) return
        val current = here.value
        if (!shuffle && current != null) {
            player.togglePlayPause()
            return
        }
        when {
            online.value -> streamFrom(null, shuffle)
            detail.tracks.any { lookup.value.isDownloaded(it.videoId) } -> play(null, shuffle)
            else -> _events.trySend(RemoteEvent.Message(R.string.search_play_offline))
        }
    }

    /** The page's songs, once each (the same video twice would be the same transient song). */
    private fun pageTracks(): List<RemoteTrack> = detail?.tracks?.distinctBy { it.videoId }.orEmpty()

    private fun source(): PlaySource = PlaySource(sourceType(), detail?.collection?.title?.ifBlank { null } ?: route.title, pageUrl())

    private fun sourceType(): PlaySource.Type = when (kind) {
        CollectionKind.ALBUM -> PlaySource.Type.ALBUM
        CollectionKind.PLAYLIST -> PlaySource.Type.PLAYLIST
        CollectionKind.ARTIST -> PlaySource.Type.ARTIST
    }

    private fun streamFrom(videoId: String?, shuffle: Boolean) {
        val tracks = pageTracks()
        if (tracks.isEmpty()) return
        val start = when {
            videoId != null -> tracks.indexOfFirst { it.videoId == videoId }.coerceAtLeast(0)
            shuffle -> random.nextInt(tracks.size)
            else -> 0
        }
        player.playOnline(tracks, start, source())
        player.setShuffle(shuffle)
    }

    /** Plays this page's songs that are on the phone, in page order, starting at [videoId] (or the first / a random one). */
    private fun play(videoId: String?, shuffle: Boolean) {
        val detail = detail ?: return
        viewModelScope.launch {
            val l = lookup.value
            val ids = detail.tracks.map { it.videoId }.distinct().filter(l::isDownloaded)
            val tracks = ids.mapNotNull { library.trackByVideoId(it) }
            if (tracks.isEmpty()) return@launch
            val index = when {
                videoId != null -> tracks.indexOfFirst { it.videoId == videoId }
                shuffle -> random.nextInt(tracks.size)
                else -> 0
            }
            if (index < 0) return@launch
            player.play(tracks, index, PlaySource(sourceType(), detail.collection.title.ifBlank { route.title }, localPlaylistId?.toString()), shuffle)
            if (videoId != null) Chrome.openPlayer() // a song tapped, not the page's Play
        }
    }

    /**
     * "Tocar a seguir" / "Adicionar à fila" for the whole page: downloaded songs go as the local
     * copy, the rest streamed. Returns how many songs were queued (0 = nothing, or no network).
     */
    fun queueAll(next: Boolean): Int {
        val tracks = pageTracks()
        if (tracks.isEmpty()) return 0
        if (!online.value) {
            _events.trySend(RemoteEvent.Message(R.string.search_play_offline))
            return 0
        }
        viewModelScope.launch {
            val l = lookup.value
            val songs = tracks.map { t -> (if (l.isDownloaded(t.videoId)) library.trackByVideoId(t.videoId) else null) ?: t.asTransientTrack() }
            if (next) player.playNext(songs) else player.enqueue(songs)
        }
        return tracks.size
    }

    /** "Na sua biblioteca": the local playlist, album or artist these songs became. */
    fun openInLibrary() {
        val detail = detail ?: return
        viewModelScope.launch {
            val l = lookup.value
            val downloaded = detail.tracks.filter { l.isDownloaded(it.videoId) }
            val destination: LocalDestination? = when (kind) {
                CollectionKind.PLAYLIST -> (
                    localPlaylistId
                        ?: library.playlistBySourceUrl(detail.collection.url)?.id
                        ?: library.playlistBySourceUrl(route.url)?.id
                    )?.let { LocalDestination.Playlist(it) }
                CollectionKind.ALBUM -> downloaded.firstNotNullOfOrNull { library.trackByVideoId(it.videoId)?.albumKey }
                    ?.let { LocalDestination.Album(it) }
                CollectionKind.ARTIST -> downloaded.firstNotNullOfOrNull { library.trackByVideoId(it.videoId) }
                    ?.let { LocalDestination.Artist(it.artist) }
            }
            _events.send(if (destination != null) RemoteEvent.Open(destination) else RemoteEvent.Message(R.string.search_remote_open_error))
        }
    }

    companion object {
        fun create(route: RemoteCollectionRoute): RemoteCollectionViewModel {
            val g = SoundApplication.graph
            return RemoteCollectionViewModel(
                route = route,
                youTube = g.youTube,
                library = g.library,
                downloads = g.downloads,
                player = g.player,
                onlineFlow = Connectivity.online(g.appContext),
                initiallyOnline = Connectivity.isOnline(g.appContext),
                playOnlineFlow = g.settings.settings.map { it.playOnlineByDefault }.distinctUntilChanged(),
                likeDownloadsFlow = g.settings.settings.map { it.likeDownloads }.distinctUntilChanged(),
                onLike = g::likeOnline,
                onRemoveDownload = g::removeDownload,
                loader = if (SpotifyLinks.isImported(route.url)) {
                    { url, _ -> g.library.importedDetail(url) ?: throw OnlineException("Imported playlist removed", kind = OnlineException.Kind.NOT_FOUND) }
                } else if (RemoteFind.isFind(route.url)) {
                    // Found by name (the player's artist / album): maybe loaded ahead already.
                    { _, k -> g.artistPrefetch.open(route.url, k, route.title, route.author) }
                } else {
                    { url, k -> g.youTube.collection(url, k) }
                },
            )
        }
    }
}

/** The real page behind a route opened by name; "not found" when YouTube Music has none. */
internal suspend fun findPage(route: RemoteCollectionRoute, kind: CollectionKind, discover: DiscoverRepository): RemoteCollection =
    findPage(kind, route.title, route.author, discover)

internal suspend fun findPage(kind: CollectionKind, title: String, author: String?, discover: DiscoverRepository): RemoteCollection {
    val page = when (kind) {
        CollectionKind.ARTIST -> discover.findArtist(title)
        CollectionKind.ALBUM -> discover.findAlbum(title, author.orEmpty())
        CollectionKind.PLAYLIST -> null
    }
    return page ?: throw OnlineException("No ${kind.name.lowercase()} named $title", kind = OnlineException.Kind.NOT_FOUND)
}
