package com.villamediana.surco.playback

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import androidx.media3.common.C

/**
 * "Normalizar volume": a gentle compressor + limiter on the player's audio session, so quiet and
 * loud downloads end up at a similar loudness (no song jumps out, nothing clips). Android 9+;
 * on devices without the effect it simply stays off.
 */
internal class VolumeLeveler {
    private var effect: DynamicsProcessing? = null
    private var sessionId: Int = C.AUDIO_SESSION_ID_UNSET

    fun apply(enabled: Boolean, audioSessionId: Int) {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
            release()
            return
        }
        if (effect != null && sessionId == audioSessionId) return
        release()
        effect = runCatching { create(audioSessionId) }.getOrNull()
        sessionId = if (effect != null) audioSessionId else C.AUDIO_SESSION_ID_UNSET
    }

    fun release() {
        effect?.let { runCatching { it.enabled = false; it.release() } }
        effect = null
        sessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun create(audioSessionId: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            /* channelCount = */ 2,
            /* preEqInUse = */ false, /* preEqBandCount = */ 0,
            /* mbcInUse = */ true, /* mbcBandCount = */ 1,
            /* postEqInUse = */ false, /* postEqBandCount = */ 0,
            /* limiterInUse = */ true,
        ).build()
        config.setMbcBandAllChannelsTo(
            0,
            DynamicsProcessing.MbcBand(
                /* enabled = */ true,
                /* cutoffFrequency = */ 20_000f,
                /* attackTime = */ 15f, /* releaseTime = */ 400f,
                /* ratio = */ 3f, /* threshold = */ -24f, /* kneeWidth = */ 8f,
                /* noiseGateThreshold = */ -90f, /* expanderRatio = */ 1f,
                /* preGain = */ 0f, /* postGain = */ 7f,
            ),
        )
        config.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                /* inUse = */ true, /* enabled = */ true, /* linkGroup = */ 0,
                /* attackTime = */ 1f, /* releaseTime = */ 80f,
                /* ratio = */ 10f, /* threshold = */ -1f, /* postGain = */ 0f,
            ),
        )
        return DynamicsProcessing(0, audioSessionId, config).apply { enabled = true }
    }
}
