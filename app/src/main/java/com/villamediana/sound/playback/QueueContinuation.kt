package com.villamediana.sound.playback

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.smart.SmartQueue
import com.villamediana.sound.smart.Taste

/**
 * What keeps a queue going so it never runs out: songs like the last few of the list (YouTube
 * Music's radio of each of them, in turns, so the whole end of the list counts and not only the
 * last song) woven with your own songs that fit. A list of your songs keeps being mostly yours with
 * a new one every few; a list of online songs goes on like a radio; a radio of a song never loses
 * that song from sight, however long it plays. Pure: the radios come from outside.
 */
internal object QueueContinuation {
    /** More songs come when this many (or fewer) are left after the one playing. */
    const val WHEN_LEFT = 1

    /** Songs added each time. */
    const val SIZE = 20

    /** How many of the list's last songs the new ones come from. */
    const val SEEDS = 3

    /** Songs asked from each of their radios. */
    const val PER_SEED = 25

    /** At most this share of what's added comes from your library: at least one new song in three. */
    const val MAX_OWN_SHARE = 2.0 / 3.0

    /** The list's end that decides the seeds and how much of it was yours. */
    private const val TAIL = 10

    /** With nothing new left, songs of yours come back once this many others played in between (at most). */
    private const val AGAIN_AFTER = 50

    /** Nothing to add (offline, say): tries again this often, this many times, while the queue stays the same. */
    const val RETRY_MS = 20_000L
    const val RETRIES = 3

    /**
     * Video ids to start radios from: the list's last songs (play order) that YouTube knows, the
     * latest first — the ones [skipped] just now left out (what is being heard leads, not what was
     * passed over), unless every one was skipped. A radio's own song ([anchor]) is always one of
     * them, right after the latest: the radio follows what is being heard without drifting away
     * from the song it was started from.
     */
    fun seeds(queue: List<Track>, count: Int = SEEDS, skipped: Set<String> = emptySet(), anchor: String? = null): List<String> {
        val tail = queue.takeLast(TAIL).asReversed()
            .mapNotNull { it.videoId?.takeIf(String::isNotBlank) }
            .distinct()
        val latest = tail.filter { it !in skipped }.ifEmpty { tail }.take(count)
        if (anchor.isNullOrBlank() || anchor in latest) return latest
        return latest.take(1) + anchor + latest.drop(1)
    }

    /** The song a radio was started from (its YouTube id), while it is still in the [queue]; null for any other list. */
    fun anchor(source: PlaySource?, queue: List<Track>): String? {
        if (source?.type != PlaySource.Type.RADIO) return null
        val id = source.id?.takeIf(String::isNotBlank) ?: return null
        return queue.firstOrNull { it.videoId == id || it.id.toString() == id }?.videoId?.takeIf(String::isNotBlank)
    }

    /**
     * How much of what's added comes from your library: as much as the list's end was yours, never
     * all of it. None for a radio of songs from YouTube ([source]): it brings songs like its own —
     * yours among them when its radio has them — not the library's.
     */
    fun ownShare(queue: List<Track>, source: PlaySource? = null): Double {
        if (source?.type == PlaySource.Type.RADIO && source.id?.toLongOrNull() == null) return 0.0
        val tail = queue.takeLast(TAIL)
        if (tail.isEmpty()) return MAX_OWN_SHARE
        return tail.count { !it.isOnline }.toDouble() / tail.size * MAX_OWN_SHARE
    }

    /**
     * The songs to add: [own] (library songs that fit, best first) and the [radios]' songs (in turns,
     * one from each, so every seed brings some; played from the library when downloaded) woven at
     * [ownShare]. Nothing already queued, no repeats, at most [limit]; when one side runs out the
     * other fills in.
     */
    fun blend(
        own: List<Track>,
        radios: List<List<RemoteTrack>>,
        ownShare: Double,
        queuedIds: Set<Long>,
        queuedVideoIds: Set<String>,
        byVideoId: Map<String, Track>,
        limit: Int = SIZE,
    ): List<Track> {
        val ids = HashSet(queuedIds)
        val videos = HashSet(queuedVideoIds)
        fun take(t: Track): Boolean {
            val v = t.videoId?.takeIf(String::isNotBlank)
            if (t.id in ids || (v != null && v in videos)) return false
            ids += t.id
            if (v != null) videos += v
            return true
        }

        val mine = own.iterator()
        fun nextOwn(): Track? {
            while (mine.hasNext()) mine.next().let { if (take(it)) return it }
            return null
        }

        val iters = radios.map { it.iterator() }
        var turn = 0
        fun nextNew(): Track? {
            var dry = 0
            while (dry < iters.size) {
                val it = iters[turn % iters.size]
                turn++
                var found: Track? = null
                while (found == null && it.hasNext()) {
                    val r = it.next()
                    if (r.videoId.isBlank()) continue
                    val t = byVideoId[r.videoId] ?: OnlineItem.of(r).toTrack()
                    if (take(t)) found = t
                }
                if (found != null) return found
                dry++
            }
            return null
        }

        val share = ownShare.coerceIn(0.0, MAX_OWN_SHARE)
        val out = ArrayList<Track>(limit)
        var owned = 0
        while (out.size < limit) {
            val wantOwn = owned < share * (out.size + 1)
            val t = if (wantOwn) nextOwn()?.also { owned++ } ?: nextNew() else nextNew() ?: nextOwn()?.also { owned++ }
            out += t ?: break
        }
        return out
    }

    /**
     * Nothing new left to add (offline with every song of yours already in the queue): your songs
     * that fit the end of the list come back, the ones heard most recently left out.
     */
    fun again(queue: List<Track>, library: List<Track>, taste: Taste, seed: Long, limit: Int = SIZE): List<Track> {
        if (library.isEmpty()) return emptyList()
        val recent = queue.takeLast((library.size / 2).coerceIn(1, AGAIN_AFTER))
        return SmartQueue.continuation(recent, library, taste, limit, seed)
    }
}
