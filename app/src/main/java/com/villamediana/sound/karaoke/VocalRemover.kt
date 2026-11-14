package com.villamediana.sound.karaoke

import com.villamediana.sound.playback.pitch.FloatFft
import java.io.Closeable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A model that hears a stretch of a song and says what in it is the voice and what is the band
 * (Spleeter's two stems, Hennequin et al. 2020, MIT): [VocalRemover.WINDOW] moments of
 * [VoiceMasks.BINS] bands, each side.
 */
interface StemModel : Closeable {
    /**
     * [mix]: the strength of every band of every moment — side, then moment, then band. Returns
     * the same for the band alone and for the voice alone.
     */
    fun stems(mix: FloatArray): Stems

    class Stems(val band: FloatArray, val voice: FloatArray)
}

/**
 * Works out where the voice is in a part of a song ([VoiceMasks]): the audio at 44.1 kHz taken
 * apart in windows, the model asked what of each band is the voice and what is the band, and from
 * the two a mask — how much of each band stays when the voice goes.
 */
class VocalRemover(private val model: StemModel) {
    private val fft = FloatFft(VoiceMasks.FFT)
    private val hann = FloatArray(VoiceMasks.FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / VoiceMasks.FFT)).toFloat() }
    private val re = FloatArray(VoiceMasks.FFT)
    private val im = FloatArray(VoiceMasks.FFT)
    private val mix = FloatArray(2 * WINDOW * VoiceMasks.BINS)

    /**
     * The masks of part [part] of a song. [audio] gives the song's samples at 44.1 kHz: frames
     * [from, from + count) of one side into the array (silence before its start and after its end).
     * Returns the part's moments one after the other, [VoiceMasks.FRAME_BYTES] each.
     */
    fun part(part: Int, audio: (side: Int, from: Long, into: FloatArray) -> Unit): ByteArray {
        // The model hears WINDOW moments at a time; the part is its middle, so every moment kept
        // was heard with what comes before and after it.
        val first = part.toLong() * VoiceMasks.PART - LEAD
        val span = (WINDOW - 1) * VoiceMasks.HOP + VoiceMasks.FFT
        val samples = FloatArray(span)
        for (side in 0..1) {
            // Moment m is the window centred at frame m * HOP.
            audio(side, first * VoiceMasks.HOP - VoiceMasks.FFT / 2, samples)
            for (t in 0 until WINDOW) {
                val at = t * VoiceMasks.HOP
                for (i in 0 until VoiceMasks.FFT) re[i] = samples[at + i] * hann[i]
                im.fill(0f)
                fft.forward(re, im)
                val row = (side * WINDOW + t) * VoiceMasks.BINS
                for (b in 0 until VoiceMasks.BINS) mix[row + b] = sqrt(re[b] * re[b] + im[b] * im[b])
            }
        }
        val stems = model.stems(mix)
        val out = ByteArray(VoiceMasks.PART * VoiceMasks.FRAME_BYTES)
        for (side in 0..1) {
            for (t in 0 until VoiceMasks.PART) {
                val row = (side * WINDOW + LEAD + t) * VoiceMasks.BINS
                val to = t * VoiceMasks.FRAME_BYTES + side * VoiceMasks.VALUES
                var top = 0f
                for (b in 0 until VoiceMasks.BINS) {
                    val band = stems.band[row + b]
                    val voice = stems.voice[row + b]
                    val both = band * band + voice * voice
                    // Where neither is heard there is nothing to take away.
                    val keep = if (both < EPSILON) 1f else band * band / both
                    // Squared: a band the voice shares with the music goes further down — the
                    // voice matters more than a little of the music behind it.
                    val mask = keep * keep
                    out[to + b] = (mask * 255f).roundToInt().coerceIn(0, 255).toByte()
                    if (b >= VoiceMasks.BINS - TOP) top += mask
                }
                // Above what the model hears (the air of a voice, its "s"): as its top bands went.
                out[to + VoiceMasks.BINS] = (top / TOP * 255f).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    companion object {
        /** Moments the model hears at a time. */
        const val WINDOW = 512

        /** Moments heard before a part's first one (and as many after its last). */
        const val LEAD = (WINDOW - VoiceMasks.PART) / 2

        private const val TOP = 128
        private const val EPSILON = 1e-10f
    }
}
