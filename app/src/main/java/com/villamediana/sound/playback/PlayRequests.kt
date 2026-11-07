package com.villamediana.sound.playback

import com.villamediana.sound.data.db.Track

/**
 * Tapping the song that is already playing, from the list it is playing from, doesn't start it
 * over: it keeps going (resumes if paused) and the player just opens. From another list it starts
 * that list, as asked.
 */
object PlayRequests {
    fun keepsCurrent(current: Track?, playingFrom: PlaySource?, asked: Track, askedFrom: PlaySource?): Boolean {
        if (current == null) return false
        val sameSong = current.id == asked.id || (current.videoId != null && current.videoId == asked.videoId)
        return sameSong && sameSource(playingFrom, askedFrom)
    }

    /** Same kind and same list: by id when both have one, else by title. */
    fun sameSource(a: PlaySource?, b: PlaySource?): Boolean {
        if (a == null || b == null) return a == null && b == null
        if (a.type != b.type) return false
        if (a.id != null && b.id != null) return a.id.equals(b.id, ignoreCase = true)
        return a.title.isNotBlank() && a.title.equals(b.title, ignoreCase = true)
    }
}
