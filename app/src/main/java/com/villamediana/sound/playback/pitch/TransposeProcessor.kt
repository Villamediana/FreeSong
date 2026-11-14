package com.villamediana.sound.playback.pitch

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The song a stretch of audio belongs to: the player's sink says it at every flush (a new song, a
 * seek), so what is done to the audio can go by song, exact to the frame where the next one starts.
 */
@OptIn(UnstableApi::class)
internal fun AudioProcessor.StreamMetadata.mediaId(): String? {
    val uid = periodUid ?: return null
    val timeline = timeline
    if (timeline.isEmpty) return null
    val index = timeline.getIndexOfPeriod(uid)
    if (index == C.INDEX_UNSET) return null
    val window = timeline.getPeriod(index, Timeline.Period()).windowIndex
    return timeline.getWindow(window, Timeline.Window()).mediaItem.mediaId
}

/**
 * Plays a song in another key, in the player's own audio: [semitonesOf] says how far the song of
 * each stretch of audio is transposed (0: as recorded, and then the audio goes through untouched,
 * at no cost). It is asked at every flush — so a song starts in its key from its first frame, and
 * the next one in its own — and again after [refresh] (the key changed while the song plays), when
 * the change comes in with a short crossfade.
 *
 * The transposed audio is the same length, frame for frame ([PitchShifter]): positions, the lyrics
 * and the crossfade's shapes further down the chain all keep meaning what they meant. Making it
 * takes a little audio from ahead, so while it runs this takes in a fraction of a second more
 * than it has given out.
 *
 * To take it out: PlaybackService's renderers factories and its TRANSPOSE command.
 *
 * [refresh] comes from the main thread; everything else runs on the playback thread.
 */
@OptIn(UnstableApi::class)
class TransposeProcessor(private val semitonesOf: (mediaId: String?) -> Int) : BaseAudioProcessor() {
    @Volatile private var asked = false

    private var mediaId: String? = null
    private var sampleRate = 0
    private var channels = 0
    private var float = false
    private var bytesPerFrame = 0

    private var shifter: PitchShifter? = null
    /** How far the audio is transposed now (0: not at all). */
    private var semitones = 0
    /** The shifter is running: what comes out is behind what went in. */
    private var wet = false
    /** How much of what comes out is the transposed audio, on its way to [goal]. */
    private var gain = 0f
    private var goal = 0f
    /** Transposed frames to throw away: the audio from before, given to the shifter to start it mid-song. */
    private var skip = 0

    /** The last moments that went through as they were (bytes, circular): what starts the shifter mid-song. */
    private var recent = ByteArray(0)
    private var recentAt = 0
    private var recentFilled = 0

    /** The audio taken in and not given out yet, as it came (interleaved floats). */
    private var held = FloatArray(0)
    private var heldFrames = 0
    private var input = FloatArray(0)
    private var shifted = FloatArray(0)

    /** The key of the song playing may have changed: ask again. */
    fun refresh() {
        asked = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        when {
            inputAudioFormat.channelCount !in 1..2 -> AudioProcessor.AudioFormat.NOT_SET
            inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val format = inputAudioFormat
        if (format.sampleRate != sampleRate || format.channelCount != channels) shifter = null
        sampleRate = format.sampleRate
        channels = format.channelCount
        float = format.encoding == C.ENCODING_PCM_FLOAT
        bytesPerFrame = format.bytesPerFrame
        val keep = PitchShifter.warmUp(sampleRate) * bytesPerFrame
        if (recent.size != keep) recent = ByteArray(keep)
        recentAt = 0
        recentFilled = 0
        heldFrames = 0
        skip = 0
        mediaId = streamMetadata.mediaId()
        asked = false
        semitones = ask()
        // A song (or a seek) that starts transposed is transposed from its first frame.
        wet = semitones != 0
        gain = if (wet) 1f else 0f
        goal = gain
        if (wet) shifter().apply {
            reset()
            ratio = PitchShifter.ratioOf(semitones)
        }
    }

    private fun ask(): Int = runCatching { semitonesOf(mediaId) }.getOrDefault(0).coerceIn(-MAX_SEMITONES, MAX_SEMITONES)

    private fun shifter(): PitchShifter = shifter ?: PitchShifter(channels, sampleRate).also { shifter = it }

    /** The key changed while the song plays. */
    private fun retarget(want: Int) {
        if (want == semitones) return
        semitones = want
        if (want == 0) {
            goal = 0f
            return
        }
        goal = 1f
        val s = shifter()
        if (wet) {
            s.ratio = PitchShifter.ratioOf(want)
            return
        }
        // From plain to transposed: the shifter starts on the moments just heard, so its audio is
        // whole from the first frame that is given out, and comes in over the plain one.
        s.reset()
        s.ratio = PitchShifter.ratioOf(want)
        val frames = recentFilled / bytesPerFrame
        if (frames > 0) {
            val warm = FloatArray(frames * channels)
            val bytes = ByteBuffer.allocate(recentFilled).order(ByteOrder.nativeOrder())
            var from = Math.floorMod(recentAt - recentFilled, recent.size)
            var left = recentFilled
            while (left > 0) {
                val n = minOf(left, recent.size - from)
                bytes.put(recent, from, n)
                from = (from + n) % recent.size
                left -= n
            }
            bytes.flip()
            for (i in warm.indices) warm[i] = if (float) bytes.float else bytes.short / 32768f
            s.write(warm, 0, frames)
        }
        skip = frames
        heldFrames = 0
        wet = true
        gain = 0f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        if (asked) {
            asked = false
            retarget(ask())
        }
        remember(inputBuffer)
        if (!wet) {
            replaceOutputBuffer(size).put(inputBuffer).flip()
            return
        }
        val frames = size / bytesPerFrame
        val samples = frames * channels
        if (input.size < samples) input = FloatArray(samples)
        val source = inputBuffer.order(ByteOrder.nativeOrder())
        if (float) for (i in 0 until samples) input[i] = source.float
        else for (i in 0 until samples) input[i] = source.short / 32768f
        // Whatever doesn't fill a whole frame (never, in practice) is dropped.
        inputBuffer.position(inputBuffer.limit())
        hold(input, frames)
        shifter().write(input, 0, frames)
        give()
    }

    override fun onQueueEndOfStream() {
        if (!wet) return
        shifter().finish()
        give()
    }

    /** Hands on every frame the shifter has ready, over the same frames as they came in. */
    private fun give() {
        val s = shifter()
        val pending = (skip + heldFrames) * channels
        if (shifted.size < pending) shifted = FloatArray(pending)
        var ready = s.read(shifted, 0, skip + heldFrames)
        val thrown = minOf(skip, ready)
        skip -= thrown
        ready -= thrown
        if (ready <= 0 && !(goal == 0f && gain <= 0f)) return
        // All of what is held may go out at once (when the plain audio is back).
        val out = replaceOutputBuffer(heldFrames * bytesPerFrame).order(ByteOrder.nativeOrder())
        val step = 1f / (sampleRate * FADE_MS / 1_000f)
        var at = thrown * channels
        var from = 0
        for (f in 0 until ready) {
            if (gain < goal) gain = minOf(goal, gain + step) else if (gain > goal) gain = maxOf(goal, gain - step)
            if (gain >= 1f) {
                for (c in 0 until channels) put(out, limit(shifted[at + c]))
            } else {
                val plain = cos(gain * PI / 2).toFloat()
                val moved = sin(gain * PI / 2).toFloat()
                for (c in 0 until channels) put(out, limit(held[from + c] * plain + shifted[at + c] * moved))
            }
            at += channels
            from += channels
        }
        heldFrames -= ready
        if (goal == 0f && gain <= 0f) {
            // Plain again: what was taken in ahead goes out as it came, and nothing runs behind anymore.
            for (i in 0 until heldFrames * channels) put(out, held[from + i])
            heldFrames = 0
            skip = 0
            wet = false
        } else if (heldFrames > 0) {
            System.arraycopy(held, from, held, 0, heldFrames * channels)
        }
        out.flip()
    }

    private fun put(out: ByteBuffer, v: Float) {
        if (float) out.putFloat(v) else out.putShort((v * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
    }

    /**
     * Turned phases stack up differently: a mix mastered right up to full scale can come out a
     * little over it. The few peaks that do are rounded off instead of cut.
     */
    private fun limit(v: Float): Float {
        val a = abs(v)
        if (a <= KNEE) return v
        val over = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
        return if (v < 0) -over else over
    }

    private fun hold(frames: FloatArray, count: Int) {
        val need = (heldFrames + count) * channels
        if (held.size < need) held = held.copyOf(need * 2)
        System.arraycopy(frames, 0, held, heldFrames * channels, count * channels)
        heldFrames += count
    }

    /** Copies [input] (not consumed) into the ring of the last moments, the oldest giving way. */
    private fun remember(input: ByteBuffer) {
        if (recent.isEmpty()) return
        val src = input.duplicate()
        var left = src.remaining()
        if (left > recent.size) {
            src.position(src.position() + left - recent.size)
            left = recent.size
        }
        recentFilled = minOf(recent.size, recentFilled + left)
        while (left > 0) {
            val n = minOf(left, recent.size - recentAt)
            src.get(recent, recentAt, n)
            recentAt = (recentAt + n) % recent.size
            left -= n
        }
    }

    override fun onReset() {
        shifter = null
        recent = ByteArray(0)
        recentAt = 0
        recentFilled = 0
        held = FloatArray(0)
        heldFrames = 0
        input = FloatArray(0)
        shifted = FloatArray(0)
        skip = 0
        wet = false
        gain = 0f
        goal = 0f
        semitones = 0
        sampleRate = 0
        channels = 0
        mediaId = null
    }

    companion object {
        /** As far as a song's chords are transposed ([com.villamediana.sound.chords.ChordShifts.MAX]). */
        const val MAX_SEMITONES = 11

        /** Plain and transposed audio cross over this long when the key changes mid-song. */
        const val FADE_MS = 60

        private const val KNEE = 0.9f
    }
}
