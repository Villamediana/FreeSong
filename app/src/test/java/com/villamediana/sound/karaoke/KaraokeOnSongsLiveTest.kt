package com.villamediana.sound.karaoke

import com.villamediana.sound.testing.Wav
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.log10

/**
 * The karaoke on real songs with the real model, end to end as on the phone: the song heard part
 * by part ([VocalRemover] + [OnnxStems]), its masks kept ([VoiceMasks]) and the voice turned down
 * as it plays ([VoiceMasker]).
 *
 * SOUND_KARAOKE_MODEL=dir with vocals.onnx and accompaniment.onnx; SOUND_AUDIO_DIR=dir with 16-bit
 * WAVs. Writes `out/<name>.karaoke.wav`. A song that comes with `<name>.vocals.wav` and
 * `<name>.band.wav` (its voice and its band alone) is measured too (tools/audio/README.md).
 */
class KaraokeOnSongsLiveTest {
    private val dir = System.getProperty("sound.audio.dir").orEmpty()
    private val model = System.getProperty("sound.karaoke.model").orEmpty()

    private class WavPcm(private val wav: Wav.Audio) : SongPcm {
        override val sampleRate = wav.rate
        override val frames = wav.frames.toLong()
        override fun read(from: Long, count: Int, left: FloatArray, right: FloatArray) {
            for (i in 0 until count) {
                val f = from + i
                if (f < 0 || f >= frames) {
                    left[i] = 0f
                    right[i] = 0f
                } else {
                    left[i] = wav.samples[(f * wav.channels).toInt()]
                    right[i] = wav.samples[(f * wav.channels).toInt() + if (wav.channels > 1) 1 else 0]
                }
            }
        }
        override fun close() = Unit
    }

    private fun play(song: Wav.Audio, samples: FloatArray, masks: VoiceMasks): FloatArray {
        val masker = VoiceMasker(song.channels, song.rate)
        masker.reset(0, startDown = true)
        masker.masks = masks
        val out = FloatArray(samples.size)
        var done = 0
        var at = 0
        while (at < song.frames) {
            val n = minOf(1_024, song.frames - at)
            masker.write(samples, at, n)
            at += n
            done += masker.read(out, done, song.frames - done)
        }
        masker.finish()
        masker.read(out, done, song.frames - done)
        return out
    }

    private fun energy(x: FloatArray) = x.sumOf { it.toDouble() * it }

    private fun sdr(ref: FloatArray, est: FloatArray): Double {
        var noise = 0.0
        for (i in ref.indices) noise += (ref[i] - est[i]).toDouble() * (ref[i] - est[i])
        return 10 * log10(energy(ref) / (noise + 1e-12))
    }

    @Test fun takeTheVoiceOut() {
        assumeTrue(dir.isNotBlank() && File(model, "vocals.onnx").isFile)
        val out = File(dir, "out").apply { mkdirs() }
        OnnxStems(File(model, "vocals.onnx"), File(model, "accompaniment.onnx")).use { stems ->
            val remover = VocalRemover(stems)
            val songs = File(dir).listFiles { f -> f.extension == "wav" && !f.name.endsWith(".vocals.wav") && !f.name.endsWith(".band.wav") }.orEmpty().sortedBy { it.name }
            for (file in songs) {
                val song = Wav.read(file)
                val audio = ModelAudio(WavPcm(song))
                val kept = File(out, "${file.nameWithoutExtension}.masks").apply { delete() }
                val masks = VoiceMasks.open(kept, VoiceMasks.framesFor(audio.frames))
                val started = System.nanoTime()
                for (p in 0 until masks.parts) masks.write(p, remover.part(p) { side, from, into -> audio.read(side, from, into) })
                val heard = (System.nanoTime() - started) / 1e9
                val seconds = song.frames.toDouble() / song.rate
                val playing = System.nanoTime()
                val karaoke = play(song, song.samples, masks)
                val played = (System.nanoTime() - playing) / 1e6
                Wav.write(File(out, "${file.nameWithoutExtension}.karaoke.wav"), song.rate, song.channels, karaoke)
                println(
                    "${file.nameWithoutExtension} (${song.rate} Hz, ${"%.0f".format(seconds)} s): heard in ${"%.1f".format(heard)} s " +
                        "(${"%.3f".format(heard / seconds)} of its length), masks ${kept.length() / 1_000_000} MB, " +
                        "playing costs ${"%.1f".format(played / seconds)} ms per second",
                )
                val voice = File(dir, "${file.nameWithoutExtension}.vocals.wav").takeIf { it.isFile }?.let(Wav::read)
                val band = File(dir, "${file.nameWithoutExtension}.band.wav").takeIf { it.isFile }?.let(Wav::read)
                if (voice != null && band != null) {
                    val score = sdr(band.samples, karaoke)
                    val voiceDown = 10 * log10(energy(voice.samples) / energy(play(song, voice.samples, masks)))
                    val bandWhole = sdr(band.samples, play(song, band.samples, masks))
                    println("   the band alone at ${"%.2f".format(score)} dB (SDR), the voice ${"%.1f".format(voiceDown)} dB down, the band itself whole to ${"%.1f".format(bandWhole)} dB")
                    assertTrue("SDR $score", score > 9.0)
                    assertTrue("voice only $voiceDown dB down", voiceDown > 8.0)
                }
                masks.close()
                kept.delete()
            }
        }
    }
}
