package com.villamediana.sound.social

import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.playback.FollowingPlayer
import com.villamediana.sound.playback.PlaybackGate
import com.villamediana.sound.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/** The jam session against a fake relay and a fake player (time compressed: settle 150 ms). */
class JamSessionTest {
    private val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)
    private var serverNow = 1_000_000L

    private fun song(n: Int) = SongRef(videoId = "vid${n.toString().padStart(8, '0')}", title = "Música $n", artist = "A", durationMs = 200_000)
    private val q = listOf(song(1), song(2), song(3))

    private class Sent(val baseRev: Long, val state: JamState, val withQueue: Boolean)

    private inner class FakeRelay : JamRelay {
        var current: CurrentJam = CurrentJam()
        val sent = mutableListOf<Sent>()
        var offline = false
        var left = 0
        var started: JamState? = null
        override fun currentJam() = current
        override fun startJam(invite: List<String>, state: JamState?): Jam {
            started = state
            return Jam("j1", "me", members = listOf(Profile("me", "eu", "Eu")), state = (state ?: JamState()).copy(rev = 1, updatedAt = serverNow))
        }
        override fun inviteToJam(jamId: String, userIds: List<String>) = error("unused")
        override fun joinJam(jamId: String) = Jam(jamId, "host", state = JamState(q, 1, 30_000, true, serverNow, rev = 5))
        override fun declineJam(jamId: String) = Unit
        override fun leaveJam(jamId: String) {
            left++
            if (offline) throw java.io.IOException("offline")
        }
        override fun endJam(jamId: String) {
            left++
            if (offline) throw java.io.IOException("offline")
        }
        /** The live line is down (changes go over HTTP), and the relay turns them down with [refusal]. */
        var lineDown = false
        var refusal: RelayException? = null
        override fun sendState(jamId: String, baseRev: Long, state: JamState, withQueue: Boolean): Boolean {
            synchronized(sent) { sent += Sent(baseRev, state, withQueue) }
            return !lineDown
        }
        override fun postState(jamId: String, baseRev: Long, state: JamState, withQueue: Boolean): Jam = throw refusal ?: error("unused")
        override fun serverNow() = serverNow
    }

    /** Records the jam's steps and plays them out on [playback], like the real player would. */
    private inner class FakePlayer(val playback: MutableStateFlow<PlaybackState>, val position: MutableStateFlow<Long>) : FollowingPlayer {
        val steps = mutableListOf<String>()
        override fun followLoad(tracks: List<Track>, index: Int, positionMs: Long, play: Boolean) {
            synchronized(steps) { steps += "load ${tracks.size}@$index:$positionMs:$play" }
            playback.value = PlaybackState(current = tracks[index], isPlaying = play, queue = tracks, queueIndex = index)
            position.value = positionMs
        }
        override fun followSkip(index: Int, positionMs: Long, play: Boolean) {
            synchronized(steps) { steps += "skip $index:$positionMs:$play" }
            val st = playback.value
            playback.value = st.copy(current = st.queue[index], queueIndex = index, isPlaying = play)
            position.value = positionMs
        }
        override fun followSeek(positionMs: Long) {
            synchronized(steps) { steps += "seek $positionMs" }
            position.value = positionMs
        }
        override fun followPlay() {
            synchronized(steps) { steps += "play" }
            playback.value = playback.value.copy(isPlaying = true)
        }
        override fun followPause() {
            synchronized(steps) { steps += "pause" }
            playback.value = playback.value.copy(isPlaying = false)
        }
        override fun livePositionMs() = position.value
    }

    private val playback = MutableStateFlow(PlaybackState())
    private val position = MutableStateFlow(0L)
    private val relay = FakeRelay()
    private val player = FakePlayer(playback, position)
    private var gate: PlaybackGate? = null

    private val session = JamSession(
        scope = scope,
        relay = relay,
        playerOf = { player },
        playbackOf = { playback },
        positionOf = { position },
        me = { "me" },
        related = { listOf(song(7), song(8)) },
        setGate = { gate = it },
        main = thread,
        settleMs = 150,
        retryMs = 100,
        tickMs = 50,
    )

    @After
    fun tearDown() {
        scope.cancel()
        thread.close()
    }

    private fun waitFor(ms: Long = 3_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(10)
        }
    }

    private fun tracks(list: List<SongRef>): List<Track> = list.map { it.toRemote().asTransientTrack() }

    @Test
    fun joiningLoadsTheJamsQueueAtItsSongAndPlace() {
        runBlocking { session.join(JamInvite(Jam("j9", "host"), Profile("host", "h", "Host"))) }
        assertNotNull(gate)
        waitFor { player.steps.any { it.startsWith("load") } }
        assertEquals("load 3@1:30000:true", player.steps.first())
    }

    @Test
    fun startingTakesWhatThisPhonePlaysNow() {
        playback.value = PlaybackState(current = tracks(q)[1], isPlaying = true, queue = tracks(q), queueIndex = 1)
        position.value = 42_000
        runBlocking { session.start(listOf("friend")) }
        val s = relay.started!!
        assertEquals(q.map { it.videoId }, s.queue.map { it.videoId })
        assertEquals(1, s.index)
        assertTrue(s.playing)
        // From where the song is now (not from the start).
        assertEquals(42_000, s.positionMs)
        assertTrue(session.active)
    }

    @Test
    fun startingFromASongTheOthersCantPlayBeginsAtTheTop() {
        val local = Track(id = 9, title = "Sem YouTube", artist = "A", filePath = "/x.m4a")
        playback.value = PlaybackState(current = local, isPlaying = true, queue = listOf(local) + tracks(q), queueIndex = 0)
        position.value = 42_000
        runBlocking { session.start(emptyList()) }
        assertEquals(0, relay.started!!.index)
        assertEquals(0, relay.started!!.positionMs)
        assertEquals(3, relay.started!!.queue.size)
    }

    @Test
    fun playFromTheEarphonesAfterALongPauseDoesNotPullTheJamBack() {
        runBlocking { session.join(JamInvite(Jam("j9", "host"), Profile("host", "h", "Host"))) }
        waitFor { playback.value.isPlaying && playback.value.queueIndex == 1 }
        Thread.sleep(250)
        playback.value = playback.value.copy(isPlaying = false)
        waitFor { session.held.value }
        // Minutes later (here: past the sampling gap), play from the earphones: no seek for everyone.
        Thread.sleep(3_400)
        val before = relay.sent.size
        playback.value = playback.value.copy(isPlaying = true)
        position.value = 30_250
        Thread.sleep(300)
        position.value = 30_500
        Thread.sleep(300)
        assertEquals(before, relay.sent.size)
        assertFalse(session.held.value)
    }

    @Test
    fun leavingOfflineStillLeavesHere() {
        runBlocking { session.join(JamInvite(Jam("j9", "host"), Profile("host", "h", "Host"))) }
        relay.offline = true
        val result = runBlocking { session.leave() }
        assertTrue(result.isFailure)
        assertNull(session.jam.value)
        assertNull(gate)
        assertEquals(1, relay.left)
    }

    @Test
    fun theAppsCommandsGoToTheJamForEveryone() {
        runBlocking { session.join(JamInvite(Jam("j9", "host"), Profile("host", "h", "Host"))) }
        waitFor { playback.value.queueIndex == 1 }
        assertTrue(gate!!.next())
        waitFor { relay.sent.isNotEmpty() }
        val first = relay.sent.first()
        assertEquals(5, first.baseRev)
        assertEquals(2, first.state.index)
        assertFalse(first.withQueue)
        // Followed right away (optimistic): this phone moves to the next song.
        waitFor { playback.value.queueIndex == 2 }
        // A new list from anywhere replaces the queue for all.
        assertTrue(gate!!.play(tracks(listOf(song(5), song(6))), 1))
        waitFor { relay.sent.size >= 2 }
        assertTrue(relay.sent[1].withQueue)
        assertEquals(listOf(song(5).videoId, song(6).videoId), relay.sent[1].state.queue.map { it.videoId })
        // Repeat would take this phone away from the others.
        assertTrue(gate!!.cycleRepeat())
    }

    @Test
    fun aPauseFromOutsideTheAppStaysHereAndPlayRejoins() {
        runBlocking { session.join(JamInvite(Jam("j9", "host"), Profile("host", "h", "Host"))) }
        waitFor { playback.value.isPlaying && playback.value.queueIndex == 1 }
        Thread.sleep(250) // settled
        val before = relay.sent.size
        // A call / the earphones pause this phone only.
        playback.value = playback.value.copy(isPlaying = false)
        waitFor { session.held.value }
        Thread.sleep(200)
        assertEquals(before, relay.sent.size)
        assertFalse(playback.value.isPlaying)
        // Play in the app: back to the jam (nobody else is paused or resumed).
        assertTrue(gate!!.togglePlayPause())
        waitFor { playback.value.isPlaying }
        assertFalse(session.held.value)
        assertEquals(before, relay.sent.size)
    }

    @Test
    fun aChangeTheRelayTurnsDownGoesBackToTheJamAsItIs() {
        runBlocking { session.join(JamInvite(Jam("j9", "host"), Profile("host", "h", "Host"))) }
        waitFor { playback.value.isPlaying && playback.value.queueIndex == 1 }
        Thread.sleep(250)
        // The line is down and the relay says "too many changes" over HTTP: the jam goes on playing.
        relay.current = CurrentJam(jam = Jam("j9", "host", state = JamState(q, 1, 30_000, true, serverNow, rev = 5)))
        relay.lineDown = true
        relay.refusal = RelayException(429, "slow_down")
        assertTrue(gate!!.togglePlayPause())
        waitFor { relay.sent.isNotEmpty() }
        // Not a pause only this phone hears: back to the relay's jam, playing.
        waitFor { session.jam.value!!.state.playing && playback.value.isPlaying }
        assertEquals(5, session.jam.value!!.state.rev)
    }

    @Test
    fun theSongEndingHereMovesTheJamOn() {
        runBlocking { session.join(JamInvite(Jam("j9", "host"), Profile("host", "h", "Host"))) }
        waitFor { playback.value.queueIndex == 1 }
        Thread.sleep(250)
        val st = playback.value
        playback.value = st.copy(current = st.queue[2], queueIndex = 2)
        waitFor { relay.sent.any { it.state.index == 2 } }
    }

    @Test
    fun theOneWhoStartedItKeepsTheQueueFromRunningOut() {
        runBlocking { session.start(emptyList()) }
        session.onEvent(RelayEvent.JamChanged(Jam("j1", "me", state = JamState(q, 2, 0, true, serverNow, rev = 3)), null))
        waitFor { relay.sent.any { it.withQueue && it.state.queue.size == 5 } }
        assertEquals(listOf(song(7).videoId, song(8).videoId), relay.sent.first { it.withQueue }.state.queue.drop(3).map { it.videoId })
    }

    @Test
    fun whenTheJamEndsThePlayerIsFreeAgain() {
        runBlocking { session.join(JamInvite(Jam("j9", "host"), Profile("host", "h", "Host"))) }
        session.onEvent(RelayEvent.JamEnded("j9"))
        waitFor { session.jam.value == null }
        assertNull(gate)
    }

    @Test
    fun backAfterALaunchTheJamIsPausedHereUntilPlay() {
        relay.current = CurrentJam(jam = Jam("j9", "host", state = JamState(q, 0, 0, true, serverNow, rev = 2)))
        runBlocking { session.refresh() }
        assertTrue(session.held.value)
        waitFor { player.steps.any { it.startsWith("load") } }
        assertEquals("load 3@0:0:false", player.steps.first())
    }

    @Test
    fun invitesComeAndGo() {
        val inv = JamInvite(Jam("j5", "x"), Profile("x", "x", "X"))
        session.onEvent(RelayEvent.JamInvited(inv))
        session.onEvent(RelayEvent.JamInvited(inv))
        waitFor { session.invites.value.isNotEmpty() }
        Thread.sleep(100)
        assertEquals(1, session.invites.value.size)
        session.onEvent(RelayEvent.JamEnded("j5"))
        waitFor { session.invites.value.isEmpty() }
    }
}
