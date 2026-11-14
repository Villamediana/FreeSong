package com.villamediana.sound.playback.pitch

import com.villamediana.sound.testing.Wav
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The transposer on real songs, to listen to and to measure: SOUND_AUDIO_DIR=dir with 16-bit WAVs.
 * Writes `<name>.up2.wav`, `<name>.down3.wav`… into `<dir>/out` and prints level, peaks and speed
 * (tools/audio/README.md).
 */
class PitchOnSongsLiveTest {
    private val dir = System.getProperty("sound.audio.dir").orEmpty()

    @Test fun transposeTheSongs() {
        assumeTrue(dir.isNotBlank())
        val out = File(dir, "out").apply { mkdirs() }
        for (file in File(dir).listFiles { f -> f.extension == "wav" }.orEmpty().sortedBy { it.name }) {
            val song = Wav.read(file)
            for (semitones in listOf(2, -3, 5)) {
                val shifter = PitchShifter(song.channels, song.rate)
                shifter.ratio = PitchShifter.ratioOf(semitones)
                val result = FloatArray(song.samples.size)
                var done = 0
                var at = 0
                val started = System.nanoTime()
                while (at < song.frames) {
                    val n = minOf(1_024, song.frames - at)
                    shifter.write(song.samples, at, n)
                    at += n
                    done += shifter.read(result, done, song.frames - done)
                }
                shifter.finish()
                done += shifter.read(result, done, song.frames - done)
                val ms = (System.nanoTime() - started) / 1e6
                assertEquals(song.frames, done)
                fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)
                val over = result.count { abs(it) > 1f }
                val name = if (semitones > 0) "up$semitones" else "down${-semitones}"
                println(
                    "${file.nameWithoutExtension} $name: level ${"%+.2f".format(20 * log10(rms(result) / rms(song.samples)))} dB, " +
                        "peak ${"%.2f".format(result.maxOf { abs(it) })} (was ${"%.2f".format(song.samples.maxOf { abs(it) })}), " +
                        "$over samples over full scale (${"%.4f".format(100.0 * over / result.size)}%), " +
                        "${"%.1f".format(ms / (song.frames.toDouble() / song.rate))} ms per second",
                )
                Wav.write(File(out, "${file.nameWithoutExtension}.$name.wav"), song.rate, song.channels, result)
            }
        }
    }
}
