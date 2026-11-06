package com.villamediana.surco.ui.player

import android.media.AudioDeviceInfo
import androidx.compose.ui.graphics.Color
import com.villamediana.surco.ui.theme.SurcoColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlayerSupportTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---------------------------------------------------------------- output route

    @Test fun `bluetooth wins over wired and the phone speaker`() {
        val route = OutputRoutes.pick(
            listOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "Pixel",
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES to null,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "WH-1000XM5",
            ),
        )
        assertEquals(OutputRoute(OutputKind.BLUETOOTH, "WH-1000XM5"), route)
    }

    @Test fun `wired then usb then phone`() {
        assertEquals(OutputKind.WIRED, OutputRoutes.pick(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to null, AudioDeviceInfo.TYPE_WIRED_HEADSET to null)).kind)
        assertEquals(OutputRoute(OutputKind.USB, "DAC"), OutputRoutes.pick(listOf(AudioDeviceInfo.TYPE_USB_HEADSET to "DAC")))
        assertEquals(OutputRoute(OutputKind.PHONE, null), OutputRoutes.pick(listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to null, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "x")))
        assertEquals(OutputRoute(OutputKind.BLUETOOTH, null), OutputRoutes.pick(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET to " ")))
    }

    // ---------------------------------------------------------------- lyrics files

    @Test fun `reads synced lyrics`() {
        val f = tmp.newFile("a.lrc").apply { writeText("﻿[ar:X]\n[00:01.00]Primeira\n[00:03.50]Segunda\n[00:05.00]\n") }
        val lyrics = LyricsFiles.read(f.absolutePath)
        assertNotNull(lyrics)
        assertTrue(lyrics!!.synced)
        assertEquals(listOf("Primeira", "Segunda", ""), lyrics.lines.map { it.text })
        assertEquals(0, lyrics.indexAt(3_000))
        assertEquals(1, lyrics.indexAt(3_600))
    }

    @Test fun `reads plain lyrics`() {
        val f = tmp.newFile("b.txt").apply { writeText("\nUma linha\nOutra linha\n\n") }
        val lyrics = LyricsFiles.read(f.absolutePath)!!
        assertFalse(lyrics.synced)
        assertEquals(listOf("Uma linha", "Outra linha"), lyrics.lines.map { it.text })
    }

    @Test fun `missing or empty files mean no lyrics`() {
        assertNull(LyricsFiles.read(null))
        assertNull(LyricsFiles.read(""))
        assertNull(LyricsFiles.read(tmp.root.resolve("nope.lrc").absolutePath))
        assertNull(LyricsFiles.read(tmp.newFile("c.lrc").apply { writeText("[00:01.00]\n[00:02.00]  \n") }.absolutePath))
        assertNull(LyricsFiles.read(tmp.root.absolutePath))
    }

    @Test fun `upcoming lines in ink only when the background can carry it`() {
        assertEquals(SurcoColors.Ink.copy(alpha = 0.72f), LyricColors.over(Color(0xFF8A5A3C)).future)
        assertEquals(SurcoColors.Text.copy(alpha = 0.34f), LyricColors.over(Color(0xFF151515)).future)
    }

    // ---------------------------------------------------------------- expand state

    // The settle animation is never run here (no frame clock); these tests check the decisions.
    private fun expandState(initial: Float) = ExpandState(initial, CoroutineScope(Job() + StandardTestDispatcher()))

    @Test fun `dragging up from the mini player past the threshold expands`() {
        val s = expandState(0f)
        s.startDrag()
        s.dragBy(-300f, rangePx = 1000f)
        assertEquals(0.3f, s.progress, 0.0001f)
        s.endDrag(velocityPx = 0f, rangePx = 1000f)
        assertEquals(1f, s.target)
        assertFalse(s.dragging)
    }

    @Test fun `a short pull from the full player snaps back`() {
        val s = expandState(1f)
        s.dragBy(100f, 1000f) // starts the drag implicitly
        assertTrue(s.dragging)
        assertEquals(0.9f, s.progress, 0.0001f)
        s.endDrag(0f, 1000f)
        assertEquals(1f, s.target)
    }

    @Test fun `a downward fling collapses`() {
        val s = expandState(1f)
        s.dragBy(50f, 1000f)
        s.endDrag(velocityPx = 1500f, rangePx = 1000f)
        assertEquals(0f, s.target)
    }

    @Test fun `progress is clamped and back gesture peeks`() {
        val s = expandState(0f)
        s.dragBy(500f, 1000f)
        assertEquals(0f, s.progress)
        s.snapTo(1f)
        s.backProgress(1f)
        assertEquals(0.86f, s.progress, 0.0001f)
        assertEquals(1f, s.target)
    }
}
