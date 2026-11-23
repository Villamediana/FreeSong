package com.villamediana.sound.playback

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Whether music is playing right now, for whoever needs to know without making the player
 * connection (the social line stays up while music plays, so messages arrive at once), and what
 * plays (the computer's mini player follows it: social/NowShare).
 */
object PlaybackPulse {
    val playing = MutableStateFlow(false)

    /** What this phone plays, or played last (null before anything). */
    val now = MutableStateFlow<Now?>(null)

    data class Now(
        /** Null: a song only on this phone (a file from elsewhere): the computer can't play it. */
        val videoId: String?,
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long,
        /** An https cover (online songs); null for the rest. */
        val cover: String?,
        val positionMs: Long,
        val playing: Boolean,
        /** When this changed here (ms). */
        val at: Long,
        /** Counts the jumps to another point (the computer follows those too). */
        val seeks: Int = 0,
        val source: PlaySource.Type? = null,
    )
}
