package com.villamediana.surco.download

import com.villamediana.surco.data.LibraryRepository
import com.villamediana.surco.data.Storage
import com.villamediana.surco.data.db.DownloadDao
import com.villamediana.surco.data.db.DownloadState
import com.villamediana.surco.data.db.DownloadTask
import com.villamediana.surco.online.CollectionDetail
import com.villamediana.surco.online.CollectionKind
import com.villamediana.surco.online.RemoteTrack
import kotlinx.coroutines.flow.first

/** How to (re)start the background worker. */
internal enum class SchedulePolicy {
    /** Fresh work with the current constraints; a worker that is downloading is stopped and replaced. */
    REPLACE,

    /** A worker is finishing: run another one right after it. */
    APPEND,
}

internal fun interface DownloadScheduler {
    suspend fun schedule(policy: SchedulePolicy)
}

/**
 * Turns "download this" requests into rows of the `downloads` table and makes sure a worker picks
 * them up. Album/playlist/artist downloads become batches; playlist downloads also create (or reuse)
 * the local playlist and reserve each song's slot in the source order.
 */
internal class DownloadQueue(
    private val dao: DownloadDao,
    private val library: LibraryRepository,
    private val storage: Storage,
    private val engine: DownloadEngine,
    private val scheduler: DownloadScheduler,
    /** Runs work that must not hold up the caller (the playlist cover download). */
    private val background: (suspend () -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Loose songs. Skips songs already in the library or already queued. Returns how many were queued. */
    suspend fun download(tracks: List<RemoteTrack>, batchId: String? = null, batchTitle: String? = null): Int {
        val now = clock()
        val active = activeVideoIds()
        val tasks = tracks.distinctBy { it.videoId }
            .filter { it.videoId !in active && library.trackByVideoId(it.videoId) == null }
            .map { it.toTask(now).copy(batchId = batchId, batchTitle = batchTitle) }
        return insert(tasks)
    }

    suspend fun downloadCollection(detail: CollectionDetail): Long? {
        val c = detail.collection
        return when (c.kind) {
            CollectionKind.ALBUM -> {
                album(detail)
                null
            }
            CollectionKind.PLAYLIST -> playlist(detail)
            CollectionKind.ARTIST -> {
                download(detail.tracks, batchId = "artist:${c.url}", batchTitle = c.title)
                null
            }
        }
    }

    private suspend fun album(detail: CollectionDetail) {
        val c = detail.collection
        val now = clock()
        val active = activeVideoIds()
        val tasks = detail.tracks.withIndex()
            .distinctBy { it.value.videoId }
            .filter { (_, t) -> t.videoId !in active && library.trackByVideoId(t.videoId) == null }
            .map { (index, t) ->
                t.toTask(now).copy(
                    album = c.title,
                    albumArtist = c.author.clean() ?: t.artist,
                    trackNumber = index + 1,
                    year = c.year,
                    albumCoverUrl = c.thumbnailUrl,
                    batchId = "album:${c.url}",
                    batchTitle = c.title,
                )
            }
        insert(tasks)
    }

    private suspend fun playlist(detail: CollectionDetail): Long {
        val c = detail.collection
        val existing = library.playlistBySourceUrl(c.url)
        val playlistId = existing?.id ?: library.createPlaylist(c.title, detail.description.clean(), null, c.url)
        val now = clock()
        val queuedHere = dao.pending().filter { it.targetPlaylistId == playlistId }.mapTo(HashSet()) { it.videoId }
        val seen = HashSet<String>()
        val tasks = ArrayList<DownloadTask>()
        detail.tracks.forEachIndexed { index, t ->
            if (!seen.add(t.videoId)) return@forEachIndexed
            val have = library.trackByVideoId(t.videoId)
            when {
                have != null -> library.addToPlaylistAt(playlistId, have.id, index)
                t.videoId in queuedHere -> Unit
                // Also when the song is being downloaded for something else: the engine runs one task
                // per video at a time, so this one finishes instantly by reusing that track.
                else -> tasks += t.toTask(now).copy(
                    targetPlaylistId = playlistId,
                    targetPosition = index,
                    batchId = "playlist:${c.url}",
                    batchTitle = c.title,
                )
            }
        }
        insert(tasks)

        val thumbnail = c.thumbnailUrl
        if (thumbnail != null) {
            val coverName = "playlist_$playlistId"
            val ours = storage.coverFile(coverName).path
            // Never replace a cover the user picked for a playlist imported earlier.
            if (existing?.coverPath == null || existing.coverPath == ours) {
                background {
                    engine.covers.obtain(coverName, listOf(thumbnail), overwrite = true)
                        ?.let { library.setPlaylistCover(playlistId, it.path) }
                }
            }
        }
        return playlistId
    }

    suspend fun cancel(taskId: Long) {
        engine.cancel(listOf(taskId))
    }

    suspend fun cancelAll() {
        engine.cancel(null)
    }

    suspend fun retry(taskId: Long) {
        if (engine.retry(listOf(taskId)) > 0) ensureWorker()
    }

    suspend fun retryAllFailed() {
        if (engine.retry(null) > 0) ensureWorker()
    }

    suspend fun clearFinished() = dao.clearFinished()

    /** App start / network back: rows of a dead process go back to the queue, and a worker is scheduled. */
    suspend fun kick() {
        engine.requeueStale()
        if (dao.pending().isNotEmpty()) ensureWorker()
    }

    /** Wakes the running loop, or schedules a worker when none is running. */
    suspend fun ensureWorker() {
        when (engine.offer()) {
            DownloadEngine.Phase.RUNNING -> Unit
            DownloadEngine.Phase.EXITING -> scheduler.schedule(SchedulePolicy.APPEND)
            DownloadEngine.Phase.IDLE -> scheduler.schedule(SchedulePolicy.REPLACE)
        }
    }

    private suspend fun insert(tasks: List<DownloadTask>): Int {
        if (tasks.isEmpty()) return 0
        supersede(tasks)
        dao.insertAll(tasks)
        ensureWorker()
        return tasks.size
    }

    /** A new request for a song replaces its old failed/canceled row of the same batch. */
    private suspend fun supersede(tasks: List<DownloadTask>) {
        val keys = tasks.mapTo(HashSet()) { it.videoId to it.batchId }
        dao.observeAll().first()
            .filter { (it.state == DownloadState.FAILED || it.state == DownloadState.CANCELED) && (it.videoId to it.batchId) in keys }
            .forEach { dao.delete(it.id) }
    }

    private suspend fun activeVideoIds(): Set<String> = dao.pending().mapTo(HashSet()) { it.videoId }

    private fun RemoteTrack.toTask(now: Long) = DownloadTask(
        videoId = videoId,
        url = url,
        title = title,
        artist = artist,
        album = album.clean(),
        durationMs = durationMs?.takeIf { it > 0 },
        thumbnailUrl = thumbnailUrl,
        fromMusicCatalog = fromMusicCatalog,
        createdAt = now,
        updatedAt = now,
    )

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
