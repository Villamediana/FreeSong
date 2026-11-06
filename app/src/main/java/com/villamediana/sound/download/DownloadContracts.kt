package com.villamediana.sound.download

import com.villamediana.sound.data.db.DownloadTask
import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.RemoteTrack
import kotlinx.coroutines.flow.Flow

data class DownloadSummary(
    val queued: Int = 0,
    val running: Int = 0,
    val failed: Int = 0,
    val done: Int = 0,
    /** 0f..1f over queued+running tasks; null when idle. */
    val progress: Float? = null,
) {
    val active: Int get() = queued + running
}

interface DownloadController {
    /** Every task (newest first) — the downloads screen. */
    val tasks: Flow<List<DownloadTask>>
    val summary: Flow<DownloadSummary>
    /** videoIds currently queued or running. */
    val inProgressVideoIds: Flow<Set<String>>

    /** Downloads loose songs (search results / shared link). Already-downloaded ones are skipped. */
    suspend fun download(tracks: List<RemoteTrack>)

    /**
     * Downloads a whole album or playlist.
     * - ALBUM: songs get album/albumArtist/trackNumber/cover from the album page; no playlist is created.
     * - PLAYLIST: a local playlist with the same name (and cover) is created — or reused if it was
     *   imported before from the same url — and every song is added in the source order, including
     *   songs that were already downloaded.
     * Returns the local playlist id for playlists, null for albums.
     */
    suspend fun downloadCollection(detail: CollectionDetail): Long?

    suspend fun cancel(taskId: Long)
    suspend fun retry(taskId: Long)
    suspend fun retryAllFailed()
    suspend fun clearFinished()
    suspend fun cancelAll()

    /** Re-schedules the worker for anything left queued/running (app start, network back). */
    fun kick()
}
