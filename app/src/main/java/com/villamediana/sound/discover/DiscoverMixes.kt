package com.villamediana.sound.discover

import com.villamediana.sound.online.RemoteTrack

/** Pure helpers that turn fetched lists into the Home's picks (artists, playlists). */
object DiscoverMixes {
    /** Artists that show up most across [lists] (ties: first seen), with one of their covers. */
    fun topArtists(lists: List<List<RemoteTrack>>, limit: Int): List<Pair<String, String?>> {
        val counts = LinkedHashMap<String, Int>()
        val names = HashMap<String, String>()
        val covers = HashMap<String, String>()
        lists.flatten().forEach { t ->
            val name = t.artist.split(",", " & ", " e ", " y ", " feat", " ft.").first().trim()
            if (name.isEmpty() || name.length > 40) return@forEach
            val k = name.lowercase()
            counts[k] = (counts[k] ?: 0) + 1
            names.putIfAbsent(k, name)
            t.thumbnailUrl?.let { covers.putIfAbsent(k, it) }
        }
        return counts.entries.sortedByDescending { it.value }.take(limit).map { names.getValue(it.key) to covers[it.key] }
    }

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
