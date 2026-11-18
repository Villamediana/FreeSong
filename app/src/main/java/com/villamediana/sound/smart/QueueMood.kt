package com.villamediana.sound.smart

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.RemoteTrack

/**
 * What the listener did to the queue by hand, read as the mood of this listening: the songs taken
 * out of what comes next (not now), the songs pulled up to play sooner or put in by hand (more of
 * this). It belongs to the queue it was made on and to these hours — nothing of it goes into the
 * taste the app keeps. Pure.
 */
data class QueueMood(
    val edits: List<Edit> = emptyList(),
    /** Songs the listener placed by hand (moved, either way): theirs now, the app leaves them where they are. */
    val placed: Set<String> = emptySet(),
    /** Grows with every edit: what was already answered is told from what is new. */
    val rev: Int = 0,
) {
    enum class Kind { REMOVED, RAISED }

    data class Edit(
        val kind: Kind,
        val key: String,
        val videoId: String?,
        val artist: String,
        val genre: String?,
        val at: Long,
        val rev: Int,
        /** A removed song the app itself had picked (the queue gets another in its place). */
        val pick: Boolean = false,
    )

    val isEmpty: Boolean get() = edits.isEmpty()

    /** [track] was taken out of what comes next. */
    fun removed(track: Track, pick: Boolean, now: Long): QueueMood =
        with(Kind.REMOVED, track, now, pick).copy(placed = placed - keyOf(track))

    /** [track] was pulled up to play sooner, or put in the queue by hand. */
    fun raised(track: Track, now: Long): QueueMood =
        with(Kind.RAISED, track, now, pick = false).copy(placed = placed + keyOf(track))

    /** [track] was pushed further down: no longer "more of this", and still where the listener put it. */
    fun lowered(track: Track): QueueMood {
        val key = keyOf(track)
        return copy(edits = edits.filterNot { it.key == key && it.kind == Kind.RAISED }, placed = placed + key, rev = rev + 1)
    }

    /** [track] was put in the queue by hand: the listener's, the app leaves it where it is (and if it had been taken out, that is forgotten). */
    fun placed(track: Track): QueueMood {
        val key = keyOf(track)
        return copy(edits = edits.filterNot { it.key == key && it.kind == Kind.REMOVED }, placed = placed + key)
    }

    private fun with(kind: Kind, track: Track, now: Long, pick: Boolean): QueueMood {
        val key = keyOf(track)
        val edit = Edit(kind, key, track.videoId?.takeIf(String::isNotBlank), track.artist, track.genre?.takeIf(String::isNotBlank), now, rev + 1, pick)
        return copy(edits = (edits.filterNot { it.key == key } + edit).takeLast(KEPT), rev = rev + 1)
    }

    /** The same mood without what is older than these hours. */
    fun fresh(now: Long): QueueMood {
        val kept = edits.filter { now - it.at < LASTS_MS }
        return if (kept.size == edits.size) this else copy(edits = kept)
    }

    /** YouTube ids of the songs pulled up, the latest first. */
    val wanted: List<String> get() = edits.asReversed().filter { it.kind == Kind.RAISED }.mapNotNull { it.videoId }

    /** YouTube ids of the songs taken out, the latest first. */
    val unwanted: List<String> get() = edits.asReversed().filter { it.kind == Kind.REMOVED }.mapNotNull { it.videoId }

    /** App picks taken out since [since]: how many the queue is owed. */
    fun picksRemovedSince(since: Int): Int = edits.count { it.kind == Kind.REMOVED && it.pick && it.rev > since }

    fun raisedSince(since: Int): Int = edits.count { it.kind == Kind.RAISED && it.rev > since }

    /** From −1 (songs of this artist were taken out) to 1 (pulled up). */
    fun artist(name: String): Double = lean(artistKey(name), ARTIST_UP, ARTIST_DOWN) { artistKey(it.artist) }

    /**
     * The same for a genre, weaker: one song says little about all of its genre — and a genre both
     * pulled up and taken out says nothing (the whole queue may be of it).
     */
    fun genre(name: String?): Double {
        val g = Genres.key(name).takeIf(String::isNotEmpty) ?: return 0.0
        val kinds = edits.filter { Genres.key(it.genre) == g }.mapTo(HashSet()) { it.kind }
        if (kinds.size != 1) return 0.0
        return lean(g, GENRE_UP, GENRE_DOWN) { Genres.key(it.genre) }
    }

    /** An artist taken out twice and never pulled up: none of them for now. */
    fun out(artistName: String): Boolean {
        val a = artistKey(artistName)
        if (a.isEmpty()) return false
        var removed = 0
        for (e in edits) {
            if (artistKey(e.artist) != a) continue
            if (e.kind == Kind.RAISED) return false
            removed++
        }
        return removed >= OUT_AFTER
    }

    /** True for a song taken out by hand (by its YouTube id or its library row). */
    fun tookOut(track: Track): Boolean = edits.any { it.kind == Kind.REMOVED && it.key == keyOf(track) }

    private inline fun lean(key: String, up: Double, down: Double, of: (Edit) -> String?): Double {
        if (key.isEmpty()) return 0.0
        var sum = 0.0
        for (e in edits) if (of(e) == key) sum += if (e.kind == Kind.RAISED) up else -down
        return sum.coerceIn(-1.0, 1.0)
    }

    companion object {
        /** How long an edit keeps saying something about the mood. */
        const val LASTS_MS = 3L * 3_600_000L
        private const val KEPT = 60
        private const val OUT_AFTER = 2
        private const val ARTIST_UP = 0.6
        private const val ARTIST_DOWN = 0.5
        private const val GENRE_UP = 0.3
        private const val GENRE_DOWN = 0.25

        /** What tells one song from another across the library and YouTube. */
        fun keyOf(track: Track): String = track.videoId?.takeIf(String::isNotBlank) ?: "id:${track.id}"
    }
}

/**
 * Which way the mood leans for any song: the edits themselves (its artist, its genre) and how close
 * the song sits to the ones pulled up ([toward]: their radios) or taken out ([away]). A song both
 * sides know follows the ones pulled up. Pure.
 */
class MoodLean(
    val mood: QueueMood,
    toward: List<List<RemoteTrack>> = emptyList(),
    away: List<List<RemoteTrack>> = emptyList(),
) {
    private val pull = nearness(toward)
    private val push = nearness(away)
    private val pullArtists = artistsOf(toward)
    private val pushArtists = artistsOf(away) - pullArtists
    private val unwanted = mood.unwanted.toHashSet()

    /** From −1 (like what was taken out) to 1 (like what was pulled up); 0 when the mood says nothing of it. */
    fun of(videoId: String?, artist: String, genre: String? = null): Double {
        val a = artistKey(artist)
        var lean = mood.artist(artist) + mood.genre(genre)
        val near = videoId?.let(pull::get)
        if (near != null) lean += NEAR * near
        else videoId?.let(push::get)?.let { lean -= FAR * it }
        if (a in pullArtists) lean += SAME_CROWD else if (a in pushArtists) lean -= SAME_CROWD
        return lean.coerceIn(-1.0, 1.0)
    }

    fun of(track: Track): Double = of(track.videoId, track.artist, track.genre)

    /** Never for this queue: a song taken out by hand, or an artist taken out twice. */
    fun rulesOut(videoId: String?, artist: String): Boolean = (videoId != null && videoId in unwanted) || mood.out(artist)

    fun rulesOut(track: Track): Boolean = mood.tookOut(track) || rulesOut(track.videoId, track.artist)

    /**
     * [songs] the way the mood wants them: what it rules out gone, the more a song is like the ones
     * pulled up the sooner it comes, what is like the ones taken out last — and songs the mood says
     * the same of stay in the order they came in.
     */
    fun <T> arrange(songs: List<T>, track: (T) -> Track): List<T> =
        songs.filterNot { rulesOut(track(it)) }
            .withIndex()
            .sortedWith(compareBy<IndexedValue<T>> { step(of(track(it.value))) }.thenBy { it.index })
            .map { it.value }

    /** The lean in a few steps (the strongest pull first): a hair of difference doesn't reorder a queue. */
    fun step(lean: Double): Int = -Math.round(lean * STEPS).toInt()

    private fun nearness(radios: List<List<RemoteTrack>>): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (radio in radios) {
            val songs = radio.distinctBy { it.videoId }
            songs.forEachIndexed { pos, t ->
                if (t.videoId.isBlank()) return@forEachIndexed
                val near = 1.0 - pos.toDouble() / songs.size
                out.merge(t.videoId, near, ::maxOf)
            }
        }
        return out
    }

    /** The artists at the top of these radios: the company the songs keep. */
    private fun artistsOf(radios: List<List<RemoteTrack>>): Set<String> =
        radios.flatMapTo(HashSet()) { r -> r.take(CROWD).map { artistKey(it.artist) } }.apply { remove("") }

    companion object {
        /** How much being at the top of the radio of a song pulled up counts. */
        private const val NEAR = 0.8
        /** The same for the radio of a song taken out. */
        private const val FAR = 0.6
        /** An artist that comes with the songs pulled up (or taken out), whatever the song. */
        private const val SAME_CROWD = 0.25
        private const val CROWD = 12
        /** Steps on each side of "the mood says nothing". */
        private const val STEPS = 4
        /** From here on a song is clearly on one side. */
        const val CLEAR = 0.2
    }
}
