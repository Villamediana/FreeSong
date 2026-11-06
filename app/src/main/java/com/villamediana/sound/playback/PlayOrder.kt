package com.villamediana.sound.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/**
 * The order the queue plays in, as timeline (player playlist) indexes: `order[playIndex] =
 * timelineIndex`. Without shuffle it is the identity; with shuffle it is the player's shuffle order.
 *
 * The UI talks in play order (what the listener sees in "A seguir"); the player talks in timeline
 * indexes. This class translates between them and computes the new shuffle order for queue edits,
 * so moving or inserting songs while shuffled lands exactly where the listener asked.
 */
class PlayOrder(order: IntArray) {
    private val order: IntArray = order.copyOf()
    private val inverse: IntArray = IntArray(order.size) { -1 }.also { inv ->
        order.forEachIndexed { play, timeline -> if (timeline in inv.indices) inv[timeline] = play }
    }

    val size: Int get() = order.size

    fun toIntArray(): IntArray = order.copyOf()

    fun toList(): List<Int> = order.toList()

    /** Timeline index at [playIndex], or -1 if out of range. */
    fun timelineIndexOf(playIndex: Int): Int = order.getOrElse(playIndex) { -1 }

    /** Play position of [timelineIndex], or -1 if out of range. */
    fun playIndexOf(timelineIndex: Int): Int = inverse.getOrElse(timelineIndex) { -1 }

    /** Order after moving the song at play position [from] to [to] (timeline untouched). */
    fun moved(from: Int, to: Int): PlayOrder {
        if (from !in order.indices || to !in order.indices || from == to) return this
        val list = order.toMutableList()
        list.add(to, list.removeAt(from))
        return PlayOrder(list.toIntArray())
    }

    /**
     * Order after [count] songs were inserted into the timeline at [timelinePosition] (existing
     * indexes ≥ it shift up) and must play at [playPosition], in their timeline order.
     */
    fun inserted(playPosition: Int, timelinePosition: Int, count: Int): PlayOrder {
        if (count <= 0) return this
        val at = playPosition.coerceIn(0, order.size)
        val shifted = order.map { if (it >= timelinePosition) it + count else it }.toMutableList()
        shifted.addAll(at, (timelinePosition until timelinePosition + count).toList())
        return PlayOrder(shifted.toIntArray())
    }

    /** Order after the timeline item [timelineIndex] was removed (relative order kept). */
    fun removed(timelineIndex: Int): PlayOrder {
        if (timelineIndex !in order.indices) return this
        return PlayOrder(order.filter { it != timelineIndex }.map { if (it > timelineIndex) it - 1 else it }.toIntArray())
    }

    /** Same order with [timelineIndex] moved to the front (the playing song stays first). */
    fun withFirst(timelineIndex: Int): PlayOrder {
        val p = playIndexOf(timelineIndex)
        return if (p <= 0) this else moved(p, 0)
    }

    override fun equals(other: Any?): Boolean = other is PlayOrder && other.order.contentEquals(order)
    override fun hashCode(): Int = order.contentHashCode()
    override fun toString(): String = "PlayOrder(${order.joinToString()})"

    companion object {
        fun identity(size: Int): PlayOrder = PlayOrder(IntArray(size) { it })

        /** Builds a valid order or null (wrong size, repeated or missing indexes). */
        fun validOrNull(order: List<Int>, size: Int): PlayOrder? {
            if (order.size != size) return null
            val seen = BooleanArray(size)
            for (i in order) {
                if (i !in 0 until size || seen[i]) return null
                seen[i] = true
            }
            return PlayOrder(order.toIntArray())
        }

        /** Reads the play order of a player timeline (follows its shuffle order when [shuffle]). */
        fun of(timeline: Timeline, shuffle: Boolean): PlayOrder {
            val n = timeline.windowCount
            if (n == 0) return PlayOrder(IntArray(0))
            if (!shuffle) return identity(n)
            val out = IntArray(n)
            val seen = BooleanArray(n)
            var count = 0
            var i = timeline.getFirstWindowIndex(true)
            while (i != C.INDEX_UNSET && count < n && !seen[i]) {
                seen[i] = true
                out[count++] = i
                i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
            }
            if (count < n) {
                // Defensive: a malformed shuffle order — append what was not reached, in timeline order.
                for (j in 0 until n) if (!seen[j]) out[count++] = j
            }
            return PlayOrder(out)
        }
    }
}
