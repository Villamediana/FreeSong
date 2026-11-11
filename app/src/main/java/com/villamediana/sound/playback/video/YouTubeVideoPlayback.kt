package com.villamediana.sound.playback.video

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.online.OnlineException
import com.villamediana.sound.online.VideoStream
import com.villamediana.sound.online.YouTubeSource
import com.villamediana.sound.playback.PlayerConnection
import com.villamediana.sound.playback.ResumablePlayer
import com.villamediana.sound.online.messageRes
import com.villamediana.sound.online.onlineKind
import com.villamediana.sound.playback.VideoPlayback
import com.villamediana.sound.playback.VideoState
import com.villamediana.sound.playback.stream.ChunkedHttpDataSource
import com.villamediana.sound.playback.stream.StreamDataSources
import com.villamediana.sound.playback.stream.StreamErrors
import com.villamediana.sound.playback.stream.StreamLoadErrorPolicy
import com.villamediana.sound.playback.stream.StreamUris
import com.villamediana.sound.playback.stream.StreamUrlCache
import com.villamediana.sound.playback.stream.resolveBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * "Ver o vídeo": a separate ExoPlayer for a song's music video — the video-only H.264 stream and the
 * audio stream merged ([MergingMediaSource]), or one muxed stream — read in 2 MB ranges like the
 * online songs. Opening pauses the music and starts the video where the song was; closing releases
 * everything and, when the song playing is the one of the video, carries on from the video's position.
 *
 * Audio focus is ExoPlayer's on both sides: the music is paused while the video plays, and if the
 * music is started from elsewhere (notification, headset) the video loses focus and pauses itself.
 * Main thread only (the methods hop there).
 */
@OptIn(UnstableApi::class)
class YouTubeVideoPlayback internal constructor(
    private val context: Context,
    scope: CoroutineScope,
    private val youTube: () -> YouTubeSource,
    private val music: () -> PlayerConnection,
) : VideoPlayback {
    constructor(context: Context, scope: CoroutineScope) :
        this(context, scope, { SoundApplication.graph.youTube }, { SoundApplication.graph.player })

    private val main = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(VideoState())
    override val state: StateFlow<VideoState> = _state.asStateFlow()

    private val _player = MutableStateFlow<Player?>(null)
    override val player: StateFlow<Player?> = _player.asStateFlow()

    /** The video being shown, where it was asked to start, and whether the music was playing when it opened. */
    private class Session(val videoId: String, val startAtMs: Long, val musicWasPlaying: Boolean) {
        /** False when the clip played is another upload (official video for an art track). */
        var sameTimeline: Boolean = true
    }

    private var session: Session? = null
    private var exo: ExoPlayer? = null
    private var loadJob: Job? = null

    private val streams = StreamUrlCache<String, VideoStream>(scope, urlOf = { it.videoUrl }) { videoId, _ ->
        youTube().videoStream(videoId, MAX_HEIGHT)
    }

    /** Placeholder uris → the current googlevideo url of that part; a refused url resolves the video again. */
    private val resolver = ChunkedHttpDataSource.UriResolver { uri, refresh ->
        val (videoId, part) = StreamUris.videoPart(uri) ?: return@UriResolver uri
        val stream = resolveBlocking { streams.get(videoId, refresh) }
        val url = if (part == StreamUris.AUDIO_PART) stream.audioUrl ?: stream.videoUrl else stream.videoUrl
        Uri.parse(url)
    }

    private val sources by lazy {
        ProgressiveMediaSource.Factory(StreamDataSources.factory(context, resolver))
            .setLoadErrorHandlingPolicy(StreamLoadErrorPolicy())
    }

    override fun open(videoId: String, title: String?, startAtMs: Long) {
        main.launch {
            // Same video asked again (the screen came back): keep playing where it is.
            // (Not after a failure: then "Tentar de novo" must really reload.)
            if (session?.videoId == videoId && exo != null && _state.value.error == null) {
                _state.value = _state.value.copy(title = title ?: _state.value.title)
                return@launch
            }
            val music = music()
            val wasPlaying = session?.musicWasPlaying ?: music.state.value.isPlaying
            releasePlayer()
            music.pauseHere()
            val start = startAtMs.coerceAtLeast(0)
            session = Session(videoId, start, wasPlaying)
            _state.value = VideoState(videoId = videoId, title = title, loading = true)
            val player = buildPlayer()
            exo = player
            _player.value = player
            load(player, videoId, start)
        }
    }

    /** Finds the streams of [videoId] and starts [player] on them at [startAtMs]. */
    private fun load(player: ExoPlayer, videoId: String, startAtMs: Long) {
        loadJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        loadJob = main.launch {
            val stream = try {
                streams.get(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (exo === player) fail(message(t.onlineKind()))
                return@launch
            }
            if (exo !== player) return@launch
            // Another upload (official clip of an art track) has its own timeline: start it from 0.
            val substitute = stream.substituteVideoId != null && stream.substituteVideoId != videoId
            session?.takeIf { it.videoId == videoId }?.sameTimeline = !substitute
            _state.value = _state.value.copy(sameTimeline = !substitute)
            player.setMediaSource(mediaSource(videoId, stream), if (substitute) 0 else startAtMs)
            player.prepare()
            player.playWhenReady = true
        }
    }

    override fun togglePlayPause() {
        main.launch {
            val player = exo ?: return@launch
            if (player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)) {
                player.pause()
                return@launch
            }
            // Music started meanwhile (notification, headset): the video takes over again.
            music().takeIf { it.state.value.isPlaying }?.pauseHere()
            when (player.playbackState) {
                Player.STATE_ENDED -> player.seekToDefaultPosition()
                Player.STATE_IDLE -> if (player.mediaItemCount > 0) {
                    // After an error (connection lost): pick up where it stopped.
                    _state.value = _state.value.copy(error = null, loading = true)
                    player.prepare()
                } else {
                    // The streams were never found (offline when it opened): try again.
                    session?.let { load(player, it.videoId, it.startAtMs) }
                    return@launch
                }
                else -> Unit
            }
            player.play()
        }
    }

    override fun setSpeed(speed: Float) {
        main.launch { exo?.setPlaybackSpeed(speed) }
    }

    override fun seekTo(positionMs: Long) {
        main.launch { exo?.seekTo(positionMs.coerceAtLeast(0)) }
    }

    override fun close(resumeMusic: Boolean) {
        main.launch {
            val s = session
            val player = exo
            val position = player?.takeIf { it.mediaItemCount > 0 && it.playbackState != Player.STATE_IDLE }
                ?.currentPosition?.coerceAtLeast(0)
            releasePlayer()
            session = null
            _state.value = VideoState()
            if (s == null) return@launch
            val music = music()
            val now = music.state.value
            val sameSong = now.current?.videoId == s.videoId
            // In a jam the song's place is everyone's: back from the video it rejoins the jam instead.
            if (sameSong && position != null && s.sameTimeline && !music.shared) {
                val duration = now.durationMs.takeIf { it > 0 } ?: now.current?.durationMs?.takeIf { it > 0 }
                music.seekTo(if (duration != null) position.coerceAtMost(duration - END_MARGIN_MS).coerceAtLeast(0) else position)
            }
            if (!resumeMusic || !(sameSong || s.musicWasPlaying)) return@launch
            // Not a toggle on [now]: right after the pause sent on open it can still say "playing"
            // (or "paused" when the music was started meanwhile), and the toggle would do the opposite.
            if (music is ResumablePlayer) music.resume()
            else if (now.current != null && !now.isPlaying) music.togglePlayPause()
        }
    }

    // ---------------------------------------------------------------- player

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
        .apply {
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            addListener(Events(this))
            // The output picked in the player (the phone's speaker with earphones on…) goes for the video too.
            val picked = runCatching { com.villamediana.sound.SoundApplication.graph.outputChoice.preferred.value }.getOrNull()
            context.getSystemService(android.media.AudioManager::class.java)?.let { audio ->
                setPreferredAudioDevice(com.villamediana.sound.playback.OutputChoice.device(audio, picked))
            }
        }

    private fun mediaSource(videoId: String, stream: VideoStream): MediaSource {
        val video = sources.createMediaSource(
            MediaItem.Builder()
                .setUri(StreamUris.video(videoId, StreamUris.VIDEO_PART))
                .setCustomCacheKey("yt-video:$videoId")
                .setMimeType(stream.videoMimeType.ifBlank { null })
                .build(),
        )
        if (stream.audioUrl == null) return video
        val audio = sources.createMediaSource(
            MediaItem.Builder()
                .setUri(StreamUris.video(videoId, StreamUris.AUDIO_PART))
                .setCustomCacheKey("yt-video-audio:$videoId")
                .setMimeType(stream.audioMimeType?.ifBlank { null })
                .build(),
        )
        // The two streams may differ by a few ms in length and start offset: align and clip them.
        return MergingMediaSource(/* adjustPeriodTimeOffsets = */ true, /* clipDurations = */ true, video, audio)
    }

    private fun releasePlayer() {
        loadJob?.cancel()
        loadJob = null
        val player = exo ?: return
        exo = null
        _player.value = null
        player.release()
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(loading = false, playing = false, error = message)
    }

    private fun message(kind: OnlineException.Kind?): String =
        context.getString(kind?.messageRes() ?: R.string.playback_error_generic)

    private inner class Events(private val player: ExoPlayer) : Player.Listener {
        private val current get() = exo === player

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!current) return
            _state.value = _state.value.copy(playing = isPlaying, error = if (isPlaying) null else _state.value.error)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!current) return
            _state.value = _state.value.copy(
                loading = playbackState == Player.STATE_BUFFERING,
                playing = player.isPlaying,
            )
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (!current || videoSize.width <= 0 || videoSize.height <= 0) return
            val ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            if (ratio.isFinite() && ratio > 0f) _state.value = _state.value.copy(aspectRatio = ratio)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!current) return
            fail(message(StreamErrors.kindOf(error.errorCode)))
        }
    }

    private companion object {
        /** "Ver o vídeo" streams up to 720p (sharp on a phone, light on data). */
        const val MAX_HEIGHT = 720
        /** Never resume the song right at its very end. */
        const val END_MARGIN_MS = 1_000L
    }
}
