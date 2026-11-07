package com.villamediana.sound.ui.downloads

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
import com.villamediana.sound.data.db.AlbumKey
import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.DownloadTask
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.download.DownloadController
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DownloadsUi(val overview: DownloadOverview, val groups: List<DownloadGroup>)

sealed interface DownloadsEvent {
    data class OpenPlaylist(val id: Long) : DownloadsEvent
    data class OpenAlbum(val key: AlbumKey) : DownloadsEvent
    data class Message(@StringRes val text: Int) : DownloadsEvent
}

/** A finished batch starts collapsed; one with something left to do starts open. */
fun DownloadGroup.collapsedByDefault(): Boolean = isBatch && !stats.isActive && stats.failed == 0

class DownloadsViewModel(
    private val downloads: DownloadController,
    private val library: LibraryRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    val ui: StateFlow<DownloadsUi?> = downloads.tasks
        .map { tasks -> DownloadsUi(DownloadGrouping.overview(tasks), DownloadGrouping.groups(tasks)) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** What queued downloads wait for: the banner on top says it. */
    val wait: StateFlow<DownloadWait> = downloads.wait.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadWait.NONE)

    /** Batches the user opened/closed by hand (key → collapsed). */
    var collapseOverrides by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set

    fun isCollapsed(group: DownloadGroup): Boolean = collapseOverrides[group.key] ?: group.collapsedByDefault()

    fun toggle(group: DownloadGroup) {
        collapseOverrides = collapseOverrides + (group.key to !isCollapsed(group))
    }

    private val _events = Channel<DownloadsEvent>(Channel.BUFFERED)
    val events: Flow<DownloadsEvent> = _events.receiveAsFlow()

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _events.send(DownloadsEvent.Message(R.string.dl_action_error))
            }
        }
    }

    fun cancel(task: DownloadTask) = act { downloads.cancel(task.id) }
    fun retry(task: DownloadTask) = act { downloads.retry(task.id) }
    fun retryAllFailed() = act { downloads.retryAllFailed() }
    fun clearFinished() = act { downloads.clearFinished() }
    fun cancelAll() = act { downloads.cancelAll() }

    fun cancelBatch(group: DownloadGroup) = act {
        group.tasks.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }.forEach { downloads.cancel(it.id) }
    }

    private suspend fun trackOf(task: DownloadTask): Track? =
        task.trackId?.let { library.getTrack(it) } ?: library.trackByVideoId(task.videoId)

    /** Plays a finished song; inside a batch, the batch's finished songs become the queue. */
    fun play(task: DownloadTask, group: DownloadGroup, looseTitle: String) = act {
        val finished = if (group.isBatch) group.tasks.filter { it.state == DownloadState.DONE } else listOf(task)
        val tracks = finished.mapNotNull { trackOf(it) }.distinctBy { it.id }
        val index = tracks.indexOfFirst { it.videoId == task.videoId }
        if (index < 0) {
            _events.send(DownloadsEvent.Message(R.string.dl_play_missing))
            return@act
        }
        val source = when {
            group.targetPlaylistId != null -> PlaySource(PlaySource.Type.PLAYLIST, group.title ?: looseTitle, group.targetPlaylistId.toString())
            group.isBatch -> PlaySource(PlaySource.Type.ALBUM, group.title ?: looseTitle)
            else -> PlaySource(PlaySource.Type.SONGS, looseTitle)
        }
        player.play(tracks, index, source)
        Chrome.openPlayer()
    }

    /** The playlist the batch filled, or the album its songs became. */
    fun openBatch(group: DownloadGroup) = act {
        val playlistId = group.targetPlaylistId
        if (playlistId != null && library.getPlaylist(playlistId) != null) {
            _events.send(DownloadsEvent.OpenPlaylist(playlistId))
            return@act
        }
        val key = group.tasks.filter { it.state == DownloadState.DONE }.firstNotNullOfOrNull { trackOf(it)?.albumKey }
        _events.send(if (key != null) DownloadsEvent.OpenAlbum(key) else DownloadsEvent.Message(R.string.search_remote_open_error))
    }

    companion object {
        fun create(): DownloadsViewModel {
            val g = SoundApplication.graph
            return DownloadsViewModel(g.downloads, g.library, g.player)
        }
    }
}
