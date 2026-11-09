package com.villamediana.sound.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.villamediana.sound.smart.SoundAnalyzer
import com.villamediana.sound.smart.SoundPrint
import java.nio.ByteBuffer

/**
 * Hears the song as it plays, to learn what it sounds like ([SoundPrint]: mood and rhythm, for the
 * DNA). It only measures — the audio goes on untouched — and only on this phone. A song measured
 * enough is not listened to again ([rest]), so most of the time this does nothing at all.
 *
 * [begin], [rest] and [take] come from the main thread; the audio from the playback thread. The
 * next song's audio starts a moment before the player says the song changed: that sliver counts
 * for the song before, which changes nothing in a whole song's measure.
 *
 * To take it out: PlaybackService's renderers factory ([SeekBlend.renderers]) and its
 * startListen/finishListen; the DNA then goes on without the sound.
 */
@OptIn(UnstableApi::class)
class SoundTap : TeeAudioProcessor.AudioBufferSink {
    private val lock = Any()
    private var analyzer: SoundAnalyzer? = null
    /** What a song measured before the audio changed format (its end came before [take]). */
    private var held: SoundPrint? = null
    private var rate = 0
    private var channels = 0
    private var pcm16 = false
    @Volatile private var on = false

    /** What goes in the player's audio path. */
    fun processor(): AudioProcessor = TeeAudioProcessor(this)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) = synchronized(lock) {
        if (sampleRateHz != rate || channelCount != channels) {
            // Another kind of audio: another song (the one before is kept for whoever asks).
            analyzer?.let { held = it.print() }
            analyzer = null
        }
        rate = sampleRateHz
        channels = channelCount
        pcm16 = encoding == C.ENCODING_PCM_16BIT
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!on) return
        synchronized(lock) {
            if (!on || !pcm16 || rate <= 0 || channels <= 0) return
            try {
                (analyzer ?: SoundAnalyzer(rate, channels).also { analyzer = it }).feed(buffer)
            } catch (e: Exception) {
                // Measuring must never get in the way of playing.
                on = false
                analyzer = null
            }
        }
    }

    /** A song starts: measure it from here. */
    fun begin() = synchronized(lock) {
        analyzer = null
        held = null
        on = true
    }

    /** This song needs no measuring. */
    fun rest() = synchronized(lock) {
        on = false
        analyzer = null
        held = null
    }

    /** The song ended: what was measured of it (null when too little), and stops until the next [begin]. */
    fun take(): SoundPrint? = synchronized(lock) {
        val print = held ?: analyzer?.print()
        on = false
        analyzer = null
        held = null
        print
    }
}
