package com.villamediana.sound.smart

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.discover.SongRadio
import com.villamediana.sound.online.RemoteTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The mood of a queue, read from what the listener did to it by hand. */
class QueueMoodTest {
    private val now = 1_800_000_000_000L
    private fun track(video: String, artist: String, genre: String? = null, id: Long = -(video.hashCode().toLong().and(0xFFFF) + 1)) =
        Track(id = id, title = "Song $video", artist = artist, genre = genre, videoId = video, filePath = "https://x/$video", durationMs = 200_000)

    private fun song(id: String, artist: String) = RemoteTrack(id, "https://music.youtube.com/watch?v=$id", "Song $id", artist, durationMs = 200_000)

    @Test fun takingOutSaysNotNowPullingUpSaysMoreOfThis() {
        var mood = QueueMood()
        assertTrue(mood.isEmpty)
        mood = mood.removed(track("r1", "Loud Band", "Rock"), pick = true, now = now)
        mood = mood.raised(track("b1", "Quiet Singer", "MPB"), now)
        assertEquals(listOf("b1"), mood.wanted)
        assertEquals(listOf("r1"), mood.unwanted)
        assertTrue(mood.artist("Quiet Singer") > 0)
        assertTrue(mood.artist("Loud Band feat. Someone") < 0)
        assertEquals(0.0, mood.artist("Nobody"), 1e-9)
        assertTrue(mood.genre("mpb") > 0)
        assertTrue(mood.genre("Rock") < 0)
        // A genre both pulled up and taken out says nothing: the whole queue may be of it.
        assertEquals(0.0, mood.removed(track("m2", "Other", "MPB"), pick = true, now = now).genre("MPB"), 1e-9)
        // Once is "not this one now", twice is "none of them for now".
        assertFalse(mood.out("Loud Band"))
        mood = mood.removed(track("r2", "Loud Band"), pick = false, now = now)
        assertTrue(mood.out("Loud Band"))
        // …unless one of theirs was pulled up: the artist is fine, those songs weren't.
        mood = mood.raised(track("r3", "Loud Band"), now)
        assertFalse(mood.out("Loud Band"))
    }

    @Test fun aSongPulledUpBelongsToTheListenerAndPushingItBackTakesTheHintAway() {
        val t = track("b1", "Quiet Singer")
        var mood = QueueMood().raised(t, now)
        assertTrue(QueueMood.keyOf(t) in mood.placed)
        val rev = mood.rev
        mood = mood.lowered(t)
        assertTrue(mood.isEmpty)
        assertTrue("still where the listener put it", QueueMood.keyOf(t) in mood.placed)
        assertTrue(mood.rev > rev)
        // Put in the queue by hand: theirs, and it says nothing about the mood.
        val added = QueueMood().placed(t)
        assertTrue(added.isEmpty)
        assertEquals(0, added.rev)
        assertTrue(QueueMood.keyOf(t) in added.placed)
    }

    @Test fun itLastsTheseHoursAndCountsWhatTheQueueIsOwed() {
        var mood = QueueMood().removed(track("old", "A"), pick = true, now = now - QueueMood.LASTS_MS - 1)
        val answered = mood.rev
        mood = mood.removed(track("p1", "B"), pick = true, now = now)
            .removed(track("mine", "C"), pick = false, now = now)
            .raised(track("up", "D"), now)
        assertEquals(1, mood.picksRemovedSince(answered))
        assertEquals(1, mood.raisedSince(answered))
        assertEquals(0, mood.raisedSince(mood.rev))
        val fresh = mood.fresh(now)
        assertEquals(listOf("mine", "p1"), fresh.unwanted)
        assertEquals(mood.rev, fresh.rev)
        assertTrue(mood.fresh(now + QueueMood.LASTS_MS + 1).isEmpty)
    }

    @Test fun theLeanFollowsTheRadiosOfWhatWasPulledUpAndTakenOut() {
        val mood = QueueMood()
            .raised(track("up", "Quiet Singer"), now)
            .removed(track("out", "Loud Band"), pick = true, now = now)
        val toward = listOf(listOf(song("t1", "Soft One"), song("t2", "Soft Two"), song("both", "Either")) + (3..20).map { song("t$it", "Far $it") })
        val away = listOf(listOf(song("a1", "Hard One"), song("both", "Either"), song("a2", "Hard Two")) + (3..20).map { song("a$it", "Off $it") })
        val lean = MoodLean(mood, toward, away)
        assertTrue(lean.of("t1", "Soft One") > MoodLean.CLEAR)
        assertTrue(lean.of("a1", "Hard One") < -MoodLean.CLEAR)
        // What both sides know follows the song pulled up.
        assertTrue(lean.of("both", "Either") > 0)
        // An artist that keeps the same company, whatever the song.
        assertTrue(lean.of("unseen", "Soft Two") > 0)
        assertTrue(lean.of("unseen", "Hard Two") < 0)
        assertEquals(0.0, lean.of("nothing", "Nobody"), 1e-9)
        assertTrue(lean.rulesOut("out", "Whoever"))
        assertFalse(lean.rulesOut("a1", "Loud Band"))

        // Arranged: toward first, away last, each side in the order it came; what is ruled out gone.
        val queue = listOf(track("a1", "Hard One"), track("n1", "Nobody"), track("t2", "Soft Two"), track("out", "Loud Band"), track("n2", "No one"), track("t1", "Soft One"))
        assertEquals(listOf("t2", "t1", "n1", "n2", "a1"), lean.arrange(queue) { it }.map { it.videoId })
        // No mood, no change.
        assertEquals(queue, MoodLean(QueueMood()).arrange(queue) { it })
    }

    @Test fun theRadioGoesTheWayTheQueueWasEdited() = runBlocking {
        // Two neighbourhoods: the calm songs' radios are calm, the loud ones' loud; the song playing sits between.
        fun upNext(of: String, size: Int): List<RemoteTrack> = when {
            of.startsWith("calm") -> (1..size).map { song("calm-$of-$it", "Calm ${it % 6}") }
            of.startsWith("loud") -> (1..size).map { song("loud-$of-$it", "Loud ${it % 6}") }
            else -> (1..size).map { if (it % 2 == 0) song("calm-mix-$it", "Calm ${it % 6}") else song("loud-mix-$it", "Loud ${it % 6}") }
        }
        val radio = SongRadio(related = { id, size -> upNext(id, size) }, profile = { null }, session = { Session() })
        fun calmShare(list: List<RemoteTrack>) = list.count { it.videoId.startsWith("calm") }.toDouble() / list.size

        val plain = (1..8).map { calmShare(radio.songs(listOf("now"), emptySet(), 12, Random(it))) }.average()
        val mood = QueueMood()
            .raised(track("calm-up", "Calm 1"), now)
            .removed(track("loud-out", "Loud 1"), pick = true, now = now)
            .removed(track("loud-out-2", "Loud 1"), pick = true, now = now)
        val tuned = (1..8).map { radio.inMood(listOf("now"), mood, emptySet(), 12, Random(it)) }
        val inMood = tuned.map { calmShare(it.songs) }.average()
        assertTrue("plain $plain in mood $inMood", plain in 0.3..0.7)
        assertTrue("plain $plain in mood $inMood", inMood > 0.85)
        tuned.forEach { t ->
            assertTrue(t.songs.none { it.videoId == "loud-out" || it.videoId == "calm-up" })
            // The artist taken out twice doesn't come.
            assertTrue(t.songs.none { it.artist == "Loud 1" })
            assertTrue(t.lean.of("calm-calm-up-1", "Calm 1") > 0)
        }
        // Without a mood it is the radio it always was.
        assertEquals(radio.songs(listOf("now"), emptySet(), 12, Random(3)), radio.inMood(listOf("now"), QueueMood(), emptySet(), 12, Random(3)).songs)
    }
}
