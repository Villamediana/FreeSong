package com.villamediana.sound.discover

import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.smart.Listen
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
    suspend fun songs(seeds: List<String>, exclude: Set<String>, count: Int, rnd: Random = Random.Default, known: Session? = null): List<RemoteTrack> {
        val ids = seeds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyList()
        val direct = radios(ids, DIRECT)
        if (direct.all { it.isEmpty() }) return emptyList()
        val widen = RadioMix.widenFrom(direct, ids.toSet(), if (ids.size == 1) 2 else 1, rnd)
        val wider = radios(widen, WIDER)
        val taste = runCatching { profile() }.getOrNull()
        val now = known ?: runCatching { session() }.getOrDefault(Session())
        return RadioMix.rank(direct, wider, taste, now, exclude + ids, count, rnd)
    }

    private suspend fun radios(ids: List<String>, size: Int): List<List<RemoteTrack>> = coroutineScope {
        ids.map { id -> async { runCatching { related(id, size) }.getOrDefault(emptyList()) } }.awaitAll()
    }

    companion object {
        const val DIRECT = 40
        const val WIDER = 25
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
