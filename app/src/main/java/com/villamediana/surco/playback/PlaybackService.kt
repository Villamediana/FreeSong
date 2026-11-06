package com.villamediana.surco.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.villamediana.surco.MainActivity
import com.villamediana.surco.Notifications
import com.villamediana.surco.R
import com.villamediana.surco.SurcoApplication
import com.villamediana.surco.data.Settings
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.smart.Taste
import com.villamediana.surco.smart.loadTaste
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Foreground media service hosting ExoPlayer + MediaLibrarySession.
 *
 * Plays local files only. Audio focus, "becoming noisy" (headphones unplugged / Bluetooth gone →
 * pause) and the wake lock are ExoPlayer's; the session makes Surco controllable from the
 * notification, lock screen, headset buttons (AVRCP), watches and cars, with a browse tree for the
 * latter. The service also owns everything that must survive the UI: smart shuffle, autoplay of
 * similar songs, the sleep timer, listening stats and the saved queue.
 */
class PlaybackService : MediaLibraryService() {
    private val graph get() = SurcoApplication.graph
    private val library get() = graph.library

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var sleep: SleepTimerRunner
    private lateinit var output: OutputDeviceMonitor
    private lateinit var browser: LibraryBrowser
    private lateinit var strings: PlaybackStrings
    private val leveler = VolumeLeveler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listen = ListenTracker { SystemClock.elapsedRealtime() }

    // Library mirror (kept fresh by the repository's flow).
    private var allTracks: List<Track> = emptyList()
    private var tracksById: Map<Long, Track> = emptyMap()
    private var libraryLoaded = false
    private var settings = Settings()

    // Queue context, published to controllers as session extras.
    private var source: PlaySource? = null
    private var smart = false
    private var outputName: String? = null

    private var tasteCache: Taste? = null
    private var tasteAt = 0L
    private var shuffleJob: Job? = null
    private var suppressShuffleReorder = false
    private var reorderOnNextTimeline = false
    private var pendingRestore: QueueSnapshot? = null
    private var extendJob: Job? = null
    private var extendedAtCount = -1
    private var saveJob: Job? = null
    private var positionSaver: Job? = null
    private var consecutiveErrors = 0
    private var buttonsKey: String? = null
    private val searchResults = HashMap<String, List<MediaItem>>()
    private val commandMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        strings = PlaybackStrings.from(this)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(PlayerEvents())

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(Notifications.CHANNEL_PLAYBACK)
                .setChannelName(R.string.channel_playback)
                .setNotificationId(Notifications.ID_PLAYBACK)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )

        session = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setId("surco")
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setMediaButtonPreferences(buttons(liked = false, shuffle = false))
            .build()

        browser = LibraryBrowser(this, library, ::libraryTracks, ::taste, strings)
        sleep = SleepTimerRunner(player) { publishExtras() }
        output = OutputDeviceMonitor(this) { name ->
            outputName = name
            publishExtras()
        }
        output.start()

        scope.launch { library.tracks.collect(::onLibrary) }
        scope.launch { graph.settings.settings.collect(::onSettings) }
        publishExtras()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveNow()
        val playing = player.playWhenReady && player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
        if (!playing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        saveNow()
        finishListen()
        sleep.release()
        output.stop()
        leveler.release()
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    // ================================================================ library & settings

    private fun onLibrary(list: List<Track>) {
        allTracks = list
        tracksById = list.associateBy { it.id }
        libraryLoaded = true
        syncQueueWithLibrary()
        updateButtons()
    }

    /** Songs deleted from the library leave the queue; edited ones (title, cover…) refresh in place. */
    private fun syncQueueWithLibrary() {
        val n = player.mediaItemCount
        if (n == 0) return
        val gone = ArrayList<Int>()
        for (i in 0 until n) {
            val item = player.getMediaItemAt(i)
            val id = MediaItems.trackId(item) ?: continue
            val track = tracksById[id]
            if (track == null) gone += i
            else if (!MediaItems.matches(item, track)) player.replaceMediaItem(i, MediaItems.of(track))
        }
        gone.asReversed().forEach { player.removeMediaItem(it) }
    }

    private fun onSettings(s: Settings) {
        val previous = settings
        settings = s
        leveler.apply(s.normalizeVolume, player.audioSessionId)
        if (!previous.autoplaySimilar && s.autoplaySimilar) maybeExtend()
    }

    private suspend fun libraryTracks(): List<Track> =
        if (libraryLoaded) allTracks else withContext(Dispatchers.IO) { library.allTracks() }

    private suspend fun tracksFor(ids: List<Long>): List<Track> =
        if (libraryLoaded) ids.mapNotNull(tracksById::get)
        else withContext(Dispatchers.IO) { ids.chunked(500).flatMap { library.getTracks(it) } }

    private suspend fun taste(): Taste {
        val now = System.currentTimeMillis()
        tasteCache?.takeIf { now - tasteAt < TASTE_TTL_MS }?.let { return it }
        return withContext(Dispatchers.IO) { library.loadTaste(now) }.also {
            tasteCache = it
            tasteAt = now
        }
    }

    // ================================================================ queue helpers

    private fun mediaIds(): List<Long?> = (0 until player.mediaItemCount).map { MediaItems.trackId(player.getMediaItemAt(it)) }

    private fun playOrder(): PlayOrder = PlayOrder.of(player.currentTimeline, player.shuffleModeEnabled)

    private fun currentTrack(): Track? = MediaItems.trackId(player.currentMediaItem)?.let(tracksById::get)

    private fun setShuffleOrder(order: PlayOrder) {
        if (order.size == player.mediaItemCount && order.size > 0) {
            player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
        }
    }

    /** Replaces the queue. [shuffleMode] = [SessionProtocol.SHUFFLE_KEEP] / ON / OFF. */
    private suspend fun playPlan(plan: QueuePlan, shuffleMode: Int, pinFirst: Boolean = true) {
        val tracks = plan.tracks
        if (tracks.isEmpty()) return
        val start = plan.startIndex.coerceIn(0, tracks.lastIndex)
        val shuffleOn = when (shuffleMode) {
            SessionProtocol.SHUFFLE_ON -> true
            SessionProtocol.SHUFFLE_OFF -> false
            else -> player.shuffleModeEnabled
        }
        val seed = Random.nextLong()
        val order = if (shuffleOn) {
            val taste = taste()
            val useSmart = settings.smartShuffle
            withContext(Dispatchers.Default) { QueueShuffler.order(tracks, if (pinFirst) start else null, useSmart, taste, seed) }
        } else null

        shuffleJob?.cancel()
        source = plan.source
        smart = plan.smart
        extendedAtCount = -1
        consecutiveErrors = 0
        reorderOnNextTimeline = false
        pendingRestore = null
        suppressShuffleReorder = true
        try {
            player.shuffleModeEnabled = shuffleOn
            player.setMediaItems(tracks.map(MediaItems::of), order?.timelineIndexOf(0) ?: start, 0)
            if (order != null) player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), seed))
        } finally {
            suppressShuffleReorder = false
        }
        listen.updateContext(source?.type?.name)
        player.prepare()
        player.play()
        publishExtras()
        updateButtons()
        maybeExtend()
        scheduleSave()
    }

    /** Inserts after the current song ([next]) or at the end — of the play order, also when shuffled. */
    private fun insert(tracks: List<Track>, next: Boolean) {
        if (tracks.isEmpty()) return
        val items = tracks.map(MediaItems::of)
        val n = player.mediaItemCount
        if (n == 0) {
            source = null
            smart = false
            player.setMediaItems(items)
            player.prepare()
            publishExtras()
            return
        }
        val cur = player.currentMediaItemIndex.coerceIn(0, n - 1)
        if (!player.shuffleModeEnabled) {
            player.addMediaItems(if (next) cur + 1 else n, items)
        } else {
            val before = playOrder()
            val timelinePos = if (next) cur + 1 else n
            val playPos = if (next) before.playIndexOf(cur) + 1 else before.size
            player.addMediaItems(timelinePos, items)
            setShuffleOrder(before.inserted(playPos, timelinePos, items.size))
        }
        extendedAtCount = -1
    }

    private fun move(from: Int, to: Int) {
        val n = player.mediaItemCount
        if (from !in 0 until n || to !in 0 until n || from == to) return
        if (!player.shuffleModeEnabled) player.moveMediaItem(from, to)
        else setShuffleOrder(playOrder().moved(from, to))
    }

    private fun removeAt(playIndex: Int) {
        val t = playOrder().timelineIndexOf(playIndex)
        if (t >= 0) player.removeMediaItem(t)
    }

    /** Shuffle was turned on: smart (or plain) order for the queue, keeping the playing song first. */
    private fun applyShuffleOrder() {
        shuffleJob?.cancel()
        if (player.mediaItemCount <= 1) return
        val ids = mediaIds()
        val items = ids.map { id -> id?.let(tracksById::get) }
        val current = player.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET }
        val useSmart = settings.smartShuffle
        val seed = Random.nextLong()
        shuffleJob = scope.launch {
            val taste = taste()
            val order = withContext(Dispatchers.Default) { QueueShuffler.order(items, current, useSmart, taste, seed) }
            if (!player.shuffleModeEnabled || mediaIds() != ids) return@launch
            val now = player.currentMediaItemIndex
            setShuffleOrder(if (now != C.INDEX_UNSET) order.withFirst(now) else order)
            scheduleSave()
        }
    }

    // ================================================================ autoplay / smart extension

    /**
     * When the last song of the play order starts (repeat off), append what fits: similar songs
     * when "autoplay similar" is on, and always for smart queues (radio, genre, mixes, smart shuffle).
     */
    private fun maybeExtend() {
        if (suppressShuffleReorder) return // mid queue replacement; the caller extends when done
        val count = player.mediaItemCount
        if (count == 0 || player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (!(settings.autoplaySimilar || smart)) return
        if (player.currentMediaItemIndex == C.INDEX_UNSET || player.hasNextMediaItem()) return
        if (extendJob?.isActive == true || extendedAtCount == count) return
        extendedAtCount = count
        val order = playOrder()
        val queueIds = (0 until order.size).mapNotNull { MediaItems.trackId(player.getMediaItemAt(order.timelineIndexOf(it))) }
        val src = source
        val seed = Random.nextLong()
        extendJob = scope.launch {
            val lib = libraryTracks()
            val taste = taste()
            val queue = tracksFor(queueIds)
            val more = withContext(Dispatchers.Default) { SmartPlans.extension(src, queue, lib, taste, seed) }
            if (player.mediaItemCount != count || player.hasNextMediaItem()) {
                extendedAtCount = -1 // the queue changed meanwhile: decide again at its real end
                return@launch
            }
            if (more.isEmpty()) return@launch // nothing left that fits; don't retry for this queue
            insert(more, next = false)
            extendedAtCount = player.mediaItemCount
            if (!smart) {
                smart = true
                publishExtras()
            }
            scheduleSave()
        }
    }

    // ================================================================ listening stats

    private fun startListen(item: MediaItem?) {
        val id = MediaItems.trackId(item) ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: tracksById[id]?.durationMs ?: 0
        listen.start(id, duration, source?.type?.name, player.isPlaying)
    }

    private fun finishListen() {
        val s = listen.finish() ?: return
        tasteCache = null
        graph.scope.launch {
            runCatching { library.recordPlay(s.trackId, s.listenedMs, s.durationMs, s.context) }
        }
    }

    // ================================================================ persistence

    private fun snapshot(): QueueSnapshot? {
        val n = player.mediaItemCount
        if (n == 0) return null
        val ids = mediaIds().map { it ?: return null }
        val shuffle = player.shuffleModeEnabled
        return QueueSnapshot(
            ids = ids,
            index = player.currentMediaItemIndex.coerceIn(0, n - 1),
            positionMs = player.currentPosition.coerceAtLeast(0),
            shuffle = shuffle,
            order = if (shuffle) playOrder().toList() else emptyList(),
            repeat = player.repeatMode.toRepeatMode(),
            source = QueueSnapshot.SavedSource.of(source),
            smart = smart,
            savedAt = System.currentTimeMillis(),
        )
    }

    private fun scheduleSave(delayMs: Long = SAVE_DEBOUNCE_MS) {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(delayMs)
            saveNow()
        }
    }

    private fun saveNow() {
        saveJob?.cancel()
        val json = snapshot()?.encode() ?: return
        val store = graph.settings
        graph.scope.launch { runCatching { store.setLastQueueJson(json) } }
    }

    private suspend fun loadSnapshot(): Pair<QueueSnapshot, List<Track>>? {
        val raw = QueueSnapshot.decode(graph.settings.current().lastQueueJson) ?: return null
        val found = tracksFor(raw.ids.distinct())
        val snap = raw.retain(found.mapTo(HashSet()) { it.id }) ?: return null
        val byId = found.associateBy { it.id }
        return snap to snap.ids.map { byId.getValue(it) }
    }

    /** Puts the saved queue back without playing (the mini player shows the last song). */
    private suspend fun restore() {
        if (player.mediaItemCount > 0) return
        val (snap, tracks) = loadSnapshot() ?: return
        if (player.mediaItemCount > 0) return
        source = snap.source?.toPlaySource()
        smart = snap.smart
        player.repeatMode = snap.repeat.toPlayer()
        suppressShuffleReorder = true
        try {
            player.shuffleModeEnabled = snap.shuffle
            player.setMediaItems(tracks.map(MediaItems::of), snap.index, snap.positionMs)
            if (snap.shuffle) PlayOrder.validOrNull(snap.order, tracks.size)?.let(::setShuffleOrder)
        } finally {
            suppressShuffleReorder = false
        }
        publishExtras()
        updateButtons()
    }

    // ================================================================ session state

    private fun publishExtras() {
        if (!::session.isInitialized) return
        val b = Bundle()
        SessionProtocol.putSource(b, source)
        b.putBoolean(SessionProtocol.EXTRA_SMART, smart)
        outputName?.let { b.putString(SessionProtocol.EXTRA_OUTPUT, it) }
        SessionProtocol.putSleep(b, if (::sleep.isInitialized) sleep.timer else SleepTimer.Off)
        session.setSessionExtras(b)
    }

    private fun updateButtons() {
        val liked = currentTrack()?.liked == true
        val shuffle = player.shuffleModeEnabled
        val key = "$liked/$shuffle"
        if (key == buttonsKey) return
        buttonsKey = key
        session.setMediaButtonPreferences(buttons(liked, shuffle))
    }

    private fun buttons(liked: Boolean, shuffle: Boolean): List<CommandButton> = listOf(
        CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(getString(if (liked) R.string.playback_unlike else R.string.playback_like))
            .setSessionCommand(SessionProtocol.command(SessionProtocol.LIKE))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(getString(if (shuffle) R.string.playback_shuffle_off else R.string.playback_shuffle_on))
            .setSessionCommand(SessionProtocol.command(SessionProtocol.SHUFFLE))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private fun toggleLike() {
        val track = currentTrack() ?: return
        val liked = !track.liked
        graph.scope.launch { runCatching { library.setLiked(track.id, liked) } }
    }

    private fun startPositionSaver() {
        positionSaver?.cancel()
        positionSaver = scope.launch {
            while (isActive) {
                delay(POSITION_SAVE_MS)
                saveNow()
            }
        }
    }

    // ================================================================ player events

    private inner class PlayerEvents : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A metadata refresh of the playing song (replaceMediaItem) is not a new listen.
            val sameSong = reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                MediaItems.trackId(mediaItem) == listen.currentTrackId
            if (!sameSong) {
                finishListen()
                startListen(mediaItem)
            }
            updateButtons()
            maybeExtend()
            scheduleSave()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Replaying after the end (or after an error) starts a new listen of the same song.
            if (isPlaying && listen.currentTrackId == null) startListen(player.currentMediaItem)
            listen.setPlaying(isPlaying)
            if (isPlaying) {
                consecutiveErrors = 0
                startPositionSaver()
            } else {
                positionSaver?.cancel()
                saveNow()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> listen.updateDuration(player.duration.takeIf { it != C.TIME_UNSET } ?: 0)
                Player.STATE_ENDED -> {
                    finishListen()
                    sleep.onEnded()
                    scheduleSave()
                }
                else -> Unit
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
            if (timeline.isEmpty) {
                finishListen()
                return
            }
            pendingRestore?.let { snap ->
                if (mediaIds() == snap.ids) {
                    pendingRestore = null
                    suppressShuffleReorder = true
                    try {
                        player.shuffleModeEnabled = snap.shuffle
                        if (snap.shuffle) PlayOrder.validOrNull(snap.order, snap.ids.size)?.let(::setShuffleOrder)
                    } finally {
                        suppressShuffleReorder = false
                    }
                }
            }
            if (reorderOnNextTimeline) {
                reorderOnNextTimeline = false
                if (player.shuffleModeEnabled) applyShuffleOrder()
            }
            maybeExtend()
            scheduleSave()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (shuffleModeEnabled && !suppressShuffleReorder) applyShuffleOrder()
            if (!shuffleModeEnabled) shuffleJob?.cancel()
            updateButtons()
            maybeExtend()
            scheduleSave()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            maybeExtend()
            scheduleSave()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) scheduleSave()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) sleep.onPausedAtEndOfItem()
        }

        override fun onPlayerError(error: PlaybackException) {
            finishListen()
            consecutiveErrors++
            // A broken or missing file shouldn't stop the music: skip it (a few times at most).
            if (consecutiveErrors <= MAX_SKIPS_ON_ERROR && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            leveler.apply(settings.normalizeVolume, audioSessionId)
        }
    }

    // ================================================================ session callback

    private inner class SessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            val commands = base.availableSessionCommands.buildUpon()
            SessionProtocol.publicCommands.forEach { commands.add(SessionProtocol.command(it)) }
            if (controller.packageName == packageName) SessionProtocol.appCommands.forEach { commands.add(SessionProtocol.command(it)) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands.build())
                .setAvailablePlayerCommands(base.availablePlayerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val ok = Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            when (customCommand.customAction) {
                SessionProtocol.LIKE -> toggleLike()
                SessionProtocol.SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                SessionProtocol.PLAY -> {
                    val ids = args.getLongArray(SessionProtocol.ARG_IDS)?.toList().orEmpty()
                    val start = args.getInt(SessionProtocol.ARG_START)
                    val shuffle = args.getInt(SessionProtocol.ARG_SHUFFLE, SessionProtocol.SHUFFLE_KEEP)
                    val src = SessionProtocol.getSource(args)
                    val isSmart = args.getBoolean(SessionProtocol.ARG_SMART)
                    return serialized {
                        val found = tracksFor(ids.distinct()).associateBy { it.id }
                        val kept = ids.withIndex().filter { it.value in found }
                        if (kept.isNotEmpty()) {
                            val startIdx = kept.indexOfFirst { it.index >= start }.coerceAtLeast(0)
                            // "Aleatório" on a whole list lets the shuffle pick the first song too;
                            // tapping a song (start > 0, or shuffle kept as it was) plays that song first.
                            val pinFirst = !(shuffle == SessionProtocol.SHUFFLE_ON && start == 0)
                            playPlan(QueuePlan(kept.map { found.getValue(it.value) }, startIdx, src, isSmart), shuffle, pinFirst)
                        }
                    }
                }
                SessionProtocol.QUEUE_ADD -> {
                    val ids = args.getLongArray(SessionProtocol.ARG_IDS)?.toList().orEmpty()
                    val next = args.getString(SessionProtocol.ARG_WHERE) == SessionProtocol.WHERE_NEXT
                    return serialized {
                        insert(tracksFor(ids), next)
                        scheduleSave()
                    }
                }
                SessionProtocol.QUEUE_MOVE -> {
                    val from = args.getInt(SessionProtocol.ARG_FROM, -1)
                    val to = args.getInt(SessionProtocol.ARG_TO, -1)
                    return serialized {
                        move(from, to)
                        scheduleSave()
                    }
                }
                SessionProtocol.QUEUE_REMOVE -> {
                    val index = args.getInt(SessionProtocol.ARG_INDEX, -1)
                    return serialized {
                        removeAt(index)
                        scheduleSave()
                    }
                }
                SessionProtocol.SLEEP -> sleep.set(SessionProtocol.getSleep(args))
                SessionProtocol.RESTORE -> return serialized { restore() }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return ok
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future {
            mediaItems.flatMap { item -> browser.resolve(item)?.tracks.orEmpty().map(MediaItems::of) }.toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val single = mediaItems.singleOrNull()?.let { browser.resolve(it) }
            if (single != null) {
                applyExternalContext(single.source, single.smart)
                MediaSession.MediaItemsWithStartPosition(single.tracks.map(MediaItems::of), single.startIndex, 0)
            } else {
                val items = mediaItems.flatMap { browser.resolve(it)?.tracks.orEmpty().map(MediaItems::of) }
                applyExternalContext(null, false)
                val index = if (startIndex == C.INDEX_UNSET) C.INDEX_UNSET else startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
                MediaSession.MediaItemsWithStartPosition(items, index, if (index == C.INDEX_UNSET) C.TIME_UNSET else startPositionMs)
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                try {
                    val (snap, tracks) = loadSnapshot() ?: run {
                        result.setException(IllegalStateException("No saved queue"))
                        return@launch
                    }
                    source = snap.source?.toPlaySource()
                    smart = snap.smart
                    extendedAtCount = -1
                    player.repeatMode = snap.repeat.toPlayer()
                    pendingRestore = snap
                    publishExtras()
                    result.set(MediaSession.MediaItemsWithStartPosition(tracks.map(MediaItems::of), snap.index, snap.positionMs))
                } catch (c: CancellationException) {
                    result.cancel(false)
                } catch (t: Throwable) {
                    result.setException(t)
                }
            }
            return result
        }

        // ------------------------------------------------------------ browsing

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                putBoolean(EXTRAS_KEY_SEARCH_SUPPORTED, true)
            }
            val rootParams = LibraryParams.Builder().setOffline(true).setExtras(extras).build()
            return Futures.immediateFuture(LibraryResult.ofItem(this@PlaybackService.browser.root(), rootParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val children = this@PlaybackService.browser.children(parentId)
            if (children == null) LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE)
            else with(LibraryBrowser) { LibraryResult.ofItemList(children.page(page, pageSize), params) }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            val item = this@PlaybackService.browser.item(mediaId)
            if (item == null) LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE) else LibraryResult.ofItem(item, null)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val results = runCatching { this@PlaybackService.browser.search(query) }.getOrDefault(emptyList())
                if (searchResults.size >= MAX_CACHED_SEARCHES) searchResults.clear()
                searchResults[query] = results
                session.notifySearchResultChanged(browser, query, results.size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val results = searchResults[query] ?: this@PlaybackService.browser.search(query).also { searchResults[query] = it }
            with(LibraryBrowser) { LibraryResult.ofItemList(results.page(page, pageSize), params) }
        }
    }

    /** A controller outside the app (car, watch, assistant) replaced the queue. */
    private fun applyExternalContext(src: PlaySource?, isSmart: Boolean) {
        source = src
        smart = isSmart
        extendedAtCount = -1
        consecutiveErrors = 0
        reorderOnNextTimeline = player.shuffleModeEnabled
        publishExtras()
    }

    /**
     * Queue commands run one at a time, in arrival order: "play album" followed right away by
     * "add to queue" must not interleave while the first one is still computing its shuffle.
     */
    private fun serialized(block: suspend () -> Unit): ListenableFuture<SessionResult> = future {
        commandMutex.withLock { block() }
        SessionResult(SessionResult.RESULT_SUCCESS)
    }

    /** Runs [block] on the main thread as a Guava future (what Media3 callbacks return). */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        scope.launch {
            try {
                result.set(block())
            } catch (c: CancellationException) {
                result.cancel(false)
            } catch (t: Throwable) {
                result.setException(t)
            }
        }
        return result
    }

    private companion object {
        const val TASTE_TTL_MS = 5 * 60_000L
        const val SAVE_DEBOUNCE_MS = 1_500L
        const val POSITION_SAVE_MS = 15_000L
        const val MAX_SKIPS_ON_ERROR = 5
        const val MAX_CACHED_SEARCHES = 16
        /** Legacy browser root hint: the app supports search (Android Auto shows the search button). */
        const val EXTRAS_KEY_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }
}

internal fun Int.toRepeatMode(): RepeatMode = when (this) {
    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
    else -> RepeatMode.OFF
}

internal fun RepeatMode.toPlayer(): Int = when (this) {
    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
}
