package com.villamediana.sound.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceWaveTest {
    @Test
    fun theWavesFollowTheVoice() {
        // Quiet, then speech, then quiet again.
        val samples = List(40) { 30 } + List(80) { if (it % 2 == 0) 20_000 else 9_000 } + List(40) { 0 }
        val wave = VoiceWave.encode(samples)
        assertEquals(VoiceWave.BARS, wave.length)
        assertTrue(wave.all { it in "0123456789abcdef" })
        assertEquals('f', wave[VoiceWave.BARS / 2])
        assertTrue(wave.first() < '3')
        assertEquals('0', wave.last())
    }

    @Test
    fun silenceAndNothingAreFlat() {
        assertEquals("0".repeat(48), VoiceWave.encode(emptyList()))
        assertEquals("0".repeat(48), VoiceWave.encode(List(10) { 0 }))
        // Fewer samples than bars still fills every bar.
        assertEquals(48, VoiceWave.encode(listOf(5, 10, 20)).length)
    }

    @Test
    fun barsNeverQuiteVanish() {
        val bars = VoiceWave.decode("0f8")
        assertEquals(3, bars.size)
        assertTrue(bars[0] > 0f)
        assertEquals(1f, bars[1])
        assertEquals(48, VoiceWave.decode(null).size)
        assertEquals(2, VoiceWave.decode("zz0f").size)
    }
}
