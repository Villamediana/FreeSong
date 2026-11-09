package com.villamediana.sound.smart

import com.villamediana.sound.online.RemoteTrack
import kotlin.random.Random

/**
 * Turns candidate songs into suggestions with the listener's [TasteProfile]: what the radios agree
 * on, nudged towards the artists they like, with room left for artists they don't know yet; never a
 * song or artist they keep skipping, never a song they already know (when asked for new ones). Pure.
 */
object Recommend {
    /** How far a liked artist moves a song up (on top of the radios' agreement). */
    private const val TASTE_LIFT = 1.1
    /** A small push for artists the listener doesn't know yet: suggestions are also discoveries. */
    private const val NEW_ARTIST_LIFT = 0.25
    /** How far a song always skipped goes down (skipped in the moment, for a moment's profile). */
    private const val SKIPPED = 1.6

    /**
     * The best [count] new songs from [radios] (each a radio of a song the listener loves): a song
     * scores for every radio it is in (more near the top), and an artist several radios agree on
     * scores more; then the taste. [exclude]: video ids not to suggest (the library, the seeds).
     * At most [perArtist] per artist; no song twice (another upload of it either).
     */
    fun fromRadios(
        radios: List<List<RemoteTrack>>,
        profile: TasteProfile,
        exclude: Set<String>,
        count: Int,
        perArtist: Int = 2,
        onlyNew: Boolean = true,
    ): List<RemoteTrack> {
        val lists = radios.map { r -> r.distinctBy { it.videoId } }.filter { it.isNotEmpty() }
        if (lists.isEmpty() || count <= 0) return emptyList()
        val artistsOf = lists.map { r -> r.mapTo(HashSet()) { artistKey(it.artist) } }
        val radiosWith = HashMap<String, Int>()
        artistsOf.forEach { set -> set.forEach { a -> radiosWith.merge(a, 1, Int::plus) } }

        val score = HashMap<String, Double>()
        val found = LinkedHashMap<String, RemoteTrack>()
        lists.forEach { r ->
            r.forEachIndexed { pos, t ->
                if (!fits(t, profile, exclude, onlyNew)) return@forEachIndexed
                found.putIfAbsent(t.videoId, t)
                score.merge(t.videoId, 1.5 - pos.toDouble() / r.size, Double::plus)
            }
        }
        val firstSeen = found.keys.withIndex().associate { (i, id) -> id to i }
        fun total(t: RemoteTrack): Double {
            val agree = 0.4 * ((radiosWith[artistKey(t.artist)] ?: 1) - 1)
            return score.getValue(t.videoId) + agree + lift(t, profile) - SKIPPED * (1.0 - profile.songFactor(t.videoId))
        }
        val ranked = found.values.sortedWith(compareByDescending<RemoteTrack> { total(it) }.thenBy { firstSeen.getValue(it.videoId) })
        return capped(ranked, count, perArtist)
    }

    /**
     * A genre shelf's songs in the listener's order: their artists and the ones they haven't heard
     * first, a little shuffled by [seed] (another draw each session), the skipped ones out.
     */
    fun order(pool: List<RemoteTrack>, profile: TasteProfile, seed: Long, count: Int, perArtist: Int = 2): List<RemoteTrack> {
        val usable = pool.distinctBy { it.videoId }.filter { fits(it, profile, emptySet(), onlyNew = false) }
        val weighted = weightedOrder(
            usable,
            id = { it.videoId.hashCode().toLong() },
            weight = { t ->
                val known = t.videoId in profile.known
                (1.0 + 3.0 * profile.affinityOf(t.artist)) * (if (known) 0.55 else 1.0) * profile.songFactor(t.videoId)
            },
            rnd = Random(seed),
        )
        return capped(weighted, count, perArtist)
    }

    private fun fits(t: RemoteTrack, profile: TasteProfile, exclude: Set<String>, onlyNew: Boolean): Boolean =
        t.videoId !in exclude && !profile.avoids(t.videoId, t.artist) && !(onlyNew && t.videoId in profile.known)

    private fun lift(t: RemoteTrack, profile: TasteProfile): Double {
        val aff = profile.affinityOf(t.artist)
        return if (aff > 0) TASTE_LIFT * aff else if (!profile.isKnownArtist(t.artist)) NEW_ARTIST_LIFT else 0.0
    }

    private fun capped(ranked: List<RemoteTrack>, count: Int, perArtist: Int): List<RemoteTrack> {
        val perA = HashMap<String, Int>()
        val songs = HashSet<String>()
        val out = ArrayList<RemoteTrack>(count)
        for (t in ranked) {
            if (out.size >= count) break
            val a = artistKey(t.artist)
            if ((perA[a] ?: 0) >= perArtist) continue
            // Another upload of the same song ("… (Official Video)", "… - Ao Vivo" of it) is the same song.
            if (!songs.add(a + "|" + normalizeText(com.villamediana.sound.online.TitleCleaner.baseTitle(t.title)))) continue
            perA.merge(a, 1, Int::plus)
            out += t
        }
        return apart(out)
    }

    /** Same order, but never the same artist twice in a row when it can be helped. */
    private fun apart(order: List<RemoteTrack>): List<RemoteTrack> {
        val left = order.toMutableList()
        val out = ArrayList<RemoteTrack>(order.size)
        while (left.isNotEmpty()) {
            val prev = out.lastOrNull()?.let { artistKey(it.artist) }
            val at = left.indices.take(4).firstOrNull { artistKey(left[it].artist) != prev } ?: 0
            out += left.removeAt(at)
        }
        return out
    }
}
