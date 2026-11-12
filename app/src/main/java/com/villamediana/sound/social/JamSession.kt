package com.villamediana.sound.social

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.playback.FollowingPlayer
import com.villamediana.sound.playback.PlaybackGate
import com.villamediana.sound.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** What the jam session needs from the relay (the real one, or a fake in the tests). */
interface JamRelay {
    fun currentJam(): CurrentJam
    fun startJam(invite: List<String>, state: JamState?): Jam
    fun inviteToJam(jamId: String, userIds: List<String>): Jam
    fun joinJam(jamId: String): Jam
    fun declineJam(jamId: String)
    fun leaveJam(jamId: String)
    fun endJam(jamId: String)
    /** Over the live line when it is up; false when it isn't (then over HTTP). */
    fun sendState(jamId: String, baseRev: Long, state: JamState, withQueue: Boolean): Boolean
    fun postState(jamId: String, baseRev: Long, state: JamState, withQueue: Boolean): Jam
    fun serverNow(): Long
}

/**
 * This phone in a jam: it follows the jam (loads its queue, its song, its place on the relay's
 * clock, play/pause) and turns the app's commands into the jam's ("qualquer um controla").
 * A pause that doesn't come from the app (a call, the earphones, the timer) stays on this phone:
 * it is [held] until the person plays again.
 */
class JamSession(
    private val scope: CoroutineScope,
    private val relay: JamRelay,
    /** The player is made only when a jam needs it (not at app start). */
    playerOf: () -> FollowingPlayer,
    playbackOf: () -> StateFlow<PlaybackState>,
    positionOf: () -> Flow<Long>,
    private val me: () -> String?,
    /** Songs for the end of the queue (the radio of the last one), when this phone created the jam. */
    private val related: suspend (videoId: String) -> List<SongRef>,
    private val setGate: (PlaybackGate?) -> Unit,
    /** Where the following runs (the main thread in the app). */
    private val main: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main,
    private val settleMs: Long = SETTLE_MS,
    private val retryMs: Long = RETRY_MS,
    private val tickMs: Long = TICK_MS,
) : PlaybackGate {
    private val player by lazy(playerOf)
    private val playback by lazy(playbackOf)
    private val position by lazy(positionOf)

    private val _jam = MutableStateFlow<Jam?>(null)
    val jam: StateFlow<Jam?> = _jam

    private val _invites = MutableStateFlow<List<JamInvite>>(emptyList())
    val invites: StateFlow<List<JamInvite>> = _invites

    private val _held = MutableStateFlow(false)
    /** Paused here by other means while the jam plays on. */
    val held: StateFlow<Boolean> = _held

    val active: Boolean get() = _jam.value != null

    private var follow: Job? = null
    private var lastStep: JamStep = JamStep.None
    private var lastStepAt = 0L
    private var lastPosition = 0L
    private var lastPositionAt = 0L
    private var lastIndex = -1
    private var wasGoing = false
    private var failedVideo: String? = null
    private var extendingAt = -1
    /** When this phone last proposed a change (its own command, not yet played out here). */
    private var proposedAt = 0L
    /** The song index each side was on at the last look: what moved since, and who moved it. */
    private var seenLocalIndex = -1
    private var seenJamIndex = -1

    /** A jam this phone left whose leave the relay hasn't taken yet (offline): never followed again, sent again. */
    private class Leaving(val jamId: String, val end: Boolean)
    private var leaving: Leaving? = null
    /** Bumped by every start, join, leave and decline: a look at the relay that began before one is old news. */
    private var epoch = 0

    // ------------------------------------------------------------------ joining and leaving

    /** A jam with these friends, starting from what this phone plays now. */
    suspend fun start(friends: List<String>): Result<Jam> = runCatching {
        // Where the song is now (the jam starts from there for everyone, this phone included).
        val state = withContext(main) { fitted(currentLocalState(player.livePositionMs())) }
        val jam = withContext(Dispatchers.IO) { relay.startJam(friends, state) }
        withContext(main) {
            epoch++
            activate(jam, held = false)
        }
        jam
    }

    suspend fun invite(friends: List<String>): Result<Jam> = runCatching {
        val current = _jam.value ?: throw IOException("no jam")
        val fresh = withContext(Dispatchers.IO) { relay.inviteToJam(current.id, friends) }
        withContext(main) { if (_jam.value?.id == fresh.id) _jam.value = fresh }
        fresh
    }

    suspend fun join(invite: JamInvite): Result<Jam> = runCatching {
        val jam = withContext(Dispatchers.IO) { relay.joinJam(invite.jam.id) }
        withContext(main) {
            epoch++
            if (leaving?.jamId == jam.id) leaving = null
            _invites.value = _invites.value.filterNot { it.jam.id == invite.jam.id }
            activate(jam, held = false)
        }
        jam
    }

    suspend fun decline(invite: JamInvite) {
        withContext(main) {
            epoch++
            _invites.value = _invites.value.filterNot { it.jam.id == invite.jam.id }
        }
        runCatching { withContext(Dispatchers.IO) { relay.declineJam(invite.jam.id) } }
    }

    /** [decline] from a screen that closes right away: it goes on in the app's scope, not the screen's. */
    fun declineNow(invite: JamInvite) {
        scope.launch { decline(invite) }
    }

    /**
     * Leaves (the one who created it ends it for all). The music here goes on as it is. This phone
     * is out at once, even offline: until the relay takes the leave, the jam is never followed
     * again here and the leave goes again at the next look ([refresh]).
     */
    suspend fun leave(): Result<Unit> {
        val current = withContext(main) {
            val jam = _jam.value ?: return@withContext null
            epoch++
            leaving = Leaving(jam.id, end = jam.hostId == me())
            deactivate()
            jam
        } ?: return Result.success(Unit)
        return sendLeave(current.id)
    }

    /**
     * [leave] from a sheet that closes right away: in the app's scope, so closing the sheet doesn't
     * cancel it halfway (out here but still in the jam for everyone else).
     */
    fun leaveNow() {
        scope.launch { leave() }
    }

    private suspend fun sendLeave(jamId: String): Result<Unit> {
        val pending = withContext(main) { leaving?.takeIf { it.jamId == jamId } } ?: return Result.success(Unit)
        return runCatching {
            withContext(Dispatchers.IO) { if (pending.end) relay.endJam(jamId) else relay.leaveJam(jamId) }
        }.recoverCatching { e ->
            // The jam is already gone on the relay (ended, or let go): nothing left to leave.
            if ((e as? RelayException)?.status != 404) throw e
        }.onSuccess {
            withContext(main) { if (leaving?.jamId == jamId) leaving = null }
        }
    }

    /** The profile is gone (deleted): out of the jam here, nothing to tell the relay. */
    suspend fun forget() = withContext(main) {
        deactivate()
        _invites.value = emptyList()
    }

    /** After launching or reconnecting: the jam this phone is in (paused here until the person plays) and the invites. */
    suspend fun refresh() {
        // A leave the relay didn't get (offline) goes first.
        withContext(main) { leaving?.jamId }?.let { sendLeave(it) }
        val asked = withContext(main) { epoch }
        val current = runCatching { withContext(Dispatchers.IO) { relay.currentJam() } }.getOrNull() ?: return
        withContext(main) {
            // Joined, started, left or declined while asking: the answer is older than that.
            if (epoch != asked) return@withContext
            _invites.value = current.invites
            // The jam this phone left stays left, even if the relay didn't hear it yet.
            val jam = current.jam?.takeIf { it.id != leaving?.jamId }
            when {
                jam == null -> if (_jam.value != null) deactivate()
                _jam.value == null -> activate(jam, held = true)
                _jam.value?.id != jam.id -> activate(jam, held = true)
                else -> _jam.value = jam
            }
        }
    }

    /** The relay's news, handled on the main thread like everything that moves the player. */
    fun onEvent(event: RelayEvent) {
        scope.launch(main) { handle(event) }
    }

    private fun handle(event: RelayEvent) {
        when (event) {
            is RelayEvent.JamInvited -> _invites.value = _invites.value.filterNot { it.jam.id == event.invite.jam.id } + event.invite
            is RelayEvent.JamChanged -> {
                if (_jam.value?.id == event.jam.id) _jam.value = event.jam
                _invites.value = _invites.value.map { if (it.jam.id == event.jam.id) it.copy(jam = event.jam) else it }
            }
            is RelayEvent.JamEnded -> {
                _invites.value = _invites.value.filterNot { it.jam.id == event.jamId }
                if (_jam.value?.id == event.jamId) deactivate()
                // The relay let this phone go (or the jam is over): nothing left to leave.
                if (leaving?.jamId == event.jamId) leaving = null
            }
            is RelayEvent.Hello -> scope.launch { refresh() }
            else -> Unit
        }
    }

    private fun activate(jam: Jam, held: Boolean) {
        _jam.value = jam
        _held.value = held
        failedVideo = null
        lastStep = JamStep.None
        lastStepAt = 0
        extendingAt = -1
        proposedAt = 0
        seenLocalIndex = -1
        seenJamIndex = -1
        setGate(this)
        follow?.cancel()
        follow = scope.launch(main) {
            launch { position.collect { p -> sample(p) } }
            launch { playback.collect { evaluate() } }
            launch { _jam.collect { evaluate() } }
            while (isActive) {
                delay(tickMs)
                evaluate()
            }
        }
    }

    private fun deactivate() {
        follow?.cancel()
        follow = null
        setGate(null)
        _jam.value = null
        _held.value = false
    }

    // ------------------------------------------------------------------ following

    private fun local(): LocalPlay {
        val st = playback.value
        return LocalPlay(st.queue.map { it.videoId }, st.queueIndex, st.isPlaying, st.isBuffering, lastPosition)
    }

    /** A jump in the position that time doesn't explain, with no step of ours: a seek made outside the app. */
    private fun sample(p: Long) {
        val now = System.currentTimeMillis()
        val jam = _jam.value
        val st = playback.value
        // Only between two ticks of a song playing (a gap means paused or not watched: no jump to judge).
        val ticking = lastPositionAt > 0 && now - lastPositionAt <= SAMPLE_GAP_MS
        if (jam != null && ticking && st.queueIndex == lastIndex && st.isPlaying && now - lastStepAt > settleMs) {
            val expected = lastPosition + (now - lastPositionAt)
            if (kotlin.math.abs(p - expected) > SEEK_JUMP_MS && JamSync.sameQueue(jam.state, local()) && st.queueIndex == jam.state.index) {
                propose(JamSync.seek(jam.state, p))
            }
        }
        lastPosition = p
        lastPositionAt = now
        lastIndex = st.queueIndex
    }

    private fun evaluate() {
        val jam = _jam.value ?: return
        val st = playback.value
        val local = local()
        val now = relay.serverNow()
        val wall = System.currentTimeMillis()
        // Settled: no step of ours playing out here, no change of ours on its way.
        val settled = wall - lastStepAt > settleMs && wall - proposedAt > settleMs
        // Who moved: this phone's player on its own (the song ended, the earphones' "next"), or the
        // jam (a command here or from a friend). Only the first one is news for the jam; the second
        // is for this phone to follow — taking it for a move here pulled the jam back to the old song.
        val localMoved = seenLocalIndex >= 0 && local.index != seenLocalIndex
        val jamMoved = seenJamIndex >= 0 && jam.state.index != seenJamIndex
        seenLocalIndex = local.index
        seenJamIndex = jam.state.index

        // The jam's song doesn't load here: this phone waits (quiet) for the next one instead of retrying forever.
        val current = jam.state.current?.videoId
        if (st.error != null && current != null && st.current?.videoId == current) failedVideo = current
        if (failedVideo != null && failedVideo != current) failedVideo = null

        if (settled) {
            // Paused (or played) here by other means: stays like that on this phone only.
            if (jam.state.playing && wasGoing && !local.going && JamSync.sameQueue(jam.state, local) && failedVideo == null) _held.value = true
            if (_held.value && local.going) _held.value = false
            // The song ended (or the earphones' "next") on this phone: the jam moves on with it.
            if (localMoved && !jamMoved) JamSync.moved(jam.state, local)?.let { moved ->
                if (st.error == null) {
                    wasGoing = local.going
                    propose(moved)
                    return
                }
            }
        }
        wasGoing = local.going

        if (failedVideo == null) {
            val step = JamSync.follow(jam.state, local, now, _held.value)
            val nowMs = System.currentTimeMillis()
            if (step != JamStep.None && (step::class != lastStep::class || nowMs - lastStepAt > retryMs)) {
                lastStep = step
                lastStepAt = nowMs
                apply(step)
            }
        } else if (local.going) {
            player.followPause()
        }
        maybeExtend(jam)
    }

    private fun apply(step: JamStep) {
        when (step) {
            is JamStep.Load -> player.followLoad(step.queue.map { it.toRemote().let(com.villamediana.sound.playback.OnlineItem::of).toTrack() }, step.index, step.positionMs, step.play)
            is JamStep.Skip -> player.followSkip(step.index, step.positionMs, step.play)
            is JamStep.Seek -> player.followSeek(step.positionMs)
            JamStep.Play -> player.followPlay()
            JamStep.Pause -> player.followPause()
            JamStep.None -> Unit
        }
    }

    /**
     * The jam never runs out: the one who created it adds the radio of the last songs when one is
     * left; anyone in it does when the last one is playing (the creator's phone may be away).
     */
    private fun maybeExtend(jam: Jam) {
        // Someone else waits a while into the last song: the creator's phone usually did it already.
        val onLast = jam.state.queue.isNotEmpty() && jam.state.index >= jam.state.queue.size - 1 &&
            jam.state.positionAt(relay.serverNow()) > GUEST_EXTEND_AFTER_MS
        if ((jam.hostId != me() && !onLast) || !JamSync.nearEnd(jam.state) || extendingAt == jam.state.queue.size) return
        val last = jam.state.queue.lastOrNull()?.videoId ?: return
        extendingAt = jam.state.queue.size
        scope.launch(main) {
            val more = withContext(Dispatchers.IO) { runCatching { related(last) }.getOrDefault(emptyList()) }
            val now = _jam.value ?: return@launch
            if (now.state.queue.lastOrNull()?.videoId != last) return@launch
            JamSync.extend(now.state, more, relay.serverNow())?.let { propose(it) }
        }
    }

    // ------------------------------------------------------------------ the app's commands → the jam

    private fun propose(proposed: JamState) {
        val jam = _jam.value ?: return
        val base = jam.state
        val next = if (proposed.queue != base.queue) fitted(proposed) else proposed
        val withQueue = next.queue != base.queue
        proposedAt = System.currentTimeMillis()
        val optimistic = next.copy(rev = base.rev, updatedAt = relay.serverNow(), by = me())
        _jam.value = jam.copy(state = optimistic)
        scope.launch(Dispatchers.IO) {
            if (!relay.sendState(jam.id, base.rev, optimistic, withQueue)) {
                runCatching { relay.postState(jam.id, base.rev, optimistic, withQueue) }
                    .onSuccess { fresh -> withContext(main) { if (_jam.value?.id == fresh.id) _jam.value = fresh } }
                    // Refused (too many changes, a song it won't take) or unreachable: back to the jam
                    // as the relay has it, so this phone doesn't play a version nobody else hears.
                    .onFailure { refresh() }
            }
        }
    }

    private inline fun withJam(block: (JamState, Long) -> JamState?): Boolean {
        val jam = _jam.value ?: return false
        block(jam.state, relay.serverNow())?.let { propose(it) }
        return true
    }

    override fun togglePlayPause(): Boolean {
        if (_jam.value == null) return false
        // Held here (a call, the earphones): play just rejoins the jam where it is.
        if (_held.value) {
            _held.value = false
            evaluate()
            return true
        }
        return withJam { s, now -> JamSync.togglePlay(s, now) }
    }

    override fun resume(): Boolean {
        if (_jam.value == null) return false
        if (_held.value) {
            _held.value = false
            evaluate()
            return true
        }
        return withJam { s, now -> if (s.playing) null else JamSync.setPlaying(s, true, now) }
    }

    override fun pause(): Boolean = withJam { s, now -> if (!s.playing) null else JamSync.setPlaying(s, false, now) }
    override fun next(): Boolean {
        val jam = _jam.value ?: return false
        JamSync.next(jam.state)?.let {
            propose(it)
            return true
        }
        // On the last song: more songs come in first (like the jam's own "never ends"), then it moves on.
        val last = jam.state.queue.lastOrNull()?.videoId ?: return true
        scope.launch(main) {
            val more = withContext(Dispatchers.IO) { runCatching { related(last) }.getOrDefault(emptyList()) }
            val now = _jam.value ?: return@launch
            val grown = if (now.state.queue.lastOrNull()?.videoId == last) JamSync.extend(now.state, more, relay.serverNow()) ?: now.state else now.state
            JamSync.next(grown)?.let { propose(it) }
        }
        return true
    }
    override fun previous(): Boolean = withJam { s, now -> JamSync.previous(s, now) }
    override fun seekTo(positionMs: Long): Boolean = withJam { s, _ -> JamSync.seek(s, positionMs) }
    override fun skipTo(queueIndex: Int): Boolean = withJam { s, _ -> JamSync.skipTo(s, queueIndex) }

    override fun play(tracks: List<Track>, startIndex: Int): Boolean = withJam { s, now ->
        val start = tracks.getOrNull(startIndex)
        val songs = tracks.mapNotNull(SongRef::of)
        val next = JamSync.play(songs, start?.let(SongRef::of)?.let { a -> songs.indexOfFirst { it.videoId == a.videoId } } ?: 0)
        // The song already playing, tapped again: the list around it changes, it doesn't start over.
        if (next != null && next.current?.videoId == s.current?.videoId) next.copy(positionMs = s.positionAt(now), playing = true) else next
    }

    override fun enqueue(tracks: List<Track>, next: Boolean): Boolean = withJam { s, now -> JamSync.enqueue(s, tracks.mapNotNull(SongRef::of), next, now) }
    override fun removeAt(queueIndex: Int): Boolean = withJam { s, now -> JamSync.remove(s, queueIndex, now) }
    override fun move(from: Int, to: Int): Boolean = withJam { s, now -> JamSync.move(s, from, to, now) }
    override fun setShuffle(enabled: Boolean): Boolean = withJam { s, now -> if (enabled) JamSync.shuffleRest(s, seed = now, serverNow = now) else null }

    /** Repeat would take this phone away from the others: off in a jam. */
    override fun cycleRepeat(): Boolean = _jam.value != null

    // ------------------------------------------------------------------ helpers

    /** The queue as the relay takes it in one message: smaller songs, cut around the one playing. */
    private fun fitted(s: JamState): JamState {
        val (queue, index) = JamSync.fit(s.queue.map { it.compact() }, s.index, ::wireSize)
        return s.copy(queue = queue, index = index)
    }

    private fun wireSize(song: SongRef): Int = RelayJson.encodeToString(SongRef.serializer(), song).toByteArray().size + 1

    /** Someone's new name or photo, in the jam and its invites (what plays doesn't change). */
    fun updateProfile(user: Profile) {
        scope.launch(main) {
            fun swap(list: List<Profile>) = list.map { if (it.id == user.id) it.copy(name = user.name, avatar = user.avatar) else it }
            _jam.value = _jam.value?.let { j -> j.copy(members = swap(j.members), invited = swap(j.invited)) }
            _invites.value = _invites.value.map { inv ->
                inv.copy(
                    jam = inv.jam.copy(members = swap(inv.jam.members), invited = swap(inv.jam.invited)),
                    from = if (inv.from.id == user.id) inv.from.copy(name = user.name, avatar = user.avatar) else inv.from,
                )
            }
        }
    }

    private fun currentLocalState(positionMs: Long): JamState {
        val st = playback.value
        val songs = st.queue.mapNotNull(SongRef::of)
        val current = st.current?.let(SongRef::of)
        val index = current?.let { c -> songs.indexOfFirst { it.videoId == c.videoId } }?.takeIf { it >= 0 }
        // The song playing isn't one the others can play (no YouTube id): the jam starts at the top, from 0.
        return JamState(
            queue = songs,
            index = index ?: 0,
            positionMs = if (index != null) positionMs else 0,
            playing = st.isPlaying && songs.isNotEmpty(),
        )
    }

    companion object {
        /** After a step of ours, the player's changes are ours too for this long. */
        const val SETTLE_MS = 2_500L
        /** How far into the jam's last song a phone that didn't create it adds more songs. */
        const val GUEST_EXTEND_AFTER_MS = 20_000L
        /** The same kind of step again only after this long (the player may still be on its way). */
        const val RETRY_MS = 1_500L
        const val TICK_MS = 1_000L
        const val SEEK_JUMP_MS = 3_000L
        /** Position ticks come every 250 ms while playing; a longer gap starts the judging over. */
        const val SAMPLE_GAP_MS = 1_200L
    }
}

/** [JamRelay] over the real relay: HTTP calls and the live line. */
class RelayJamBridge(private val api: RelayApi, private val link: RelayLink) : JamRelay {
    override fun currentJam() = api.currentJam()
    override fun startJam(invite: List<String>, state: JamState?) = api.startJam(invite, state)
    override fun inviteToJam(jamId: String, userIds: List<String>) = api.inviteToJam(jamId, userIds)
    override fun joinJam(jamId: String) = api.joinJam(jamId)
    override fun declineJam(jamId: String) = api.declineJam(jamId)
    override fun leaveJam(jamId: String) = api.leaveJam(jamId)
    override fun endJam(jamId: String) = api.endJam(jamId)
    override fun serverNow() = link.serverNow()

    override fun sendState(jamId: String, baseRev: Long, state: JamState, withQueue: Boolean): Boolean {
        if (!link.connected.value) return false
        return link.send(buildJsonObject {
            put("t", "jam.state")
            put("jamId", jamId)
            put("baseRev", baseRev)
            if (withQueue) put("queue", RelayJson.encodeToJsonElement(ListSerializer(SongRef.serializer()), state.queue))
            put("index", state.index)
            put("positionMs", state.positionMs)
            put("playing", state.playing)
        })
    }

    override fun postState(jamId: String, baseRev: Long, state: JamState, withQueue: Boolean): Jam =
        api.postJamState(jamId, baseRev, state, withQueue)
}
