package com.villamediana.sound.ui.downloads

import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.DownloadTask

/** Counts of one set of tasks. */
data class GroupStats(
    val total: Int,
    val done: Int,
    val queued: Int,
    val running: Int,
    val failed: Int,
    val canceled: Int,
    /** 0f..1f over the songs that were not canceled, counting partial progress. */
    val fraction: Float,
) {
    val active: Int get() = queued + running
    val isActive: Boolean get() = active > 0
    /** Not canceled. */
    val wanted: Int get() = total - canceled

    companion object {
        fun of(tasks: List<DownloadTask>): GroupStats {
            var done = 0
            var queued = 0
            var running = 0
            var failed = 0
            var canceled = 0
            var sum = 0f
            for (t in tasks) {
                when (t.state) {
                    DownloadState.DONE -> { done++; sum += 1f }
                    DownloadState.QUEUED -> queued++
                    DownloadState.RUNNING -> { running++; sum += t.progress.coerceIn(0f, 1f) }
                    DownloadState.FAILED -> failed++
                    DownloadState.CANCELED -> canceled++
                }
            }
            val wanted = tasks.size - canceled
            return GroupStats(tasks.size, done, queued, running, failed, canceled, if (wanted == 0) 0f else (sum / wanted).coerceIn(0f, 1f))
        }
    }
}

/** One block of the downloads screen: an album/playlist batch, or the loose songs. */
data class DownloadGroup(
    /** Stable LazyColumn key. */
    val key: String,
    val batchId: String?,
    val title: String?,
    val coverUrl: String?,
    /** In display order (source order for batches, newest first for loose songs). */
    val tasks: List<DownloadTask>,
    val stats: GroupStats,
    /** Local playlist the batch fills (playlist downloads). */
    val targetPlaylistId: Long?,
) {
    val isBatch: Boolean get() = batchId != null
}

/**
 * The header of the downloads screen. The "wave" is everything enqueued since the oldest song that
 * is still waiting — so "3 de 12" talks about the current album/playlist, not the whole history.
 */
data class DownloadOverview(
    val queued: Int,
    val running: Int,
    val failed: Int,
    val done: Int,
    val canceled: Int,
    val waveDone: Int,
    val waveTotal: Int,
    /** 0f..1f of the current wave; null when nothing is downloading. */
    val fraction: Float?,
) {
    val active: Int get() = queued + running
    val isActive: Boolean get() = active > 0
    val finished: Int get() = done + canceled
    val isEmpty: Boolean get() = queued + running + failed + done + canceled == 0

    companion object {
        val Empty = DownloadOverview(0, 0, 0, 0, 0, 0, 0, null)
    }
}

object DownloadGrouping {
    const val LOOSE_KEY = "loose"

    private val sourceOrder = compareBy<DownloadTask>(
        { it.targetPosition ?: Int.MAX_VALUE },
        { it.trackNumber ?: Int.MAX_VALUE },
        { it.createdAt },
        { it.id },
    )
    private val newestFirst = compareByDescending<DownloadTask> { it.createdAt }.thenByDescending { it.id }

    /** Batches (newest first), then the loose songs. */
    fun groups(tasks: List<DownloadTask>): List<DownloadGroup> {
        val (batched, loose) = tasks.partition { it.batchId != null }
        val batches = batched.groupBy { it.batchId!! }
            .map { (batchId, items) ->
                val ordered = items.sortedWith(sourceOrder)
                DownloadGroup(
                    key = "batch:$batchId",
                    batchId = batchId,
                    title = ordered.firstNotNullOfOrNull { it.batchTitle?.takeIf(String::isNotBlank) }
                        ?: ordered.firstNotNullOfOrNull { it.album?.takeIf(String::isNotBlank) },
                    coverUrl = ordered.firstNotNullOfOrNull { it.albumCoverUrl } ?: ordered.firstNotNullOfOrNull { it.thumbnailUrl },
                    tasks = ordered,
                    stats = GroupStats.of(ordered),
                    targetPlaylistId = ordered.firstNotNullOfOrNull { it.targetPlaylistId },
                )
            }
            .sortedWith(compareByDescending<DownloadGroup> { g -> g.tasks.maxOf { it.createdAt } }.thenBy { it.key })
        val looseGroup = loose.takeIf { it.isNotEmpty() }?.sortedWith(newestFirst)?.let {
            DownloadGroup(LOOSE_KEY, null, null, null, it, GroupStats.of(it), null)
        }
        return batches + listOfNotNull(looseGroup)
    }

    fun overview(tasks: List<DownloadTask>): DownloadOverview {
        if (tasks.isEmpty()) return DownloadOverview.Empty
        val stats = GroupStats.of(tasks)
        val oldestActive = tasks.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }.minOfOrNull { it.createdAt }
        if (oldestActive == null) {
            return DownloadOverview(0, 0, stats.failed, stats.done, stats.canceled, 0, 0, null)
        }
        val wave = tasks.filter { it.createdAt >= oldestActive && (it.state == DownloadState.DONE || it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING) }
        val waveDone = wave.count { it.state == DownloadState.DONE }
        val partial = wave.filter { it.state == DownloadState.RUNNING }.sumOf { it.progress.coerceIn(0f, 1f).toDouble() }.toFloat()
        return DownloadOverview(
            queued = stats.queued,
            running = stats.running,
            failed = stats.failed,
            done = stats.done,
            canceled = stats.canceled,
            waveDone = waveDone,
            waveTotal = wave.size,
            fraction = ((waveDone + partial) / wave.size).coerceIn(0f, 1f),
        )
    }
}
