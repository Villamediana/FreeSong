package com.villamediana.sound.playback.crossfade

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The crossfade inside a player's own audio: volume and bass of each moment of the song follow its
 * [FadeShape] by that moment's position in the song, so the fade is smooth to the sample and
 * keeps time with the music even when the main thread is busy. Plain audio passes untouched.
 *
 * - The main player [arm]s the next song's shape right before it seeks into it: the shape takes
 *   over at that seek's flush and ends at any later seek (a skip, a tap on the bar): a plain cut.
 * - The tail player [hold]s the shape of the song going out: it only ever plays that.
 * - [release] goes back to plain audio with a short ramp (a pause or skip during the fade).
 *
 * [arm], [hold] and [release] come from the main thread; the audio runs on the playback thread.
 */
@OptIn(UnstableApi::class)
class FadeShaper : BaseAudioProcessor() {
    @Volatile private var armed: FadeShape? = null
    @Volatile private var held: FadeShape? = null
    @Volatile private var releaseAsked = false

    private var shape: FadeShape? = null
    private var fromArm = false
    private var sampleRate = 0
    private var channels = 0
    private var float = false

    /** Where the audio handed in since the last flush starts in the song, and how many frames since. */
    private var segmentStartUs = C.TIME_UNSET
    private var segmentFrames = 0L

    private val shelf = LowShelf()
    private var lastGain = 1f
    private var lastBassDb = 0f

    /** Back to plain: from [rampGain]/[rampBassDb] to 1/0 dB over [rampFrames]. */
    private var rampFrames = 0
    private var rampDone = 0
    private var rampGain = 1f
    private var rampBassDb = 0f

    /** The next seek goes into the song shaped by [shape] (main player). */
    fun arm(shape: FadeShape) {
        releaseAsked = false
        held = null
        armed = shape
    }

    /** This player plays the song shaped by [shape] from now on (tail player). */
    fun hold(shape: FadeShape) {
        releaseAsked = false
        armed = null
        held = shape
    }

    /** Plain audio again (with a quick ramp if a fade was under way). */
    fun release() {
        armed = null
        held = null
        releaseAsked = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val format = inputAudioFormat
        sampleRate = format.sampleRate
        channels = format.channelCount
        float = format.encoding == C.ENCODING_PCM_FLOAT
        val pending = armed
        val keep = held
        when {
            pending != null -> {
                armed = null
                shape = pending
                fromArm = true
            }
            keep != null -> {
                shape = keep
                fromArm = false
            }
            // A flush after audio went through is a new seek: the shaped song is left, plain cut.
            // One before any audio is the sink's own second flush of the same seek.
            fromArm && segmentFrames > 0 -> {
                shape = null
                fromArm = false
            }
        }
        val position = streamMetadata.positionOffsetUs
        segmentStartUs = if (position != C.TIME_UNSET) position else shape?.startUs ?: 0L
        segmentFrames = 0
        rampFrames = 0
        rampDone = 0
        shelf.reset(channels, sampleRate)
    }

    /** What the main thread asked for since the last buffer. */
    private fun takeRequests() {
        if (releaseAsked) {
            releaseAsked = false
            if (shape != null || rampDone < rampFrames) {
                rampGain = lastGain
                rampBassDb = lastBassDb
                rampFrames = (sampleRate * RELEASE_MS / 1_000).coerceAtLeast(1)
                rampDone = 0
            }
            shape = null
            fromArm = false
        }
        val keep = held
        if (keep != null && shape !== keep) {
            shape = keep
            fromArm = false
            rampFrames = 0
            rampDone = 0
            shelf.reset(channels, sampleRate)
        }
    }

    private fun positionUs(frames: Long): Long = segmentStartUs + frames * 1_000_000L / sampleRate

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        takeRequests()
        val bytesPerFrame = channels * if (float) 4 else 2
        val frames = size / bytesPerFrame
        val out = replaceOutputBuffer(size)
        val s = shape
        if (s == null && rampDone >= rampFrames) {
            out.put(inputBuffer)
            out.flip()
            segmentFrames += frames
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        var f = 0
        while (f < frames) {
            val n = minOf(STEP_FRAMES, frames - f)
            val g0: Float
            val g1: Float
            val bass: Float
            if (s != null) {
                val at = segmentFrames + f
                g0 = s.gain(positionUs(at))
                g1 = s.gain(positionUs(at + n))
                bass = s.bassDb(positionUs(at + n / 2))
            } else {
                val k0 = rampDone.toFloat() / rampFrames
                val k1 = minOf(rampDone + n, rampFrames).toFloat() / rampFrames
                g0 = rampGain + (1f - rampGain) * k0
                g1 = rampGain + (1f - rampGain) * k1
                bass = rampBassDb * (1f - k1)
                rampDone = minOf(rampDone + n, rampFrames)
            }
            shelf.set(bass)
            for (k in 0 until n) {
                val g = g0 + (g1 - g0) * k / n
                for (c in 0 until channels) {
                    val x = if (float) input.float.toDouble() else input.short / 32768.0
                    val y = shelf.process(c, x) * g
                    if (float) out.putFloat(y.toFloat()) else out.putShort((y * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
            lastGain = g1
            lastBassDb = bass
            f += n
        }
        // Whatever doesn't fill a whole frame (never, in practice) goes through as it is.
        while (input.hasRemaining()) out.put(input.get())
        segmentFrames += frames
        // The next song is in, at its own level and bass: plain from here on.
        if (s != null && s.role == FadeShape.Role.IN && s.over(positionUs(segmentFrames - SETTLE_FRAMES))) {
            shape = null
            fromArm = false
            lastGain = 1f
            lastBassDb = 0f
        }
        out.flip()
    }

    override fun onReset() {
        armed = null
        held = null
        releaseAsked = false
        shape = null
        fromArm = false
        segmentStartUs = C.TIME_UNSET
        segmentFrames = 0
        rampFrames = 0
        rampDone = 0
        lastGain = 1f
        lastBassDb = 0f
    }

    companion object {
        /** Volume and bass are worked out every this many frames, the volume eased in between. */
        const val STEP_FRAMES = 64

        /** After the incoming fade ends, the bass filter rings down for this long before it's let go. */
        private const val SETTLE_FRAMES = 4_800L

        /** A fade cut short (pause, skip) gets back to plain over this. */
        const val RELEASE_MS = 40

        /** ExoPlayer's renderers with [processors] in the audio path (the tail player's: just its shaper). */
        fun renderers(context: Context, vararg processors: AudioProcessor): RenderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(arrayOf(*processors))
                    .build()
        }
    }
}

/**
 * A low shelf (RBJ cookbook, slope 1) at [FadeShape.BASS_HZ], one state per channel, direct form I
 * (steady while its gain moves). At 0 dB it is exactly the input.
 */
internal class LowShelf {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = DoubleArray(0)
    private var x2 = DoubleArray(0)
    private var y1 = DoubleArray(0)
    private var y2 = DoubleArray(0)
    private var rate = 0
    private var gainDb = Float.NaN

    fun reset(channels: Int, sampleRate: Int) {
        x1 = DoubleArray(channels)
        x2 = DoubleArray(channels)
        y1 = DoubleArray(channels)
        y2 = DoubleArray(channels)
        rate = sampleRate
        gainDb = Float.NaN
        set(0f)
    }

    fun set(db: Float) {
        if (rate <= 0 || (!gainDb.isNaN() && abs(db - gainDb) < 0.01f)) return
        gainDb = db
        val a = 10.0.pow(db / 40.0)
        val w0 = 2.0 * PI * FadeShape.BASS_HZ / rate
        val cosW = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt(2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cosW + twoSqrtAAlpha
        b0 = a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha) / a0
        b1 = 2 * a * ((a - 1) - (a + 1) * cosW) / a0
        b2 = a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha) / a0
        a1 = -2 * ((a - 1) + (a + 1) * cosW) / a0
        a2 = ((a + 1) + (a - 1) * cosW - twoSqrtAAlpha) / a0
    }

    fun process(c: Int, x: Double): Double {
        val y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
        x2[c] = x1[c]
        x1[c] = x
        y2[c] = y1[c]
        y1[c] = y
        return y
    }
}
