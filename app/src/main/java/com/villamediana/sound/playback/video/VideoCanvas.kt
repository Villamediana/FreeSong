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

    /**
     * A second copy of the clip, drawn over [player]: near the end of a loop it starts from the
     * beginning and [twinAlpha] fades it in (or out), so the loop melts into its own start.
     */
    val twin: StateFlow<Player?> get() = NO_PLAYER
    val twinAlpha: StateFlow<Float> get() = NO_ALPHA
}

private val NO_PLAYER = MutableStateFlow<Player?>(null)
private val NO_ALPHA = MutableStateFlow(0f)

/**
 * The loop's seam, without a seam: when the copy in front is [LoopCrossfade.FADE_MS] from its end,
 * the other copy starts from the beginning and takes over over that time (an S curve), then they
 * swap roles. Pure: tested on its own.
 */
object LoopCrossfade {
    const val FADE_MS = 1_200L

    /** Time to start the other copy: the one playing is within the fade of its end. */
    fun shouldStart(positionMs: Long, durationMs: Long): Boolean =
        durationMs > FADE_MS * 2 && durationMs - positionMs <= FADE_MS

    /** How far the takeover is (0 → 1) when the incoming copy is at [incomingPositionMs]. */
    fun progress(incomingPositionMs: Long): Float {
        val t = (incomingPositionMs.toFloat() / FADE_MS).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** Alpha of the top copy: it comes in when it is the incoming one, goes out when it hands back. */
    fun topAlpha(incomingIsTop: Boolean, progress: Float): Float = if (incomingIsTop) progress else 1f - progress
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

    private val _twin = MutableStateFlow<Player?>(null)
    override val twin: StateFlow<Player?> = _twin.asStateFlow()

    private val _twinAlpha = MutableStateFlow(0f)
    override val twinAlpha: StateFlow<Float> = _twinAlpha.asStateFlow()

    private var exo: ExoPlayer? = null
    /** The copy drawn on top ([twin]); null when it couldn't play (then [exo] loops by itself). */
    private var top: ExoPlayer? = null
    /** The copy the loop is on now ([exo] or [top]); the other waits at the start. */
    private var front: ExoPlayer? = null
    private var crossing = false
    private var loopJob: Job? = null
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
        // Small ranges: the loop is ten seconds, not the whole video.
        val upstream = StreamDataSources.factory(context, resolver, chunkSize = CANVAS_CHUNK_BYTES)
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
                (front ?: player).play()
                if (crossing) other()?.play()
                startLoop()
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
            // The second copy reads the same bytes from the disk cache.
            val second = top ?: build().also {
                top = it
                it.addListener(TopEvents(it))
            }
            player.setMediaSource(clip(videoId, stream, startMs))
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.prepare()
            player.play()
            // Prepared once the first copy has the whole loop in the cache (see [step]): started
            // together, both would download the same bytes.
            second.stop()
            second.setMediaSource(clip(videoId, stream, startMs))
            front = player
            crossing = false
            _twinAlpha.value = 0f
            _player.value = player
            _twin.value = second
            _videoId.value = videoId
            startLoop()
        }
    }

    private fun other(): ExoPlayer? = if (front === exo) top else exo

    /** Watches the copy in front and hands over to the other one at the end of the loop. */
    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = main.launch {
            while (true) {
                delay(FRAME_MS)
                step()
            }
        }
    }

    private fun step() {
        val cur = front ?: return
        val next = other()
        // The second copy readies itself from the disk cache, after the first one loaded the loop.
        val first = exo
        if (next != null && next.playbackState == Player.STATE_IDLE && first != null &&
            first.playbackState == Player.STATE_READY && !first.isLoading
        ) {
            next.prepare()
        }
        if (next == null) {
            // No second copy: the clip loops on its own (with its cut).
            if (cur.repeatMode != Player.REPEAT_MODE_ONE) cur.repeatMode = Player.REPEAT_MODE_ONE
            return
        }
        if (!crossing) {
            // The other copy wasn't ready in time: start over on this one rather than freeze.
            if (cur.playbackState == Player.STATE_ENDED) {
                cur.seekTo(0)
                cur.play()
                return
            }
            if (!cur.isPlaying) return
            if (LoopCrossfade.shouldStart(cur.currentPosition, cur.duration) && next.playbackState == Player.STATE_READY) {
                next.seekTo(0)
                next.play()
                crossing = true
            }
            return
        }
        val progress = LoopCrossfade.progress(next.currentPosition)
        _twinAlpha.value = LoopCrossfade.topAlpha(incomingIsTop = next === top, progress)
        if (progress >= 1f || cur.playbackState == Player.STATE_ENDED) {
            cur.pause()
            cur.seekTo(0)
            front = next
            crossing = false
            _twinAlpha.value = if (next === top) 1f else 0f
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
            repeatMode = Player.REPEAT_MODE_OFF
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }

    /** Hidden: paused now, let go of the decoder a little later (a quick collapse/expand keeps it). */
    private fun pause() {
        loopJob?.cancel()
        loopJob = null
        exo?.pause()
        top?.pause()
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
        loopJob?.cancel()
        loopJob = null
        top?.let { t ->
            top = null
            _twin.value = null
            t.release()
        }
        front = null
        crossing = false
        _twinAlpha.value = 0f
        val player = exo ?: return
        exo = null
        _player.value = null
        _videoId.value = null
        player.release()
    }

    /** The second copy failing is no reason to lose the clip: it just loops on its own again. */
    private inner class TopEvents(private val player: ExoPlayer) : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (top !== player) return
            top = null
            _twin.value = null
            _twinAlpha.value = 0f
            if (front === player) front = exo
            crossing = false
            exo?.let { if (!it.isPlaying) { it.seekTo(0); it.play() } }
            player.release()
        }
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
        private const val FRAME_MS = 16L
        private const val CANVAS_CHUNK_BYTES = 512L * 1024
        private const val CACHE_BYTES = 64L * 1024 * 1024

        @Volatile private var sharedCache: SimpleCache? = null

        /** The clips kept on disk (Ajustes › Armazenamento shows and clears it). */
        internal fun sharedCache(context: Context): SimpleCache = cache(context)

        /** One cache per process (a cache folder can't be opened twice). */
        private fun cache(context: Context): SimpleCache = sharedCache ?: synchronized(this) {
            sharedCache ?: SimpleCache(
                File(context.cacheDir, "canvas"),
                LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
                com.villamediana.sound.playback.stream.AudioCache.databaseProvider(context),
            ).also { sharedCache = it }
        }
    }
}
