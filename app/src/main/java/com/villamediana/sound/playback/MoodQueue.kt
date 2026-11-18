package com.villamediana.sound.playback

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.smart.MoodLean
import com.villamediana.sound.smart.QueueMood

/**
 * The queue after the listener edited it by hand, following the mood those edits show
 * ([QueueMood]). Only the app's own picks move: the songs it added to keep the queue going and, in
 * a radio, the radio's songs — never one the listener chose or placed. Among the picks, what the
 * mood rules out leaves, what is like the songs pulled up comes first, what is like the ones taken
 * out goes last, and new songs in the mood come in. Pure.
 */
internal object MoodQueue {
    /** New songs a re-made queue gets at most. */
    const val MAX_NEW = 10

    /** New songs that come with a song pulled up, besides one for each pick taken out. */
    const val FOR_A_RAISE = 3

    /** One song of what comes next. [pick] = the app chose it and the listener didn't touch it. */
    class Entry<T>(val value: T, val track: Track, val pick: Boolean)

    /** True for a song the app may move: its own pick (every song of a radio is one), not placed by hand. */
    fun isPick(track: Track, suggested: Boolean, source: PlaySource?, mood: QueueMood): Boolean =
        (suggested || source?.type == PlaySource.Type.RADIO) && QueueMood.keyOf(track) !in mood.placed

    /** How many new songs to ask for: one for each pick taken out or ruled out, a few for a song pulled up. */
    fun wanted(mood: QueueMood, answered: Int, ruledOut: Int): Int {
        val raised = if (mood.raisedSince(answered) > 0) FOR_A_RAISE else 0
        return (mood.picksRemovedSince(answered) + ruledOut + raised).coerceAtMost(MAX_NEW)
    }

    /**
     * What comes next, re-made: the listener's songs exactly where they are, the picks ([upcoming]'s
     * and the [fresh] ones) in the mood's order in the places the picks had, the rest at the end.
     */
    fun <T> remake(upcoming: List<Entry<T>>, fresh: List<Entry<T>>, lean: MoodLean): List<Entry<T>> {
        val picks = lean.arrange(upcoming.filter { it.pick } + fresh) { it.track }.iterator()
        val out = ArrayList<Entry<T>>(upcoming.size + fresh.size)
        for (e in upcoming) {
            if (!e.pick) out += e else if (picks.hasNext()) out += picks.next()
        }
        picks.forEachRemaining { out += it }
        return out
    }

    /** Where two lists stop being the same, or -1 when they are (by the identity of what they carry). */
    fun <T> firstChange(before: List<Entry<T>>, after: List<Entry<T>>): Int {
        val n = minOf(before.size, after.size)
        for (i in 0 until n) if (before[i].value !== after[i].value) return i
        return if (before.size == after.size) -1 else n
    }
}
