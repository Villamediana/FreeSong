package com.villamediana.surco.ui.search

import com.villamediana.surco.data.db.DownloadState
import com.villamediana.surco.data.db.DownloadTask

/** What the download button of one online song shows. */
sealed interface ItemDownload {
    /** Not on the phone: tap downloads. */
    data object None : ItemDownload
    /** Waiting in the queue (or just requested). */
    data object Queued : ItemDownload
    /** Downloading; [progress] is 0f..1f. */
    data class Running(val progress: Float) : ItemDownload
    /** On the phone: tap plays the local track. */
    data object Done : ItemDownload
    /** Last attempt failed: tap retries [taskId]. */
    data class Failed(val taskId: Long, val error: String?) : ItemDownload
}

/**
 * Snapshot that answers "what is the download state of video X" for the online screens.
 * Built from the library (what is already on the phone), the download queue and the songs the
 * user just asked for (shown as queued until the queue catches up).
 */
class DownloadLookup private constructor(
    val downloaded: Set<String>,
    private val tasks: Map<String, DownloadTask>,
    private val requested: Set<String>,
) {
    fun stateOf(videoId: String): ItemDownload {
        if (videoId in downloaded) return ItemDownload.Done
        val task = tasks[videoId]
        return when (task?.state) {
            DownloadState.QUEUED -> ItemDownload.Queued
            DownloadState.RUNNING -> ItemDownload.Running(task.progress.coerceIn(0f, 1f))
            DownloadState.FAILED -> if (videoId in requested) ItemDownload.Queued else ItemDownload.Failed(task.id, task.error)
            DownloadState.DONE, DownloadState.CANCELED, null -> if (videoId in requested) ItemDownload.Queued else ItemDownload.None
        }
    }

    /** The task that decides the state of [videoId], if any. */
    fun taskOf(videoId: String): DownloadTask? = tasks[videoId]

    /** True once the library or the queue know about [videoId] (the optimistic flag can go). */
    fun isKnown(videoId: String): Boolean {
        if (videoId in downloaded) return true
        val state = tasks[videoId]?.state ?: return false
        return state == DownloadState.QUEUED || state == DownloadState.RUNNING
    }

    fun isDownloaded(videoId: String): Boolean = videoId in downloaded

    companion object {
        val Empty = DownloadLookup(emptySet(), emptyMap(), emptySet())

        /**
         * One task per video: an active one (queued/running) wins over finished ones; otherwise
         * the newest. [tasks] can come in any order.
         */
        fun build(downloaded: Set<String>, tasks: List<DownloadTask>, requested: Set<String> = emptySet()): DownloadLookup {
            val byVideo = HashMap<String, DownloadTask>(tasks.size)
            for (task in tasks) {
                val current = byVideo[task.videoId]
                if (current == null || task.beats(current)) byVideo[task.videoId] = task
            }
            return DownloadLookup(downloaded, byVideo, requested)
        }

        private fun DownloadTask.isActive() = state == DownloadState.QUEUED || state == DownloadState.RUNNING

        private fun DownloadTask.beats(other: DownloadTask): Boolean {
            if (isActive() != other.isActive()) return isActive()
            if (createdAt != other.createdAt) return createdAt > other.createdAt
            return id > other.id
        }
    }
}

/** Progress of a set of songs (an album/playlist page). */
data class BatchProgress(
    val total: Int,
    val done: Int,
    val queued: Int,
    val running: Int,
    val failed: Int,
    /** 0f..1f over all songs, counting partial progress of the running ones. */
    val fraction: Float,
) {
    val active: Int get() = queued + running
    val allDone: Boolean get() = total > 0 && done == total
    val remaining: Int get() = total - done

    companion object {
        val Empty = BatchProgress(0, 0, 0, 0, 0, 0f)

        fun of(videoIds: List<String>, lookup: DownloadLookup): BatchProgress {
            val ids = videoIds.distinct()
            if (ids.isEmpty()) return Empty
            var done = 0
            var queued = 0
            var running = 0
            var failed = 0
            var sum = 0f
            for (id in ids) {
                when (val s = lookup.stateOf(id)) {
                    ItemDownload.Done -> { done++; sum += 1f }
                    ItemDownload.Queued -> queued++
                    is ItemDownload.Running -> { running++; sum += s.progress }
                    is ItemDownload.Failed -> failed++
                    ItemDownload.None -> Unit
                }
            }
            return BatchProgress(ids.size, done, queued, running, failed, (sum / ids.size).coerceIn(0f, 1f))
        }
    }
}

/** The big button of an online album/playlist/artist page. */
sealed interface PrimaryAction {
    /** Nothing to download (empty page). */
    data object Hidden : PrimaryAction
    /** Waiting for the queue to accept the request. */
    data object Enqueuing : PrimaryAction
    /** "Baixar tudo" ([remaining] == total) or "Baixar N restantes". */
    data class Download(val remaining: Int, val total: Int) : PrimaryAction {
        val partial: Boolean get() = remaining < total
    }
    /** Every song is on the phone but the local playlist does not exist yet. */
    data object SavePlaylist : PrimaryAction
    /** "Baixando 3 de 12". */
    data class Progress(val done: Int, val total: Int, val fraction: Float) : PrimaryAction
    /** Everything is on the phone: opens it in the library. */
    data object InLibrary : PrimaryAction

    companion object {
        fun of(isPlaylist: Boolean, progress: BatchProgress, hasLocalPlaylist: Boolean, enqueuing: Boolean): PrimaryAction = when {
            enqueuing -> Enqueuing
            progress.total == 0 -> Hidden
            progress.active > 0 -> Progress(progress.done, progress.total, progress.fraction)
            progress.allDone && isPlaylist && !hasLocalPlaylist -> SavePlaylist
            progress.allDone -> InLibrary
            else -> Download(progress.remaining, progress.total)
        }
    }
}
