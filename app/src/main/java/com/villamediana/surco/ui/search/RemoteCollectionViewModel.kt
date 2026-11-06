package com.villamediana.surco.ui.search

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.villamediana.surco.R
import com.villamediana.surco.SurcoApplication
import com.villamediana.surco.data.LibraryRepository
import com.villamediana.surco.data.db.AlbumKey
import com.villamediana.surco.download.DownloadController
import com.villamediana.surco.online.CollectionDetail
import com.villamediana.surco.online.CollectionKind
import com.villamediana.surco.online.OnlineException
import com.villamediana.surco.online.RemoteTrack
import com.villamediana.surco.online.YouTubeSource
import com.villamediana.surco.playback.PlaySource
import com.villamediana.surco.playback.PlayerConnection
import com.villamediana.surco.ui.nav.RemoteCollectionRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

sealed interface RemoteEvent {
    data class Open(val destination: LocalDestination) : RemoteEvent
    data class Message(@StringRes val text: Int) : RemoteEvent
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
) : ViewModel() {

    val kind: CollectionKind = CollectionKind.entries.firstOrNull { it.name == route.kind } ?: CollectionKind.PLAYLIST

    val online: StateFlow<Boolean> = onlineFlow.stateIn(viewModelScope, SharingStarted.Eagerly, initiallyOnline)

    private val _load = MutableStateFlow<RemoteLoad>(RemoteLoad.Loading)
    val load: StateFlow<RemoteLoad> = _load

    /** Waiting for the queue to take a "Baixar tudo". */
    var enqueuing by mutableStateOf(false)
        private set

    /** The local playlist this online playlist was (or is being) imported into. */
    var localPlaylistId by mutableStateOf<Long?>(null)
        private set

    private val requested = MutableStateFlow<Set<String>>(emptySet())

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
            attempt { youTube.collection(route.url, kind) }
                .onSuccess { detail ->
                    if (kind == CollectionKind.PLAYLIST) {
                        localPlaylistId = attempt {
                            library.playlistBySourceUrl(detail.collection.url) ?: library.playlistBySourceUrl(route.url)
                        }.getOrNull()?.id
                    }
                    _load.value = RemoteLoad.Loaded(detail)
                }
                .onFailure { e -> _load.value = RemoteLoad.Failed(e.toRemoteError(online.value)) }
        }
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
                    .onSuccess { withTimeoutOrNull(5_000) { lookup.first { it.isKnown(track.videoId) } } }
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

    fun onSongTap(track: RemoteTrack) {
        when (val s = lookup.value.stateOf(track.videoId)) {
            ItemDownload.None -> download(track)
            is ItemDownload.Failed -> retry(s.taskId, track.videoId)
            ItemDownload.Done -> play(track.videoId)
            ItemDownload.Queued, is ItemDownload.Running -> Unit
        }
    }

    /** Plays this page's songs that are on the phone, in page order, starting at [videoId]. */
    private fun play(videoId: String) {
        val detail = detail ?: return
        viewModelScope.launch {
            val l = lookup.value
            val ids = detail.tracks.map { it.videoId }.distinct().filter(l::isDownloaded)
            val tracks = ids.mapNotNull { library.trackByVideoId(it) }
            val index = tracks.indexOfFirst { it.videoId == videoId }
            if (index < 0) return@launch
            val type = when (kind) {
                CollectionKind.ALBUM -> PlaySource.Type.ALBUM
                CollectionKind.PLAYLIST -> PlaySource.Type.PLAYLIST
                CollectionKind.ARTIST -> PlaySource.Type.ARTIST
            }
            player.play(tracks, index, PlaySource(type, detail.collection.title.ifBlank { route.title }, localPlaylistId?.toString()))
        }
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
            val g = SurcoApplication.graph
            return RemoteCollectionViewModel(
                route = route,
                youTube = g.youTube,
                library = g.library,
                downloads = g.downloads,
                player = g.player,
                onlineFlow = Connectivity.online(g.appContext),
                initiallyOnline = Connectivity.isOnline(g.appContext),
            )
        }
    }
}
