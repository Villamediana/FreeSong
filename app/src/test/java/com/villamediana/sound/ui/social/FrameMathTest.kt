package com.villamediana.sound.ui.social

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Framing a photo in the round frame: always covered, the fingers' point still, a square cut. */
class FrameMathTest {
    private val math = FrameMath(width = 1000, height = 600, frame = 300f)

    @Test
    fun itStartsCenteredJustCoveringTheFrame() {
        val f = math.start()
        assertEquals(1f, f.zoom)
        assertEquals(Offset(-100f, 0f), f.offset)
        val cut = math.cut(f)
        assertEquals(200f, cut.left, 0.01f)
        assertEquals(0f, cut.top, 0.01f)
        assertEquals(600f, cut.width, 0.01f)
        assertEquals(cut.width, cut.height, 0.01f)
    }

    @Test
    fun draggingPastAnEdgeStopsAtIt() {
        val f = math.gesture(math.start(), pan = Offset(500f, 500f), factor = 1f, focus = Offset(150f, 150f))
        assertEquals(Offset(0f, 0f), f.offset)
        assertEquals(0f, math.cut(f).left, 0.01f)
        val other = math.gesture(math.start(), pan = Offset(-5000f, 0f), factor = 1f, focus = Offset(150f, 150f))
        assertEquals(1000f, math.cut(other).right, 0.01f)
    }

    @Test
    fun zoomingKeepsThePointUnderTheFingers() {
        val start = math.start()
        val focus = Offset(120f, 90f)
        fun photoPoint(f: Framing) = Offset((focus.x - f.offset.x) / math.scale(f.zoom), (focus.y - f.offset.y) / math.scale(f.zoom))
        val zoomed = math.gesture(start, Offset.Zero, factor = 2.5f, focus = focus)
        assertEquals(2.5f, zoomed.zoom, 0.001f)
        val before = photoPoint(start)
        val after = photoPoint(zoomed)
        assertEquals(before.x, after.x, 0.01f)
        assertEquals(before.y, after.y, 0.01f)
        // The cut is the smaller square it now shows.
        assertEquals(240f, math.cut(zoomed).width, 0.01f)
    }

    @Test
    fun zoomStaysBetweenOneAndFiveAndTheCutAlwaysFitsThePhoto() {
        assertEquals(1f, math.zoomTo(math.start(), 0.3f).zoom)
        assertEquals(FrameMath.MAX_ZOOM, math.zoomTo(math.start(), 40f).zoom)
        val rnd = Random(7)
        var f = math.start()
        repeat(500) {
            f = math.gesture(f, Offset(rnd.nextFloat() * 400 - 200, rnd.nextFloat() * 400 - 200), 0.6f + rnd.nextFloat() * 0.9f, Offset(rnd.nextFloat() * 300, rnd.nextFloat() * 300))
            val cut = math.cut(f)
            assertTrue(cut.left >= -0.01f && cut.top >= -0.01f && cut.right <= 1000.01f && cut.bottom <= 600.01f)
            assertEquals(cut.width, cut.height, 0.01f)
        }
    }
}
