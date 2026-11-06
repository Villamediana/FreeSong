package com.villamediana.sound.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ShuffleOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Index mapping between what the listener sees (play order) and the player's playlist (timeline). */
class PlayOrderTest {
    /** Minimal playlist timeline driven by a real ExoPlayer [ShuffleOrder], like ExoPlayer's own. */
    private class PlaylistTimeline(private val shuffleOrder: ShuffleOrder) : Timeline() {
        private val n = shuffleOrder.length
        override fun getWindowCount() = n
        override fun getFirstWindowIndex(shuffleModeEnabled: Boolean) =
            if (n == 0) C.INDEX_UNSET else if (shuffleModeEnabled) shuffleOrder.firstIndex else 0
        override fun getLastWindowIndex(shuffleModeEnabled: Boolean) =
            if (n == 0) C.INDEX_UNSET else if (shuffleModeEnabled) shuffleOrder.lastIndex else n - 1
        override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
            if (repeatMode == Player.REPEAT_MODE_ONE) return windowIndex
            val next = if (shuffleModeEnabled) shuffleOrder.getNextIndex(windowIndex) else if (windowIndex < n - 1) windowIndex + 1 else C.INDEX_UNSET
            return if (next == C.INDEX_UNSET && repeatMode == Player.REPEAT_MODE_ALL) getFirstWindowIndex(shuffleModeEnabled) else next
        }
        override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int =
            if (shuffleModeEnabled) shuffleOrder.getPreviousIndex(windowIndex) else if (windowIndex > 0) windowIndex - 1 else C.INDEX_UNSET
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long) = window
        override fun getPeriodCount() = n
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean) = period
        override fun getIndexOfPeriod(uid: Any) = (uid as? Int) ?: C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
    }

    private fun timeline(vararg order: Int) = PlaylistTimeline(ShuffleOrder.DefaultShuffleOrder(order, 42))

    @Test
    fun `reads the shuffle order the service sets on ExoPlayer`() {
        val tl = timeline(3, 0, 4, 1, 2)
        assertEquals(listOf(3, 0, 4, 1, 2), PlayOrder.of(tl, shuffle = true).toList())
        assertEquals(listOf(0, 1, 2, 3, 4), PlayOrder.of(tl, shuffle = false).toList())
        assertEquals(emptyList<Int>(), PlayOrder.of(timeline(), shuffle = true).toList())
    }

    @Test
    fun `translates play indexes to timeline indexes and back`() {
        val o = PlayOrder(intArrayOf(3, 0, 4, 1, 2))
        assertEquals(3, o.timelineIndexOf(0))
        assertEquals(2, o.timelineIndexOf(4))
        assertEquals(-1, o.timelineIndexOf(5))
        assertEquals(1, o.playIndexOf(0))
        assertEquals(0, o.playIndexOf(3))
        for (p in 0 until o.size) assertEquals(p, o.playIndexOf(o.timelineIndexOf(p)))
    }

    @Test
    fun `moving in play order only permutes the shuffle order`() {
        // Listener sees: t3 t0 t4 t1 t2 and drags the 4th (t1) to 2nd place.
        val moved = PlayOrder(intArrayOf(3, 0, 4, 1, 2)).moved(3, 1)
        assertEquals(listOf(3, 1, 0, 4, 2), moved.toList())
        // What the UI sees after the service applies it on a real ShuffleOrder.
        val tl = PlaylistTimeline(ShuffleOrder.DefaultShuffleOrder(moved.toIntArray(), 1))
        assertEquals(moved, PlayOrder.of(tl, shuffle = true))
        assertEquals(PlayOrder(intArrayOf(0, 1, 2)), PlayOrder(intArrayOf(0, 1, 2)).moved(0, 9))
    }

    @Test
    fun `inserting after the current song while shuffled`() {
        // Playing t4 (play index 2). "Tocar a seguir" with 2 songs: timeline gets them at 5..6
        // (after t4 in the timeline) and they must play right after t4.
        val before = PlayOrder(intArrayOf(3, 0, 4, 1, 2))
        val after = before.inserted(playPosition = 3, timelinePosition = 5, count = 2)
        assertEquals(listOf(3, 0, 4, 5, 6, 1, 2), after.toList())
        // Inserting in the middle of the timeline shifts the later indexes.
        val mid = before.inserted(playPosition = 3, timelinePosition = 1, count = 2)
        assertEquals(listOf(5, 0, 6, 1, 2, 3, 4), mid.toList())
        // Enqueue at the end of the play order.
        assertEquals(listOf(3, 0, 4, 1, 2, 5), before.inserted(5, 5, 1).toList())
    }

    @Test
    fun `removing keeps the relative order, like ExoPlayer's shuffle order does`() {
        val o = PlayOrder(intArrayOf(3, 0, 4, 1, 2))
        assertEquals(listOf(2, 0, 3, 1), o.removed(1).toList())
        val exo = ShuffleOrder.DefaultShuffleOrder(o.toIntArray(), 7).cloneAndRemove(1, 2)
        assertEquals(o.removed(1), PlayOrder.of(PlaylistTimeline(exo), shuffle = true))
    }

    @Test
    fun `keeps the playing song first`() {
        assertEquals(listOf(4, 3, 0, 1, 2), PlayOrder(intArrayOf(3, 0, 4, 1, 2)).withFirst(4).toList())
        assertEquals(listOf(2, 0, 1), PlayOrder(intArrayOf(2, 0, 1)).withFirst(2).toList())
    }

    @Test
    fun `validates saved orders`() {
        assertEquals(listOf(2, 0, 1), PlayOrder.validOrNull(listOf(2, 0, 1), 3)?.toList())
        assertNull(PlayOrder.validOrNull(listOf(2, 0), 3))
        assertNull(PlayOrder.validOrNull(listOf(2, 2, 1), 3))
        assertNull(PlayOrder.validOrNull(listOf(0, 1, 3), 3))
    }
}
