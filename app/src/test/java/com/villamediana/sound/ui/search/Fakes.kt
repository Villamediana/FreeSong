package com.villamediana.sound.ui.search

import com.villamediana.sound.data.AudioQuality
import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.DownloadTask
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.download.DownloadController
import com.villamediana.sound.download.DownloadSummary
import com.villamediana.sound.online.AudioStream
import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.ResolvedLink
import com.villamediana.sound.online.SearchFilter
import com.villamediana.sound.online.SearchItem
import com.villamediana.sound.online.SearchPage
import com.villamediana.sound.online.YouTubeSource
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.playback.PlayerConnection
import com.villamediana.sound.playback.SleepTimer
import com.villamediana.sound.playback.SmartSeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.Collections

fun remoteTrack(id: String, title: String = "Song $id", artist: String = "Artist") =
    RemoteTrack(videoId = id, url = "https://music.youtube.com/watch?v=$id", title = title, artist = artist, durationMs = 200_000, fromMusicCatalog = true)

fun songPage(query: String, filter: SearchFilter, ids: List<String>, cursor: Any? = null) =
    SearchPage(query, filter, ids.map { SearchItem.Song(remoteTrack(it)) }, cursor)

class FakeYouTube : YouTubeSource {
    val searches: MutableList<Pair<String, SearchFilter>> = Collections.synchronizedList(mutableListOf())
    var onSearch: (String, SearchFilter) -> SearchPage = { q, f -> SearchPage(q, f, emptyList()) }
    var onMore: (SearchPage) -> SearchPage = { p -> SearchPage(p.query, p.filter, emptyList()) }
    var onSuggestions: (String) -> List<String> = { emptyList() }
    var onResolve: (String) -> ResolvedLink = { ResolvedLink.Unsupported }
    var onCollection: (String, CollectionKind) -> CollectionDetail = { _, _ -> error("no collection") }
    val resolved: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun search(query: String, filter: SearchFilter): SearchPage {
        searches += query to filter
        return onSearch(query, filter)
    }

    override suspend fun more(page: SearchPage): SearchPage = onMore(page)
    override suspend fun suggestions(query: String): List<String> = onSuggestions(query)
    override suspend fun resolve(text: String): ResolvedLink {
        resolved += text
        return onResolve(text)
    }

    override suspend fun collection(url: String, kind: CollectionKind, limit: Int): CollectionDetail = onCollection(url, kind)
    override suspend fun audioStream(videoId: String, quality: AudioQuality): AudioStream = error("not used by the UI")
    override suspend fun track(videoId: String): RemoteTrack = remoteTrack(videoId)
}

/** Queue that records calls and turns requests into QUEUED tasks, like the real controller. */
class FakeDownloads : DownloadController {
    val taskFlow = MutableStateFlow<List<DownloadTask>>(emptyList())
    override val tasks: Flow<List<DownloadTask>> = taskFlow
    override val summary: Flow<DownloadSummary> = flowOf(DownloadSummary())
    override val inProgressVideoIds: Flow<Set<String>> = taskFlow.map { list ->
        list.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }.map { it.videoId }.toSet()
    }

    /** What queued downloads wait for (a connection, the Wi-Fi). */
    val waitFlow = MutableStateFlow(com.villamediana.sound.download.DownloadWait.NONE)
    override val wait: Flow<com.villamediana.sound.download.DownloadWait> = waitFlow

    val downloadCalls: MutableList<List<RemoteTrack>> = Collections.synchronizedList(mutableListOf())
    val collectionCalls: MutableList<CollectionDetail> = Collections.synchronizedList(mutableListOf())
    val retried: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    var playlistIdForCollections: Long? = 77
    private var nextId = 1L

    private fun enqueue(tracks: List<RemoteTrack>, batch: String? = null) {
        taskFlow.update { list ->
            list + tracks.map { t ->
                DownloadTask(id = nextId++, videoId = t.videoId, url = t.url, title = t.title, artist = t.artist, batchId = batch, batchTitle = batch)
            }
        }
    }

    override suspend fun download(tracks: List<RemoteTrack>) {
        downloadCalls += tracks
        enqueue(tracks)
    }

    override suspend fun downloadCollection(detail: CollectionDetail): Long? {
        collectionCalls += detail
        enqueue(detail.tracks, detail.collection.title)
        return if (detail.collection.kind == CollectionKind.PLAYLIST) playlistIdForCollections else null
    }

    override suspend fun cancel(taskId: Long) {
        taskFlow.update { l -> l.map { if (it.id == taskId) it.copy(state = DownloadState.CANCELED) else it } }
    }

    override suspend fun retry(taskId: Long) {
        retried += taskId
        taskFlow.update { l -> l.map { if (it.id == taskId) it.copy(state = DownloadState.QUEUED, error = null) else it } }
    }

    override suspend fun retryAllFailed() = Unit
    override suspend fun clearFinished() = Unit
    override suspend fun cancelAll() = Unit
    override fun kick() = Unit

    fun setState(videoId: String, state: DownloadState, progress: Float = 0f) {
        taskFlow.update { l -> l.map { if (it.videoId == videoId) it.copy(state = state, progress = progress) else it } }
    }
}

class FakePlayer : PlayerConnection {
    data class Play(val tracks: List<Track>, val index: Int, val source: PlaySource, val shuffle: Boolean = false)
    data class OnlinePlay(val tracks: List<RemoteTrack>, val index: Int, val source: PlaySource)

    val plays: MutableList<Play> = Collections.synchronizedList(mutableListOf())
    val onlinePlays: MutableList<OnlinePlay> = Collections.synchronizedList(mutableListOf())
    val shuffles: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    val queued: MutableList<Pair<String, List<Track>>> = Collections.synchronizedList(mutableListOf())
    var toggles = 0
    val stateFlow = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = stateFlow
    override val positionMs: Flow<Long> = flowOf(0L)
    override val sleepTimer: StateFlow<SleepTimer> = MutableStateFlow(SleepTimer.Off)

    override fun play(tracks: List<Track>, startIndex: Int, source: PlaySource, shuffle: Boolean) {
        plays += Play(tracks, startIndex, source, shuffle)
    }

    override fun playOnline(tracks: List<RemoteTrack>, startIndex: Int, source: PlaySource) {
        onlinePlays += OnlinePlay(tracks, startIndex, source)
    }

    val prefetched: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun prefetch(videoIds: List<String>) {
        prefetched += videoIds
    }

    val smartPlays: MutableList<SmartSeed> = Collections.synchronizedList(mutableListOf())

    override fun playSmart(seed: SmartSeed) {
        smartPlays += seed
    }
    override fun togglePlayPause() {
        toggles++
    }
    override fun pause() = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun skipTo(queueIndex: Int) = Unit
    override fun setShuffle(enabled: Boolean) {
        shuffles += enabled
    }
    override fun cycleRepeat() = Unit
    override fun playNext(tracks: List<Track>) {
        queued += "next" to tracks
    }
    override fun enqueue(tracks: List<Track>) {
        queued += "end" to tracks
    }
    override fun removeAt(queueIndex: Int) = Unit
    override fun move(from: Int, to: Int) = Unit
    override fun setSleepTimer(timer: SleepTimer) = Unit
}

/** Polls [condition] in real time, delivering snapshot changes like a frame would. */
fun awaitUntil(timeoutMs: Long = 5_000, message: String = "condition", condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        if (condition()) return
        check(System.currentTimeMillis() < end) { "Timed out waiting for $message" }
        Thread.sleep(10)
    }
}
