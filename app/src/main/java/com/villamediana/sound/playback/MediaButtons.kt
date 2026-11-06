package com.villamediana.sound.playback

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver

/**
 * Whether a saved queue exists for the headset's play button to bring back while Sound is closed.
 * The queue itself is in DataStore; this mirrors only its existence in plain SharedPreferences,
 * because [SoundMediaButtonReceiver] has to decide on the spot, without waiting on anything.
 * [PlaybackService] sets it whenever it saves the queue and clears it when the queue is gone.
 */
internal object ResumableQueue {
    private const val PREFS = "sound_playback"
    private const val KEY = "resumable"

    fun available(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

    fun set(context: Context, available: Boolean) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY, false) != available) prefs.edit().putBoolean(KEY, available).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Media3's media button receiver (headset play with the app closed → [PlaybackService] resumes the
 * last queue), except that it doesn't start the service when there's nothing to resume: started in
 * the foreground for nothing, the service never posts its notification and the system kills the
 * app ~10 s later (ForegroundServiceDidNotStartInTimeException).
 */
@OptIn(UnstableApi::class)
class SoundMediaButtonReceiver : MediaButtonReceiver() {
    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean = ResumableQueue.available(context)
}
