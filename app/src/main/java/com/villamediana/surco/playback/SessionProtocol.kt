package com.villamediana.surco.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * Custom commands and session extras shared by [PlaybackService] and [MediaPlayerConnection].
 * Anything the standard Player commands can't express exactly (shuffle-aware queue edits, the
 * sleep timer, where the queue came from) travels through here.
 */
internal object SessionProtocol {
    // ---------------------------------------------------------------- commands (all controllers)
    /** Toggles "liked" on the current song (heart button in notification, watch, car). */
    const val LIKE = "surco.LIKE"
    /** Toggles shuffle (notification button). */
    const val SHUFFLE = "surco.SHUFFLE"

    // ---------------------------------------------------------------- commands (Surco's own UI)
    /** Replace the queue: [ARG_IDS], [ARG_START], [ARG_SHUFFLE], source args, [ARG_SMART]. */
    const val PLAY = "surco.PLAY"
    /** Insert [ARG_IDS] after the current song ([ARG_WHERE] = [WHERE_NEXT]) or at the end. */
    const val QUEUE_ADD = "surco.QUEUE_ADD"
    /** Move play position [ARG_FROM] to [ARG_TO]. */
    const val QUEUE_MOVE = "surco.QUEUE_MOVE"
    /** Remove play position [ARG_INDEX]. */
    const val QUEUE_REMOVE = "surco.QUEUE_REMOVE"
    /** Sleep timer: [ARG_SLEEP_KIND], [ARG_SLEEP_ENDS_AT], [ARG_SLEEP_TOTAL]. */
    const val SLEEP = "surco.SLEEP"
    /** Loads the last saved queue, paused, if the player is empty. */
    const val RESTORE = "surco.RESTORE"

    val publicCommands = listOf(LIKE, SHUFFLE)
    val appCommands = listOf(PLAY, QUEUE_ADD, QUEUE_MOVE, QUEUE_REMOVE, SLEEP, RESTORE)

    fun command(action: String) = SessionCommand(action, Bundle.EMPTY)

    const val ARG_IDS = "ids"
    const val ARG_START = "start"
    const val ARG_SHUFFLE = "shuffle"
    const val ARG_SMART = "smart"
    const val ARG_WHERE = "where"
    const val ARG_FROM = "from"
    const val ARG_TO = "to"
    const val ARG_INDEX = "index"
    const val WHERE_NEXT = "next"
    const val WHERE_END = "end"

    /** [ARG_SHUFFLE] values. */
    const val SHUFFLE_KEEP = 0
    const val SHUFFLE_ON = 1
    const val SHUFFLE_OFF = 2

    // ---------------------------------------------------------------- source / sleep (args and extras)
    private const val SOURCE_TYPE = "source_type"
    private const val SOURCE_TITLE = "source_title"
    private const val SOURCE_ID = "source_id"
    const val ARG_SLEEP_KIND = "sleep_kind"
    const val ARG_SLEEP_ENDS_AT = "sleep_ends_at"
    const val ARG_SLEEP_TOTAL = "sleep_total"
    private const val SLEEP_OFF = "off"
    private const val SLEEP_AT = "at"
    private const val SLEEP_END = "end"

    // ---------------------------------------------------------------- session extras
    const val EXTRA_SMART = "smart"
    const val EXTRA_OUTPUT = "output"

    fun putSource(b: Bundle, source: PlaySource?) {
        if (source == null) {
            b.remove(SOURCE_TYPE); b.remove(SOURCE_TITLE); b.remove(SOURCE_ID)
            return
        }
        b.putString(SOURCE_TYPE, source.type.name)
        b.putString(SOURCE_TITLE, source.title)
        b.putString(SOURCE_ID, source.id)
    }

    fun getSource(b: Bundle?): PlaySource? {
        val type = b?.getString(SOURCE_TYPE) ?: return null
        val t = runCatching { PlaySource.Type.valueOf(type) }.getOrNull() ?: return null
        return PlaySource(t, b.getString(SOURCE_TITLE).orEmpty(), b.getString(SOURCE_ID))
    }

    fun putSleep(b: Bundle, timer: SleepTimer) {
        when (timer) {
            SleepTimer.Off -> b.putString(ARG_SLEEP_KIND, SLEEP_OFF)
            SleepTimer.EndOfTrack -> b.putString(ARG_SLEEP_KIND, SLEEP_END)
            is SleepTimer.At -> {
                b.putString(ARG_SLEEP_KIND, SLEEP_AT)
                b.putLong(ARG_SLEEP_ENDS_AT, timer.endsAtElapsedMs)
                b.putLong(ARG_SLEEP_TOTAL, timer.totalMs)
            }
        }
    }

    fun getSleep(b: Bundle?): SleepTimer = when (b?.getString(ARG_SLEEP_KIND)) {
        SLEEP_END -> SleepTimer.EndOfTrack
        SLEEP_AT -> SleepTimer.At(b.getLong(ARG_SLEEP_ENDS_AT), b.getLong(ARG_SLEEP_TOTAL))
        else -> SleepTimer.Off
    }
}
