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
 * and of one or two songs drawn from them ([wider]). How close a song is to the seeds decides: what
 * several radios agree on and what sits near the top of them comes first. The listener's taste only
 * nudges it (a favourite far from the seed never passes a song right next to it), and so does a
 * little chance ([rnd]), so the same song never starts the same radio twice. A song from a radio of
 * a radio by an artist the seeds' own radios don't have counts less (it may be another style).
 * Songs the listener already knows take at most [KNOWN_SHARE] of it while there are new ones. Never
 * a song queued or played just now, nor one skipped now or again and again; an artist skipped twice
 * now waits. Pure.
 */
object RadioMix {
    /** How much each seed's radio counts: the song playing now most, the ones before less. */
    private val SEED_WEIGHTS = doubleArrayOf(1.0, 0.75, 0.55, 0.45)
    private const val WIDER_WEIGHT = 0.5
    private const val AGREE = 0.5
    /** How much more a song by the favourite artist counts (a share of how close it is, not a jump). */
    private const val TASTE = 0.3
    /** The same for an artist the friends like (all of it when they are listening along). */
    private const val FRIENDS = 0.25
    /** How much chance moves a song (the closest songs still lead; the rest changes from one start to the next). */
    private const val CHANCE = 0.5
    /** What a song only a radio of a radio has, by an artist the seeds' radios don't know, keeps of its closeness. */
    private const val STRANGER = 0.5
    /** How far a song skipped in this moment goes down. */
    private const val SKIPPED = 1.6
    /** How far a song whose own sound fits this moment (more or less than its artist's) moves. */
    private const val SOUND = 0.6
    /** At most this share of a radio is songs the listener already knows, while there are new ones. */
    const val KNOWN_SHARE = 1.0 / 3.0

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
        val near = HashMap<String, Double>()
        val radiosOf = HashMap<String, Int>()
        val found = LinkedHashMap<String, RemoteTrack>()
        fun add(list: List<RemoteTrack>, weight: Double, head: Double) {
            val distinct = list.distinctBy { it.videoId }
            distinct.forEachIndexed { pos, t ->
                if (t.videoId.isBlank() || !fits(t, profile, session, exclude)) return@forEachIndexed
                found.putIfAbsent(t.videoId, t)
                near.merge(t.videoId, weight * (head - pos.toDouble() / distinct.size), Double::plus)
                radiosOf.merge(t.videoId, 1, Int::plus)
            }
        }
        direct.forEachIndexed { i, r -> add(r, SEED_WEIGHTS[minOf(i, SEED_WEIGHTS.lastIndex)], 1.6) }
        wider.forEach { r -> add(r, WIDER_WEIGHT, 1.2) }
        if (found.isEmpty()) return emptyList()
        // The style of the seeds: every song and artist their own radios have (the ones left out too).
        val ownSongs = direct.flatMapTo(HashSet()) { r -> r.map { it.videoId } }
        val ownArtists = direct.flatMapTo(HashSet()) { r -> r.map { artistKey(it.artist) } }

        // Drawn in a fixed order first, so the same rnd gives the same radio.
        val total = found.values.sortedBy { it.videoId }.associate { t ->
            var close = near.getValue(t.videoId) + AGREE * ((radiosOf[t.videoId] ?: 1) - 1)
            if (t.videoId !in ownSongs && artistKey(t.artist) !in ownArtists) close *= STRANGER
            val taste = profile?.let { 1.0 + TASTE * it.affinityOf(t.artist) + FRIENDS * it.friendsLike(t.artist) } ?: 1.0
            val tired = if ((session.skippedArtists[artistKey(t.artist)] ?: 0) >= 1) 0.6 else 0.0
            // Skipped in this moment (for a moment's profile): further down now, not out.
            val skips = SKIPPED * (1.0 - (profile?.songFactor(t.videoId) ?: 1.0))
            val sound = SOUND * ln(profile?.soundFit(t.videoId) ?: 1.0)
            t.videoId to close * taste + sound - tired - skips + rnd.nextDouble() * CHANCE
        }
        val ranked = found.values.sortedWith(compareByDescending<RemoteTrack> { total.getValue(it.videoId) }.thenBy { it.videoId })
        val known = profile?.known.orEmpty()
        return Picks.cut(ranked, count, perArtist, known = { it.videoId in known }, knownShare = KNOWN_SHARE)
    }

    private fun fits(t: RemoteTrack, profile: TasteProfile?, session: Session, exclude: Set<String>): Boolean {
        if (t.videoId in exclude || t.videoId in session.heard || t.videoId in session.skipped) return false
        if (profile?.avoids(t.videoId, t.artist) == true) return false
        return (session.skippedArtists[artistKey(t.artist)] ?: 0) < 2
    }

    /** The songs whose radios widen the mix: [count] drawn by [rnd] from the top of the direct radios, not the seeds' own. */
    fun widenFrom(direct: List<List<RemoteTrack>>, seeds: Set<String>, count: Int, rnd: Random, top: Int = 12): List<String> {
        val pool = direct.flatMap { it.take(top) }.map { it.videoId }.filter { it.isNotBlank() && it !in seeds }.distinct()
        return pool.shuffled(rnd).take(count)
    }
}
