package com.villamediana.sound.playback

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.smart.SmartQueue
import com.villamediana.sound.smart.Taste
import kotlin.random.Random

/**
 * Computes the player's shuffle order for a queue: smart (weighted by taste, artists spread out)
 * or plain random, with the playing song kept first so turning shuffle on never interrupts it.
 */
internal object QueueShuffler {
    /**
     * @param items the queue in timeline order (null = song not in the library any more).
     * @param first timeline index to keep first, or null to let the shuffle choose the first song too.
     * @return a permutation of the timeline indexes (play order).
     */
    fun order(items: List<Track?>, first: Int?, smart: Boolean, taste: Taste, seed: Long): PlayOrder {
        val n = items.size
        if (n == 0) return PlayOrder(IntArray(0))
        val pinned = first?.takeIf { it in 0 until n }
        val others = (0 until n).filter { it != pinned }
        val rest: List<Int> = if (smart) {
            val known = others.filter { items[it] != null }
            val unknown = others.filter { items[it] == null }
            // The same song can be queued twice: hand its indexes out in turn.
            val slots = HashMap<Long, ArrayDeque<Int>>()
            known.forEach { i -> slots.getOrPut(items[i]!!.id) { ArrayDeque() }.addLast(i) }
            val shuffled = SmartQueue.smartShuffle(known.map { items[it]!! }, taste, seed)
            shuffled.map { slots.getValue(it.id).removeFirst() } + unknown.shuffled(Random(seed))
        } else {
            others.shuffled(Random(seed))
        }
        return PlayOrder((listOfNotNull(pinned) + rest).toIntArray())
    }
}
