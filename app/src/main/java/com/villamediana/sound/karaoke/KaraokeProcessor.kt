package com.villamediana.sound.karaoke

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.villamediana.sound.playback.pitch.mediaId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The karaoke in the player's own audio: [masksOf] gives the masks of the song of each stretch of
 * audio (null: this song keeps its voice — and then the audio goes through untouched, at no cost).
 * It is asked at every flush, so a song whose masks are ready has no voice from its first frame
 * and the next one goes by its own; and again after [refresh] (the karaoke turned on or off, or a
 * song's masks just became readable), when the voice goes or comes back over a moment, mid-song.
 *
 * The masks may still be on their way for parts of the song: there the voice simply stays until
 * they come ([VoiceMasker]). The audio that comes out is the same length, frame for frame.
 *
 * To take it out: PlaybackService's renderers factories and its KARAOKE command.
 *
 * [refresh] comes from the main thread; everything else runs on the playback thread.
 */
@OptIn(UnstableApi::class)
class KaraokeProcessor(private val masksOf: (mediaId: String?) -> MaskSource?) : BaseAudioProcessor() {
    @Volatile private var asked = false

    private var mediaId: String? = null
    private var sampleRate = 0
    private var channels = 0
    private var float = false
    private var bytesPerFrame = 0

    private var masker: VoiceMasker? = null
    /** The masker is running: what comes out is behind what went in. */
    private var on = false
    /** The voice was asked back: once it is all back the masker stops. */
    private var leaving = false
    /** Frames of the masker's to throw away: the audio from before, given to it to start mid-song. */
    private var skip = 0
    /** Where in the song the next frame that comes in is. */
    private var songFrame = 0L

    private var recent = ByteArray(0)
    private var recentAt = 0
    private var recentFilled = 0
    private var held = FloatArray(0)
    private var heldFrames = 0
    private var input = FloatArray(0)
    private var output = FloatArray(0)

    /** The karaoke changed (on, off, or a song's masks arrived): ask again. */
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
        if (format.sampleRate != sampleRate || format.channelCount != channels) masker = null
        sampleRate = format.sampleRate
        channels = format.channelCount
        float = format.encoding == C.ENCODING_PCM_FLOAT
        bytesPerFrame = format.bytesPerFrame
        val keep = VoiceMasker.SIZE * bytesPerFrame
        if (recent.size != keep) recent = ByteArray(keep)
        recentAt = 0
        recentFilled = 0
        heldFrames = 0
        skip = 0
        leaving = false
        mediaId = streamMetadata.mediaId()
        val position = streamMetadata.positionOffsetUs
        songFrame = if (position == C.TIME_UNSET) 0L else position * sampleRate / 1_000_000L
        asked = false
        val masks = ask()
        on = masks != null
        if (masks != null) masker().apply {
            // A song (or a seek) that starts without its voice has none from the first frame.
            reset(songFrame, startDown = true)
            this.masks = masks
            wanted = true
        }
    }

    private fun ask(): MaskSource? = runCatching { masksOf(mediaId) }.getOrNull()

    private fun masker(): VoiceMasker = masker ?: VoiceMasker(channels, sampleRate).also { masker = it }

    private fun retarget(masks: MaskSource?) {
        if (masks == null) {
            if (on) {
                // The voice comes back over a moment; then the masker stops.
                masker().wanted = false
                leaving = true
            }
            return
        }
        val m = masker()
        leaving = false
        if (on) {
            m.masks = masks
            m.wanted = true
            return
        }
        // Mid-song: the masker starts on the moments just heard (so its audio is whole from the
        // first frame it gives), as the audio is; the voice then goes down over a moment.
        val frames = recentFilled / bytesPerFrame
        m.reset(songFrame - frames, startDown = false)
        m.masks = masks
        m.wanted = true
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
            m.write(warm, 0, frames)
        }
        skip = frames
        heldFrames = 0
        on = true
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        if (asked) {
            asked = false
            retarget(ask())
        }
        remember(inputBuffer)
        val frames = size / bytesPerFrame
        songFrame += frames
        if (!on) {
            replaceOutputBuffer(size).put(inputBuffer).flip()
            return
        }
        val samples = frames * channels
        if (input.size < samples) input = FloatArray(samples)
        val source = inputBuffer.order(ByteOrder.nativeOrder())
        if (float) for (i in 0 until samples) input[i] = source.float
        else for (i in 0 until samples) input[i] = source.short / 32768f
        inputBuffer.position(inputBuffer.limit())
        hold(input, frames)
        masker().write(input, 0, frames)
        give()
    }

    override fun onQueueEndOfStream() {
        if (!on) return
        masker().finish()
        give()
    }

    private fun give() {
        val m = masker()
        val pending = (skip + heldFrames) * channels
        if (output.size < pending) output = FloatArray(pending)
        var ready = m.read(output, 0, skip + heldFrames)
        val thrown = minOf(skip, ready)
        skip -= thrown
        ready -= thrown
        val done = leaving && m.settled
        if (ready <= 0 && !done) return
        val out = replaceOutputBuffer(heldFrames * bytesPerFrame).order(ByteOrder.nativeOrder())
        val at = thrown * channels
        for (i in 0 until ready * channels) put(out, output[at + i])
        heldFrames -= ready
        if (done) {
            // The voice is all back: what was taken in ahead goes out as it came, and nothing runs behind.
            val from = ready * channels
            for (i in 0 until heldFrames * channels) put(out, held[from + i])
            heldFrames = 0
            skip = 0
            on = false
            leaving = false
        } else if (heldFrames > 0) {
            System.arraycopy(held, ready * channels, held, 0, heldFrames * channels)
        }
        out.flip()
    }

    private fun put(out: ByteBuffer, v: Float) {
        if (float) out.putFloat(v) else out.putShort((v * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
    }

    private fun hold(frames: FloatArray, count: Int) {
        val need = (heldFrames + count) * channels
        if (held.size < need) held = held.copyOf(need * 2)
        System.arraycopy(frames, 0, held, heldFrames * channels, count * channels)
        heldFrames += count
    }

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
        masker = null
        recent = ByteArray(0)
        recentAt = 0
        recentFilled = 0
        held = FloatArray(0)
        heldFrames = 0
        input = FloatArray(0)
        output = FloatArray(0)
        skip = 0
        on = false
        leaving = false
        sampleRate = 0
        channels = 0
        mediaId = null
    }
}
