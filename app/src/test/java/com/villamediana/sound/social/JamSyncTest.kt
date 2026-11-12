package com.villamediana.sound.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JamSyncTest {
    private fun song(n: Int, ms: Long? = 200_000) = SongRef(videoId = "vid${n.toString().padStart(8, '0')}", title = "Música $n", artist = "A", durationMs = ms)
    private val q = listOf(song(1), song(2), song(3))
    private fun ids(list: List<SongRef>) = list.map { it.videoId }

    private fun jam(index: Int = 0, pos: Long = 0, playing: Boolean = true, at: Long = 1_000) =
        JamState(queue = q, index = index, positionMs = pos, playing = playing, updatedAt = at, rev = 7)

    private fun local(index: Int = 0, playing: Boolean = true, buffering: Boolean = false, pos: Long = 0, queue: List<SongRef> = q) =
        LocalPlay(ids(queue), index, playing, buffering, pos)

    @Test
    fun theJamsPositionMovesWithTheClockOnlyWhilePlaying() {
        assertEquals(15_000, jam(pos = 10_000, at = 1_000).positionAt(6_000))
        assertEquals(10_000, jam(pos = 10_000, playing = false, at = 1_000).positionAt(60_000))
        // Never past the song's end, never back in time.
        assertEquals(200_000, jam(pos = 190_000, at = 0).positionAt(60_000))
        assertEquals(10_000, jam(pos = 10_000, at = 5_000).positionAt(1_000))
    }

    @Test
    fun anotherQueueLoadsTheJamsAtItsSongAndPlace() {
        val step = JamSync.follow(jam(index = 1, pos = 30_000, at = 0), local(queue = listOf(song(9))), serverNow = 4_000, held = false)
        assertEquals(JamStep.Load(q, 1, 34_000, true), step)
    }

    @Test
    fun sameQueueOtherSongSkips() {
        assertEquals(JamStep.Skip(2, 0, true), JamSync.follow(jam(index = 2, at = 0), local(index = 0), 0, held = false))
    }

    @Test
    fun playAndPauseFollowTheJam() {
        assertEquals(JamStep.Pause, JamSync.follow(jam(playing = false), local(playing = true), 1_000, held = false))
        assertEquals(JamStep.Play, JamSync.follow(jam(playing = true), local(playing = false), 1_000, held = false))
        // Loading the stream is not paused.
        assertEquals(JamStep.None, JamSync.follow(jam(playing = true), local(playing = false, buffering = true), 1_000, held = false))
    }

    @Test
    fun heldHereStaysPausedButOnTheJamsSong() {
        assertEquals(JamStep.None, JamSync.follow(jam(playing = true), local(playing = false), 1_000, held = true))
        assertEquals(JamStep.Skip(1, 0, false), JamSync.follow(jam(index = 1, at = 1_000), local(index = 0, playing = false), 1_000, held = true))
    }

    @Test
    fun farFromTheJamsPlaceSeeksCloseEnoughLeavesItAlone() {
        assertEquals(JamStep.None, JamSync.follow(jam(pos = 10_000, at = 0), local(pos = 11_500), 0, held = false))
        assertEquals(JamStep.Seek(10_000), JamSync.follow(jam(pos = 10_000, at = 0), local(pos = 13_000), 0, held = false))
        // While loading, the position isn't judged.
        assertEquals(JamStep.None, JamSync.follow(jam(pos = 10_000, at = 0), local(pos = 0, buffering = true, playing = false), 0, held = false))
    }

    @Test
    fun anEmptyJamStopsThisPhone() {
        val empty = JamState()
        assertEquals(JamStep.Pause, JamSync.follow(empty, local(), 0, held = false))
        assertEquals(JamStep.None, JamSync.follow(empty, local(playing = false), 0, held = false))
    }

    @Test
    fun commandsBecomeTheJamsNewState() {
        val s = jam(index = 1, pos = 20_000, at = 0)
        assertEquals(false, JamSync.togglePlay(s, 5_000).playing)
        assertEquals(25_000, JamSync.togglePlay(s, 5_000).positionMs)
        assertEquals(2, JamSync.next(s)!!.index)
        assertNull(JamSync.next(jam(index = 2)))
        // "Previous": restarts past 3 s, goes back before.
        assertEquals(1, JamSync.previous(s, 0).index)
        assertEquals(0, JamSync.previous(s, 0).positionMs)
        assertEquals(0, JamSync.previous(jam(index = 1, pos = 1_000, at = 0), 0).index)
        assertEquals(45_000, JamSync.seek(s, 45_000).positionMs)
        assertEquals(0, JamSync.skipTo(s, 0)!!.index)
        assertNull(JamSync.skipTo(s, 9))
    }

    @Test
    fun playingAListAnywhereReplacesTheJamsQueue() {
        val p = JamSync.play(listOf(song(5), song(6), song(5)), 1)!!
        assertEquals(ids(listOf(song(5), song(6))), ids(p.queue))
        assertEquals(1, p.index)
        assertTrue(p.playing)
        assertNull(JamSync.play(emptyList(), 0))
    }

    @Test
    fun queueEditsKeepTheSongPlaying() {
        val s = jam(index = 1)
        assertEquals(ids(listOf(song(1), song(2), song(8), song(3))), ids(JamSync.enqueue(s, listOf(song(8)), next = true, serverNow = 0)!!.queue))
        assertEquals(ids(q + song(8)), ids(JamSync.enqueue(s, listOf(song(8)), next = false, serverNow = 0)!!.queue))
        val removed = JamSync.remove(s, 0, 0)!!
        assertEquals(ids(listOf(song(2), song(3))), ids(removed.queue))
        assertEquals(0, removed.index)
        assertNull(JamSync.remove(s, 1, 0)) // the one playing
        val moved = JamSync.move(s, 2, 0, 0)!!
        assertEquals(ids(listOf(song(3), song(1), song(2))), ids(moved.queue))
        assertEquals(song(2).videoId, moved.current!!.videoId)
    }

    @Test
    fun queueEditsCarryWhereTheSongIsNotWhereItStarted() {
        // Playing since "next" (place 0) at 1 s; 40 s later someone edits the queue: the change goes
        // out stamped "now", so it must say 40 s — 0 would send everyone back to the song's start.
        val s = jam(index = 1, pos = 0, at = 1_000)
        val now = 41_000L
        listOf(
            JamSync.enqueue(s, listOf(song(8)), next = true, serverNow = now)!!,
            JamSync.enqueue(s, listOf(song(8)), next = false, serverNow = now)!!,
            JamSync.remove(s, 0, now)!!,
            JamSync.move(s, 2, 0, now)!!,
            JamSync.shuffleRest(s, 42, now),
            JamSync.extend(s, listOf(song(7)), now)!!,
        ).forEach { edited ->
            assertEquals(40_000, edited.positionMs)
            assertEquals(song(2).videoId, edited.current!!.videoId)
            assertTrue(edited.playing)
            // Stamped "now" (as propose and the relay do), it is still where the song is.
            assertEquals(40_000, edited.copy(updatedAt = now).positionAt(now))
            assertEquals(JamStep.None, JamSync.follow(edited.copy(updatedAt = now), local(index = edited.index, pos = 40_000, queue = edited.queue), now, held = false))
        }
        // Paused: it stays where it was paused.
        assertEquals(15_000, JamSync.enqueue(jam(index = 1, pos = 15_000, playing = false, at = 0), listOf(song(8)), next = true, serverNow = 90_000)!!.positionMs)
    }

    @Test
    fun movingAroundASongThatIsInTheQueueTwiceKeepsTheOnePlaying() {
        // 1, 2, [2 again — playing], 3: moving the 3 to the top must not send everyone to the first 2.
        val s = JamState(queue = listOf(song(1), song(2), song(2), song(3)), index = 2)
        val moved = JamSync.move(s, 3, 0, 0)!!
        assertEquals(ids(listOf(song(3), song(1), song(2), song(2))), ids(moved.queue))
        assertEquals(3, moved.index)
        // The playing one itself, moved: it goes where it was dropped.
        assertEquals(0, JamSync.move(s, 2, 0, 0)!!.index)
        // From after it to before it, and from before it to after it.
        assertEquals(3, JamSync.move(s, 3, 1, 0)!!.index)
        assertEquals(1, JamSync.move(s, 0, 3, 0)!!.index)
        // Both on the same side: it stays.
        assertEquals(2, JamSync.move(s, 0, 1, 0)!!.index)
    }

    @Test
    fun shuffleOnlyMixesWhatComesAfter() {
        val long = JamState(queue = (1..12).map { song(it) }, index = 3)
        val mixed = JamSync.shuffleRest(long, 42, 0)
        assertEquals(ids(long.queue.take(4)), ids(mixed.queue.take(4)))
        assertEquals(ids(long.queue).toSet(), ids(mixed.queue).toSet())
    }

    @Test
    fun theEndOfTheQueueGrowsWithoutRepeats() {
        assertTrue(JamSync.nearEnd(jam(index = 1)))
        assertFalse(JamSync.nearEnd(JamState(queue = (1..5).map { song(it) }, index = 1)))
        val grown = JamSync.extend(jam(), listOf(song(2), song(7), song(7), song(8)), 0)!!
        assertEquals(ids(q + song(7) + song(8)), ids(grown.queue))
        assertNull(JamSync.extend(jam(), listOf(song(1)), 0))
    }

    @Test
    fun theSongEndingHereMovesTheJamOnLagDoesNot() {
        val moved = JamSync.moved(jam(index = 0), local(index = 1, pos = 400))!!
        assertEquals(1, moved.index)
        assertEquals(400, moved.positionMs)
        assertNull(JamSync.moved(jam(index = 0), local(index = 0)))
        assertNull(JamSync.moved(jam(index = 0), local(index = 1, queue = listOf(song(1), song(9)))))
    }

    @Test
    fun aSongFarDownALongListIsTheOneThatPlays() {
        val list = (1..500).map { song(it) }
        val p = JamSync.play(list, 349)!!
        assertEquals(song(350).videoId, p.current!!.videoId)
        assertTrue(p.queue.size <= JamSync.MAX_QUEUE)
        // Mostly what comes after it.
        assertTrue(p.queue.size - p.index > p.index)
    }

    @Test
    fun aQueueIsCutToWhatOneMessageCarriesAroundTheSongPlaying() {
        val list = (1..300).map { song(it) }
        val size = { _: SongRef -> 300 }
        val (queue, index) = JamSync.fit(list, 120, size, budget = 300 * 40)
        assertEquals(40, queue.size)
        assertEquals(song(121).videoId, queue[index].videoId)
        // The songs after it first: here all 39 fit after.
        assertEquals(0, index)
        // Near the end, the room left goes to the ones before it.
        val (tail, at) = JamSync.fit(list, 295, size, budget = 300 * 40)
        assertEquals(40, tail.size)
        assertEquals(song(296).videoId, tail[at].videoId)
        assertEquals(listOf(song(300).videoId), listOf(tail.last().videoId))
        // A queue that already fits stays as it is.
        val small = list.take(10)
        assertEquals(small to 3, JamSync.fit(small, 3, size))
    }

    @Test
    fun aSongTravelsWithoutWhatTheOthersWorkOut() {
        val s = SongRef("abcdefghijk", "T", "A", cover = SongRef.thumbnailOf("abcdefghijk"), url = com.villamediana.sound.online.YouTubeLinks.watchUrl("abcdefghijk", music = true))
        val c = s.compact()
        assertNull(c.cover)
        assertNull(c.url)
        // The other phone gets the same picture and link back.
        assertEquals(s.toRemote(), c.toRemote())
        val own = s.copy(cover = "https://lh3.googleusercontent.com/x=w544-h544")
        assertEquals(own.cover, own.compact().cover)
    }

    @Test
    fun aJamGoesOnWithItsRadioWhatYouHeardTogetherAndTheFriendsFavourites() {
        val radio = (1..10).map { song(it) }
        val together = listOf(song(50), song(51), song(3))
        val friends = listOf(song(70), song(50))
        val out = JamSync.weave(radio, together, friends, count = 10)
        assertEquals(10, out.size)
        assertEquals(out.size, out.map { it.videoId }.toSet().size)
        // Every third: a song you've heard together; every fifth: a friend's favourite.
        assertEquals(song(50).videoId, out[2].videoId)
        assertEquals(song(70).videoId, out[4].videoId)
        assertTrue(out.any { it.videoId == song(51).videoId })
        // Without any of those, the radio alone.
        assertEquals(ids(radio.take(4)), ids(JamSync.weave(radio, emptyList(), emptyList(), count = 4)))
    }
}
