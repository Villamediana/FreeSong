package com.villamediana.sound.social

/** What this phone is playing, as the jam compares it. */
data class LocalPlay(
    /** The queue in play order, by YouTube id (null for a song without one). */
    val videoIds: List<String?>,
    val index: Int,
    val playing: Boolean,
    val buffering: Boolean,
    val positionMs: Long,
) {
    /** Playing, or about to (loading the stream): it is not a pause. */
    val going: Boolean get() = playing || buffering
}

/** One step for this phone to play what the jam plays. */
sealed interface JamStep {
    data object None : JamStep
    /** The jam's whole queue, at [index], from [positionMs]. */
    data class Load(val queue: List<SongRef>, val index: Int, val positionMs: Long, val play: Boolean) : JamStep
    /** Same queue, another song. */
    data class Skip(val index: Int, val positionMs: Long, val play: Boolean) : JamStep
    data class Seek(val positionMs: Long) : JamStep
    data object Play : JamStep
    data object Pause : JamStep
}

/**
 * The jam's rules, pure (no player, no network): where this phone should be, and how a command
 * changes what everyone plays. Every phone streams the song itself; the relay only keeps the queue,
 * the song, play/pause and where it was at a moment of the relay's clock.
 */
object JamSync {
    /** Further than this from the jam's position: seek to it. */
    const val DRIFT_MS = 2_000L
    /** "Previous" restarts the song past this point, like the player does. */
    const val RESTART_MS = 3_000L

    fun sameQueue(jam: JamState, local: LocalPlay): Boolean =
        local.videoIds.size == jam.queue.size && local.videoIds.indices.all { local.videoIds[it] == jam.queue[it].videoId }

    /**
     * The next step towards the jam. [held] = the person paused this phone by other means (a call,
     * the earphones' button, the timer): it stays paused, still on the jam's song and place.
     */
    fun follow(jam: JamState, local: LocalPlay, serverNow: Long, held: Boolean): JamStep {
        if (jam.queue.isEmpty()) return if (local.going) JamStep.Pause else JamStep.None
        val play = jam.playing && !held
        val target = jam.positionAt(serverNow)
        if (!sameQueue(jam, local)) return JamStep.Load(jam.queue, jam.index, target, play)
        if (local.index != jam.index) return JamStep.Skip(jam.index, target, play)
        if (!play && local.going) return JamStep.Pause
        if (play && !local.going) return JamStep.Play
        // While the stream loads the position stands still; it is judged once it plays.
        if (!local.buffering && kotlin.math.abs(local.positionMs - target) > DRIFT_MS) return JamStep.Seek(target)
        return JamStep.None
    }

    // ------------------------------------------------------------------ commands → the jam's new state

    private fun JamState.at(serverNow: Long) = positionAt(serverNow)

    fun togglePlay(jam: JamState, serverNow: Long): JamState =
        jam.copy(playing = !jam.playing && jam.queue.isNotEmpty(), positionMs = jam.at(serverNow))

    fun setPlaying(jam: JamState, playing: Boolean, serverNow: Long): JamState =
        jam.copy(playing = playing && jam.queue.isNotEmpty(), positionMs = jam.at(serverNow))

    fun next(jam: JamState): JamState? =
        if (jam.index + 1 < jam.queue.size) jam.copy(index = jam.index + 1, positionMs = 0, playing = true) else null

    fun previous(jam: JamState, serverNow: Long): JamState = when {
        jam.at(serverNow) > RESTART_MS || jam.index == 0 -> jam.copy(positionMs = 0)
        else -> jam.copy(index = jam.index - 1, positionMs = 0)
    }

    fun seek(jam: JamState, positionMs: Long): JamState = jam.copy(positionMs = positionMs.coerceAtLeast(0))

    fun skipTo(jam: JamState, index: Int): JamState? =
        if (index in jam.queue.indices) jam.copy(index = index, positionMs = 0, playing = true) else null

    /**
     * A new list played from [start] (a song tapped anywhere, a playlist, a radio…). The tapped song
     * is always the one that plays, however far down a long list it is (the queue is cut around it).
     */
    fun play(songs: List<SongRef>, start: Int): JamState? {
        val list = songs.distinctBy { it.videoId }
        if (list.isEmpty()) return null
        val asked = songs.getOrNull(start)?.videoId
        val index = list.indexOfFirst { it.videoId == asked }.coerceAtLeast(0)
        val from = (index - MAX_QUEUE / 4).coerceAtLeast(0)
        val window = list.subList(from, minOf(list.size, from + MAX_QUEUE)).toList()
        return JamState(queue = window, index = index - from, positionMs = 0, playing = true)
    }

    /** "Tocar a seguir" ([next]) or "Adicionar à fila". */
    fun enqueue(jam: JamState, songs: List<SongRef>, next: Boolean): JamState? {
        if (songs.isEmpty()) return null
        if (jam.queue.isEmpty()) return play(songs, 0)
        val at = if (next) jam.index + 1 else jam.queue.size
        val queue = (jam.queue.take(at) + songs + jam.queue.drop(at)).take(MAX_QUEUE)
        return jam.copy(queue = queue)
    }

    fun remove(jam: JamState, index: Int): JamState? {
        if (index !in jam.queue.indices || index == jam.index) return null
        val queue = jam.queue.toMutableList().apply { removeAt(index) }
        return jam.copy(queue = queue, index = if (index < jam.index) jam.index - 1 else jam.index)
    }

    fun move(jam: JamState, from: Int, to: Int): JamState? {
        if (from !in jam.queue.indices || to !in jam.queue.indices || from == to) return null
        val queue = jam.queue.toMutableList().apply { add(to, removeAt(from)) }
        val current = jam.queue[jam.index].videoId
        return jam.copy(queue = queue, index = queue.indexOfFirst { it.videoId == current })
    }

    /** "Aleatório" in a jam: the songs after the current one, shuffled for everyone. */
    fun shuffleRest(jam: JamState, seed: Long): JamState {
        val head = jam.queue.take(jam.index + 1)
        val rest = jam.queue.drop(jam.index + 1).shuffled(kotlin.random.Random(seed))
        return jam.copy(queue = head + rest)
    }

    /** More songs at the end (the radio of the last ones), so the jam doesn't run out. */
    fun extend(jam: JamState, more: List<SongRef>): JamState? {
        val known = jam.queue.mapTo(HashSet()) { it.videoId }
        val fresh = more.filter { known.add(it.videoId) }.take(MAX_QUEUE - jam.queue.size)
        if (fresh.isEmpty()) return null
        return jam.copy(queue = jam.queue + fresh)
    }

    /** The jam is on its last songs: time to add more. */
    fun nearEnd(jam: JamState): Boolean = jam.queue.isNotEmpty() && jam.queue.size - 1 - jam.index <= 1

    /**
     * Something this phone did that the jam should take, judged from the player alone: the song
     * moved on (it ended, or the earphones' "next") in the same queue. Null when it's only lag.
     */
    fun moved(jam: JamState, local: LocalPlay): JamState? {
        if (!sameQueue(jam, local) || local.index == jam.index || local.index !in jam.queue.indices) return null
        return jam.copy(index = local.index, positionMs = local.positionMs.coerceAtLeast(0), playing = jam.playing)
    }

    const val MAX_QUEUE = 200

    /**
     * More songs for a jam, for everyone in it: the radio of the last songs (already in the taste of
     * this moment and of these people) carries it; every third song is one you have heard together
     * before, and every fifth one of the friends' favourites. No repeats; at most [count].
     */
    fun weave(radio: List<SongRef>, together: List<SongRef>, friends: List<SongRef>, count: Int = 25): List<SongRef> {
        val seen = HashSet<String>()
        val r = radio.iterator()
        val t = together.iterator()
        val f = friends.iterator()
        fun next(it: Iterator<SongRef>): SongRef? {
            while (it.hasNext()) it.next().let { if (seen.add(it.videoId)) return it }
            return null
        }
        val out = ArrayList<SongRef>(count)
        var i = 0
        while (out.size < count) {
            i++
            val pick = when {
                i % 5 == 0 -> next(f) ?: next(t) ?: next(r)
                i % 3 == 0 -> next(t) ?: next(r) ?: next(f)
                else -> next(r) ?: next(t) ?: next(f)
            } ?: break
            out += pick
        }
        return out
    }

    /**
     * Bytes of songs one change to the relay may carry: its messages stop at 64 KB, and a longer
     * one closed the line without changing anything (a song tapped in a long list didn't play).
     */
    const val QUEUE_BUDGET = 40_000

    /**
     * A queue the relay takes: at most [MAX_QUEUE] songs and [budget] bytes, the song at [index]
     * always in, then the songs after it, then the ones before it (nearest first). The new queue and
     * where that song is in it.
     */
    fun fit(queue: List<SongRef>, index: Int, sizeOf: (SongRef) -> Int, budget: Int = QUEUE_BUDGET): Pair<List<SongRef>, Int> {
        if (queue.isEmpty()) return queue to 0
        val at = index.coerceIn(0, queue.lastIndex)
        var left = budget - sizeOf(queue[at])
        var count = 1
        var end = at + 1
        while (end < queue.size && count < MAX_QUEUE) {
            val size = sizeOf(queue[end])
            if (size > left) break
            left -= size
            count++
            end++
        }
        var start = at
        while (start > 0 && count < MAX_QUEUE) {
            val size = sizeOf(queue[start - 1])
            if (size > left) break
            left -= size
            count++
            start--
        }
        if (start == 0 && end == queue.size) return queue to at
        return queue.subList(start, end).toList() to (at - start)
    }
}
