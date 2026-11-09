package com.villamediana.sound.playback

import com.villamediana.sound.data.db.Track

/**
 * A say over the player's commands from the app: in a jam, a tap on play, next, a song or the queue
 * changes what EVERYONE plays (the jam takes it and this phone follows the jam). Each call returns
 * true when the command was taken (the player then does nothing by itself).
 */
interface PlaybackGate {
    fun togglePlayPause(): Boolean
    fun resume(): Boolean
    fun pause(): Boolean
    fun next(): Boolean
    fun previous(): Boolean
    fun seekTo(positionMs: Long): Boolean
    fun skipTo(queueIndex: Int): Boolean
    fun play(tracks: List<Track>, startIndex: Int): Boolean
    fun enqueue(tracks: List<Track>, next: Boolean): Boolean
    fun removeAt(queueIndex: Int): Boolean
    fun move(from: Int, to: Int): Boolean
    fun setShuffle(enabled: Boolean): Boolean
    fun cycleRepeat(): Boolean
}

/** How the jam moves this phone's player without going through the [PlaybackGate] again. */
interface FollowingPlayer {
    /** [tracks] from [index] at [positionMs], playing or paused; the song playing keeps going if it is the same. */
    fun followLoad(tracks: List<Track>, index: Int, positionMs: Long, play: Boolean)
    fun followSkip(index: Int, positionMs: Long, play: Boolean)
    fun followSeek(positionMs: Long)
    fun followPlay()
    fun followPause()
}
