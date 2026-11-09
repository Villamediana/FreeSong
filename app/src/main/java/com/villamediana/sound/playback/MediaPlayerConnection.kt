package com.villamediana.sound.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.villamediana.sound.R
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.SettingsStore
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.messageRes
import com.villamediana.sound.playback.stream.StreamErrors
import com.villamediana.sound.smart.loadTaste
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Music that can be told to carry on: it plays if it isn't playing and does nothing if it is,
 * judged on the player's own live state — unlike a toggle decided on a [PlaybackState] that may
 * still lag a command behind.
 */
internal interface ResumablePlayer {
    fun resume()
}

/**
 * The app's [PlayerConnection]: a Media3 [MediaController] bound to [PlaybackService].
 *
 * Everything runs on the main thread (callers may use any thread). Commands issued before the
 * controller connects are queued and replayed in order. The queue is exposed in play order (the
 * shuffled order while shuffling) and every index the UI passes back is a play-order index; the
 * service does the shuffle-aware edits so a moved or inserted song lands exactly where asked.
 *
 * Online songs are transient tracks (data/OnlineTrack.kt) both ways: [playOnline] and any command
 * given online tracks describe them to the service, and the queue's "yt:" items come back as
 * transient tracks (or as the library song, once it's downloaded) so every screen keeps working.
 */
class MediaPlayerConnection(
    context: Context,
    private val library: LibraryRepository,
    @Suppress("unused") settings: SettingsStore,
    scope: CoroutineScope,
) : PlayerConnection, ResumablePlayer, FollowingPlayer {
    private val app: Context = context.applicationContext
    private val main = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)
    private val strings by lazy { PlaybackStrings.from(app) }

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    override val positionMs: Flow<Long> = _position.asStateFlow()

    private val _sleep = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    override val sleepTimer: StateFlow<SleepTimer> = _sleep.asStateFlow()

    /** The jam, when there is one: the app's commands go to it instead of straight to the player. */
    @Volatile var gate: PlaybackGate? = null

    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var restoreRequested = false

    /** Library tracks by id (from the repository flow) plus tracks handed to us that it doesn't have yet. */
    private var known: Map<Long, Track> = emptyMap()
    private var knownByVideoId: Map<String, Track> = emptyMap()
    private val extra = HashMap<Long, Track>()
    private val fetching = HashSet<Long>()

    // Session extras.
    private var source: PlaySource? = null
    private var smart = false
    private var outputName: String? = null

    private var error: String? = null
    private var errorJob: Job? = null

    /** Visible queue position → play-order index (they differ only while a song can't be resolved). */
    private var visibleToPlay: IntArray = IntArray(0)
    private var order: PlayOrder = PlayOrder(IntArray(0))

    init {
        main.launch {
            library.tracks.collect { all ->
                known = all.associateBy { it.id }
                knownByVideoId = all.mapNotNull { t -> t.videoId?.takeIf { it.isNotBlank() }?.let { it to t } }.toMap()
                extra.keys.removeAll(known.keys)
                refresh()
            }
        }
        main.launch {
            combine(
                _state.map { it.isPlaying }.distinctUntilChanged(),
                _position.subscriptionCount.map { it > 0 }.distinctUntilChanged(),
            ) { playing, watched -> playing && watched }
                .distinctUntilChanged()
                .collectLatest { tick ->
                    emitPosition()
                    while (tick && isActive) {
                        delay(POSITION_TICK_MS)
                        emitPosition()
                    }
                }
        }
        main.launch { ensureConnected() }
    }

    // ================================================================ connection

    private fun ensureConnected() {
        if (controller?.isConnected == true || connecting != null) return
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).setListener(ControllerEvents()).buildAsync()
        connecting = future
        future.addListener({
            connecting = null
            val c = runCatching { future.get() }.getOrNull() ?: run {
                // The service couldn't be reached; drop what was waiting rather than replaying it much later.
                pending.clear()
                return@addListener
            }
            controller = c
            c.addListener(PlayerEvents())
            readExtras(c.sessionExtras)
            refresh()
            emitPosition()
            // An empty player gets the last queue back, paused, before anything else runs (the
            // service handles queue commands in order, so a play() issued meanwhile still wins).
            if (!restoreRequested && c.mediaItemCount == 0) {
                restoreRequested = true
                c.sendCustomCommand(SessionProtocol.command(SessionProtocol.RESTORE), Bundle.EMPTY)
            }
            while (pending.isNotEmpty()) pending.removeFirst().invoke(c)
        }, ContextCompat.getMainExecutor(app))
    }

    /** Runs [block] with a connected controller, now or right after connecting. */
    private fun withController(block: (MediaController) -> Unit) {
        main.launch {
            val c = controller
            if (c != null && c.isConnected) block(c) else {
                pending.addLast(block)
                ensureConnected()
            }
        }
    }

    private fun send(action: String, args: Bundle = Bundle.EMPTY) = withController { it.sendCustomCommand(SessionProtocol.command(action), args) }

    private inner class ControllerEvents : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            readExtras(extras)
            refresh()
        }

        override fun onDisconnected(controller: MediaController) {
            if (this@MediaPlayerConnection.controller === controller) this@MediaPlayerConnection.controller = null
            controller.release()
            // Next connection (made by the next command) brings the saved queue back if the service restarted empty.
            restoreRequested = false
            _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
        }
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
            if (events.containsAny(
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED,
                )
            ) emitPosition()
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            if (error != null) showError(errorMessage(error))
        }
    }

    private fun readExtras(extras: Bundle?) {
        source = SessionProtocol.getSource(extras)
        smart = extras?.getBoolean(SessionProtocol.EXTRA_SMART) ?: false
        outputName = extras?.getString(SessionProtocol.EXTRA_OUTPUT)
        _sleep.value = SessionProtocol.getSleep(extras)
    }

    // ================================================================ state

    private fun track(id: Long?): Track? = id?.let { known[it] ?: extra[it] }

    /** The track behind a queue item: a library song, or an online one (the library song once downloaded). */
    private fun track(item: MediaItem?): Track? {
        MediaItems.trackId(item)?.let { return track(it) }
        val videoId = MediaItems.videoId(item) ?: return null
        return knownByVideoId[videoId] ?: MediaItems.onlineTrack(item)
    }

    private fun refresh() {
        val c = controller ?: return
        val count = c.mediaItemCount
        val shuffle = c.shuffleModeEnabled
        val timeline = c.currentTimeline
        order = if (timeline.windowCount == count) PlayOrder.of(timeline, shuffle) else PlayOrder.identity(count)
        val currentIndex = c.currentMediaItemIndex
        val queue = ArrayList<Track>(count)
        val playIndexes = ArrayList<Int>(count)
        val suggested = HashSet<Int>()
        var queueIndex = -1
        val missing = ArrayList<Long>()
        for (p in 0 until order.size) {
            val t = order.timelineIndexOf(p)
            val item = c.getMediaItemAt(t)
            val track = track(item)
            if (track == null) {
                MediaItems.trackId(item)?.let { missing += it }
                continue
            }
            if (t == currentIndex) queueIndex = queue.size
            if (MediaItems.isSuggested(item)) suggested += queue.size
            queue += track
            playIndexes += p
        }
        visibleToPlay = playIndexes.toIntArray()
        if (missing.isNotEmpty()) fetch(missing)

        val current = track(c.currentMediaItem)
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: current?.durationMs ?: 0L
        _state.value = PlaybackState(
            current = current,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING && c.playWhenReady,
            durationMs = duration,
            shuffle = shuffle,
            repeat = c.repeatMode.toRepeatMode(),
            queue = queue,
            queueIndex = queueIndex,
            source = source,
            smart = smart,
            suggested = suggested,
            outputName = outputName,
            error = error,
        )
    }

    /** Songs in the queue the library flow doesn't have yet (e.g. right after a download). */
    private fun fetch(ids: List<Long>) {
        val wanted = ids.filter { it !in fetching }
        if (wanted.isEmpty()) return
        fetching += wanted
        main.launch {
            val found = runCatching { withContext(Dispatchers.IO) { wanted.chunked(500).flatMap { library.getTracks(it) } } }.getOrDefault(emptyList())
            found.forEach { extra[it.id] = it }
            fetching -= wanted.toSet()
            if (found.isNotEmpty()) refresh()
        }
    }

    private fun emitPosition() {
        _position.value = controller?.currentPosition?.coerceAtLeast(0) ?: 0L
    }

    private fun showError(message: String) {
        error = message
        refresh()
        errorJob?.cancel()
        errorJob = main.launch {
            delay(ERROR_VISIBLE_MS)
            clearError()
        }
    }

    private fun clearError() {
        if (error == null) return
        error = null
        errorJob?.cancel()
        refresh()
    }

    private fun errorMessage(e: PlaybackException): String = app.getString(
        StreamErrors.kindOf(e.errorCode)?.messageRes() ?: when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            -> R.string.playback_error_missing
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            -> R.string.playback_error_unreadable
            else -> R.string.playback_error_generic
        },
    )

    /** Visible queue index (what the UI shows) → play-order index. */
    private fun playIndex(queueIndex: Int): Int = visibleToPlay.getOrElse(queueIndex) { -1 }

    private fun remember(tracks: List<Track>) {
        // Online songs describe themselves in the queue items; only library songs are looked up by id.
        tracks.forEach { t -> if (!t.isOnline && t.id !in known) extra[t.id] = t }
    }

    // ================================================================ commands

    override fun play(tracks: List<Track>, startIndex: Int, source: PlaySource, shuffle: Boolean) =
        play(tracks, startIndex, source, shuffle, smart = false)

    private fun play(tracks: List<Track>, startIndex: Int, source: PlaySource, shuffle: Boolean, smart: Boolean) {
        if (tracks.isEmpty()) return
        if (gate?.play(tracks, startIndex.coerceIn(0, tracks.lastIndex)) == true) return
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        // The song already playing, tapped again in its own list: keep it going, don't start over
        // (only turning shuffle on when that was asked).
        if (PlayRequests.keepsCurrent(_state.value.current, _state.value.source, tracks[start], source)) {
            if (shuffle && !_state.value.shuffle) setShuffle(true)
            resume()
            return
        }
        main.launch {
            remember(tracks)
            clearError()
        }
        send(
            SessionProtocol.PLAY,
            playArgs(
                tracks, start, source, PlayRequests.shuffleMode(shuffle, source), smart = smart,
                continueCurrent = PlayRequests.continuesCurrent(_state.value.current, tracks[start], source),
            ),
        )
    }

    override fun playOnline(tracks: List<RemoteTrack>, startIndex: Int, source: PlaySource) {
        val songs = tracks.filter { it.videoId.isNotBlank() }.distinctBy { it.videoId }
        if (songs.isEmpty()) return
        val start = tracks.getOrNull(startIndex)?.videoId?.let { id -> songs.indexOfFirst { it.videoId == id } }?.takeIf { it >= 0 } ?: 0
        // An online radio, mix or genre list is a smart queue: it keeps going with YouTube Music's
        // "up next" (same genre and style) even when "Continuar com músicas parecidas" is off.
        play(songs.map { OnlineItem.of(it).toTrack() }, start, source, shuffle = false, smart = source.type in SMART_ONLINE)
    }

    override fun prefetch(videoIds: List<String>) {
        if (videoIds.isEmpty()) return
        send(SessionProtocol.PREFETCH, Bundle().apply { putStringArray(SessionProtocol.ARG_VIDEO_IDS, videoIds.toTypedArray()) })
    }

    override fun playSmart(seed: SmartSeed) {
        main.launch {
            val all = known.values.toList().ifEmpty { withContext(Dispatchers.IO) { library.allTracks() } }
            val taste = withContext(Dispatchers.IO) { library.loadTaste() }
            val plan = withContext(Dispatchers.Default) {
                SmartPlans.plan(seed, all, taste, strings, System.currentTimeMillis())
            } ?: return@launch
            if (gate?.play(plan.tracks, plan.startIndex) == true) return@launch
            remember(plan.tracks)
            clearError()
            val startTrack = plan.tracks.getOrNull(plan.startIndex)
            send(
                SessionProtocol.PLAY,
                playArgs(
                    plan.tracks, plan.startIndex, plan.source, SessionProtocol.SHUFFLE_OFF, smart = plan.smart,
                    continueCurrent = startTrack != null && PlayRequests.continuesCurrent(_state.value.current, startTrack, plan.source),
                ),
            )
        }
    }

    private fun playArgs(tracks: List<Track>, start: Int, source: PlaySource?, shuffle: Int, smart: Boolean, continueCurrent: Boolean = false) = Bundle().apply {
        putLongArray(SessionProtocol.ARG_IDS, tracks.map { it.id }.toLongArray())
        putInt(SessionProtocol.ARG_START, start)
        putBoolean(SessionProtocol.ARG_CONTINUE, continueCurrent)
        putInt(SessionProtocol.ARG_SHUFFLE, shuffle)
        putBoolean(SessionProtocol.ARG_SMART, smart)
        SessionProtocol.putSource(this, source)
        SessionProtocol.putOnline(this, tracks)
    }

    override fun togglePlayPause() {
        if (gate?.togglePlayPause() == true) return
        togglePlayPauseNow()
    }

    private fun togglePlayPauseNow() = withController { c ->
        if (c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)) c.pause()
        else resume(c)
    }

    override fun resume() {
        if (gate?.resume() == true) return
        resumeNow()
    }

    private fun resumeNow() = withController { c ->
        val playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
        if (c.mediaItemCount > 0 && !playing) resume(c)
    }

    private fun resume(c: MediaController) {
        clearError()
        when (c.playbackState) {
            Player.STATE_IDLE -> c.prepare()
            Player.STATE_ENDED -> c.seekToDefaultPosition()
            else -> Unit
        }
        c.play()
    }

    override fun pause() {
        if (gate?.pause() == true) return
        withController { it.pause() }
    }

    override fun pauseHere() = withController { it.pause() }

    override val shared: Boolean get() = gate != null

    override fun next() {
        if (gate?.next() == true) return
        nextNow()
    }

    private fun nextNow() = withController { c ->
        clearError()
        c.seekToNext()
    }

    override fun previous() {
        if (gate?.previous() == true) return
        previousNow()
    }

    private fun previousNow() = withController { c ->
        clearError()
        if (c.currentPosition > RESTART_THRESHOLD_MS || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
        emitPosition()
    }

    override fun seekTo(positionMs: Long) {
        if (gate?.seekTo(positionMs) == true) return
        seekNow(positionMs)
    }

    private fun seekNow(positionMs: Long) = withController { c ->
        c.seekTo(positionMs.coerceAtLeast(0))
        _position.value = positionMs.coerceAtLeast(0)
    }

    override fun seekBlended(positionMs: Long) {
        if (gate?.seekTo(positionMs) == true) return
        val pos = positionMs.coerceAtLeast(0)
        send(SessionProtocol.SEEK_BLEND, Bundle().apply { putLong(SessionProtocol.ARG_POSITION, pos) })
        _position.value = pos
    }

    override fun skipTo(queueIndex: Int) {
        if (gate?.skipTo(queueIndex) == true) return
        skipNow(queueIndex)
    }

    private fun skipNow(queueIndex: Int) = withController { c ->
        val t = order.timelineIndexOf(playIndex(queueIndex))
        if (t < 0) return@withController
        c.seekTo(t, 0)
        resume(c)
    }

    /**
     * In line with the queue commands (not a plain player command): right after [play] or
     * [playOnline] it applies to the new queue, whose start song stays first, even while the
     * service is still computing that queue's order.
     */
    override fun setShuffle(enabled: Boolean) {
        if (gate?.setShuffle(enabled) == true) return
        setShuffleNow(enabled)
    }

    private fun setShuffleNow(enabled: Boolean) = send(SessionProtocol.SET_SHUFFLE, Bundle().apply { putBoolean(SessionProtocol.ARG_ENABLED, enabled) })

    override fun cycleRepeat() {
        if (gate?.cycleRepeat() == true) return
        cycleRepeatNow()
    }

    private fun cycleRepeatNow() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun playNext(tracks: List<Track>) = addToQueue(tracks, SessionProtocol.WHERE_NEXT)

    override fun enqueue(tracks: List<Track>) = addToQueue(tracks, SessionProtocol.WHERE_END)

    private fun addToQueue(tracks: List<Track>, where: String) {
        if (tracks.isEmpty()) return
        if (gate?.enqueue(tracks, next = where == SessionProtocol.WHERE_NEXT) == true) return
        main.launch { remember(tracks) }
        send(
            SessionProtocol.QUEUE_ADD,
            Bundle().apply {
                putLongArray(SessionProtocol.ARG_IDS, tracks.map { it.id }.toLongArray())
                putString(SessionProtocol.ARG_WHERE, where)
                SessionProtocol.putOnline(this, tracks)
            },
        )
    }

    override fun removeAt(queueIndex: Int) {
        if (gate?.removeAt(queueIndex) == true) return
        removeNow(queueIndex)
    }

    private fun removeNow(queueIndex: Int) = withController { c ->
        val p = playIndex(queueIndex)
        if (p < 0) return@withController
        c.sendCustomCommand(SessionProtocol.command(SessionProtocol.QUEUE_REMOVE), Bundle().apply { putInt(SessionProtocol.ARG_INDEX, p) })
    }

    override fun move(from: Int, to: Int) {
        if (gate?.move(from, to) == true) return
        moveNow(from, to)
    }

    private fun moveNow(from: Int, to: Int) = withController { c ->
        val f = playIndex(from)
        val t = playIndex(to)
        if (f < 0 || t < 0 || f == t) return@withController
        c.sendCustomCommand(
            SessionProtocol.command(SessionProtocol.QUEUE_MOVE),
            Bundle().apply {
                putInt(SessionProtocol.ARG_FROM, f)
                putInt(SessionProtocol.ARG_TO, t)
            },
        )
    }

    // ================================================================ following a jam (no gate)

    override fun followLoad(tracks: List<Track>, index: Int, positionMs: Long, play: Boolean) {
        if (tracks.isEmpty()) return
        val start = index.coerceIn(0, tracks.lastIndex)
        val source = PlaySource(PlaySource.Type.JAM, app.getString(R.string.social_jam_source))
        main.launch {
            remember(tracks)
            clearError()
        }
        send(
            SessionProtocol.PLAY,
            playArgs(
                tracks, start, source, SessionProtocol.SHUFFLE_OFF, smart = false,
                continueCurrent = PlayRequests.continuesCurrent(_state.value.current, tracks[start], source),
            ).apply {
                putLong(SessionProtocol.ARG_START_POSITION, positionMs.coerceAtLeast(0))
                putBoolean(SessionProtocol.ARG_AUTOPLAY, play)
            },
        )
    }

    override fun followSkip(index: Int, positionMs: Long, play: Boolean) = withController { c ->
        val t = order.timelineIndexOf(playIndex(index))
        if (t < 0) return@withController
        c.seekTo(t, positionMs.coerceAtLeast(0))
        if (play) resume(c) else c.pause()
    }

    override fun followSeek(positionMs: Long) = seekNow(positionMs)

    override fun followPlay() = resumeNow()

    override fun followPause() = withController { it.pause() }

    override fun livePositionMs(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: _position.value

    override fun setSleepTimer(timer: SleepTimer) {
        _sleep.value = timer
        send(SessionProtocol.SLEEP, Bundle().apply { SessionProtocol.putSleep(this, timer) })
    }

    private companion object {
        const val POSITION_TICK_MS = 250L
        const val RESTART_THRESHOLD_MS = 3_000L
        const val ERROR_VISIBLE_MS = 5_000L
    }
}

/** Online queues that never just end: they flow into the radio of their last song. */
private val SMART_ONLINE = setOf(PlaySource.Type.RADIO, PlaySource.Type.MIX, PlaySource.Type.GENRE)
