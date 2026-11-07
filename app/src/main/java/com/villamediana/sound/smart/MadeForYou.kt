package com.villamediana.sound.smart

import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.RemoteTrack
import kotlin.math.ln
import kotlin.random.Random

/**
 * "Feitos pra você": up to [MAX_MIXES] mixes, one per genre you listen to most. Each one takes some
 * of your songs of that genre and new ones alike (the YouTube Music radio of a few of them — same
 * style, not only the same artist), mixed together. Made once a day: the next day brings new songs.
 * Pure: the radios come in from outside.
 */
object MadeForYou {
    const val MAX_MIXES = 3
    const val OWN = 10
    const val NEW = 15
    const val SEEDS = 3
    const val PREFIX = "foryou:"

    /** What a mix is made of before its new songs arrive. */
    data class Plan(
        val id: String,
        /** The genre's name as the library writes it; null = the whole library (no genres known). */
        val genre: String?,
        val own: List<Track>,
        /** Songs whose radio brings the new ones (they have a YouTube id). */
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
            return Plan(PREFIX + key, genre, ranked.take(OWN), ranked.filter { it.videoId != null }.take(SEEDS))
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

    /**
     * The new songs: in turns from each seed's radio (so every seed brings some), none you already
     * have, no repeats, at most [count].
     */
    fun pickNew(radios: List<List<RemoteTrack>>, inLibrary: Set<String>, count: Int = NEW): List<RemoteTrack> {
        val seen = HashSet<String>(inLibrary)
        val out = ArrayList<RemoteTrack>(count)
        val iters = radios.map { it.iterator() }
        while (out.size < count && iters.any { it.hasNext() }) {
            for (it in iters) {
                while (it.hasNext()) {
                    val t = it.next()
                    if (seen.add(t.videoId)) {
                        out += t
                        break
                    }
                }
                if (out.size >= count) break
            }
        }
        return out
    }

    /** The mix: your songs and the new ones woven together (yours lead), cover art from both. */
    fun assemble(plan: Plan, new: List<RemoteTrack>, labels: Labels): Mix {
        val order = SmartMixes.interleave(plan.own, new.map { it.asTransientTrack() })
        val artists = order.map { com.villamediana.sound.stats.Recaps.mainArtist(it.artist) }.filter { it.isNotBlank() }.distinct().take(3)
        val covers = SmartMixes.coversOf(order)
        return Mix(
            id = plan.id,
            title = plan.genre?.let(labels.title) ?: labels.untitled,
            subtitle = labels.subtitle(artists.joinToString(", ")),
            kind = Mix.Kind.GENRE,
            tracks = order,
            covers = covers,
            tint = plan.own.firstNotNullOfOrNull { it.accentColor },
        )
    }

    private fun slug(key: String) = key.replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "genre" }
}
