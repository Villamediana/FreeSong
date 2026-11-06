package com.villamediana.sound.ui.player

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import com.villamediana.sound.playback.PlayerConnection
import kotlinx.coroutines.isActive

/**
 * Smooth playback position. The player ticks ~4×/s; this interpolates between ticks on every
 * frame so the seek bar glides. [positionMs] is snapshot state but is meant to be read only in
 * draw lambdas or behind derivedStateOf — never directly in a big composable.
 */
@Stable
internal class PlaybackClock {
    private val shown = mutableLongStateOf(0L)
    val positionMs: Long get() = shown.longValue

    private var basePos = 0L
    private var baseNanos = 0L
    private var playing = false
    private var durationMs = 0L

    // Optimistic seek: until the player reports a position near it, stale ticks are ignored.
    private var pendingSeek: Long? = null
    private var pendingSinceNanos = 0L

    fun fraction(): Float = PlayerMath.fraction(positionMs, durationMs)

    fun onTick(positionMs: Long, nowNanos: Long) {
        pendingSeek?.let { target ->
            val fresh = kotlin.math.abs(positionMs - target) < SEEK_MATCH_MS
            val expired = nowNanos - pendingSinceNanos > SEEK_HOLD_NANOS
            if (!fresh && !expired) return
            pendingSeek = null
        }
        basePos = positionMs
        baseNanos = nowNanos
        val next = PlayerMath.interpolate(positionMs, 0, playing, durationMs)
        shown.longValue = if (playing) PlayerMath.monotonic(shown.longValue, next) else next
    }

    fun onState(playing: Boolean, durationMs: Long, nowNanos: Long) {
        if (playing == this.playing && durationMs == this.durationMs) return
        // Re-base on what is on screen so toggling play/pause never jumps.
        basePos = shown.longValue
        baseNanos = nowNanos
        this.playing = playing
        this.durationMs = durationMs
    }

    fun onFrame(nowNanos: Long) {
        if (!playing) return
        val raw = PlayerMath.interpolate(basePos, (nowNanos - baseNanos) / 1_000_000, true, durationMs)
        shown.longValue = PlayerMath.monotonic(shown.longValue, raw)
    }

    /** Called when the track changes: jump without smoothing. */
    fun reset(positionMs: Long, nowNanos: Long) {
        pendingSeek = null
        basePos = positionMs
        baseNanos = nowNanos
        shown.longValue = positionMs
    }

    fun seek(positionMs: Long, nowNanos: Long) {
        pendingSeek = positionMs
        pendingSinceNanos = nowNanos
        basePos = positionMs
        baseNanos = nowNanos
        shown.longValue = positionMs
    }

    private companion object {
        const val SEEK_MATCH_MS = 1_500L
        const val SEEK_HOLD_NANOS = 1_200_000_000L
    }
}

/** Feeds a [PlaybackClock] from the player: ticks, play state and a frame loop while playing. */
@Composable
internal fun rememberPlaybackClock(player: PlayerConnection, playing: Boolean, durationMs: Long, trackKey: Any?): PlaybackClock {
    val clock = remember { PlaybackClock() }
    // Keyed by media identity (videoId when there is one): a streamed song that lands in the
    // library changes its row id but not the song, and must not jump back to 0:00.
    val lastTrack = remember { arrayOf<Any?>(trackKey) }
    LaunchedEffect(player) {
        player.positionMs.collect { clock.onTick(it, System.nanoTime()) }
    }
    LaunchedEffect(trackKey) {
        // A new song starts from zero; don't let the old position glide on until its first tick.
        // (Not on first composition: a paused song restored mid-way keeps its ticked position.)
        val previous = lastTrack[0]
        lastTrack[0] = trackKey
        if (trackKey != null && previous != null && previous != trackKey) clock.reset(0L, System.nanoTime())
    }
    LaunchedEffect(playing, durationMs) {
        clock.onState(playing, durationMs, System.nanoTime())
        if (playing) {
            // An endless frame loop (only while playing): declared as such so tooling/tests can idle.
            while (isActive) withInfiniteAnimationFrameNanos { clock.onFrame(System.nanoTime()) }
        }
    }
    return clock
}

