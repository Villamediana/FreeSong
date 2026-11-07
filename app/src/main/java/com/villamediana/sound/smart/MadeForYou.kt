package com.villamediana.sound.smart

import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.RemoteTrack
import kotlin.math.ln
import kotlin.random.Random

/**
 * "Feitos pra você": up to [MAX_MIXES] mixes, one per genre you listen to most. Each one takes your
 * songs of that genre and new ones in the same style: the YouTube Music radios of several of its
 * songs are compared, and the songs (and artists) they agree on are the style's core — a radio that
 * goes off on its own counts less. Your songs closest to that core come first. Made once a day: the
 * next day brings new songs. Pure: the radios come in from outside.
 */
object MadeForYou {
    const val MAX_MIXES = 3
    /** Your songs in a mix. */
    const val OWN = 20
    /** New songs in a mix. */
    const val NEW = 40
    /** Songs whose radios bring the new ones. */
    const val SEEDS = 5
    /** At most this many new songs by one artist. */
    const val PER_ARTIST = 3
    const val PREFIX = "foryou:"

    /** Your songs considered for a mix (best first); the ones closest to its style are kept. */
    private const val POOL = 60
    /** How far up a song of yours moves when its artist is in the mix's style. */
    private const val FIT_LIFT = 12

    /** What a mix is made of before its new songs arrive. */
    data class Plan(
        val id: String,
        /** The genre's name as the library writes it; null = the whole library (no genres known). */
        val genre: String?,
        /** Your songs of the genre, best first (up to [POOL]); [assemble] keeps [OWN] of them. */
        val own: List<Track>,
        /** Songs whose radio brings the new ones (they have a YouTube id), several artists. */
        val seeds: List<Track>,
    )

    data class Labels(val title: (String) -> String, val untitled: String, val subtitle: (String) -> String)

    /** The day's plans: the top genres (by songs and recent plays), your favourites first in each. */
    fun plan(library: List<Track>, taste: Taste, day: Long): List<Plan> {
        val songs = library.distinctBy { it.videoId ?: "t:${it.id}" }
        if (songs.isEmpty()) return emptyList()
        fun plays(t: Track) = taste.recentPlays[t.id] ?: 0
        fun score(t: Track, rnd: Random) =
            (if (t.liked) 3.0 else 0.0) + 2.0 * ln(1.0 + plays(t)) + 0.5 * ln(1.0 + t.playCount) + rnd.nextDouble() * 1.5

        fun build(key: String, genre: String?, list: List<Track>): Plan {
            val rnd = Random(key.hashCode().toLong() * 1_000_003L + day)
            val ranked = list.map { it to score(it, rnd) }.sortedByDescending { it.second }.map { it.first }
            return Plan(PREFIX + key, genre, ranked.take(POOL), seedsOf(ranked))
        }

        val byGenre = songs.filter { !it.genre.isNullOrBlank() }.groupBy { it.genre!!.trim().lowercase() }
        val plans = byGenre.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<Track>>> { (_, v) -> v.size + 2 * v.sumOf(::plays) }.thenBy { it.key })
            .take(MAX_MIXES)
            .map { (key, list) ->
                val name = list.groupingBy { it.genre!!.trim() }.eachCount().maxByOrNull { it.value }!!.key
                build(slug(key), name, list)
            }
            .filter { it.seeds.isNotEmpty() || it.own.size >= 2 }
        // No genres known (or none to start a radio from): one mix of the whole library.
        return plans.ifEmpty { listOf(build("all", null, songs)) }
    }

    /** The best songs to start radios from, one per artist first (more of the style), then the rest. */
    private fun seedsOf(ranked: List<Track>): List<Track> {
        val withId = ranked.filter { it.videoId != null }
        val first = withId.distinctBy { artistKey(it.artist) }.take(SEEDS)
        return (first + withId.filterNot { it in first }).take(SEEDS)
    }

    /**
     * The new songs, in the mix's style: a song gets points from every radio it is in (more the
     * earlier it comes, i.e. the closer to that radio's song), and an artist several radios agree
     * on gets more. A radio whose artists the others don't share (a song off the style) weighs
     * less. None you have, no song twice (another upload of it either), at most [PER_ARTIST] per
     * artist, at most [count]; the best fits first.
     */
    fun pickNew(radios: List<List<RemoteTrack>>, inLibrary: Set<String>, count: Int = NEW): List<RemoteTrack> {
        val lists = radios.map { r -> r.distinctBy { it.videoId } }.filter { it.isNotEmpty() }
        if (lists.isEmpty() || count <= 0) return emptyList()
        val artistsOf = lists.map { r -> r.mapTo(HashSet()) { artistKey(it.artist) } }
        val weights = lists.indices.map { i ->
            if (lists.size == 1) return@map 1.0
            val others = HashSet<String>().apply { artistsOf.forEachIndexed { j, a -> if (j != i) addAll(a) } }
            val shared = artistsOf[i].count { it in others }.toDouble() / artistsOf[i].size.coerceAtLeast(1)
            0.35 + 0.65 * shared
        }
        val radiosWith = HashMap<String, Int>()
        artistsOf.forEach { set -> set.forEach { a -> radiosWith.merge(a, 1, Int::plus) } }

        val score = HashMap<String, Double>()
        val found = LinkedHashMap<String, RemoteTrack>()
        lists.forEachIndexed { i, r ->
            r.forEachIndexed { pos, t ->
                if (t.videoId in inLibrary) return@forEachIndexed
                found.putIfAbsent(t.videoId, t)
                score.merge(t.videoId, weights[i] * (1.5 - pos.toDouble() / r.size), Double::plus)
            }
        }
        val firstSeen = found.keys.withIndex().associate { (i, id) -> id to i }
        fun total(t: RemoteTrack) = score.getValue(t.videoId) + 0.4 * ((radiosWith[artistKey(t.artist)] ?: 1) - 1)
        val ranked = found.values.sortedWith(compareByDescending<RemoteTrack> { total(it) }.thenBy { firstSeen.getValue(it.videoId) })

        val perArtist = HashMap<String, Int>()
        val songs = HashSet<String>()
        val out = ArrayList<RemoteTrack>(count)
        for (t in ranked) {
            if (out.size >= count) break
            val artist = artistKey(t.artist)
            if ((perArtist[artist] ?: 0) >= PER_ARTIST) continue
            if (!songs.add(artist + "|" + com.villamediana.sound.online.CoverMatch.key(t.title))) continue
            perArtist.merge(artist, 1, Int::plus)
            out += t
        }
        return out
    }

    /**
     * The mix: [OWN] of your songs — the ones whose artists are in the new songs' style move up —
     * woven with the new ones (yours lead), never the same artist twice in a row when avoidable.
     */
    fun assemble(plan: Plan, new: List<RemoteTrack>, labels: Labels): Mix {
        val style = new.mapTo(HashSet()) { artistKey(it.artist) }
        val own = if (style.isEmpty()) {
            plan.own.take(OWN)
        } else {
            plan.own.withIndex()
                .sortedBy { (i, t) -> if (artistKey(t.artist) in style) i - FIT_LIFT else i }
                .map { it.value }
                .take(OWN)
        }
        val order = apart(SmartMixes.interleave(own, new.map { it.asTransientTrack() }))
        val artists = order.map { com.villamediana.sound.stats.Recaps.mainArtist(it.artist) }.filter { it.isNotBlank() }.distinct().take(3)
        val covers = SmartMixes.coversOf(order)
        return Mix(
            id = plan.id,
            title = plan.genre?.let(labels.title) ?: labels.untitled,
            subtitle = labels.subtitle(artists.joinToString(", ")),
            kind = Mix.Kind.GENRE,
            tracks = order,
            covers = covers,
            tint = own.firstNotNullOfOrNull { it.accentColor },
        )
    }

    /** Keeps the order but moves a song down a little when its artist just played. */
    internal fun apart(order: List<Track>): List<Track> {
        val left = order.toMutableList()
        val out = ArrayList<Track>(order.size)
        while (left.isNotEmpty()) {
            val prev = out.lastOrNull()?.let { artistKey(it.artist) }
            val at = left.indices.take(4).firstOrNull { artistKey(left[it].artist) != prev } ?: 0
            out += left.removeAt(at)
        }
        return out
    }

    private fun artistKey(artist: String) = com.villamediana.sound.stats.Recaps.mainArtist(artist).lowercase()

    private fun slug(key: String) = key.replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "genre" }
}
