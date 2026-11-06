package com.villamediana.sound.playback

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.RemoteTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class RepeatMode { OFF, ALL, ONE }

/** Where the current queue came from — shown as "Tocando de …" in the player. */
data class PlaySource(val type: Type, val title: String, val id: String? = null) {
    enum class Type { SONGS, ALBUM, ARTIST, PLAYLIST, GENRE, LIKED, MIX, SEARCH, RADIO, SMART_SHUFFLE, QUEUE }
}

/** Seeds for the "smart" playback modes (offline, computed from the library). */
sealed interface SmartSeed {
    /** Whole library, weighted by taste: liked and often-finished songs come more, skipped ones less. */
    data object Library : SmartSeed
    /** Songs of one genre, smart-shuffled, then flowing into neighbouring genres. */
    data class Genre(val name: String) : SmartSeed
    /** Starts from [track] and keeps going with what sounds alike (same artist/genre/era, your favourites). */
    data class Radio(val track: Track) : SmartSeed
    /** A mix built by [com.villamediana.sound.smart.SmartMixes] (Home cards). */
    data class Mix(val id: String) : SmartSeed
}

sealed interface SleepTimer {
    data object Off : SleepTimer
    data class At(val endsAtElapsedMs: Long, val totalMs: Long) : SleepTimer
    data object EndOfTrack : SleepTimer
}

data class PlaybackState(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    /** The queue in play order (already shuffled when shuffle is on). */
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val source: PlaySource? = null,
    /** True when the queue is extended automatically with similar songs (radio / smart modes). */
    val smart: Boolean = false,
    /** Name of the output device when known ("Fones Bluetooth", "Alto-falante"…). */
    val outputName: String? = null,
    val error: String? = null,
) {
    val hasMedia: Boolean get() = current != null
    val upNext: List<Track> get() = if (queueIndex in queue.indices) queue.drop(queueIndex + 1) else emptyList()
}

/**
 * The app's handle on the player (which lives in [PlaybackService]). Safe to call from the UI
 * thread; commands issued before the service is connected are queued.
 */
interface PlayerConnection {
    val state: StateFlow<PlaybackState>
    /** Current position, ticking ~4×/s while playing (and once on every seek/track change). */
    val positionMs: Flow<Long>
    val sleepTimer: StateFlow<SleepTimer>

    fun play(tracks: List<Track>, startIndex: Int = 0, source: PlaySource, shuffle: Boolean = false)

    /**
     * Streams songs straight from YouTube without downloading them (the default when tapping a
     * search result). Each item becomes a transient Track (see data/OnlineTrack.kt, mediaId
     * "yt:<videoId>"); when a song is already in the library the local file is used instead.
     */
    fun playOnline(tracks: List<RemoteTrack>, startIndex: Int = 0, source: PlaySource) {}

    fun playSmart(seed: SmartSeed)
    fun togglePlayPause()
    fun pause()
    fun next()
    /** Restarts the track if more than ~3 s in, otherwise goes to the previous one. */
    fun previous()
    fun seekTo(positionMs: Long)
    fun skipTo(queueIndex: Int)
    fun setShuffle(enabled: Boolean)
    fun cycleRepeat()
    fun playNext(tracks: List<Track>)
    fun enqueue(tracks: List<Track>)
    fun removeAt(queueIndex: Int)
    fun move(from: Int, to: Int)
    fun setSleepTimer(timer: SleepTimer)
}
