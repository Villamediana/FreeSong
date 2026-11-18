package com.villamediana.sound.playback

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.smart.MoodLean
import com.villamediana.sound.smart.QueueMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The queue re-made after the listener edited it: only the app's picks move. */
class MoodQueueTest {
    private val now = 1_800_000_000_000L
    private fun track(video: String, artist: String) =
        Track(id = -(video.hashCode().toLong().and(0xFFFF) + 1), title = video, artist = artist, videoId = video, filePath = "https://x/$video", durationMs = 200_000)

    private fun entry(video: String, artist: String, pick: Boolean) = MoodQueue.Entry(video, track(video, artist), pick)
    private fun ids(list: List<MoodQueue.Entry<String>>) = list.map { it.value }

    private val mood = QueueMood()
        .raised(track("up", "Quiet"), now)
        .removed(track("gone1", "Loud"), pick = true, now = now)
        .removed(track("gone2", "Loud"), pick = true, now = now)
    private val lean = MoodLean(mood)

    @Test fun theListenersSongsStayWhereTheyAreAndThePicksFollowTheMood() {
        val upcoming = listOf(
            entry("up", "Quiet", pick = false),      // pulled up by hand
            entry("mine", "Loud", pick = false),      // the listener's own, even by the artist taken out
            entry("p1", "Other", pick = true),
            entry("p2", "Loud", pick = true),         // ruled out: the artist was taken out twice
            entry("p3", "Quiet", pick = true),        // like what was pulled up
            entry("p4", "Else", pick = true),
        )
        val fresh = listOf(entry("new1", "Quiet", pick = true), entry("new2", "Someone", pick = true))
        val after = MoodQueue.remake(upcoming, fresh, lean)
        assertEquals(listOf("up", "mine", "p3", "new1", "p1", "p4", "new2"), ids(after))
        assertEquals(2, MoodQueue.firstChange(upcoming, after))
    }

    @Test fun nothingToMoveNothingChanges() {
        val upcoming = listOf(entry("a", "One", pick = false), entry("b", "Two", pick = true), entry("c", "Three", pick = true))
        val after = MoodQueue.remake(upcoming, emptyList(), lean)
        assertEquals(-1, MoodQueue.firstChange(upcoming, after))
        // New songs only: the change starts where the list ended.
        val more = MoodQueue.remake(upcoming, listOf(entry("n", "Four", pick = true)), lean)
        assertEquals(upcoming.size, MoodQueue.firstChange(upcoming, more))
        assertEquals(listOf("a", "b", "c", "n"), ids(more))
    }

    @Test fun onlyTheAppsOwnSongsArePicks() {
        val radio = PlaySource(PlaySource.Type.RADIO, "Rádio", "seed")
        val playlist = PlaySource(PlaySource.Type.PLAYLIST, "Minha", "7")
        val t = track("x", "A")
        assertTrue(MoodQueue.isPick(t, suggested = true, source = playlist, mood = QueueMood()))
        assertFalse(MoodQueue.isPick(t, suggested = false, source = playlist, mood = QueueMood()))
        // Every song of a radio is the app's — until the listener places it by hand.
        assertTrue(MoodQueue.isPick(t, suggested = false, source = radio, mood = QueueMood()))
        assertFalse(MoodQueue.isPick(t, suggested = false, source = radio, mood = QueueMood().raised(t, now)))
        assertFalse(MoodQueue.isPick(t, suggested = true, source = playlist, mood = QueueMood().placed(t)))
    }

    @Test fun theQueueGetsBackWhatLeftAndAFewForASongPulledUp() {
        assertEquals(2 + MoodQueue.FOR_A_RAISE, MoodQueue.wanted(mood, answered = 0, ruledOut = 0))
        assertEquals(1, MoodQueue.wanted(mood, answered = mood.rev, ruledOut = 1))
        assertEquals(0, MoodQueue.wanted(mood, answered = mood.rev, ruledOut = 0))
        assertEquals(MoodQueue.MAX_NEW, MoodQueue.wanted(mood, answered = 0, ruledOut = 40))
    }
}
