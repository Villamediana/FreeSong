package com.villamediana.surco.playback.video

import android.content.Context
import androidx.media3.common.Player
import com.villamediana.surco.playback.VideoPlayback
import com.villamediana.surco.playback.VideoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Separate ExoPlayer for music videos (video-only + audio streams merged). (Stub.) */
class YouTubeVideoPlayback(private val context: Context, private val scope: CoroutineScope) : VideoPlayback {
    override val state: StateFlow<VideoState> = MutableStateFlow(VideoState())
    override val player: StateFlow<Player?> = MutableStateFlow(null)
    override fun open(videoId: String, title: String?, startAtMs: Long) {}
    override fun togglePlayPause() {}
    override fun seekTo(positionMs: Long) {}
    override fun close(resumeMusic: Boolean) {}
}
