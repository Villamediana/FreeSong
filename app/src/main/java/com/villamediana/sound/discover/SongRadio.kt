package com.villamediana.sound.discover

import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.smart.Listen
import com.villamediana.sound.smart.MoodLean
import com.villamediana.sound.smart.QueueMood
import com.villamediana.sound.smart.RadioMix
import com.villamediana.sound.smart.Session
import com.villamediana.sound.smart.TasteProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random

/**
 * Songs like the ones being heard — the radios and the queue's "a seguir" alike: YouTube Music's
 * radio of each seed and of one or two songs drawn from them (another draw every time), ranked by
 * [RadioMix] with the listener's taste and this session's listening (what was just heard or skipped).
 */
class SongRadio(
    private val related: suspend (videoId: String, size: Int) -> List<RemoteTrack>,
    private val profile: suspend () -> TasteProfile?,
    private val session: suspend () -> Session,
) {
    /**
     * Up to [count] songs like [seeds] (video ids, the one playing first), none of [exclude]. Empty
     * when no radio came (offline).
     */
    suspend fun songs(seeds: List<String>, exclude: Set<String>, count: Int, rnd: Random = Random.Default, known: Session? = null): List<RemoteTrack> =
        inMood(seeds, QueueMood(), exclude, count, rnd, known).songs

    /**
     * The same, following the [mood] of the queue (what the listener pulled up in it or took out
     * by hand): the songs pulled up lead the radios, what is like the ones taken out goes down or
     * stays out. Comes with the reading of the mood ([Tuned.lean]), to judge any other song by.
     */
    suspend fun inMood(seeds: List<String>, mood: QueueMood, exclude: Set<String>, count: Int, rnd: Random = Random.Default, known: Session? = null): Tuned {
        val wanted = mood.wanted.take(MOOD_SEEDS)
        // What plays now first, then the songs pulled up, then the rest of the list's end.
        val ids = (seeds.take(1) + wanted + seeds.drop(1)).filter { it.isNotBlank() }.distinct()
            .take(maxOf(seeds.size, 1 + wanted.size))
        val unwanted = mood.unwanted.filter { it !in ids }.take(MOOD_SEEDS)
        if (ids.isEmpty()) return Tuned(emptyList(), MoodLean(mood))
        val (direct, away) = coroutineScope {
            val a = async { radios(ids, DIRECT) }
            val b = async { radios(unwanted, WIDER) }
            a.await() to b.await()
        }
        val toward = ids.withIndex().filter { it.value in wanted }.map { direct[it.index] }
        val lean = MoodLean(mood, toward, away)
        if (direct.all { it.isEmpty() }) return Tuned(emptyList(), lean)
        val widen = RadioMix.widenFrom(direct, ids.toSet(), if (ids.size == 1) 2 else 1, rnd)
        val wider = radios(widen, WIDER)
        val taste = runCatching { profile() }.getOrNull()
        val now = known ?: runCatching { session() }.getOrDefault(Session())
        val songs = RadioMix.rank(direct, wider, taste, now, exclude + ids, count, rnd, mood = lean.takeUnless { mood.isEmpty })
        return Tuned(songs, lean)
    }

    /** A radio and the reading of the mood it was made with. */
    class Tuned(val songs: List<RemoteTrack>, val lean: MoodLean)

    private suspend fun radios(ids: List<String>, size: Int): List<List<RemoteTrack>> = coroutineScope {
        ids.map { id -> async { runCatching { related(id, size) }.getOrDefault(emptyList()) } }.awaitAll()
    }

    companion object {
        const val DIRECT = 40
        const val WIDER = 25
        /** How many of the songs pulled up (and of the ones taken out) have their radios asked. */
        const val MOOD_SEEDS = 2
        /** What counts as "this session". */
        const val SESSION_MS = 3L * 3_600_000L

        /** The session from the library's and the streamed listening of the last [SESSION_MS]. */
        suspend fun sessionOf(library: LibraryRepository, now: Long = System.currentTimeMillis()): Session {
            val since = now - SESSION_MS
            val tracks = library.playEventsListSince(since)
            val byId = if (tracks.isEmpty()) emptyMap() else library.getTracks(tracks.map { it.trackId }.distinct()).associateBy { it.id }
            val listens = tracks.mapNotNull { e ->
                byId[e.trackId]?.let { t -> Listen(t.videoId, t.title, t.artist, t.genre, e.playedAt, e.listenedMs, t.durationMs, e.completed, e.skipped) }
            } + library.onlinePlaysListSince(since).map { p ->
                Listen(p.videoId, p.title, p.artist, null, p.playedAt, p.listenedMs, p.durationMs, p.completed, p.skipped)
            }
            return Session.of(listens)
        }
    }
}
