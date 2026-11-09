package com.villamediana.sound.smart

import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/** One listening session, from the library or streamed: what the profile learns from. */
data class Listen(
    val videoId: String?,
    val title: String,
    val artist: String,
    /** The song's genre tag, when the library knows it. */
    val genre: String? = null,
    val at: Long,
    val listenedMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val skipped: Boolean,
    /** Where it was heard (a place the app learned by itself), when places are on. */
    val place: Long? = null,
    /** Who was listening along (the friends in the jam). */
    val people: List<String> = emptyList(),
)

/** A heart: the strongest "I like this". */
data class Heart(val videoId: String?, val title: String, val artist: String, val genre: String? = null, val at: Long)

/**
 * What the listener likes, learned from everything they do: songs heard to the end (on the phone
 * or streamed), songs skipped early, hearts, and the genres they picked. Two memories — the long one
 * (weeks) is the taste, the short one (days) is the moment — blended [LONG_SHARE] / [SHORT_SHARE],
 * so an evening of something else nudges the suggestions instead of turning them over. One song on
 * repeat counts for its artist only up to a cap. Pure: built from plain data, easy to test.
 */
class TasteProfile private constructor(
    /** Artists by how much they are liked now (best first), only the liked ones. */
    val artists: List<ArtistTaste>,
    /** Home genre ids by how much they are listened (and picked), best first. */
    val genres: List<GenreTaste>,
    /** Songs (video ids) skipped early again and again and never heard to the end: not suggested again. */
    val avoidSongs: Set<String>,
    /** Artists (keys) skipped almost every time: not suggested again. */
    val avoidArtists: Set<String>,
    /** Songs (video ids) already heard for a while or hearted: not "new" any more. */
    val known: Set<String>,
    private val byArtist: Map<String, ArtistTaste>,
    /** For a moment's profile: how much each song skipped in it is wanted, 0..1 (absent = 1). */
    private val songFactors: Map<String, Double> = emptyMap(),
    /** For a moment's profile: how a song's own sound fits it against its artist's usual one (absent = 1). */
    private val soundFits: Map<String, Double> = emptyMap(),
    /** What the friends like ([FriendsTaste]) and how much of it counts now (artist key → 0..1). */
    private val friends: FriendsTaste = FriendsTaste.NONE,
    private val friendsLike: Map<String, Double> = friends.likes(),
) {
    /** A song of an artist, as the profile remembers it. */
    data class SongTaste(val videoId: String?, val title: String, val score: Double, val genre: String?)

    data class ArtistTaste(
        val key: String,
        /** As it is written most often ("Djavan"). */
        val name: String,
        /** 0..1 (1 = the favourite), blended long and short memory. */
        val affinity: Double,
        /** 0..1, the last days only. */
        val now: Double,
        /** Their songs heard and liked, best first. */
        val songs: List<SongTaste>,
        /** Home genre id, when known. */
        val genre: String?,
        /** Songs heard to the end plus hearts: how sure the profile is. */
        val evidence: Int,
    )

    data class GenreTaste(val id: String, val score: Double, val picked: Boolean)

    val isEmpty: Boolean get() = artists.isEmpty()

    /** How much the artist of [artistName] is liked (0..1; 0 = unknown or not liked). */
    fun affinityOf(artistName: String): Double = byArtist[artistKey(artistName)]?.affinity ?: 0.0

    fun isKnownArtist(artistName: String): Boolean = byArtist.containsKey(artistKey(artistName))

    /** Never suggested: a song skipped again and again, or an artist skipped almost always. */
    fun avoids(videoId: String, artistName: String): Boolean = videoId in avoidSongs || artistKey(artistName) in avoidArtists

    /** How much a song is wanted in this moment, 0..1 (its skips here, for a moment's profile; else 1). */
    fun songFactor(videoId: String): Double = songFactors[videoId] ?: 1.0

    /**
     * How a song's own sound (mood, rhythm) fits this moment, against what its artist usually
     * sounds like: 1 = the same or not known; above, it fits better (for a moment's profile; else 1).
     */
    fun soundFit(videoId: String): Double = soundFits[videoId] ?: 1.0

    /**
     * How much the friends like the artist of [artistName], 0..1 (0 = no friend does): more when
     * several do, and in full for a friend listening along (for a moment's profile).
     */
    fun friendsLike(artistName: String): Double = if (friendsLike.isEmpty()) 0.0 else friendsLike[artistKey(artistName)] ?: 0.0

    /**
     * This taste for [m], with the [dna]: the artists and genres of this part of the day, of
     * weekdays or the weekend, of this place and of these friends move up (the others down a
     * little), and what is skipped in this moment — songs, artists, genres — goes down in it (only
     * in it: the taste of every other moment stays), out when skipped here again and again. So do
     * the artists whose sound is the moment's (calm, intense, still, moving), heard in it or not.
     * What the friends along like counts in full.
     */
    fun inMoment(dna: MusicDna, m: DnaMoment): TasteProfile {
        val liked = if (m.people.isEmpty()) friendsLike else friends.likes(m.people)
        if (dna.isEmpty || isEmpty) {
            return if (liked === friendsLike) this else TasteProfile(artists, genres, avoidSongs, avoidArtists, known, byArtist, songFactors, soundFits, friends, liked)
        }
        val lifted = artists.map { a -> a.copy(affinity = (a.affinity * dna.lift(a.key, m) * dna.soundLift(null, a.key, m)).coerceAtMost(1.0)) }
            .sortedWith(compareByDescending<ArtistTaste> { it.affinity }.thenBy { it.key })
        val moved = genres.map { g -> g.copy(score = g.score * dna.genreLift(g.id, m)) }
            .sortedWith(compareByDescending<GenreTaste> { it.score }.thenBy { it.id })
        return TasteProfile(lifted, moved, avoidSongs + dna.avoidNow(m), avoidArtists, known, lifted.associateBy { it.key }, dna.songFactors(m), dna.soundFits(m), friends, liked)
    }

    /**
     * The artists the listener is into right now, for "Porque você curte…": liked over several
     * songs or hearted (one lucky play is not a taste), the moment weighing a bit more than usual.
     */
    fun favoritesNow(count: Int): List<ArtistTaste> =
        artists.filter { it.evidence >= 2 && it.songs.any { s -> s.videoId != null } }
            .sortedByDescending { 0.55 * it.affinity + 0.45 * it.now }
            .take(count)

    /**
     * The songs to start the day's discoveries from: the best loved, one per artist, taking turns
     * across the listener's genres so one style doesn't fill everything.
     */
    fun seeds(count: Int): List<SongTaste> {
        val firsts = artists.filter { it.evidence >= 1 }.mapNotNull { a -> a.songs.firstOrNull { it.videoId != null }?.let { a to it } }
        val byGenre = firsts.groupBy { it.first.genre }.values.map { it.toMutableList() }.toMutableList()
        val out = ArrayList<SongTaste>(count)
        while (out.size < count && byGenre.any { it.isNotEmpty() }) {
            for (list in byGenre) {
                if (out.size >= count) break
                if (list.isNotEmpty()) out += list.removeAt(0).second
            }
        }
        return out
    }

    companion object {
        const val LONG_HALF_LIFE_MS = 45L * DAY_MS
        const val SHORT_HALF_LIFE_MS = 5L * DAY_MS
        const val HEART_HALF_LIFE_MS = 240L * DAY_MS
        const val LONG_SHARE = 0.7
        const val SHORT_SHARE = 0.3
        /** What one song can add to its artist (a song on repeat is not a whole artist). */
        const val SONG_CAP = 5.0
        /** A picked genre counts like this share of the strongest learned one. */
        const val PICKED_SHARE = 0.35
        /** A downloaded song, in hearts. */
        const val SAVED_WEIGHT = 0.6
        /** Skips that leave a song never heard through out for good. */
        const val AVOID_SKIPS = 4
        /** Skips (with no song of theirs ever heard through) that leave an artist out for good. */
        const val AVOID_ARTIST_SKIPS = 6

        private class Acc(val videoId: String?, val title: String, var genre: String?) {
            var long = 0.0
            var short = 0.0
            var completes = 0
            var skips = 0
            var heard = false
            var hearted = false
        }

        /**
         * Builds the profile. [artistGenres] says the genre of an artist (artist key → genre name)
         * when the songs don't carry one (streamed songs); [picked] are the genres chosen on the Home;
         * [saved] the songs downloaded (a quiet "I like this", a fraction of a heart, long memory only);
         * [friends] what the friends like (it never changes the taste: it only nudges what is suggested).
         */
        fun build(
            listens: List<Listen>,
            hearts: List<Heart>,
            now: Long,
            artistGenres: Map<String, String> = emptyMap(),
            picked: Set<String> = emptySet(),
            saved: List<Heart> = emptyList(),
            friends: FriendsTaste = FriendsTaste.NONE,
        ): TasteProfile {
            val songs = LinkedHashMap<String, Pair<String, Acc>>() // song key → (artist key, acc)
            val names = HashMap<String, HashMap<String, Int>>()
            fun acc(videoId: String?, title: String, artist: String, genre: String?): Acc? {
                val a = artistKey(artist)
                if (a.isEmpty()) return null
                names.getOrPut(a) { HashMap() }.merge(artist.trim(), 1, Int::plus)
                val key = videoId?.takeIf { it.isNotBlank() } ?: ("t:" + normalizeText(title) + "|" + a)
                val entry = songs.getOrPut(key) { a to Acc(videoId?.takeIf { it.isNotBlank() }, title, genre) }
                if (entry.second.genre.isNullOrBlank() && !genre.isNullOrBlank()) entry.second.genre = genre
                return entry.second
            }

            for (l in listens) {
                val s = acc(l.videoId, l.title, l.artist, l.genre) ?: continue
                val age = (now - l.at).coerceAtLeast(0)
                // A skip teaches nothing against the song (maybe it just wasn't the moment): it is
                // counted, only for the rare "out for good" (never heard through, skipped again and again).
                val value = when {
                    l.skipped -> 0.0
                    l.completed -> 1.0
                    l.listenedMs >= 60_000 || (l.durationMs > 0 && l.listenedMs * 2 >= l.durationMs) -> 0.6
                    else -> 0.25
                }
                s.long += value * decay(age, LONG_HALF_LIFE_MS)
                s.short += value * decay(age, SHORT_HALF_LIFE_MS)
                if (l.skipped) s.skips++ else s.heard = true
                if (l.completed) s.completes++
            }
            for (h in hearts) {
                val s = acc(h.videoId, h.title, h.artist, h.genre) ?: continue
                val age = (now - h.at).coerceAtLeast(0)
                s.long += 3.0 * decay(age, HEART_HALF_LIFE_MS)
                s.short += 1.5 * decay(age, SHORT_HALF_LIFE_MS * 2)
                s.hearted = true
            }
            for (h in saved) {
                val s = acc(h.videoId, h.title, h.artist, h.genre) ?: continue
                s.long += SAVED_WEIGHT * decay((now - h.at).coerceAtLeast(0), HEART_HALF_LIFE_MS)
            }

            // Artists: their songs added up (each capped), skips counted.
            class ArtistAcc {
                var long = 0.0
                var short = 0.0
                var skips = 0
                var plays = 0
                var evidence = 0
                var hearted = false
                val songs = ArrayList<Acc>()
                val genres = HashMap<String, Int>()
            }
            val artists = HashMap<String, ArtistAcc>()
            for ((_, pair) in songs) {
                val (a, s) = pair
                val x = artists.getOrPut(a) { ArtistAcc() }
                x.long += if (s.long > 0) min(s.long, SONG_CAP) else s.long
                x.short += if (s.short > 0) min(s.short, SONG_CAP) else s.short
                x.skips += s.skips
                x.plays += s.skips + (if (s.heard) maxOf(1, s.completes) else 0)
                x.evidence += s.completes + (if (s.hearted) 2 else 0)
                x.hearted = x.hearted || s.hearted
                x.songs += s
                s.genre?.takeIf { it.isNotBlank() }?.let { x.genres.merge(it, 1, Int::plus) }
            }
            val maxLong = artists.values.maxOfOrNull { it.long }?.takeIf { it > 0 } ?: 1.0
            val maxShort = artists.values.maxOfOrNull { it.short }?.takeIf { it > 0 } ?: 1.0

            // Out for good only what was never heard through and skipped again and again (a skip
            // alone only takes a song out of the moment: the radio's session leaves it out for hours).
            val avoidArtists = artists.filter { (_, x) -> !x.hearted && x.evidence == 0 && x.skips >= AVOID_ARTIST_SKIPS && x.skips >= x.plays }.keys
            val avoidSongs = songs.values.mapNotNull { (_, s) ->
                s.videoId?.takeIf { !s.hearted && s.completes == 0 && s.skips >= AVOID_SKIPS }
            }.toSet()
            val known = songs.values.mapNotNull { (_, s) -> s.videoId?.takeIf { s.hearted || s.completes > 0 || (s.heard && s.long > 0.3) } }.toSet()

            val tastes = artists.mapNotNull { (key, x) ->
                if (key in avoidArtists) return@mapNotNull null
                val long = x.long / maxLong
                val short = (x.short / maxShort).coerceAtLeast(0.0)
                val affinity = LONG_SHARE * long + SHORT_SHARE * short
                if (affinity <= 0.0) return@mapNotNull null
                val genreName = x.genres.maxByOrNull { it.value }?.key ?: artistGenres[key]
                ArtistTaste(
                    key = key,
                    name = names[key]?.maxByOrNull { it.value }?.key ?: key,
                    affinity = affinity.coerceAtMost(1.0),
                    now = short.coerceAtMost(1.0),
                    songs = x.songs.filter { it.long > 0 && it.videoId !in avoidSongs }
                        .sortedByDescending { it.long + it.short }
                        .map { SongTaste(it.videoId, it.title, it.long + it.short, it.genre) },
                    genre = GenreMatch.idOf(genreName),
                    evidence = x.evidence,
                )
            }.sortedWith(compareByDescending<ArtistTaste> { it.affinity }.thenBy { it.key })

            // Genres: the artists' affinity, more for an artist heard across several songs; the picked ones join in.
            val genreScore = HashMap<String, Double>()
            for (a in tastes) {
                val id = a.genre ?: continue
                genreScore.merge(id, a.affinity * (1.0 + ln(1.0 + a.songs.size)), Double::plus)
            }
            val strongest = genreScore.values.maxOrNull() ?: 1.0
            for (id in picked) genreScore.merge(id, PICKED_SHARE * strongest, Double::plus)
            val genres = genreScore.map { (id, score) -> GenreTaste(id, score, id in picked) }
                .sortedWith(compareByDescending<GenreTaste> { it.score }.thenBy { it.id })

            return TasteProfile(tastes, genres, avoidSongs, avoidArtists, known, tastes.associateBy { it.key }, friends = friends)
        }

        private fun decay(ageMs: Long, halfLifeMs: Long): Double = 0.5.pow(ageMs.toDouble() / halfLifeMs)
    }
}
