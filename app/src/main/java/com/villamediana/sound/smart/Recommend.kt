package com.villamediana.sound.smart

import com.villamediana.sound.online.RemoteTrack
import kotlin.math.ln
import kotlin.random.Random

/**
 * Turns candidate songs into suggestions with the listener's [TasteProfile]. What the radios agree
 * on decides (how close a song is to the songs they started from); the taste only nudges it — a
 * favourite artist counts a share more, never enough to bring in a song from far away — with room
 * left for artists they don't know yet. What was suggested in the last days counts less, and a
 * little chance moves the rest, so the lists are not the same day after day. Never a song or artist
 * they keep skipping, never a song they already know (when asked for new ones). Pure.
 */
object Recommend {
    /** How much more a song by the favourite artist counts (a share of what the radios say). */
    private const val TASTE_LIFT = 0.3
    /** A small push for artists the listener doesn't know yet: suggestions are also discoveries. */
    private const val NEW_ARTIST_LIFT = 0.1
    /** How far a song skipped in this moment goes down (for a moment's profile). */
    private const val SKIPPED = 1.6
    /** How far a song whose own sound fits this moment (more or less than its artist's) moves. */
    private const val SOUND = 0.6
    /** The same for an artist the friends like (all of it when they are listening along). */
    private const val FRIENDS = 0.25
    /** How much chance moves a song (the radios' favourites still lead). */
    private const val CHANCE = 0.5
    /** At most this share of a list that may bring known songs is songs the listener already knows. */
    const val KNOWN_SHARE = 1.0 / 3.0

    /**
     * The best [count] new songs from [radios] (each a radio of a song the listener loves): a song
     * scores for every radio it is in (more near the top), and an artist several radios agree on
     * scores more; then the taste, as a share of that. [exclude]: video ids not to suggest (the
     * library, the seeds). [seen]: what was suggested in the last days (video id → 0..1, 1 = today),
     * which counts less now; [rnd]: a little chance (none without it). At most [perArtist] per
     * artist; no song twice (another upload of it either). With [onlyNew] off, the songs they
     * already know take at most [KNOWN_SHARE] of the list.
     */
    fun fromRadios(
        radios: List<List<RemoteTrack>>,
        profile: TasteProfile,
        exclude: Set<String>,
        count: Int,
        perArtist: Int = 2,
        onlyNew: Boolean = true,
        seen: Map<String, Double> = emptyMap(),
        rnd: Random? = null,
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
        // Drawn in a fixed order first, so the same rnd gives the same list.
        val total = found.values.sortedBy { it.videoId }.associate { t ->
            val close = score.getValue(t.videoId) + 0.4 * ((radiosWith[artistKey(t.artist)] ?: 1) - 1)
            t.videoId to close * lift(t, profile) * Picks.fresh(seen[t.videoId]) + SOUND * ln(profile.soundFit(t.videoId)) -
                SKIPPED * (1.0 - profile.songFactor(t.videoId)) + (rnd?.nextDouble() ?: 0.0) * CHANCE
        }
        val ranked = found.values.sortedWith(compareByDescending<RemoteTrack> { total.getValue(it.videoId) }.thenBy { firstSeen.getValue(it.videoId) })
        return Picks.cut(ranked, count, perArtist, known = { it.videoId in profile.known }, knownShare = if (onlyNew) 1.0 else KNOWN_SHARE)
    }

    /**
     * A genre shelf's songs in the listener's order: the ones they haven't heard and their artists
     * a little ahead, shuffled by [seed] (another draw each session), the skipped ones out, the
     * ones [seen] in the last days behind.
     */
    fun order(
        pool: List<RemoteTrack>,
        profile: TasteProfile,
        seed: Long,
        count: Int,
        perArtist: Int = 2,
        seen: Map<String, Double> = emptyMap(),
    ): List<RemoteTrack> {
        val usable = pool.distinctBy { it.videoId }.filter { fits(it, profile, emptySet(), onlyNew = false) }
        val weighted = weightedOrder(
            usable,
            id = { it.videoId.hashCode().toLong() },
            weight = { t ->
                val known = t.videoId in profile.known
                (1.0 + 2.0 * profile.affinityOf(t.artist) + 0.5 * profile.friendsLike(t.artist)) * (if (known) 0.4 else 1.0) *
                    profile.songFactor(t.videoId) * profile.soundFit(t.videoId) * Picks.fresh(seen[t.videoId])
            },
            rnd = Random(seed),
        )
        return Picks.cut(weighted, count, perArtist, known = { it.videoId in profile.known }, knownShare = KNOWN_SHARE)
    }

    private fun fits(t: RemoteTrack, profile: TasteProfile, exclude: Set<String>, onlyNew: Boolean): Boolean =
        t.videoId !in exclude && !profile.avoids(t.videoId, t.artist) && !(onlyNew && t.videoId in profile.known)

    /** What the taste multiplies a song's closeness by (1 = nothing known about its artist). */
    private fun lift(t: RemoteTrack, profile: TasteProfile): Double {
        val aff = profile.affinityOf(t.artist)
        val own = if (aff > 0) TASTE_LIFT * aff else if (!profile.isKnownArtist(t.artist)) NEW_ARTIST_LIFT else 0.0
        return 1.0 + own + FRIENDS * profile.friendsLike(t.artist)
    }
}
