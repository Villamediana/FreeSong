package com.villamediana.surco.playback

import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

// ------------------------------------------------------------------ equalizer & effects

/** One band of the device equalizer. */
data class EqBand(val index: Int, val centerHz: Int, val levelMb: Int)

/** Surco's own presets (ids are stable; the UI maps them to localized names). */
enum class EqPreset { FLAT, BASS, TREBLE, VOCAL, ROCK, POP, ELECTRONIC, HIPHOP, LATIN, JAZZ, CLASSICAL, ACOUSTIC, LATE_NIGHT }

data class EqState(
    /** False when the device exposes no equalizer (the UI explains it and disables controls). */
    val available: Boolean = false,
    val enabled: Boolean = false,
    val bands: List<EqBand> = emptyList(),
    val minLevelMb: Int = -1500,
    val maxLevelMb: Int = 1500,
    /** null = custom curve. */
    val preset: EqPreset? = EqPreset.FLAT,
    /** 0..1000 */
    val bassBoost: Int = 0,
    val bassBoostAvailable: Boolean = false,
    /** 0..1000 — stereo widening / "surround" for headphones. */
    val virtualizer: Int = 0,
    val virtualizerAvailable: Boolean = false,
)

/**
 * The equalizer for everything Surco plays (offline and online). Lives in AppGraph; the
 * PlaybackService calls [attach] with ExoPlayer's audio session id. Settings persist.
 */
interface AudioEffectsController {
    val state: StateFlow<EqState>
    fun setEnabled(enabled: Boolean)
    fun selectPreset(preset: EqPreset)
    fun setBandLevel(band: Int, levelMb: Int)
    fun setBassBoost(strength: Int)
    fun setVirtualizer(strength: Int)
    fun reset()
    /** Called by PlaybackService whenever the player's audio session id changes. */
    fun attach(audioSessionId: Int)
    fun release()
}

// ------------------------------------------------------------------ video

data class VideoState(
    val videoId: String? = null,
    val title: String? = null,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val error: String? = null,
    /** width / height of the video. */
    val aspectRatio: Float = 16f / 9f,
)

/**
 * "Ver o vídeo": plays the music video of a song (always online). Opening pauses the music and
 * starts the video where the song was; closing resumes the music at the video's position.
 */
interface VideoPlayback {
    val state: StateFlow<VideoState>
    /** The Player to render (media3 ui-compose PlayerSurface); null when closed. */
    val player: StateFlow<Player?>
    fun open(videoId: String, title: String?, startAtMs: Long)
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun close(resumeMusic: Boolean = true)
}
