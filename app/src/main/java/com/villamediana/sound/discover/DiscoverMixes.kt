package com.villamediana.sound.discover

import com.villamediana.sound.online.RemoteTrack

/** Pure helpers that turn fetched lists into the Home's mixes and picks. Deterministic by seed. */
object DiscoverMixes {
    /**
     * Interleaves [lists] (one song from each in turn), drops repeats and starts each list at a
     * [seed]-dependent offset, so a mix of the same genres changes from one slot to the next.
     */
    /**
     * Up to [size] songs from [lists] (one per genre), no repeats, rotated by [seed] so the mix
     * changes every slot. Genres take turns in runs of [run] songs — a few of one sound, then the
     * next — instead of jumping genre at every song.
     */
    fun blend(lists: List<List<RemoteTrack>>, size: Int, seed: Long, run: Int = 4): List<RemoteTrack> {
        val sources = lists.filter { it.isNotEmpty() }.mapIndexed { i, l ->
            val offset = ((seed + i * 7) % l.size).toInt().let { if (it < 0) it + l.size else it }
            l.drop(offset) + l.take(offset)
        }
        val seen = HashSet<String>()
        val out = ArrayList<RemoteTrack>(size)
        val taken = IntArray(sources.size)
        while (out.size < size && sources.indices.any { taken[it] < sources[it].size }) {
            for ((i, src) in sources.withIndex()) {
                var added = 0
                while (added < run && taken[i] < src.size && out.size < size) {
                    if (seen.add(src[taken[i]].videoId)) {
                        out += src[taken[i]]
                        added++
                    }
                    taken[i]++
                }
                if (out.size == size) break
            }
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
