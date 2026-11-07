package com.villamediana.sound.recognition

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The fingerprint is byte for byte the one the reference generator makes from the same audio. */
class ShazamSignatureTest {
    @Test
    fun `the signature matches the reference for real music`() {
        val raw = javaClass.getResource("/music/natalia7.raw")!!.readBytes()
        val pcm = ShortArray(raw.size / 2)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        val expected = java.util.Base64.getDecoder().decode(javaClass.getResource("/music/natalia7.sig.b64")!!.readText().trim())
        val sig = ShazamSignature.generate(pcm, maxSeconds = 6.0)
        assertEquals(112_000, sig.numberSamples)
        assertEquals(229, sig.peakCount)
        assertArrayEquals(expected, ShazamSignature.encode(sig))
    }
}

/** Live (SOUND_LIVE=1): the reference clip is named by Shazam from this machine. */
class SongIdLiveTest {
    @Test
    fun theReferenceClipIsRecognised() {
        org.junit.Assume.assumeTrue(System.getProperty("sound.live") == "1")
        val raw = javaClass.getResource("/music/natalia7.raw")!!.readBytes()
        val pcm = ShortArray(raw.size / 2)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        val result = kotlinx.coroutines.runBlocking { SongId.identify(pcm) }
        println("LIVE shazam: ${result.getOrNull()} ${result.exceptionOrNull()}")
        org.junit.Assert.assertTrue(result.isSuccess)
    }
}
