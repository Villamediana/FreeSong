package com.villamediana.surco.smart

import com.villamediana.surco.data.db.Track
import kotlin.random.Random

/**
 * Offline queue intelligence. Everything here is pure and deterministic for the same input and
 * seed (the input order does not matter), so the player can recompute a queue and the tests can
 * pin the behaviour.
 */
object SmartQueue {
    /**
     * Shuffle that feels random to humans: every track exactly once, favourites (liked, often
     * finished) a bit earlier, often-skipped and just-played ones later, no same artist back to back
     * when avoidable and songs of one album spread apart.
     */
    fun smartShuffle(tracks: List<Track>, taste: Taste, seed: Long): List<Track> {
        if (tracks.size <= 1) return tracks
        val rnd = Random(seed)
        return spread(weightedOrder(tracks.feats(), taste, rnd)).map { it.track }
    }

    /**
     * Radio from a seed track: the seed first (once), then what sounds alike in the library — same
     * artist, same album artist, featured artists, same genre or a related one, close in time —
     * favouring what the listener likes, varied, never the same song twice.
     */
    fun radio(seed: Track, library: List<Track>, taste: Taste, limit: Int = 50, seedValue: Long = 0): List<Track> {
        if (limit <= 0) return emptyList()
        val anchor = Feat(seed)
        val pool = library.asSequence().filter { it.id != seed.id }.distinctBy { it.id }.toList().feats()
            .sortedBy { it.id }
            .distinctSongs(exclude = setOf(anchor.song))
        val rnd = Random(seedValue * 31 + seed.id)
        val picked = pickSimilar(pool, { similarity(anchor, it) }, taste, limit - 1, rnd, previous = anchor)
        return listOf(seed) + picked.map { it.track }
    }

    /**
     * Genre session: the songs of [genre] smart-shuffled; when they run out it flows into the same
     * family ("rock" → "indie", "alternativo") and then into neighbouring families.
     */
    fun genre(genre: String, library: List<Track>, taste: Taste, limit: Int = 80, seedValue: Long = 0): List<Track> {
        if (limit <= 0) return emptyList()
        val key = Genres.key(genre)
        if (key.isEmpty()) return emptyList()
        val family = Genres.familyOfKey(key)
        val near = family?.let(Genres::neighboursOf).orEmpty()
        val feats = library.distinctBy { it.id }.feats().sortedBy { it.id }.distinctSongs()
        val exact = feats.filter { it.genre == key }
        val sameFamily = if (family == null) emptyList() else feats.filter { it.genre != key && it.family == family }
        val neighbours = if (family == null) emptyList() else feats.filter { it.family != null && it.family in near }
        val base = Random(seedValue * 17 + key.hashCode())
        // Each tier is spread on its own: the genre stays together even when one artist dominates it.
        val ordered = ArrayList<Feat>(minOf(limit, exact.size + sameFamily.size + neighbours.size))
        for (tier in listOf(exact, sameFamily, neighbours)) {
            if (ordered.size >= limit) break
            val rnd = Random(base.nextLong())
            ordered += spread(weightedOrder(tier, taste, rnd).take(limit - ordered.size), previous = ordered.lastOrNull())
        }
        return ordered.take(limit).map { it.track }
    }

    /**
     * Songs to append when the queue ends and "autoplay similar" is on: what fits the last few songs
     * (the latest weighs most), excluding everything already in the queue.
     */
    fun continuation(queue: List<Track>, library: List<Track>, taste: Taste, limit: Int = 20, seedValue: Long = 0): List<Track> {
        if (limit <= 0 || library.isEmpty()) return emptyList()
        val inQueue = queue.mapTo(HashSet()) { it.id }
        val queueFeats = queue.feats()
        val pool = library.asSequence().filter { it.id !in inQueue }.distinctBy { it.id }.toList().feats()
            .sortedBy { it.id }
            .distinctSongs(exclude = queueFeats.mapTo(HashSet()) { it.song })
        if (pool.isEmpty()) return emptyList()
        val rnd = Random(seedValue * 13 + queue.size)
        if (queueFeats.isEmpty()) return spread(weightedOrder(pool, taste, rnd)).take(limit).map { it.track }

        val tail = queueFeats.takeLast(TAIL_WEIGHTS.size).reversed()
        val weights = TAIL_WEIGHTS.take(tail.size)
        val total = weights.sum()
        val score: (Feat) -> Double = { c -> tail.indices.sumOf { i -> weights[i] * similarity(tail[i], c) } / total }
        return pickSimilar(pool, score, taste, limit, rnd, previous = queueFeats.last()).map { it.track }
    }

    private val TAIL_WEIGHTS = listOf(1.0, 0.7, 0.5, 0.35, 0.25)
}
