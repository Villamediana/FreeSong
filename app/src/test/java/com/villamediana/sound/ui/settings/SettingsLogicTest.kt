package com.villamediana.sound.ui.settings

import com.villamediana.sound.data.AudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SettingsLogicTest {

    @Test
    fun sizePerSongFollowsTheBitrate() {
        // 160 kbps × 210 s = 4.2 MB
        assertEquals(4_200_000L, approxSongBytes(AudioQuality.BEST))
        assertEquals(3_360_000L, approxSongBytes(AudioQuality.COMPATIBLE))
        assertEquals(1_680_000L, approxSongBytes(AudioQuality.SAVER))
        assertEquals(1_200_000L, approxSongBytes(AudioQuality.BEST, 60_000))
        assertTrue(qualityKbps(AudioQuality.BEST) > qualityKbps(AudioQuality.COMPATIBLE))
        assertTrue(qualityKbps(AudioQuality.COMPATIBLE) > qualityKbps(AudioQuality.SAVER))
    }

    @Test
    fun hoursUseOneDecimalWhenSmall() {
        val br = Locale.forLanguageTag("pt-BR")
        assertEquals("0,0", formatHours(0, br))
        assertEquals("0,5", formatHours(30 * 60_000L, br))
        assertEquals("7,5", formatHours(450 * 60_000L, br))
        assertEquals("12", formatHours(12 * 3_600_000L + 10 * 60_000L, br))
        assertEquals("230", formatHours(230 * 3_600_000L, br))
    }

    @Test
    fun versionLine() {
        assertEquals("1.0.0 (1)", versionLabel("1.0.0", 1))
    }
}
