package com.villamediana.surco

import android.content.Context
import com.villamediana.surco.data.LibraryRepository
import com.villamediana.surco.data.SettingsStore
import com.villamediana.surco.data.Storage
import com.villamediana.surco.data.db.SurcoDatabase
import com.villamediana.surco.download.DownloadController
import com.villamediana.surco.download.WorkDownloadController
import com.villamediana.surco.online.CatalogMetadataEnricher
import com.villamediana.surco.online.LrclibLyricsSource
import com.villamediana.surco.online.LyricsSource
import com.villamediana.surco.online.MetadataEnricher
import com.villamediana.surco.online.NewPipeHttp
import com.villamediana.surco.online.NewPipeYouTubeSource
import com.villamediana.surco.online.YouTubeSource
import com.villamediana.surco.playback.MediaPlayerConnection
import com.villamediana.surco.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual DI: one instance of each service for the whole process. */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val db: SurcoDatabase by lazy { SurcoDatabase.build(appContext) }
    val storage: Storage by lazy { Storage(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val library: LibraryRepository by lazy { LibraryRepository(db, scope) }

    val youTube: YouTubeSource by lazy {
        NewPipeHttp.init(http)
        NewPipeYouTubeSource(http)
    }
    val enricher: MetadataEnricher by lazy { CatalogMetadataEnricher(http) }
    val lyrics: LyricsSource by lazy { LrclibLyricsSource(http) }

    val downloads: DownloadController by lazy { WorkDownloadController(appContext, db, library, storage, settings) }
    val player: PlayerConnection by lazy { MediaPlayerConnection(appContext, library, settings, scope) }
}
