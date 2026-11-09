package com.villamediana.sound.smart

import com.villamediana.sound.online.RemoteTrack
import kotlin.math.ln
import kotlin.random.Random

/**
 * What was heard in the last hours (this listening session): songs heard and skipped, artists
 * skipped. The radio follows it — a song just skipped doesn't come back, an artist skipped twice
 * is left out for now, a song just heard isn't repeated.
 */
data class Session(
    val heard: Set<String> = emptySet(),
    val skipped: Set<String> = emptySet(),
    /** Artist key → skips in the session (without a song of theirs heard through since). */
    val skippedArtists: Map<String, Int> = emptyMap(),
) {
    companion object {
        /** The session from listens (newest first or not): a song counts as skipped only if never heard through. */
        fun of(listens: List<Listen>): Session {
            val heard = HashSet<String>()
            val skipped = HashSet<String>()
            val skipsBy = HashMap<String, Int>()
            val heardBy = HashSet<String>()
            for (l in listens) {
                val id = l.videoId?.takeIf(String::isNotBlank)
                val a = artistKey(l.artist)
                if (l.skipped) {
                    id?.let(skipped::add)
                    skipsBy.merge(a, 1, Int::plus)
                } else {
                    id?.let(heard::add)
                    if (l.completed) heardBy += a
                }
            }
            skipped.removeAll(heard)
            return Session(heard, skipped, skipsBy.filterKeys { it !in heardBy })
        }
    }
}

/**
 * The songs of a radio, from the radios of the songs being heard ([direct], the latest seed first)
 * and of one or two songs drawn from them ([wider]): what several radios agree on and what sits
 * close to the seeds first, nudged by the listener's taste, with a little chance in it ([rnd]) so the
 * same song never starts the same radio twice. Never a song queued or played just now, nor one
 * skipped now or again and again; an artist skipped twice now waits. Pure.
 */
object RadioMix {
    /** How much each seed's radio counts: the song playing now most, the ones before less. */
    private val SEED_WEIGHTS = doubleArrayOf(1.0, 0.75, 0.55, 0.45)
    private const val WIDER_WEIGHT = 0.5
    private const val AGREE = 0.5
    private const val TASTE = 0.8
    /** How much chance moves a song (the closest songs still lead; the rest changes from one start to the next). */
    private const val CHANCE = 1.0
    /** How far a song skipped in this moment goes down. */
    private const val SKIPPED = 1.6
    /** How far a song whose own sound fits this moment (more or less than its artist's) moves. */
    private const val SOUND = 0.6

    fun rank(
        direct: List<List<RemoteTrack>>,
        wider: List<List<RemoteTrack>>,
        profile: TasteProfile?,
        session: Session,
        exclude: Set<String>,
        count: Int,
        rnd: Random,
        perArtist: Int = 3,
    ): List<RemoteTrack> {
        if (count <= 0) return emptyList()
        val score = HashMap<String, Double>()
        val radiosOf = HashMap<String, Int>()
        val found = LinkedHashMap<String, RemoteTrack>()
        fun add(list: List<RemoteTrack>, weight: Double, head: Double) {
            val distinct = list.distinctBy { it.videoId }
            distinct.forEachIndexed { pos, t ->
                if (t.videoId.isBlank() || !fits(t, profile, session, exclude)) return@forEachIndexed
                found.putIfAbsent(t.videoId, t)
                score.merge(t.videoId, weight * (head - pos.toDouble() / distinct.size), Double::plus)
                radiosOf.merge(t.videoId, 1, Int::plus)
            }
        }
        direct.forEachIndexed { i, r -> add(r, SEED_WEIGHTS[minOf(i, SEED_WEIGHTS.lastIndex)], 1.6) }
        wider.forEach { r -> add(r, WIDER_WEIGHT, 1.2) }
        if (found.isEmpty()) return emptyList()

        // Drawn in a fixed order first, so the same rnd gives the same radio.
        val total = found.values.sortedBy { it.videoId }.associate { t ->
            val agree = AGREE * ((radiosOf[t.videoId] ?: 1) - 1)
            val taste = profile?.affinityOf(t.artist)?.let { TASTE * it } ?: 0.0
            val tired = if ((session.skippedArtists[artistKey(t.artist)] ?: 0) >= 1) 0.6 else 0.0
            // Skipped in this moment (for a moment's profile): further down now, not out.
            val skips = SKIPPED * (1.0 - (profile?.songFactor(t.videoId) ?: 1.0))
            val sound = SOUND * ln(profile?.soundFit(t.videoId) ?: 1.0)
            t.videoId to score.getValue(t.videoId) + agree + taste + sound - tired - skips + rnd.nextDouble() * CHANCE
        }
        val ranked = found.values.sortedWith(compareByDescending<RemoteTrack> { total.getValue(it.videoId) }.thenBy { it.videoId })

        val perA = HashMap<String, Int>()
        val songs = HashSet<String>()
        val out = ArrayList<RemoteTrack>(count)
        for (t in ranked) {
            if (out.size >= count) break
            val a = artistKey(t.artist)
            if ((perA[a] ?: 0) >= perArtist) continue
            if (!songs.add(a + "|" + normalizeText(com.villamediana.sound.online.TitleCleaner.baseTitle(t.title)))) continue
            perA.merge(a, 1, Int::plus)
            out += t
        }
        return apart(out)
    }

    private fun fits(t: RemoteTrack, profile: TasteProfile?, session: Session, exclude: Set<String>): Boolean {
        if (t.videoId in exclude || t.videoId in session.heard || t.videoId in session.skipped) return false
        if (profile?.avoids(t.videoId, t.artist) == true) return false
        return (session.skippedArtists[artistKey(t.artist)] ?: 0) < 2
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

    /** The songs whose radios widen the mix: [count] drawn by [rnd] from the top of the direct radios, not the seeds' own. */
    fun widenFrom(direct: List<List<RemoteTrack>>, seeds: Set<String>, count: Int, rnd: Random, top: Int = 12): List<String> {
        val pool = direct.flatMap { it.take(top) }.map { it.videoId }.filter { it.isNotBlank() && it !in seeds }.distinct()
        return pool.shuffled(rnd).take(count)
    }
}
