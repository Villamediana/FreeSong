package com.villamediana.sound.download

import android.content.Context
import android.util.Log
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.Settings
import com.villamediana.sound.data.SettingsStore
import com.villamediana.sound.data.Storage
import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.DownloadTask
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.RemoteTrack
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Download queue on WorkManager. Requests become rows of the `downloads` table ([DownloadQueue]);
 * [DownloadWorker] drains them through the process-wide [DownloadEngine].
 */
class WorkDownloadController(
    private val context: Context,
    private val db: SoundDatabase,
    private val library: LibraryRepository,
    private val storage: Storage,
    private val settings: SettingsStore,
) : DownloadController {
    private val dao = db.downloads()
    private val appContext: Context = context.applicationContext ?: context
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> Log.e(TAG, "download controller", t) },
    )

    private val liveProgress = MutableStateFlow<Map<Long, TaskProgress>>(emptyMap())

    /** Built on first use: the online services come from the app graph. */
    internal val engine: DownloadEngine by lazy {
        val graph = SoundApplication.graph
        DownloadEngine(
            dao = dao,
            library = library,
            storage = storage,
            settings = settings::current,
            youTube = graph.youTube,
            enricher = graph.enricher,
            lyrics = graph.lyrics,
            http = graph.http,
            messages = AndroidDownloadMessages(appContext),
            isOnline = { isOnline(appContext) },
            liveProgress = liveProgress,
        )
    }

    private val scheduler = WorkScheduler(appContext, settings::current)

    private val queue: DownloadQueue by lazy {
        DownloadQueue(
            dao = dao,
            library = library,
            storage = storage,
            engine = engine,
            scheduler = scheduler,
            background = { block -> scope.launch { block() } },
        )
    }

    private val allTasks: Flow<List<DownloadTask>> = combine(dao.observeAll(), liveProgress, ::withLiveProgress)
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    override val tasks: Flow<List<DownloadTask>> = allTasks

    override val summary: Flow<DownloadSummary> = allTasks.map(::summarize).distinctUntilChanged()

    override val inProgressVideoIds: Flow<Set<String>> = allTasks
        .map { list -> list.filter { it.state.isActive }.mapTo(HashSet()) { it.videoId } as Set<String> }
        .distinctUntilChanged()

    init {
        scope.launch { followWifiOnly(settings.settings, hasPending = { dao.pending().isNotEmpty() }, scheduler) }
    }

    override suspend fun download(tracks: List<RemoteTrack>) {
        withContext(Dispatchers.IO) { queue.download(tracks) }
    }

    override suspend fun downloadCollection(detail: CollectionDetail): Long? =
        withContext(Dispatchers.IO) { queue.downloadCollection(detail) }

    override suspend fun cancel(taskId: Long) = withContext(Dispatchers.IO) { queue.cancel(taskId) }

    override suspend fun retry(taskId: Long) = withContext(Dispatchers.IO) { queue.retry(taskId) }

    override suspend fun retryAllFailed() = withContext(Dispatchers.IO) { queue.retryAllFailed() }

    override suspend fun clearFinished() = withContext(Dispatchers.IO) { queue.clearFinished() }

    override suspend fun cancelAll() = withContext(Dispatchers.IO) { queue.cancelAll() }

    override fun kick() {
        scope.launch { queue.kick() }
    }

    /** The Cancel button of the progress notification. */
    internal fun cancelAllInBackground() {
        scope.launch { queue.cancelAll() }
    }

    private companion object {
        const val TAG = "SoundDownload"
    }
}

/**
 * "Only on Wi-Fi" changed: re-creates the work so its network constraint follows the setting —
 * also while a worker is downloading. WorkManager never re-reads the constraints of a request, so
 * a running worker would keep downloading on mobile data (setting turned on) or stay stuck waiting
 * for Wi-Fi (turned off). The replaced worker puts its tasks back in the queue with their .part
 * files and the new one resumes them.
 */
internal suspend fun followWifiOnly(settings: Flow<Settings>, hasPending: suspend () -> Boolean, scheduler: DownloadScheduler) {
    settings.map { it.wifiOnly }.distinctUntilChanged().drop(1).collect {
        if (hasPending()) scheduler.schedule(SchedulePolicy.REPLACE)
    }
}

/** [rows] with the byte progress the engine keeps in memory for the running ones (the table lags a few seconds). */
internal fun withLiveProgress(rows: List<DownloadTask>, live: Map<Long, TaskProgress>): List<DownloadTask> {
    if (live.isEmpty()) return rows
    return rows.map { row ->
        val p = live[row.id]
        if (p == null || row.state != DownloadState.RUNNING) row
        else row.copy(progress = p.fraction, bytesDone = p.bytesDone, bytesTotal = p.bytesTotal)
    }
}

internal val DownloadState.isActive: Boolean
    get() = this == DownloadState.QUEUED || this == DownloadState.RUNNING

/** Counts per state and the mean progress of the queued + running tasks (null when idle). */
internal fun summarize(tasks: List<DownloadTask>): DownloadSummary {
    var queued = 0
    var running = 0
    var failed = 0
    var done = 0
    var progress = 0.0
    for (t in tasks) {
        when (t.state) {
            DownloadState.QUEUED -> {
                queued++
                progress += t.progress
            }
            DownloadState.RUNNING -> {
                running++
                progress += t.progress
            }
            DownloadState.FAILED -> failed++
            DownloadState.DONE -> done++
            DownloadState.CANCELED -> Unit
        }
    }
    val active = queued + running
    return DownloadSummary(
        queued = queued,
        running = running,
        failed = failed,
        done = done,
        progress = if (active == 0) null else (progress / active).toFloat().coerceIn(0f, 1f),
    )
}
