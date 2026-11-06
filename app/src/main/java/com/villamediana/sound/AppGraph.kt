package com.villamediana.sound

import android.content.Context
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.SettingsStore
import com.villamediana.sound.data.Storage
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.download.DownloadController
import com.villamediana.sound.download.WorkDownloadController
import com.villamediana.sound.online.CatalogMetadataEnricher
import com.villamediana.sound.online.LrclibLyricsSource
import com.villamediana.sound.online.LyricsSource
import com.villamediana.sound.online.MetadataEnricher
import com.villamediana.sound.online.NewPipeHttp
import com.villamediana.sound.online.NewPipeYouTubeSource
import com.villamediana.sound.online.YouTubeSource
import com.villamediana.sound.playback.AudioEffectsController
import com.villamediana.sound.playback.MediaPlayerConnection
import com.villamediana.sound.playback.PlayerConnection
import com.villamediana.sound.playback.VideoPlayback
import com.villamediana.sound.playback.effects.DeviceAudioEffects
import com.villamediana.sound.playback.video.YouTubeVideoPlayback
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

    val db: SoundDatabase by lazy { SoundDatabase.build(appContext) }
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
    val effects: AudioEffectsController by lazy { DeviceAudioEffects(appContext, scope) }
    val video: VideoPlayback by lazy { YouTubeVideoPlayback(appContext, scope) }
}
