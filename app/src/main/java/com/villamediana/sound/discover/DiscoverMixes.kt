package com.villamediana.sound.discover

import com.villamediana.sound.online.RemoteTrack

/** Pure helpers that turn fetched lists into the Home's mixes and picks. Deterministic by seed. */
object DiscoverMixes {
    /**
     * Interleaves [lists] (one song from each in turn), drops repeats and starts each list at a
     * [seed]-dependent offset, so a mix of the same genres changes from one slot to the next.
     */
    fun blend(lists: List<List<RemoteTrack>>, size: Int, seed: Long): List<RemoteTrack> {
        val sources = lists.filter { it.isNotEmpty() }.mapIndexed { i, l ->
            val offset = ((seed + i * 7) % l.size).toInt().let { if (it < 0) it + l.size else it }
            l.drop(offset) + l.take(offset)
        }
        val seen = HashSet<String>()
        val out = ArrayList<RemoteTrack>(size)
        var round = 0
        while (out.size < size && sources.any { round < it.size }) {
            for (src in sources) {
                if (round < src.size && seen.add(src[round].videoId)) out += src[round]
                if (out.size == size) break
            }
            round++
        }
        return out
    }

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

    /** Covers for a mix card: distinct, from the start of the mix. */
    fun covers(tracks: List<RemoteTrack>, n: Int = 4): List<String> = tracks.mapNotNull { it.thumbnailUrl }.distinct().take(n)
}
