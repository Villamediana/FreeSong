package com.villamediana.sound.playback.video

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.online.VideoStream
import com.villamediana.sound.online.YouTubeSource
import com.villamediana.sound.playback.stream.ChunkedHttpDataSource
import com.villamediana.sound.playback.stream.StreamDataSources
import com.villamediana.sound.playback.stream.StreamUris
import com.villamediana.sound.playback.stream.StreamUrlCache
import com.villamediana.sound.playback.stream.resolveBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** The moving backdrop of the full player (see [VideoCanvas]); swappable in tests. */
interface PlayerCanvas {
    /** The muted loop to draw; null when there is none. */
    val player: StateFlow<Player?>

    /** The song whose clip [player] is showing (a video was found for it). */
    val videoId: StateFlow<String?>

    /** Shows the clip of [videoId] behind the cover, or stops (null). Main thread. */
    fun show(videoId: String?)
}

/**
 * A short, silent stretch of a song's music video looping behind the cover of the full player.
 * Its own small ExoPlayer: video track only (no sound, no audio focus — the music keeps it), 360p,
 * ten seconds from the 30th (from the start for a short clip), read through a disk cache so the loop
 * doesn't download again. Only alive while the full player is on screen; let go a little after.
 */
@OptIn(UnstableApi::class)
class VideoCanvas internal constructor(
    private val context: Context,
    scope: CoroutineScope,
    private val youTube: () -> YouTubeSource,
) : PlayerCanvas {
    constructor(context: Context, scope: CoroutineScope) : this(context, scope, { SoundApplication.graph.youTube })

    private val main = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)

    private val _player = MutableStateFlow<Player?>(null)
    override val player: StateFlow<Player?> = _player.asStateFlow()

    private val _videoId = MutableStateFlow<String?>(null)
    override val videoId: StateFlow<String?> = _videoId.asStateFlow()

    private var exo: ExoPlayer? = null
    private var current: String? = null
    /** Where the loop of [current] starts (30 s, or 0 after a clip too short for that). */
    private var clipStart = CLIP_START_MS
    private var loadJob: Job? = null
    private var releaseJob: Job? = null

    /** Songs known to have no clip (art tracks with no official video): not asked again. */
    private val noVideo = HashSet<String>()

    private val streams = StreamUrlCache<String, VideoStream>(scope, urlOf = { it.videoUrl }) { videoId, _ ->
        youTube().videoStream(videoId, MAX_HEIGHT)
    }

    private val resolver = ChunkedHttpDataSource.UriResolver { uri, refresh ->
        val (videoId, _) = StreamUris.videoPart(uri) ?: return@UriResolver uri
        Uri.parse(resolveBlocking { streams.get(videoId, refresh) }.videoUrl)
    }

    private val sources by lazy {
        val upstream = StreamDataSources.factory(context, resolver)
        val cached = CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        ProgressiveMediaSource.Factory(cached)
    }

    override fun show(videoId: String?) {
        main.launch {
            if (videoId == null || videoId in noVideo) {
                pause()
                return@launch
            }
            releaseJob?.cancel()
            val player = exo
            if (videoId == current && player != null) {
                player.play()
                return@launch
            }
            release()
            current = videoId
            load(videoId, CLIP_START_MS)
        }
    }

    private fun load(videoId: String, startMs: Long) {
        loadJob?.cancel()
        loadJob = main.launch {
            val stream = try {
                streams.get(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (current == videoId) noVideo += videoId
                return@launch
            }
            if (current != videoId) return@launch
            clipStart = startMs
            val player = exo ?: build().also {
                exo = it
                it.addListener(Events(it))
            }
            player.setMediaSource(clip(videoId, stream, startMs))
            player.prepare()
            player.play()
            _player.value = player
            _videoId.value = videoId
        }
    }

    private fun clip(videoId: String, stream: VideoStream, startMs: Long): MediaSource {
        val video = sources.createMediaSource(
            MediaItem.Builder()
                .setUri(StreamUris.video(videoId, StreamUris.VIDEO_PART))
                .setCustomCacheKey("canvas:$videoId")
                .setMimeType(stream.videoMimeType.ifBlank { null })
                .build(),
        )
        return ClippingMediaSource.Builder(video)
            .setStartPositionMs(startMs)
            .setEndPositionMs(startMs + CLIP_MS)
            .build()
    }

    private fun build(): ExoPlayer = ExoPlayer.Builder(context)
        .build()
        .apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }

    /** Hidden: paused now, let go of the decoder a little later (a quick collapse/expand keeps it). */
    private fun pause() {
        exo?.pause()
        releaseJob?.cancel()
        releaseJob = main.launch {
            delay(RELEASE_AFTER_MS)
            release()
            current = null
        }
    }

    private fun release() {
        loadJob?.cancel()
        loadJob = null
        val player = exo ?: return
        exo = null
        _player.value = null
        _videoId.value = null
        player.release()
    }

    private inner class Events(private val player: ExoPlayer) : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val videoId = current
            if (exo !== player || videoId == null) return
            // A clip shorter than the stretch asked for: its first seconds instead, once.
            if (clipStart != 0L) {
                load(videoId, 0)
                return
            }
            noVideo += videoId
            release()
            current = null
        }
    }

    companion object {
        /** Light on data: the backdrop is darkened and soft anyway. */
        const val MAX_HEIGHT = 360
        const val CLIP_START_MS = 30_000L
        const val CLIP_MS = 10_000L
        const val RELEASE_AFTER_MS = 15_000L
        private const val CACHE_BYTES = 64L * 1024 * 1024

        @Volatile private var sharedCache: SimpleCache? = null

        /** One cache per process (a cache folder can't be opened twice). */
        private fun cache(context: Context): SimpleCache = sharedCache ?: synchronized(this) {
            sharedCache ?: SimpleCache(
                File(context.cacheDir, "canvas"),
                LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
                StandaloneDatabaseProvider(context),
            ).also { sharedCache = it }
        }
    }
}
