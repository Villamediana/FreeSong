package com.villamediana.surco.playback

import com.villamediana.surco.data.db.Track
import com.villamediana.surco.smart.TestLibrary.Companion.library
import com.villamediana.surco.smart.neutralTaste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QueueShufflerTest {
    private val lib = library { repeat(20) { add("Artist ${it % 5}") } }

    private fun assertPermutation(order: PlayOrder, n: Int) = assertEquals((0 until n).toList(), order.toList().sorted())

    @Test
    fun `keeps the playing song first and shuffles the rest`() {
        listOf(true, false).forEach { smart ->
            repeat(20) { seed ->
                val order = QueueShuffler.order(lib, first = 7, smart = smart, taste = neutralTaste(), seed = seed.toLong())
                assertPermutation(order, lib.size)
                assertEquals(7, order.timelineIndexOf(0))
            }
        }
    }

    @Test
    fun `without a pinned song the shuffle picks the first one too`() {
        val firsts = (0 until 30).map { QueueShuffler.order(lib, first = null, smart = true, taste = neutralTaste(), seed = it.toLong()).timelineIndexOf(0) }
        assertNotEquals(1, firsts.distinct().size)
    }

    @Test
    fun `duplicates and songs missing from the library are all kept`() {
        val items: List<Track?> = lib.take(5) + lib.take(2) + listOf(null, null)
        val order = QueueShuffler.order(items, first = 0, smart = true, taste = neutralTaste(), seed = 3)
        assertPermutation(order, items.size)
        // Unknown songs go last.
        assertEquals(setOf(7, 8), setOf(order.timelineIndexOf(7), order.timelineIndexOf(8)))
    }

    @Test
    fun `smart order avoids the same artist back to back`() {
        repeat(20) { seed ->
            val order = QueueShuffler.order(lib, first = null, smart = true, taste = neutralTaste(), seed = seed.toLong())
            val artists = order.toList().map { lib[it].artist }
            assertEquals(0, artists.zipWithNext().count { (a, b) -> a == b })
        }
    }

    @Test
    fun `empty queue`() {
        assertEquals(0, QueueShuffler.order(emptyList(), first = 0, smart = true, taste = neutralTaste(), seed = 1).size)
    }
}
