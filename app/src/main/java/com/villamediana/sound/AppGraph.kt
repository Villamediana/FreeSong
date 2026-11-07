package com.villamediana.sound

import com.villamediana.sound.data.db.DownloadState
import kotlinx.coroutines.flow.first
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
import kotlinx.coroutines.launch
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

    /** Public Spotify playlists (no login) and their import into a downloaded playlist. */
    val spotify: com.villamediana.sound.online.SpotifyPlaylists by lazy { com.villamediana.sound.online.SpotifyPlaylists(http) }
    val spotifyImport: com.villamediana.sound.online.SpotifyImporter by lazy {
        com.villamediana.sound.online.SpotifyImporter(
            search = { q ->
                youTube.search(q, com.villamediana.sound.online.SearchFilter.SONGS).items
                    .filterIsInstance<com.villamediana.sound.online.SearchItem.Song>().map { it.track }
            },
            save = { p, tracks -> library.saveImported(p.url, p.name, p.owner, p.coverUrl, tracks) },
            scope = scope,
        )
    }

    val downloads: DownloadController by lazy { WorkDownloadController(appContext, db, library, storage, settings) }
    val player: PlayerConnection by lazy { MediaPlayerConnection(appContext, library, settings, scope) }
    val discover: com.villamediana.sound.discover.DiscoverRepository by lazy {
        com.villamediana.sound.discover.DiscoverRepository(youTube, appContext.filesDir.resolve("discover.json"))
    }
    // ---------------------------------------------------------------- hearts & removal
    // Every heart and every "remove from the phone" goes through here, so the rules hold everywhere.

    /**
     * The heart of a streamed song (rows, song sheet). With "Curtir já baixa" on, a like also
     * downloads it in the background and unliking takes it off the phone (or stops its download).
     * Turning the setting on later touches only what is liked from then on.
     */
    fun likeOnline(remote: com.villamediana.sound.online.RemoteTrack, liked: Boolean) {
        scope.launch {
            val art = if (remote.fromMusicCatalog) null else covers.coverFor(remote.videoId)
            library.setLiked(if (art != null) remote.copy(thumbnailUrl = art) else remote, liked)
            val auto = settings.current().likeDownloads
            if (auto && liked) runCatching { downloads.download(listOf(remote)) }
            if (auto && !liked) removeFromPhone(remote.videoId)
        }
    }

    /** The heart of a library song (player, song sheet): same rule for unliking with "Curtir já baixa". */
    fun likeTrack(trackId: Long, liked: Boolean) {
        scope.launch {
            val track = library.getTrack(trackId) ?: return@launch
            library.setLiked(trackId, liked)
            val videoId = track.videoId
            if (!liked && videoId != null && settings.current().likeDownloads) removeFromPhone(videoId)
        }
    }

    /** The download button of a song that is on the phone (or on its way): take it off. */
    fun removeDownload(videoId: String) {
        scope.launch { removeFromPhone(videoId) }
    }

    /** Stops its download if one is going and deletes the song; a liked one stays in Curtidas, streamed. */
    private suspend fun removeFromPhone(videoId: String) {
        runCatching {
            downloads.tasks.first()
                .filter { it.videoId == videoId && (it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING) }
                .forEach { downloads.cancel(it.id) }
        }
        library.trackByVideoId(videoId)?.let { library.deleteTracks(listOf(it.id)) }
    }

    val effects: AudioEffectsController by lazy { DeviceAudioEffects(appContext, scope) }
    val video: VideoPlayback by lazy { YouTubeVideoPlayback(appContext, scope) }
    /** Album covers for songs that came as video uploads (their thumbnail is a video frame). */
    val covers: com.villamediana.sound.online.SongCovers by lazy {
        com.villamediana.sound.online.SongCovers({ youTube }, appContext.filesDir.resolve("covers.json"), scope)
    }

    /** The silent clip looping behind the cover of the full player. */
    val canvas: com.villamediana.sound.playback.video.VideoCanvas by lazy { com.villamediana.sound.playback.video.VideoCanvas(appContext, scope) }
}
