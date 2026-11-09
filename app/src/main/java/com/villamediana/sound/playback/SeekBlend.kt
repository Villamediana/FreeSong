package com.villamediana.sound.playback

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
import kotlin.math.cos
import kotlin.math.sin

/*
 * A tap on a lyric line goes there with a quick crossfade instead of a dry cut: what was playing
 * keeps sounding for a moment, fading out, while the new part comes in.
 *
 * It lives in the player's own audio, ahead of the output: a seek throws away what was already
 * handed on and not heard yet (the output's buffer, a few hundred ms), and that is exactly how the
 * song would have gone on. [SeekBlendProcessor] keeps the last moments it handed on and, after a
 * seek it was told about, mixes them — fading out — into the first moments of the new part, fading
 * in. Nothing else plays, nothing waits: the tap is as quick as a plain seek.
 *
 * To take it out: PlaybackService's renderers factory ([SeekBlend.renderers]) and its SEEK_BLEND
 * command, PlayerActions.seekLine; the lyrics then seek as the seek bar does.
 */

/** The crossfade's shape (pure: tested on its own). */
object SeekBlend {
    /** How long the two parts overlap: quick, so the tap still feels instant. */
    const val FADE_MS = 300

    /** Less unheard audio than this left to fade out, and the seek is a plain cut. */
    const val MIN_TAIL_MS = 30

    /** The seek has to come this soon after being announced, or it isn't the one announced. */
    const val ARM_TIMEOUT_MS = 1_000L

    /** New audio this late after the seek (still loading): the old moment has passed, no blend. */
    const val STALE_MS = 700L

    /** How much handed-on audio is kept: the output's buffer (up to ~750 ms) and the fade. */
    const val KEEP_MS = 1_500

    /**
     * What to fade out, as frames counted back from the last one handed on: from the one being heard
     * ([fromEnd] frames back) for [frames] frames. Null when there is nothing sensible to blend: no
     * position known, the heard one past what was handed on or older than what is kept.
     */
    data class Tail(val fromEnd: Int, val frames: Int)

    fun tail(handedOnUntilUs: Long, heardUs: Long, sampleRate: Int, keptFrames: Int, fadeMs: Int = FADE_MS): Tail? {
        if (handedOnUntilUs == C.TIME_UNSET || heardUs == C.TIME_UNSET || sampleRate <= 0) return null
        val unheard = ((handedOnUntilUs - heardUs) * sampleRate / 1_000_000L)
        if (unheard < MIN_TAIL_MS.toLong() * sampleRate / 1_000 || unheard > keptFrames) return null
        val fade = fadeMs.toLong() * sampleRate / 1_000
        return Tail(unheard.toInt(), minOf(unheard, fade).toInt())
    }

    /**
     * Gains at [frame] of a blend [frames] long: (outgoing, incoming), equal power so the loudness
     * doesn't dip in the middle. The outgoing part also starts with a few ms of ramp: it resumes
     * after the seek's short silence and would click if it came back at full level.
     */
    fun gains(frame: Int, frames: Int, sampleRate: Int): Pair<Float, Float> {
        val t = (frame.toFloat() / frames).coerceIn(0f, 1f)
        val ramp = (frame.toFloat() / (sampleRate * RAMP_MS / 1_000f)).coerceAtMost(1f)
        return (cos(t * PI / 2).toFloat() * ramp) to sin(t * PI / 2).toFloat()
    }

    private const val RAMP_MS = 4

    /** ExoPlayer's renderers with [before] and then [blend] in the audio path (everything else as by default). */
    @OptIn(UnstableApi::class)
    fun renderers(context: Context, blend: SeekBlendProcessor, vararg before: AudioProcessor): RenderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(*before, blend))
                .build()
    }
}

/**
 * Passes the audio through, keeping the last [SeekBlend.KEEP_MS] of it. [arm] says the next seek is
 * a lyric tap, with the position being heard; at that seek's flush the not-yet-heard part is set
 * aside and blended into the new audio. Any other seek, or a new song, is a plain cut as ever.
 *
 * [arm] comes from the main thread; everything else runs on the playback thread.
 */
@OptIn(UnstableApi::class)
class SeekBlendProcessor(private val nanoTime: () -> Long = System::nanoTime) : BaseAudioProcessor() {
    @Volatile private var armedHeardUs = C.TIME_UNSET
    @Volatile private var armedAtNanos = 0L

    private var sampleRate = 0
    private var channels = 0
    private var bytesPerFrame = 0
    private var float = false

    /** The handed-on audio, circular: [ringFilled] bytes ending just before [ringWrite]. */
    private var ring = ByteArray(0)
    private var ringWrite = 0
    private var ringFilled = 0

    /** Where the audio handed on since the last flush started, and how many frames since. */
    private var segmentStartUs = C.TIME_UNSET
    private var segmentFrames = 0L

    /** The part fading out, its length and how far the blend has gone. */
    private var tail: ByteBuffer? = null
    private var tailFrames = 0
    private var tailDone = 0
    private var tailAtNanos = 0L

    /** The next seek is a lyric tap; [heardPositionMs] is where the song is heard now. */
    fun arm(heardPositionMs: Long) {
        armedAtNanos = nanoTime()
        armedHeardUs = heardPositionMs * 1_000
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val format = inputAudioFormat
        if (format.sampleRate != sampleRate || format.channelCount != channels || format.bytesPerFrame != bytesPerFrame) {
            sampleRate = format.sampleRate
            channels = format.channelCount
            bytesPerFrame = format.bytesPerFrame
            float = format.encoding == C.ENCODING_PCM_FLOAT
            ring = ByteArray(maxOf(0, sampleRate.toLong() * SeekBlend.KEEP_MS / 1_000 * bytesPerFrame).toInt())
            dropTail()
            armedHeardUs = C.TIME_UNSET
        } else {
            capture()
        }
        // A new stretch of audio: the sink says where it starts (on the flush before its first buffer).
        segmentStartUs = streamMetadata.positionOffsetUs
        segmentFrames = 0
        ringWrite = 0
        ringFilled = 0
    }

    /** At the announced seek: sets aside what was handed on and not heard. */
    private fun capture() {
        val heardUs = armedHeardUs
        if (heardUs == C.TIME_UNSET) {
            // Another flush while a blend waits for audio is the sink's own second flush for the same
            // seek (its position); one that comes with audio already counted is a new seek: a plain cut.
            if (segmentFrames > 0) dropTail()
            return
        }
        armedHeardUs = C.TIME_UNSET
        val now = nanoTime()
        val sinceArmUs = (now - armedAtNanos) / 1_000
        if (sinceArmUs > SeekBlend.ARM_TIMEOUT_MS * 1_000 || segmentStartUs == C.TIME_UNSET || bytesPerFrame == 0) {
            dropTail()
            return
        }
        val handedOnUntilUs = segmentStartUs + segmentFrames * 1_000_000L / sampleRate
        val pick = SeekBlend.tail(handedOnUntilUs, heardUs + sinceArmUs, sampleRate, ringFilled / bytesPerFrame)
        if (pick == null) {
            dropTail()
            return
        }
        val bytes = pick.frames * bytesPerFrame
        val out = ByteArray(bytes)
        var from = Math.floorMod(ringWrite - pick.fromEnd * bytesPerFrame, ring.size)
        var copied = 0
        while (copied < bytes) {
            val n = minOf(bytes - copied, ring.size - from)
            System.arraycopy(ring, from, out, copied, n)
            copied += n
            from = (from + n) % ring.size
        }
        tail = ByteBuffer.wrap(out).order(ByteOrder.nativeOrder())
        tailFrames = pick.frames
        tailDone = 0
        tailAtNanos = now
    }

    private fun dropTail() {
        tail = null
        tailFrames = 0
        tailDone = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        keep(inputBuffer)
        val frames = size / bytesPerFrame
        segmentFrames += frames
        val out = replaceOutputBuffer(size)
        val old = tail
        if (old != null && tailDone == 0 && nanoTime() - tailAtNanos > SeekBlend.STALE_MS * 1_000_000) dropTail()
        if (tail == null || old == null) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        var f = 0
        while (f < frames && tailDone < tailFrames) {
            val (gOld, gNew) = SeekBlend.gains(tailDone, tailFrames, sampleRate)
            val at = tailDone * bytesPerFrame
            for (c in 0 until channels) {
                if (float) {
                    val v = input.float * gNew + old.getFloat(at + c * 4) * gOld
                    out.putFloat(v)
                } else {
                    val v = input.short * gNew + old.getShort(at + c * 2) * gOld
                    out.putShort(v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
            }
            tailDone++
            f++
        }
        if (tailDone >= tailFrames) dropTail()
        out.put(input)
        out.flip()
    }

    /** Copies [input] (not consumed) into the ring, the oldest giving way. */
    private fun keep(input: ByteBuffer) {
        if (ring.isEmpty()) return
        val src = input.duplicate()
        var left = src.remaining()
        // Only the newest that fits matters.
        if (left > ring.size) {
            src.position(src.position() + left - ring.size)
            left = ring.size
        }
        while (left > 0) {
            val n = minOf(left, ring.size - ringWrite)
            src.get(ring, ringWrite, n)
            ringWrite = (ringWrite + n) % ring.size
            left -= n
        }
        ringFilled = minOf(ring.size, ringFilled + (input.remaining().coerceAtMost(ring.size)))
    }

    override fun onReset() {
        dropTail()
        armedHeardUs = C.TIME_UNSET
        ring = ByteArray(0)
        ringWrite = 0
        ringFilled = 0
        sampleRate = 0
        channels = 0
        bytesPerFrame = 0
        segmentStartUs = C.TIME_UNSET
        segmentFrames = 0
    }
}
