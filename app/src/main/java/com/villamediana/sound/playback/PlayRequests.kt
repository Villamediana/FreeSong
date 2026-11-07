package com.villamediana.sound.playback

import com.villamediana.sound.data.db.Track

/**
 * Tapping the song that is already playing, from the list it is playing from, doesn't start it
 * over: it keeps going (resumes if paused) and the player just opens. From another list it starts
 * that list, as asked.
 */
object PlayRequests {
    fun keepsCurrent(current: Track?, playingFrom: PlaySource?, asked: Track, askedFrom: PlaySource?): Boolean =
        sameSong(current, asked) && sameSource(playingFrom, askedFrom)

    /** The same song: the same library row, or the same YouTube video (online / downloaded copy). */
    fun sameSong(current: Track?, asked: Track): Boolean {
        if (current == null) return false
        return current.id == asked.id || (current.videoId != null && current.videoId == asked.videoId)
    }

    /**
     * Shuffle for a new queue: on when asked; off for a radio, mix or genre list (their order is
     * the point: "next" is the next song they show); otherwise as it was.
     */
    fun shuffleMode(shuffle: Boolean, source: PlaySource?): Int = when {
        shuffle -> SessionProtocol.SHUFFLE_ON
        source?.type in ORDERED -> SessionProtocol.SHUFFLE_OFF
        else -> SessionProtocol.SHUFFLE_KEEP
    }

    /** A radio started from the song playing goes on from where the song is (no start over). */
    fun continuesCurrent(current: Track?, asked: Track, source: PlaySource?): Boolean =
        source?.type == PlaySource.Type.RADIO && sameSong(current, asked)

    private val ORDERED = setOf(PlaySource.Type.RADIO, PlaySource.Type.MIX, PlaySource.Type.GENRE)

    /** Same kind and same list: by id when both have one, else by title. */
    fun sameSource(a: PlaySource?, b: PlaySource?): Boolean {
        if (a == null || b == null) return a == null && b == null
        if (a.type != b.type) return false
        if (a.id != null && b.id != null) return a.id.equals(b.id, ignoreCase = true)
        return a.title.isNotBlank() && a.title.equals(b.title, ignoreCase = true)
    }
}
