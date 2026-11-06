package com.villamediana.surco.download

import android.database.sqlite.SQLiteConstraintException
import android.media.MediaMetadataRetriever
import android.util.Log
import com.villamediana.surco.data.LibraryRepository
import com.villamediana.surco.data.Settings
import com.villamediana.surco.data.Storage
import com.villamediana.surco.data.db.AlbumKey
import com.villamediana.surco.data.db.DownloadDao
import com.villamediana.surco.data.db.DownloadState
import com.villamediana.surco.data.db.DownloadTask
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.online.AudioStream
import com.villamediana.surco.online.LyricsSource
import com.villamediana.surco.online.MetaInput
import com.villamediana.surco.online.MetadataEnricher
import com.villamediana.surco.online.TrackMeta
import com.villamediana.surco.online.YouTubeSource
import com.villamediana.surco.ui.components.CoverColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

/**
 * The download pipeline, free of WorkManager so it can be tested: pulls QUEUED rows from the
 * `downloads` table, downloads up to `parallelDownloads` of them at once, and turns each finished
 * file into a library [Track] (clean metadata, square cover, accent color, lyrics, playlist slot).
 *
 * All state lives in the database, so a dead process loses nothing: rows left RUNNING are put back
 * in the queue by [requeueStale]. Every state change of a row goes through [stateLock], which makes
 * "cancel" and "the download just finished" race-free.
 *
 * One instance per process (owned by [WorkDownloadController]); [DownloadWorker] calls [runQueue].
 */
internal class DownloadEngine(
    private val dao: DownloadDao,
    private val library: LibraryRepository,
    private val storage: Storage,
    private val settings: suspend () -> Settings,
    private val youTube: YouTubeSource,
    private val enricher: MetadataEnricher,
    private val lyrics: LyricsSource,
    http: OkHttpClient,
    private val messages: DownloadMessages,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isOnline: () -> Boolean = { true },
    val covers: CoverArt = CoverArt(http, storage),
    private val accentOf: suspend (String) -> Int? = { CoverColors.extract(it) },
    private val probeDuration: (File) -> Long? = ::probeDurationMs,
    chunkSize: Long = CHUNK_SIZE,
    private val retryDelayMs: (attempt: Int) -> Long = { 2_000L shl (it - 1) },
    /** Where [progress] lives; the controller hands in its own to merge it into the task list. */
    private val liveProgress: MutableStateFlow<Map<Long, TaskProgress>> = MutableStateFlow(emptyMap()),
) {
    enum class Phase {
        /** No run in this process. */
        IDLE,

        /** A run is active and picks up anything queued meanwhile. */
        RUNNING,

        /** The run decided there is nothing left and is wrapping up (summary notification). */
        EXITING,
    }

    private class Running(val task: DownloadTask, val startedAt: Long) {
        @Volatile var job: Job? = null
        @Volatile var cancelRequested = false
        @Volatile var bytesDone = 0L
        @Volatile var bytesTotal = 0L
        @Volatile var lastWrite = 0L

        /** Audio file already moved out of tmp/ but not yet committed as a Track. */
        @Volatile var finalFile: File? = null

        val fraction: Float get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f

        /** Nothing reported yet (just claimed): the row keeps the progress of the previous attempt. */
        val started: Boolean get() = bytesDone > 0 || bytesTotal > 0

        fun progress() = TaskProgress(fraction, bytesDone, bytesTotal)
    }

    private sealed interface Outcome {
        data class Done(val task: DownloadTask) : Outcome
        data class Failed(val task: DownloadTask) : Outcome
        data object NetworkLost : Outcome
        data object Canceled : Outcome
    }

    /** Thrown inside a task when its row was canceled; handled like a coroutine cancellation. */
    private class TaskCanceled : CancellationException("download canceled")

    private val downloader = ChunkedDownloader(http, chunkSize)
    private val inflight = ConcurrentHashMap<Long, Running>()
    private val stateLock = Mutex()
    private val phaseLock = Mutex()
    private val runLock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile var phase: Phase = Phase.IDLE
        private set

    /**
     * Byte progress of the running tasks, by task id. It lives in memory: the table gets it only every
     * [PROGRESS_WRITE_INTERVAL_MS] and on state changes, so the downloads list (with all the history)
     * is not reloaded by every observer several times a second. Only meaningful for RUNNING rows.
     */
    val progress: StateFlow<Map<Long, TaskProgress>> get() = liveProgress

    @Volatile private var lastPublish = 0L

    @Volatile private var current: RunReport? = null

    // ------------------------------------------------------------------ run loop

    /**
     * Downloads everything queued, picking up tasks added meanwhile, until nothing is left.
     * Returns early (with [RunReport.networkLost]) when the connection drops: the interrupted tasks
     * go back to QUEUED and the caller should retry once the network is back.
     */
    suspend fun runQueue(): RunReport = runLock.withLock {
        val report = RunReport()
        current = report
        phaseLock.withLock { phase = Phase.RUNNING }
        try {
            requeueStale()
            coroutineScope {
                while (true) {
                    if (!report.networkLost) fillSlots(this, report)
                    if (inflight.isEmpty()) {
                        val exit = phaseLock.withLock {
                            val more = !report.networkLost && dao.pending().any { it.state == DownloadState.QUEUED }
                            if (!more) phase = Phase.EXITING
                            !more
                        }
                        if (exit) break else continue
                    }
                    wake.receive()
                }
            }
        } finally {
            withContext(NonCancellable) {
                phaseLock.withLock { if (phase == Phase.RUNNING) phase = Phase.EXITING }
            }
            liveProgress.value = emptyMap()
        }
        report
    }

    /**
     * Marks the end of a run started with [runQueue] (after its summary was shown). Ignored while
     * another run holds the loop: a replaced worker ends after the one that replaced it started.
     */
    suspend fun idle() = withContext(NonCancellable) {
        // runQueue sets RUNNING under phaseLock while it holds runLock, so this check cannot race it.
        phaseLock.withLock { if (!runLock.isLocked) phase = Phase.IDLE }
    }

    /**
     * Called after tasks were queued. Wakes the loop when a run is active ([Phase.RUNNING]: it will
     * pick them up); otherwise the returned phase tells the caller how to schedule a worker.
     */
    suspend fun offer(): Phase = phaseLock.withLock {
        if (phase == Phase.RUNNING) wake.trySend(Unit)
        phase
    }

    /** RUNNING rows nobody in this process is working on (the process died mid-download) → QUEUED. */
    suspend fun requeueStale() = stateLock.withLock {
        val now = clock()
        dao.pending().filter { it.state == DownloadState.RUNNING && !inflight.containsKey(it.id) }
            .forEach { dao.setState(it.id, DownloadState.QUEUED, null, now) }
    }

    /** What the progress notification shows. */
    suspend fun snapshot(): RunSnapshot {
        val queued = dao.pending().count { it.state == DownloadState.QUEUED }
        val running = inflight.values.sortedBy { it.startedAt }.map { RunningItem(it.task.title, it.task.artist, it.fraction) }
        return RunSnapshot(finished = current?.finished ?: 0, queued = queued, running = running)
    }

    private suspend fun fillSlots(scope: CoroutineScope, report: RunReport) {
        val parallel = settings().parallelDownloads.coerceIn(1, 4)
        if (inflight.size >= parallel) return
        val busyVideos = inflight.values.mapTo(HashSet()) { it.task.videoId }
        for (candidate in dao.pending()) {
            if (inflight.size >= parallel) break
            // One task per video at a time: duplicates (same song in two playlists) share tmp/<id>.part.
            if (candidate.state != DownloadState.QUEUED || candidate.videoId in busyVideos) continue
            val entry = claim(candidate.id) ?: continue
            busyVideos += entry.task.videoId
            entry.job = scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { runTask(entry, report) }
        }
    }

    private suspend fun claim(id: Long): Running? = stateLock.withLock {
        val cur = dao.get(id) ?: return@withLock null
        if (cur.state != DownloadState.QUEUED) return@withLock null
        val now = clock()
        val running = cur.copy(state = DownloadState.RUNNING, error = null, updatedAt = now)
        val entry = Running(running, now)
        liveProgress.update { it - id } // left by an earlier attempt in this run
        inflight[id] = entry
        dao.update(running)
        entry
    }

    private suspend fun runTask(entry: Running, report: RunReport) {
        try {
            when (val outcome = process(entry)) {
                is Outcome.Done -> report.done += outcome.task
                is Outcome.Failed -> report.failed += outcome.task
                Outcome.NetworkLost -> {
                    report.networkLost = true
                    inflight.values.filter { it !== entry }.forEach { it.job?.cancel() }
                }
                Outcome.Canceled -> withContext(NonCancellable) { discard(entry) }
            }
        } catch (ce: CancellationException) {
            withContext(NonCancellable) { interrupted(entry) }
            throw ce
        } catch (t: Throwable) {
            // Something unexpected (database?) — don't take the whole run down with it.
            Log.e(TAG, "download ${entry.task.videoId} crashed", t)
            withContext(NonCancellable) {
                runCatching { fail(entry, DownloadError.Unknown, permanent = false) }
                    .onSuccess { if (it is Outcome.Failed) report.failed += it.task }
            }
        } finally {
            inflight.remove(entry.task.id)
            wake.trySend(Unit)
        }
    }

    // ------------------------------------------------------------------ one task

    private suspend fun process(entry: Running): Outcome {
        val task = dao.get(entry.task.id) ?: return Outcome.Canceled
        if (task.state != DownloadState.RUNNING) return Outcome.Canceled

        while (true) {
            // Already on the phone (another task of the same video, or a commit that failed late):
            // just finish this task with that track — which joins the album when this is an album download.
            library.trackByVideoId(task.videoId)?.let { have ->
                val outcome = commit(entry, existingId = have.id, newTrack = null)
                val album = have.inAlbumOf(task)?.albumKey
                if (outcome is Outcome.Done && album != null) guarded { useAlbumCover(listOf(have.id), album, task.albumCoverUrl) }
                return outcome
            }
            val failure: Failure = try {
                return downloadAndSave(entry, task)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "download ${task.videoId} failed: $t")
                classify(t, isOnline())
            }
            currentCoroutineContext().ensureActive()
            when (failure) {
                Failure.NetworkLost -> {
                    stateLock.withLock {
                        if (dao.get(task.id)?.state == DownloadState.RUNNING) dao.setState(task.id, DownloadState.QUEUED, null, clock())
                    }
                    writeProgress(entry)
                    return Outcome.NetworkLost
                }
                is Failure.Permanent -> return fail(entry, failure.error, permanent = true)
                is Failure.Transient -> {
                    val attempts = stateLock.withLock {
                        val cur = dao.get(task.id)
                        if (cur == null || cur.state != DownloadState.RUNNING) return Outcome.Canceled
                        (cur.attempts + 1).also { dao.update(cur.copy(attempts = it, updatedAt = clock())) }
                    }
                    if (attempts >= MAX_ATTEMPTS) return fail(entry, failure.error, permanent = false, countAttempt = false)
                    delay(retryDelayMs(attempts))
                }
            }
        }
    }

    private suspend fun downloadAndSave(entry: Running, task: DownloadTask): Outcome {
        val s = settings()
        var stream = youTube.audioStream(task.videoId, s.audioQuality)
        val part = storage.tempFile(task.videoId)
        val sig = signatureFile(part)
        // A .part only resumes into the very same stream (format/bitrate/length) it was started with.
        if (readText(sig) != stream.signature()) {
            part.delete()
            sig.writeText(stream.signature())
        }
        downloader.download(
            dest = part,
            url = stream.url,
            contentLength = stream.contentLength,
            refresh = {
                val fresh = youTube.audioStream(task.videoId, s.audioQuality)
                if (fresh.signature() != stream.signature()) {
                    part.delete()
                    sig.writeText(fresh.signature())
                }
                stream = fresh
                FreshUrl(fresh.url, fresh.contentLength)
            },
            beforeChunk = { checkCanceled(entry) },
            onProgress = { done, total -> onProgress(entry, done, total) },
        )
        entry.bytesDone = part.length()
        entry.bytesTotal = part.length()
        writeProgress(entry)
        return try {
            save(entry, task, stream, part, s)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            restorePart(entry)
            throw t
        }
    }

    private suspend fun save(entry: Running, task: DownloadTask, stream: AudioStream, part: File, s: Settings): Outcome {
        checkCanceled(entry)
        val dest = storage.audioFile(task.videoId, stream.extension)
        move(part, dest)
        entry.finalFile = dest

        val meta: TrackMeta? = guarded {
            enricher.enrich(
                MetaInput(
                    title = task.title,
                    artist = task.artist,
                    album = task.album,
                    albumArtist = task.albumArtist,
                    trackNumber = task.trackNumber,
                    year = task.year,
                    durationMs = task.durationMs,
                    fromMusicCatalog = task.fromMusicCatalog,
                ),
            )
        }
        val title = meta?.title.clean() ?: task.title
        val artist = meta?.artist.clean() ?: task.artist
        // What the album page said wins over the catalog guess.
        val album = task.album.clean() ?: meta?.album.clean()
        val albumArtist = task.albumArtist.clean() ?: meta?.albumArtist.clean()
        val year = task.year ?: meta?.year
        val trackNumber = task.trackNumber ?: meta?.trackNumber
        val duration = task.durationMs?.takeIf { it > 0 } ?: probeDuration(dest) ?: 0L

        val coverName = album?.let { CoverArt.albumCoverName(AlbumKey(albumArtist ?: artist, it)) } ?: task.videoId
        val coverUrls = listOfNotNull(task.albumCoverUrl, meta?.artworkUrl, task.thumbnailUrl)
        val cover = guarded { covers.obtain(coverName, coverUrls) }
        val accent = cover?.let { guarded { accentOf(it.path) } }
        val lyricsFile = if (s.fetchLyrics) guarded { saveLyrics(task.videoId, title, artist, album, duration) } else null

        checkCanceled(entry)
        val track = Track(
            videoId = task.videoId,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = meta?.genre.clean(),
            year = year,
            trackNumber = trackNumber,
            durationMs = duration,
            filePath = dest.path,
            fileSize = dest.length(),
            mimeType = stream.mimeType,
            bitrate = stream.bitrate.takeIf { it > 0 },
            coverPath = cover?.path,
            lyricsPath = lyricsFile?.first?.path,
            lyricsSynced = lyricsFile?.second ?: false,
            sourceUrl = task.url,
            addedAt = clock(),
            accentColor = accent,
        )
        return commit(entry, existingId = null, newTrack = track)
    }

    private suspend fun saveLyrics(videoId: String, title: String, artist: String, album: String?, durationMs: Long): Pair<File, Boolean>? {
        val found = lyrics.find(title, artist, album, durationMs.takeIf { it > 0 }) ?: return null
        if (found.text.isBlank()) return null
        val file = storage.lyricsFile(videoId, found.synced)
        storage.lyricsFile(videoId, !found.synced).delete()
        withContext(Dispatchers.IO) { file.writeText(found.text) }
        return file to found.synced
    }

    /**
     * Inserts the track (or reuses the row of the same video), places it in its playlist, marks DONE.
     * Never cut halfway by a stop: a track inserted but not marked DONE would make [interrupted] move
     * its audio file back to tmp/ and leave the library pointing at nothing.
     */
    private suspend fun commit(entry: Running, existingId: Long?, newTrack: Track?): Outcome = withContext(NonCancellable) {
        stateLock.withLock { commitLocked(entry, existingId, newTrack) }
    }

    /** Must hold [stateLock]. */
    private suspend fun commitLocked(entry: Running, existingId: Long?, newTrack: Track?): Outcome {
        val cur = dao.get(entry.task.id)
        if (cur == null || cur.state != DownloadState.RUNNING || entry.cancelRequested) return Outcome.Canceled
        val existing = existingId?.let { library.getTrack(it) } ?: library.trackByVideoId(cur.videoId)
        val trackId = when {
            existing != null -> {
                if (newTrack != null && existing.filePath != newTrack.filePath) File(newTrack.filePath).delete()
                joinAlbum(existing, cur)
                existing.id
            }
            newTrack != null -> try {
                library.insertTrack(newTrack)
            } catch (e: SQLiteConstraintException) {
                library.trackByVideoId(cur.videoId)?.id ?: throw e
            }
            else -> return Outcome.Canceled
        }
        entry.finalFile = null
        place(cur, trackId)
        val size = (existing ?: newTrack)?.fileSize ?: cur.bytesTotal
        val done = cur.copy(
            state = DownloadState.DONE,
            progress = 1f,
            bytesDone = size,
            bytesTotal = size,
            trackId = trackId,
            error = null,
            updatedAt = clock(),
        )
        dao.update(done)
        // The .sig, and a .part left by an earlier attempt when the song came from elsewhere.
        deletePartIfUnused(cur.videoId)
        return Outcome.Done(done)
    }

    /**
     * A song already on the phone, asked for again from an album page, joins that album: it takes
     * the page's album, album artist, track number and year, like a fresh download would. Returns
     * the track as it is now; null (nothing changes) when [task] is not an album download.
     */
    suspend fun joinAlbum(track: Track, task: DownloadTask): Track? {
        val joined = track.inAlbumOf(task) ?: return null
        if (joined != track) library.setAlbumInfo(joined.id, joined.album, joined.albumArtist, joined.trackNumber, joined.year)
        return joined
    }

    /** Gives [trackIds] the cover shared by the songs of [album], fetched from [coverUrl] when it is not on the phone yet. */
    suspend fun useAlbumCover(trackIds: List<Long>, album: AlbumKey, coverUrl: String?) {
        val cover = covers.obtain(CoverArt.albumCoverName(album), listOfNotNull(coverUrl)) ?: return
        val accent = guarded { accentOf(cover.path) }
        library.setCovers(trackIds, cover.path, accent)
    }

    private suspend fun place(task: DownloadTask, trackId: Long) {
        val playlistId = task.targetPlaylistId ?: return
        if (library.getPlaylist(playlistId) == null) return // deleted while downloading
        val position = task.targetPosition
        if (position != null) library.addToPlaylistAt(playlistId, trackId, position)
        else library.addToPlaylist(playlistId, listOf(trackId))
    }

    private suspend fun fail(entry: Running, error: DownloadError, permanent: Boolean, countAttempt: Boolean = true): Outcome =
        stateLock.withLock {
            val cur = dao.get(entry.task.id)
            if (cur == null || cur.state != DownloadState.RUNNING) return@withLock Outcome.Canceled
            val failed = cur.copy(
                state = DownloadState.FAILED,
                error = messages.text(error),
                attempts = if (countAttempt) cur.attempts + 1 else cur.attempts,
                updatedAt = clock(),
            )
            dao.update(failed)
            // A permanent failure will never resume: no point keeping the partial file.
            if (permanent) deletePartIfUnused(cur.videoId)
            Outcome.Failed(failed)
        }

    // ------------------------------------------------------------------ progress & cancellation

    private suspend fun onProgress(entry: Running, done: Long, total: Long?) {
        entry.bytesDone = done
        entry.bytesTotal = total ?: 0L
        if (entry.cancelRequested) throw TaskCanceled()
        val now = clock()
        if (now - lastPublish >= PROGRESS_INTERVAL_MS) {
            lastPublish = now
            liveProgress.update { live -> live + inflight.values.filter { it.started }.associate { it.task.id to it.progress() } }
        }
        if (now - entry.lastWrite >= PROGRESS_WRITE_INTERVAL_MS) {
            entry.lastWrite = now
            writeProgress(entry)
        }
    }

    /** Progress into the table (and the live map, so both agree): on state changes and every few seconds. */
    private suspend fun writeProgress(entry: Running) {
        liveProgress.update { it + (entry.task.id to entry.progress()) }
        dao.setProgress(entry.task.id, entry.fraction, entry.bytesDone, entry.bytesTotal, clock())
    }

    /** Between chunks: stop when the coroutine was cancelled or the row is no longer RUNNING. */
    private suspend fun checkCanceled(entry: Running) {
        currentCoroutineContext().ensureActive()
        if (entry.cancelRequested) throw TaskCanceled()
        if (dao.get(entry.task.id)?.state != DownloadState.RUNNING) {
            entry.cancelRequested = true
            throw TaskCanceled()
        }
    }

    /** The task's coroutine stopped: back to the queue (worker stopped) or cleaned up (canceled). */
    private suspend fun interrupted(entry: Running) {
        val cur = stateLock.withLock {
            val cur = dao.get(entry.task.id)
            if (cur != null && cur.state == DownloadState.RUNNING && !entry.cancelRequested) {
                dao.setState(cur.id, DownloadState.QUEUED, null, clock())
            }
            cur
        }
        when {
            cur == null || cur.state == DownloadState.CANCELED || entry.cancelRequested -> discard(entry)
            // Finished (committed or failed) before the stop reached it: nothing to put back.
            cur.state == DownloadState.DONE || cur.state == DownloadState.FAILED -> Unit
            else -> {
                restorePart(entry)
                writeProgress(entry)
            }
        }
    }

    /** Deletes what a canceled task left behind. */
    private suspend fun discard(entry: Running) {
        entry.finalFile?.let { file ->
            if (library.trackByVideoId(entry.task.videoId)?.filePath != file.path) file.delete()
        }
        entry.finalFile = null
        stateLock.withLock { deletePartIfUnused(entry.task.videoId) }
    }

    /** Puts an uncommitted final file back as the .part so the next attempt resumes instantly. */
    private suspend fun restorePart(entry: Running) {
        val file = entry.finalFile ?: return
        // Never take the file of a track that made it into the library.
        if (file.exists() && library.trackByVideoId(entry.task.videoId)?.filePath != file.path) {
            move(file, storage.tempFile(entry.task.videoId))
        }
        entry.finalFile = null
    }

    /** Must hold [stateLock]. Keeps the .part while another task of the same video still needs it. */
    private suspend fun deletePartIfUnused(videoId: String) {
        if (dao.activeFor(videoId) != null) return
        val part = storage.tempFile(videoId)
        part.delete()
        signatureFile(part).delete()
    }

    // ------------------------------------------------------------------ commands

    /** Cancels the given tasks (every pending one when [ids] is null). Returns the canceled rows. */
    suspend fun cancel(ids: Collection<Long>?): List<DownloadTask> {
        val canceled = stateLock.withLock {
            val now = clock()
            dao.pending().filter { ids == null || it.id in ids }
                .onEach { dao.setState(it.id, DownloadState.CANCELED, null, now) }
                .also { rows ->
                    rows.filter { !inflight.containsKey(it.id) }.map { it.videoId }.distinct().forEach { deletePartIfUnused(it) }
                }
        }
        for (task in canceled) {
            inflight[task.id]?.let { entry ->
                entry.cancelRequested = true
                entry.job?.cancel()
            }
        }
        wake.trySend(Unit)
        return canceled
    }

    /** Puts FAILED (and, for explicit ids, CANCELED) tasks back in the queue. Returns how many. */
    suspend fun retry(ids: Collection<Long>?): Int = stateLock.withLock {
        val now = clock()
        val targets = if (ids == null) {
            dao.observeAll().first().filter { it.state == DownloadState.FAILED }
        } else {
            ids.mapNotNull { dao.get(it) }.filter { it.state == DownloadState.FAILED || it.state == DownloadState.CANCELED }
        }
        targets.forEach {
            dao.update(it.copy(state = DownloadState.QUEUED, attempts = 0, error = null, progress = 0f, bytesDone = 0, bytesTotal = 0, updatedAt = now))
        }
        targets.size
    }

    // ------------------------------------------------------------------ helpers

    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Log.w(TAG, "optional step failed: $t")
        null
    }

    companion object {
        const val CHUNK_SIZE = 8L * 1024 * 1024
        const val MAX_ATTEMPTS = 3
        const val MAX_URL_REFRESHES = 3
        /** How often the live [progress] of the running tasks is published. */
        const val PROGRESS_INTERVAL_MS = 250L

        /** How often a running task's progress is written to the table (it only matters after a restart). */
        const val PROGRESS_WRITE_INTERVAL_MS = 5_000L
        private const val TAG = "SurcoDownload"

        internal fun signatureFile(part: File): File = File(part.path + ".sig")

        private fun AudioStream.signature(): String = "$mimeType|${codec.orEmpty()}|$bitrate|${contentLength ?: "?"}"

        private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

        private fun readText(file: File): String? = if (file.isFile) runCatching { file.readText() }.getOrNull() else null

        private fun move(from: File, to: File) {
            to.parentFile?.mkdirs()
            if (to.exists()) to.delete()
            if (!from.renameTo(to)) {
                from.copyTo(to, overwrite = true)
                from.delete()
            }
        }
    }
}

/** [this] track with the album metadata an album-page [task] carries; null for any other task. */
internal fun Track.inAlbumOf(task: DownloadTask): Track? {
    if (!task.isFromAlbumPage) return null
    val album = task.album?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return copy(
        album = album,
        albumArtist = task.albumArtist?.trim()?.takeIf { it.isNotEmpty() } ?: albumArtist,
        trackNumber = task.trackNumber ?: trackNumber,
        year = task.year ?: year,
    )
}

/** What one [DownloadEngine.runQueue] did. */
internal class RunReport {
    val done: MutableList<DownloadTask> = CopyOnWriteArrayList()
    val failed: MutableList<DownloadTask> = CopyOnWriteArrayList()

    @Volatile var networkLost: Boolean = false
    val finished: Int get() = done.size + failed.size
}

internal data class RunningItem(val title: String, val artist: String, val fraction: Float)

/** Live progress of one running task. */
internal data class TaskProgress(val fraction: Float, val bytesDone: Long, val bytesTotal: Long)

/** The current run as the notification sees it. */
internal data class RunSnapshot(val finished: Int, val queued: Int, val running: List<RunningItem>) {
    val total: Int get() = finished + queued + running.size
    val hasWork: Boolean get() = queued > 0 || running.isNotEmpty()

    /** "Baixando [position] de [total]". */
    val position: Int get() = min(finished + 1, total)

    /** Overall 0..1, counting the partial progress of the songs being downloaded. */
    val fraction: Float
        get() = if (total == 0) 0f else ((finished + running.sumOf { it.fraction.toDouble() }) / total).toFloat().coerceIn(0f, 1f)
}

/** Duration of an audio file, or null when the platform cannot read it. */
internal fun probeDurationMs(file: File): Long? = try {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.path)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
    } finally {
        retriever.release()
    }
} catch (e: Exception) {
    null
}
