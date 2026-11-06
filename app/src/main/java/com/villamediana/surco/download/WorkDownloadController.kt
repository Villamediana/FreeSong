package com.villamediana.surco.download

import android.content.Context
import com.villamediana.surco.data.LibraryRepository
import com.villamediana.surco.data.SettingsStore
import com.villamediana.surco.data.Storage
import com.villamediana.surco.data.db.DownloadTask
import com.villamediana.surco.data.db.SurcoDatabase
import com.villamediana.surco.online.CollectionDetail
import com.villamediana.surco.online.RemoteTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** Download queue on WorkManager. (Stub — implemented by the download module.) */
class WorkDownloadController(
    private val context: Context,
    private val db: SurcoDatabase,
    private val library: LibraryRepository,
    private val storage: Storage,
    private val settings: SettingsStore,
) : DownloadController {
    override val tasks: Flow<List<DownloadTask>> = db.downloads().observeAll()
    override val summary: Flow<DownloadSummary> = flowOf(DownloadSummary())
    override val inProgressVideoIds: Flow<Set<String>> = flowOf(emptySet())
    override suspend fun download(tracks: List<RemoteTrack>) {}
    override suspend fun downloadCollection(detail: CollectionDetail): Long? = null
    override suspend fun cancel(taskId: Long) {}
    override suspend fun retry(taskId: Long) {}
    override suspend fun retryAllFailed() {}
    override suspend fun clearFinished() {}
    override suspend fun cancelAll() {}
    override fun kick() {}
}
