package com.villamediana.sound.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.db.DownloadTask
import com.villamediana.sound.data.db.OnlinePlay
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.YouTubeLinks
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * "Downloads inteligentes": the songs you keep listening to online come to the phone by themselves,
 * on Wi-Fi (no need to be charging). A song qualifies after [MIN_PLAYS] listens (not skipped) in the
 * last [WINDOW_MS]; one that was ever downloaded, queued or taken off the phone is left alone.
 */
object SmartDownloads {
    const val MIN_PLAYS = 3
    const val WINDOW_MS = 30L * 24 * 3_600_000
    const val MAX_PER_RUN = 20

    fun pick(plays: List<OnlinePlay>, onPhone: Set<String>, tasks: List<DownloadTask>, limit: Int = MAX_PER_RUN): List<RemoteTrack> {
        val known = tasks.mapTo(HashSet()) { it.videoId } + onPhone
        return plays.asSequence()
            .filter { !it.skipped && it.videoId !in known }
            .groupBy { it.videoId }
            .filterValues { it.size >= MIN_PLAYS }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<OnlinePlay>>> { it.value.size }.thenByDescending { e -> e.value.maxOf { it.playedAt } })
            .take(limit)
            .map { (videoId, list) ->
                val p = list.maxBy { it.playedAt }
                RemoteTrack(
                    videoId = videoId,
                    url = p.url?.takeIf { it.startsWith("http") } ?: YouTubeLinks.watchUrl(videoId, music = true),
                    title = p.title,
                    artist = p.artist,
                    album = p.album,
                    durationMs = p.durationMs.takeIf { it > 0 },
                    thumbnailUrl = p.thumbnailUrl,
                    fromMusicCatalog = p.url?.contains("music.youtube.com") == true,
                )
            }
    }

    private const val PERIODIC = "smart-downloads"
    private const val NOW = "smart-downloads-now"

    private fun wifi() = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

    /** On: a run now (when on Wi-Fi) and every 12 h after. Off: nothing scheduled. */
    fun sync(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(NOW)
            return
        }
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SmartDownloadWorker>(12, TimeUnit.HOURS).setConstraints(wifi()).build(),
        )
        wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SmartDownloadWorker>().setConstraints(wifi()).build())
    }
}

class SmartDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val graph = SoundApplication.graph
        if (!graph.settings.current().smartDownloads) return Result.success()
        val since = System.currentTimeMillis() - SmartDownloads.WINDOW_MS
        val picks = SmartDownloads.pick(
            plays = graph.library.onlinePlaysListSince(since),
            onPhone = graph.library.downloadedVideoIds.first(),
            tasks = graph.downloads.tasks.first(),
        )
        if (picks.isNotEmpty()) runCatching { graph.downloads.download(picks) }
        return Result.success()
    }
}
