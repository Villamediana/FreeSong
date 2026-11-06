package com.villamediana.surco.playback

/**
 * Counts how long the current song was really heard (wall time while playing — seeking ahead does
 * not count as listening, pauses don't either). When the song ends, changes or playback stops,
 * [finish] hands back one [Session] for the listening stats.
 */
class ListenTracker(private val clock: () -> Long) {
    data class Session(val trackId: Long, val listenedMs: Long, val durationMs: Long, val context: String?)

    private var trackId: Long? = null
    private var durationMs: Long = 0
    private var context: String? = null
    private var accumulated: Long = 0
    private var playingSince: Long? = null

    val currentTrackId: Long? get() = trackId

    /** Milliseconds heard so far of the current song. */
    val listenedMs: Long
        get() = accumulated + (playingSince?.let { (clock() - it).coerceAtLeast(0) } ?: 0)

    /** Starts counting a new song (call [finish] first for the previous one). */
    fun start(trackId: Long, durationMs: Long, context: String?, playing: Boolean) {
        this.trackId = trackId
        this.durationMs = durationMs.coerceAtLeast(0)
        this.context = context
        accumulated = 0
        playingSince = if (playing) clock() else null
    }

    fun setPlaying(playing: Boolean) {
        if (trackId == null) return
        val since = playingSince
        if (playing && since == null) playingSince = clock()
        if (!playing && since != null) {
            accumulated += (clock() - since).coerceAtLeast(0)
            playingSince = null
        }
    }

    /** The player learned the real duration (the library value may be missing or rounded). */
    fun updateDuration(durationMs: Long) {
        if (trackId != null && durationMs > 0) this.durationMs = durationMs
    }

    fun updateContext(context: String?) {
        if (trackId != null) this.context = context
    }

    /** Ends the current session; null when nothing was being tracked. Listened time never exceeds the duration. */
    fun finish(): Session? {
        val id = trackId ?: return null
        var listened = listenedMs
        if (durationMs > 0) listened = listened.coerceAtMost(durationMs)
        val session = Session(id, listened, durationMs, context)
        trackId = null
        accumulated = 0
        playingSince = null
        durationMs = 0
        context = null
        return session
    }
}
