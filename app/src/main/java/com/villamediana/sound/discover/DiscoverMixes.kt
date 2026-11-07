package com.villamediana.sound.discover

import com.villamediana.sound.online.RemoteTrack

/** Pure helpers that turn fetched lists into the Home's picks (playlists). */
object DiscoverMixes {
    /** Round-robin of several collection lists, without repeats. */
    fun blendCollections(lists: List<List<com.villamediana.sound.online.RemoteCollection>>, size: Int): List<com.villamediana.sound.online.RemoteCollection> {
        val seen = HashSet<String>()
        val out = ArrayList<com.villamediana.sound.online.RemoteCollection>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) {
            if (i < l.size && seen.add(l[i].url)) out += l[i]
            if (out.size == size) return out
        }
        return out
    }

}
