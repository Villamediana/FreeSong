package com.villamediana.sound.playback

import com.villamediana.sound.playback.stream.AudioCache
import com.villamediana.sound.data.isOnline
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import com.villamediana.sound.MainActivity
import com.villamediana.sound.Notifications
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.Settings
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.playback.crossfade.AudioScanner
import com.villamediana.sound.playback.crossfade.CrossfadeMeasure
import com.villamediana.sound.playback.crossfade.FadeShaper
import com.villamediana.sound.playback.stream.OnlineStreams
import com.villamediana.sound.playback.stream.StreamDataSources
import com.villamediana.sound.playback.stream.StreamLoadErrorPolicy
import com.villamediana.sound.playback.stream.hasInternet
import com.villamediana.sound.smart.Taste
import com.villamediana.sound.smart.loadTaste
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import androidx.annotation.OptIn as AndroidxOptIn

/**
 * Foreground media service hosting ExoPlayer + MediaLibrarySession.
 *
 * Plays local files and online songs (streamed from YouTube in 2 MB ranges, see
 * [com.villamediana.sound.playback.stream]); a queue may mix both. Audio focus, "becoming noisy" (headphones unplugged / Bluetooth gone →
 * pause) and the wake lock are ExoPlayer's; the session makes Sound controllable from the
 * notification, lock screen, headset buttons (AVRCP), watches and cars, with a browse tree for the
 * latter. The service also owns everything that must survive the UI: smart shuffle, autoplay of
 * similar songs (from the library, or YouTube Music's radio after an online song), the sleep
 * timer, listening stats (library songs only) and the saved queue (online songs included). It also
 * hands ExoPlayer's audio session to the equalizer ([com.villamediana.sound.playback.AudioEffectsController]).
 */
@AndroidxOptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val graph get() = SoundApplication.graph
    private val library get() = graph.library

    private lateinit var player: ExoPlayer
    private lateinit var crossfader: Crossfader
    private lateinit var session: MediaLibrarySession
    private lateinit var sleep: SleepTimerRunner
    private lateinit var output: OutputDeviceMonitor
    private lateinit var browser: LibraryBrowser
    private lateinit var strings: PlaybackStrings
    private lateinit var streams: OnlineStreams
    private lateinit var artwork: ArtworkShare
    private val leveler = VolumeLeveler()
    /** The quick crossfade of a tap on a lyric line ([SeekBlend]). */
    private val seekBlend = SeekBlendProcessor()
    private val soundTap = SoundTap()
    /** The crossfade's volume and bass in the main player's own audio ([Crossfader]). */
    private val fadeShaper = FadeShaper()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listen = ListenTracker { SystemClock.elapsedRealtime() }

    // Library mirror (kept fresh by the repository's flow).
    private var allTracks: List<Track> = emptyList()
    private var tracksById: Map<Long, Track> = emptyMap()
    private var tracksByVideoId: Map<String, Track> = emptyMap()
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
    /** Media ids of the queue handed to Media3 by [onPlaybackResumption], to recognise it when it lands. */
    private var pendingRestoreKeys: List<String>? = null
    private var extendJob: Job? = null
    /** Songs of yours being picked to go on with, the connection gone ([bridgeOffline]). */
    private var bridgeJob: Job? = null
    private var extendedAtCount = -1
    /** Retries of an extension that found nothing (reset once one lands, or a new queue starts). */
    private var extendRetries = 0
    private var saveJob: Job? = null
    private var persistJob: Job? = null
    private var positionSaver: Job? = null
    private var consecutiveErrors = 0
    private var buttonsKey: String? = null
    private var wakeMode = C.WAKE_MODE_LOCAL
    private val searchResults = HashMap<String, List<MediaItem>>()
    private val commandMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        strings = PlaybackStrings.from(this)
        streams = OnlineStreams(graph.scope, online = { hasInternet(this) }) { graph.youTube }
        artwork = ArtworkShare(this)
        player = ExoPlayer.Builder(this, SeekBlend.renderers(this, seekBlend, soundTap.processor(), fadeShaper))
            // The song playing is kept whole in memory (back buffer): going back in it, or seeking to
            // a part already heard, plays at once instead of downloading that part again.
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBackBuffer(BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
                    .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(StreamDataSources.factory(this, streams, AudioCache.get(this)))
                    .setLoadErrorHandlingPolicy(StreamLoadErrorPolicy()),
            )
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
        attachEffects(player.audioSessionId)

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(Notifications.CHANNEL_PLAYBACK)
                .setChannelName(R.string.channel_playback)
                .setNotificationId(Notifications.ID_PLAYBACK)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )

        session = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setId("sound")
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
        // Crossfade: the end of the song and the start of the next are measured ahead; a tail player
        // plays the end out while the next one comes in, both shaped inside their own audio.
        crossfader = Crossfader(
            player = player,
            shaper = fadeShaper,
            newTail = {
                val tailShaper = FadeShaper()
                val tailPlayer = ExoPlayer.Builder(this, FadeShaper.renderers(this, tailShaper))
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(StreamDataSources.factory(this, streams, AudioCache.get(this)))
                            .setLoadErrorHandlingPolicy(StreamLoadErrorPolicy()),
                    )
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                        /* handleAudioFocus = */ false,
                    )
                    .build()
                Crossfader.Tail(tailPlayer, tailShaper)
            },
            measure = CrossfadeMeasure(scope, AudioScanner(StreamDataSources.factory(this, streams, AudioCache.get(this)))),
            seconds = { settings.crossfadeSeconds },
            blocked = { sleep.timer != SleepTimer.Off },
            // Same as the player's own "repeat one" transition: every time around is a listen.
            onLoop = {
                finishListen()
                startListen(player.currentMediaItem)
            },
        ).also { it.start() }
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
        // Closing Sound from Recents closes the music too (the Miguel's call): the queue is saved,
        // the listen recorded, the player paused and the service stopped.
        saveNow()
        finishListen()
        player.pause()
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        PlaybackPulse.playing.value = false
        saveNow()
        finishListen()
        crossfader.release()
        sleep.release()
        output.stop()
        leveler.release()
        runCatching { graph.effects.release() }
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    // ================================================================ library & settings

    private fun onLibrary(list: List<Track>) {
        allTracks = list
        tracksById = list.associateBy { it.id }
        tracksByVideoId = list.mapNotNull { t -> t.videoId?.takeIf { it.isNotBlank() }?.let { it to t } }.toMap()
        libraryLoaded = true
        syncQueueWithLibrary()
        updateButtons()
    }

    /**
     * Songs deleted from the library leave the queue; edited ones (title, cover…) refresh in place.
     * An online song that got downloaded meanwhile plays from the file from its next start on (the
     * playing one keeps streaming unless [includeCurrent], used right as it starts).
     */
    private fun syncQueueWithLibrary(includeCurrent: Boolean = false) {
        val n = player.mediaItemCount
        if (n == 0) return
        val current = player.currentMediaItemIndex
        val gone = ArrayList<Int>()
        val swaps = ArrayList<Pair<Int, MediaItem>>()
        var currentToFile: MediaItem? = null
        for (i in 0 until n) {
            val item = player.getMediaItemAt(i)
            val id = MediaItems.trackId(item)
            if (id == null) {
                val local = MediaItems.videoId(item)?.let(tracksByVideoId::get) ?: continue
                when {
                    i != current -> swaps += i to MediaItems.keepingMark(mediaItemOf(local), item)
                    includeCurrent -> currentToFile = MediaItems.keepingMark(mediaItemOf(local), item)
                }
                continue
            }
            val track = tracksById[id]
            if (track == null) gone += i
            else if (!upToDate(item, track)) swaps += i to MediaItems.keepingMark(mediaItemOf(track), item)
        }
        if (swaps.isEmpty() && gone.isEmpty() && currentToFile == null) return
        // A replaced uri is a remove + add for ExoPlayer, which would drop the song somewhere random in the shuffle.
        val before = if (player.shuffleModeEnabled && swaps.isNotEmpty()) playOrder() else null
        swaps.forEach { (i, replacement) -> player.replaceMediaItem(i, replacement) }
        before?.let(::setShuffleOrder)
        currentToFile?.let(::replaceCurrent)
        gone.asReversed().forEach { player.removeMediaItem(it) }
    }

    /**
     * Swaps the playing item for another source of the same song (it restarts from the top, so only
     * used as it starts): the replacement goes right after it in play order, then the old one leaves —
     * the player moves on to the replacement, and the rest of the (shuffle) order stays as it was.
     */
    private fun replaceCurrent(replacement: MediaItem) {
        val i = player.currentMediaItemIndex
        // On "repeat one" the player would have no "next" to move to; the song simply keeps streaming.
        if (i == C.INDEX_UNSET || player.repeatMode == Player.REPEAT_MODE_ONE) return
        val before = playOrder()
        suppressShuffleReorder = true
        try {
            player.addMediaItem(i + 1, replacement)
            if (player.shuffleModeEnabled) setShuffleOrder(before.inserted(before.playIndexOf(i) + 1, i + 1, 1))
            player.removeMediaItem(i)
        } finally {
            suppressShuffleReorder = false
        }
    }

    /** Library songs by video id (also before the library flow delivered, straight from the database). */
    private suspend fun localByVideoId(videoIds: Collection<String>): Map<String, Track> {
        if (videoIds.isEmpty()) return emptyMap()
        if (libraryLoaded) return videoIds.mapNotNull { id -> tracksByVideoId[id]?.let { id to it } }.toMap()
        return withContext(Dispatchers.IO) {
            videoIds.distinct().mapNotNull { id -> runCatching { library.trackByVideoId(id) }.getOrNull()?.let { id to it } }.toMap()
        }
    }

    /** The songs a command names (library ids and online songs), in order; null where nothing resolves. */
    private suspend fun resolveIds(ids: List<Long>, online: Map<Long, OnlineItem>): List<Track?> {
        val local = tracksFor(ids.filter { it >= 0 }.distinct()).associateBy { it.id }
        val byVideo = localByVideoId(online.values.map { it.videoId })
        return OnlineQueue.resolve(ids, online, local, byVideo)
    }

    private fun attachEffects(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        // Equalizer/BassBoost/Virtualizer and the leveler's DynamicsProcessing are separate effects on the same session.
        runCatching { graph.effects.attach(audioSessionId) }
    }

    private fun onSettings(s: Settings) {
        val previous = settings
        settings = s
        leveler.apply(s.normalizeVolume, player.audioSessionId)
        if (!previous.autoplaySimilar && s.autoplaySimilar) maybeExtend()
    }

    private suspend fun libraryTracks(): List<Track> =
        if (libraryLoaded) allTracks else withContext(Dispatchers.IO) { library.allTracks() }

    /** The whole library, smart-shuffled — what plays when there's no queue to go back to; null when it's empty. */
    private suspend fun libraryPlan(): QueuePlan? {
        val lib = libraryTracks()
        if (lib.isEmpty()) return null
        val taste = taste()
        val seed = Random.nextLong()
        return withContext(Dispatchers.Default) { SmartPlans.plan(SmartSeed.Library, lib, taste, strings, seed) }
    }

    private suspend fun tracksFor(ids: List<Long>): List<Track> =
        if (libraryLoaded) ids.mapNotNull(tracksById::get)
        else withContext(Dispatchers.IO) { ids.chunked(500).flatMap { library.getTracks(it) } }

    private suspend fun taste(): Taste {
        val now = System.currentTimeMillis()
        tasteCache?.takeIf { now - tasteAt < TASTE_TTL_MS }?.let { return it }
        // What fits this moment (the hour, the day, the place, who is along) plays first.
        val fit = runCatching { graph.forYou.libraryFit() }.getOrDefault(emptyMap())
        return withContext(Dispatchers.IO) { library.loadTaste(now, fit) }.also {
            tasteCache = it
            tasteAt = now
        }
    }

    // ================================================================ queue helpers

    /**
     * Media3 item for a song: the library file, or the online stream at the current quality setting.
     * Its cover is a content:// uri: the queue reaches other apps too (car, system media controls).
     */
    private fun mediaItemOf(track: Track): MediaItem = artwork.shareable(MediaItems.of(withAlbumArt(track), settings.audioQuality))

    /** A streamed video upload shows its album cover (when the app found one) instead of a frame of the clip. */
    private fun withAlbumArt(track: Track): Track {
        val videoId = track.videoId ?: return track
        if (!track.isOnline || (track.sourceUrl ?: track.filePath).contains("music.youtube.com")) return track
        val art = runCatching { graph.covers.coverFor(videoId) }.getOrNull() ?: return track
        return track.copy(coverPath = art)
    }

    /** True when the queue's [item] already shows what [track] says. */
    private fun upToDate(item: MediaItem, track: Track): Boolean =
        MediaItems.matches(item, track, artwork.shareableUri(MediaItems.coverUri(track.coverPath)))

    /** Media ids in timeline order — what identifies "the same queue" across async work. */
    private fun mediaKeys(): List<String> = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    /** The queue as tracks, timeline order: library songs, online songs as transient tracks; null = gone. */
    private fun queueTracks(): List<Track?> = (0 until player.mediaItemCount).map { i ->
        val item = player.getMediaItemAt(i)
        MediaItems.trackId(item)?.let(tracksById::get) ?: MediaItems.onlineTrack(item)
    }

    /** Keeps Wi-Fi awake too (not just the CPU) while the queue streams, so the screen going off doesn't cut it. */
    private fun updateWakeMode() {
        val streaming = (0 until player.mediaItemCount).any { MediaItems.isOnline(player.getMediaItemAt(it)) }
        val mode = if (streaming) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL
        if (mode == wakeMode) return
        wakeMode = mode
        player.setWakeMode(mode)
    }

    /** Resolves the next song's stream ahead of time, so an online song starts as fast as a local one. */
    private fun prefetchUpcoming() {
        val next = player.nextMediaItemIndex
        if (next == C.INDEX_UNSET || next >= player.mediaItemCount) return
        streams.prefetch(player.getMediaItemAt(next).localConfiguration?.uri)
    }

    private fun playOrder(): PlayOrder = PlayOrder.of(player.currentTimeline, player.shuffleModeEnabled)

    private fun currentTrack(): Track? = MediaItems.trackId(player.currentMediaItem)?.let(tracksById::get)

    private fun setShuffleOrder(order: PlayOrder) {
        if (order.size == player.mediaItemCount && order.size > 0) {
            player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
        }
    }

    /** Replaces the queue, starting at the plan's start song (also first when shuffled). [shuffleMode] = [SessionProtocol.SHUFFLE_KEEP] / ON / OFF. */
    /** The item playing is [track] (same library row, or same YouTube video). */
    private fun isPlaying(track: Track): Boolean {
        val item = player.currentMediaItem ?: return false
        val id = MediaItems.trackId(item)
        if (id != null && id == track.id) return true
        val videoId = MediaItems.videoId(item) ?: id?.let { tracksById[it]?.videoId }
        return videoId != null && videoId == track.videoId
    }

    private suspend fun playPlan(plan: QueuePlan, shuffleMode: Int, keepCurrent: Boolean = false, startPositionMs: Long = 0L, autoPlay: Boolean = true) {
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
            withContext(Dispatchers.Default) { QueueShuffler.order(tracks, start, useSmart, taste, seed) }
        } else null

        // Work computed for the old queue (its shuffle, its autoplay songs) must not land in this one.
        shuffleJob?.cancel()
        extendJob?.cancel()
        source = plan.source
        smart = plan.smart
        extendedAtCount = -1
        extendRetries = 0
        consecutiveErrors = 0
        reorderOnNextTimeline = false
        pendingRestore = null
        pendingRestoreKeys = null
        suppressShuffleReorder = true
        try {
            player.shuffleModeEnabled = shuffleOn
            if (keepCurrent && order == null && isPlaying(tracks[start])) {
                // A radio from the song playing: it keeps going, the radio is put around it.
                val idx = player.currentMediaItemIndex
                if (idx + 1 < player.mediaItemCount) player.removeMediaItems(idx + 1, player.mediaItemCount)
                if (idx > 0) player.removeMediaItems(0, idx)
                val before = tracks.take(start).map(::mediaItemOf)
                val after = tracks.drop(start + 1).map(::mediaItemOf)
                if (before.isNotEmpty()) player.addMediaItems(0, before)
                if (after.isNotEmpty()) player.addMediaItems(player.mediaItemCount, after)
            } else {
                player.setMediaItems(tracks.map(::mediaItemOf), order?.timelineIndexOf(0) ?: start, startPositionMs.coerceAtLeast(0))
            }
            if (order != null) player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), seed))
        } finally {
            suppressShuffleReorder = false
        }
        listen.updateContext(source?.type?.name)
        player.prepare()
        if (autoPlay) player.play() else player.pause()
        publishExtras()
        updateButtons()
        maybeExtend()
        prefetchUpcoming()
        scheduleSave()
    }

    /**
     * Inserts after the current song ([next]) or at the end — of the play order, also when shuffled.
     * The listener's "add to queue" goes after their own songs but before the ones the app added to
     * keep the queue going; the app's own ([suggested]) go at the very end, marked as such.
     * When the queue had already ended, the player moves on to the first added song: paused (Play
     * starts it), or carrying on when [keepPlaying] (autoplay's songs landing late).
     */
    private fun insert(tracks: List<Track>, next: Boolean, keepPlaying: Boolean = false, suggested: Boolean = false) {
        if (tracks.isEmpty()) return
        val items = tracks.map(::mediaItemOf).let { list -> if (suggested) list.map(MediaItems::suggested) else list }
        val n = player.mediaItemCount
        if (n == 0) {
            source = null
            smart = false
            player.setMediaItems(items)
            player.prepare()
            publishExtras()
            return
        }
        // Left at ENDED, the player ignores new songs and Play would restart the one that just finished.
        val ended = player.playbackState == Player.STATE_ENDED
        val cur = player.currentMediaItemIndex.coerceIn(0, n - 1)
        val before = playOrder()
        val playPos = when {
            next -> before.playIndexOf(cur) + 1
            suggested -> before.size
            else -> firstSuggestedAfter(before, cur) ?: before.size
        }
        // Unshuffled, the timeline is the play order.
        val timelinePos = if (!player.shuffleModeEnabled) playPos else if (next) cur + 1 else n
        player.addMediaItems(timelinePos, items)
        if (player.shuffleModeEnabled) setShuffleOrder(before.inserted(playPos, timelinePos, items.size))
        extendedAtCount = -1
        if (ended) {
            if (!keepPlaying) player.pause()
            player.seekTo(timelinePos, 0)
        }
    }

    /** Play index of the first song after [current] (timeline index) that the app added; null when none. */
    private fun firstSuggestedAfter(order: PlayOrder, current: Int): Int? =
        (order.playIndexOf(current) + 1 until order.size).firstOrNull { MediaItems.isSuggested(player.getMediaItemAt(order.timelineIndexOf(it))) }

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
        // Online songs shuffle like any other song (they just have no listening history to weigh).
        val keys = mediaKeys()
        val items = queueTracks()
        val current = player.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET }
        val useSmart = settings.smartShuffle
        val seed = Random.nextLong()
        shuffleJob = scope.launch {
            val taste = taste()
            val order = withContext(Dispatchers.Default) { QueueShuffler.order(items, current, useSmart, taste, seed) }
            if (player.shuffleModeEnabled && mediaKeys() == keys) {
                val now = player.currentMediaItemIndex
                setShuffleOrder(if (now != C.INDEX_UNSET) order.withFirst(now) else order)
                scheduleSave()
            }
            // The queue's end is known only now (see maybeExtend).
            shuffleJob = null
            maybeExtend()
        }
    }

    // ================================================================ autoplay / smart extension

    /**
     * The queue never runs out (repeat off; "autoplay similar" on, or a smart queue): when one song
     * is left after the playing one, songs like the last few of the list are added after it — your
     * own that fit, with new ones from YouTube Music in between ([QueueContinuation]). They are
     * marked as the app's: the queue shows them apart and "add to queue" goes before them.
     * [again] = called by the extension itself (its retry, or a queue that changed meanwhile).
     */
    private fun maybeExtend(again: Boolean = false) {
        if (suppressShuffleReorder) return // mid queue replacement; the caller extends when done
        // The shuffled order is being made: songs added now would change the queue under it and the
        // playing song would not go first. It extends once the order is set.
        if (shuffleJob?.isActive == true) return
        val count = player.mediaItemCount
        if (count == 0 || player.repeatMode != Player.REPEAT_MODE_OFF) return
        // A jam's queue is everyone's: it grows from the jam itself (JamSession), not here. Out of
        // the jam, the queue it left behind goes on like any other.
        if (source?.type == PlaySource.Type.JAM && graph.social.jam.active) return
        if (!settings.autoplaySimilar && !smart) return
        val current = player.currentMediaItemIndex
        if (current == C.INDEX_UNSET) return
        val order = playOrder()
        if (order.size - 1 - order.playIndexOf(current) > QueueContinuation.WHEN_LEFT) return
        if ((!again && extendJob?.isActive == true) || extendedAtCount == count) return
        extendedAtCount = count
        val items = (0 until order.size).map { player.getMediaItemAt(order.timelineIndexOf(it)) }
        val src = source
        val seed = Random.nextLong()
        extendJob = scope.launch {
            val local = tracksFor(items.mapNotNull(MediaItems::trackId).distinct()).associateBy { it.id }
            val list = items.mapNotNull { item -> MediaItems.trackId(item)?.let(local::get) ?: MediaItems.onlineTrack(item) }
            // Like what is being heard: the queue's last songs not skipped just now (and the song
            // a radio was started from, always), their radios and one drawn from them (another draw
            // every time), in the listener's taste.
            val session = runCatching { com.villamediana.sound.discover.SongRadio.sessionOf(library) }
                .getOrDefault(com.villamediana.sound.smart.Session())
            val seeds = QueueContinuation.seeds(list, skipped = session.skipped, anchor = QueueContinuation.anchor(src, list))
            val radio = runCatching {
                graph.songRadio.songs(seeds, list.mapNotNullTo(HashSet()) { it.videoId }, QueueContinuation.SIZE + 10, Random(seed), session)
            }.getOrDefault(emptyList())
            val radios = listOf(radio)
            val lib = libraryTracks()
            val taste = taste()
            val byVideo = localByVideoId(radios.flatten().map { it.videoId })
            val more = withContext(Dispatchers.Default) {
                // Your songs come in when they fit the end of the list; with nothing new to add
                // (offline), whatever of yours is closest keeps it going.
                val own = SmartPlans.extension(src, list, lib, taste, seed, fill = radio.isEmpty())
                QueueContinuation.blend(
                    own = own,
                    radios = radios,
                    ownShare = QueueContinuation.ownShare(list, src),
                    queuedIds = list.mapTo(HashSet()) { it.id },
                    queuedVideoIds = list.mapNotNullTo(HashSet()) { it.videoId },
                    byVideoId = byVideo,
                ).ifEmpty { QueueContinuation.again(list, lib, taste, seed) }
            }
            if (player.mediaItemCount != count) {
                // The queue changed meanwhile: decide again for the queue as it is now.
                extendedAtCount = -1
                maybeExtend(again = true)
                return@launch
            }
            if (more.isEmpty()) {
                retryExtend(count)
                return@launch
            }
            extendRetries = 0
            insert(more, next = false, keepPlaying = true, suggested = true)
            if (!smart) {
                smart = true
                publishExtras()
            }
            prefetchUpcoming()
            scheduleSave()
        }
    }

    /** Nothing to add yet (offline, say): tries again in a while, a few times, while the queue stays the same. */
    private fun retryExtend(count: Int) {
        if (extendRetries >= QueueContinuation.RETRIES) return
        extendRetries++
        extendJob = scope.launch {
            delay(QueueContinuation.RETRY_MS)
            if (player.mediaItemCount != count) return@launch
            extendedAtCount = -1
            maybeExtend(again = true)
        }
    }

    // ================================================================ listening stats

    /** Library songs by their id, online ones by their (negative) transient id. */
    private fun listenId(item: MediaItem?): Long? = MediaItems.trackId(item) ?: MediaItems.onlineItem(item)?.trackId

    /** The online song being listened to (its metadata goes with the listen). */
    private var listeningOnline: OnlineItem? = null

    private fun startListen(item: MediaItem?) {
        val id = listenId(item) ?: return
        listeningOnline = MediaItems.onlineItem(item)
        // The DNA: what the song sounds like, measured as it plays — unless it was measured enough already.
        val videoId = listeningOnline?.videoId ?: tracksById[id]?.videoId
        soundAsk?.cancel()
        soundAsk = null
        if (videoId.isNullOrBlank()) soundTap.rest() else {
            soundTap.begin()
            soundAsk = scope.launch {
                val known = try {
                    library.soundOf(videoId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (known?.settled == true) soundTap.rest()
            }
        }
        // The DNA: who is along (the jam) and where the phone is (when places are on), for this listen.
        listenPeople = runCatching { graph.jamPeople().toList() }.getOrDefault(emptyList())
        listenPlace = graph.places.last
        placeAsk?.cancel()
        placeAsk = scope.launch { runCatching { graph.places.now() }.getOrNull()?.let { listenPlace = it } }
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: tracksById[id]?.durationMs ?: listeningOnline?.durationMs ?: 0
        listen.start(id, duration, source?.type?.name, player.isPlaying)
    }

    private fun finishListen() {
        soundAsk?.cancel()
        soundAsk = null
        val sound = soundTap.take()
        val s = listen.finish() ?: return
        val online = listeningOnline?.takeIf { it.trackId == s.trackId }
        val videoId = online?.videoId ?: tracksById[s.trackId]?.videoId
        listeningOnline = null
        tasteCache = null
        val place = listenPlace
        val people = listenPeople
        graph.scope.launch {
            runCatching {
                if (online != null) library.recordOnlinePlay(online.toRemote(), s.listenedMs, s.durationMs, s.context, placeId = place, people = people)
                else if (s.trackId >= 0) library.recordPlay(s.trackId, s.listenedMs, s.durationMs, s.context, placeId = place, people = people)
            }
            if (sound != null && !videoId.isNullOrBlank()) runCatching { library.learnSound(videoId, sound) }
        }
    }

    /** Where and with whom the song being heard is heard (the DNA learns it with the song). */
    private var listenPlace: Long? = null
    private var listenPeople: List<String> = emptyList()
    private var placeAsk: kotlinx.coroutines.Job? = null
    private var soundAsk: kotlinx.coroutines.Job? = null

    // ================================================================ persistence

    private fun snapshot(): QueueSnapshot? {
        val n = player.mediaItemCount
        if (n == 0) return null
        val ids = ArrayList<Long>(n)
        val online = LinkedHashMap<String, OnlineItem>()
        for (i in 0 until n) {
            val item = player.getMediaItemAt(i)
            val local = MediaItems.trackId(item)
            if (local != null) {
                ids += local
                continue
            }
            val o = MediaItems.onlineItem(item) ?: return null
            ids += o.trackId
            online.putIfAbsent(o.videoId, o)
        }
        val shuffle = player.shuffleModeEnabled
        return QueueSnapshot(
            ids = ids,
            index = player.currentMediaItemIndex.coerceIn(0, n - 1),
            // A queue that ended comes back at the start of its last song, not at its very end (where Play does nothing).
            positionMs = if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0),
            shuffle = shuffle,
            order = if (shuffle) playOrder().toList() else emptyList(),
            repeat = player.repeatMode.toRepeatMode(),
            source = QueueSnapshot.SavedSource.of(source),
            smart = smart,
            savedAt = System.currentTimeMillis(),
            online = online.values.toList(),
            suggested = (0 until n).filter { MediaItems.isSuggested(player.getMediaItemAt(it)) },
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
        persist(json)
    }

    /** The queue is gone (emptied, or every song of the saved one deleted): nothing to bring back. */
    private fun forgetSavedQueue() {
        saveJob?.cancel()
        persist(null)
    }

    /** Writes the saved queue in call order (a save launched just before a "forget" must not land after it). */
    private fun persist(json: String?) {
        val store = graph.settings
        val previous = persistJob
        persistJob = graph.scope.launch {
            previous?.join()
            runCatching { store.setLastQueueJson(json) }
        }
    }

    /** The saved queue, without songs deleted meanwhile; online songs stream again (or play from the file if downloaded since). */
    private suspend fun loadSnapshot(): Pair<QueueSnapshot, List<Track>>? {
        val raw = QueueSnapshot.decode(graph.settings.current().lastQueueJson)
        val found = raw?.let { tracksFor(it.ids.filter { id -> id >= 0 }.distinct()) }.orEmpty()
        val snap = raw?.retain(found.mapTo(HashSet()) { it.id }) ?: run {
            // Nothing (left) to resume; unless a new queue started meanwhile, drop what's saved.
            if (player.mediaItemCount == 0) forgetSavedQueue()
            return null
        }
        val byId = found.associateBy { it.id }
        val online = snap.onlineById
        val byVideo = localByVideoId(online.values.map { it.videoId })
        return snap to snap.ids.map { id ->
            if (id < 0) online.getValue(id).let { byVideo[it.videoId] ?: it.toTrack() } else byId.getValue(id)
        }
    }

    /** The saved queue's items, the songs the app had added marked again. */
    private fun itemsOf(snap: QueueSnapshot, tracks: List<Track>): List<MediaItem> {
        val marked = snap.suggested.toHashSet()
        return tracks.mapIndexed { i, t -> mediaItemOf(t).let { if (i in marked) MediaItems.suggested(it) else it } }
    }

    /** Puts the saved queue back without playing (the mini player shows the last song). */
    private suspend fun restore() {
        if (player.mediaItemCount > 0) return
        val (snap, tracks) = loadSnapshot() ?: return
        if (player.mediaItemCount > 0) return
        extendJob?.cancel()
        extendedAtCount = -1
        extendRetries = 0
        source = snap.source?.toPlaySource()
        smart = snap.smart
        player.repeatMode = snap.repeat.toPlayer()
        suppressShuffleReorder = true
        try {
            player.shuffleModeEnabled = snap.shuffle
            player.setMediaItems(itemsOf(snap, tracks), snap.index, snap.positionMs)
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
        // An online song isn't in the library: nothing to like (the app offers to download it instead).
        val online = MediaItems.isOnline(player.currentMediaItem)
        val key = "$liked/$shuffle/$online"
        if (key == buttonsKey) return
        buttonsKey = key
        session.setMediaButtonPreferences(buttons(liked, shuffle, online))
    }

    private fun buttons(liked: Boolean, shuffle: Boolean, online: Boolean = false): List<CommandButton> = listOfNotNull(
        if (online) null
        else CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
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
                listenId(mediaItem) == listen.currentTrackId
            if (!sameSong) {
                finishListen()
                startListen(mediaItem)
            }
            // A song chosen while the player stood on an error ("next", "previous", a row of the
            // queue — from the app, the notification or the earphones): it plays, instead of the
            // player sitting idle on it.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
                player.playbackState == Player.STATE_IDLE && player.playerError != null
            ) {
                player.prepare()
            }
            // Downloaded while it waited in the queue: switch to the file now that it starts from the top.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                MediaItems.videoId(mediaItem)?.let(tracksByVideoId::containsKey) == true
            ) {
                syncQueueWithLibrary(includeCurrent = true)
            }
            updateButtons()
            maybeExtend()
            prefetchUpcoming()
            scheduleSave()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlaybackPulse.playing.value = isPlaying
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
            updateWakeMode()
            if (timeline.isEmpty) {
                finishListen()
                forgetSavedQueue()
                return
            }
            pendingRestore?.let { snap ->
                if (mediaKeys() == pendingRestoreKeys) {
                    pendingRestore = null
                    pendingRestoreKeys = null
                    // The shuffle mode was set before the queue landed; only the saved order is left.
                    if (player.shuffleModeEnabled) PlayOrder.validOrNull(snap.order, snap.ids.size)?.let(::setShuffleOrder)
                }
            }
            if (reorderOnNextTimeline) {
                reorderOnNextTimeline = false
                if (player.shuffleModeEnabled) applyShuffleOrder()
            }
            maybeExtend()
            prefetchUpcoming()
            scheduleSave()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (shuffleModeEnabled && !suppressShuffleReorder) applyShuffleOrder()
            if (!shuffleModeEnabled) shuffleJob?.cancel()
            updateButtons()
            maybeExtend()
            prefetchUpcoming()
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
            if (continueOffline(error)) return
            consecutiveErrors++
            // A broken or missing file, or a song that can't be streamed (offline, unavailable),
            // shouldn't stop the music: skip it (a few times at most). The app shows why.
            if (consecutiveErrors <= MAX_SKIPS_ON_ERROR && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            leveler.apply(settings.normalizeVolume, audioSessionId)
            attachEffects(audioSessionId)
        }
    }

    /**
     * The connection dropped under an online song (offline it fails at once, no request, no retries
     * — see OnlineStreams; a network that carries nothing fails once the player's retries run out).
     * The music goes on with the songs on the phone, without counting as broken songs: the next
     * downloaded song of the queue, past the other online ones; with none ahead, songs of yours like
     * what was playing come in right after this one ([OfflineBridge]). The app shows why ("Sem
     * conexão"). With nothing on the phone (or in a jam, whose queue is everyone's) it stops here.
     */
    private fun continueOffline(error: PlaybackException): Boolean {
        if (!OfflineBridge.isConnectionLoss(error.errorCode, MediaItems.isOnline(player.currentMediaItem))) return false
        val next = nextLocalIndex()
        if (next != C.INDEX_UNSET) {
            player.seekTo(next, 0)
            player.prepare()
        } else if (!(source?.type == PlaySource.Type.JAM && graph.social.jam.active)) {
            bridgeOffline()
        }
        return true
    }

    /**
     * No song of the phone ahead in the queue: songs of yours like the ones heard go in right after
     * the one that stopped, and play. The online songs stay after them.
     */
    private fun bridgeOffline() {
        bridgeJob?.cancel()
        val current = player.currentMediaItemIndex
        if (current == C.INDEX_UNSET) return
        val keys = mediaKeys()
        val order = playOrder()
        val heard = (0..order.playIndexOf(current)).map { player.getMediaItemAt(order.timelineIndexOf(it)) }
        val src = source
        val seed = Random.nextLong()
        bridgeJob = scope.launch {
            val local = tracksFor(heard.mapNotNull(MediaItems::trackId).distinct()).associateBy { it.id }
            val list = heard.mapNotNull { item -> MediaItems.trackId(item)?.let(local::get) ?: MediaItems.onlineTrack(item) }
            val lib = libraryTracks()
            val taste = taste()
            val songs = withContext(Dispatchers.Default) { OfflineBridge.songs(src, list, lib, taste, seed) }
            // Made for the queue as it was: the listener may have moved on meanwhile (or pressed play).
            if (songs.isEmpty() || mediaKeys() != keys || player.currentMediaItemIndex != current || player.playerError == null) return@launch
            insert(songs, next = true)
            // Right after the one that stopped, in play order too (shuffled or not).
            player.seekTo(current + 1, 0)
            player.prepare()
            scheduleSave()
        }
    }

    /** Timeline index of the next library song after the current one, in play order (around again on "repeat all"). */
    private fun nextLocalIndex(): Int {
        val timeline = player.currentTimeline
        val current = player.currentMediaItemIndex
        if (timeline.isEmpty || current == C.INDEX_UNSET) return C.INDEX_UNSET
        val repeat = if (player.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        var i = current
        while (true) {
            i = timeline.getNextWindowIndex(i, repeat, player.shuffleModeEnabled)
            if (i == C.INDEX_UNSET || i == current) return C.INDEX_UNSET
            if (!MediaItems.isOnline(player.getMediaItemAt(i))) return i
        }
    }

    // ================================================================ session callback

    private inner class SessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            val commands = base.availableSessionCommands.buildUpon()
            SessionProtocol.publicCommands.forEach { commands.add(SessionProtocol.command(it)) }
            if (controller.packageName == packageName) SessionProtocol.appCommands.forEach { commands.add(SessionProtocol.command(it)) }
            else artwork.grantCovers(controller.packageName, graph.storage.coversDir)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands.build())
                // Explicit: Media3 1.11's default result handed controllers a single player command, so
                // the app (mini player, queue, lyrics) never saw the current song or the queue.
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
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
                    val keepCurrent = args.getBoolean(SessionProtocol.ARG_CONTINUE)
                    val startPosition = args.getLong(SessionProtocol.ARG_START_POSITION, 0L)
                    val autoPlay = args.getBoolean(SessionProtocol.ARG_AUTOPLAY, true)
                    val online = SessionProtocol.getOnline(args)
                    return serialized {
                        val kept = resolveIds(ids, online).withIndex().mapNotNull { (i, t) -> t?.let { IndexedValue(i, it) } }
                        if (kept.isNotEmpty()) {
                            // The requested song plays first, shuffled or not ("Aleatório" buttons pick a random start themselves).
                            val startIdx = kept.indexOfFirst { it.index >= start }.coerceAtLeast(0)
                            playPlan(QueuePlan(kept.map { it.value }, startIdx, src, isSmart), shuffle, keepCurrent, startPosition, autoPlay)
                        }
                    }
                }
                SessionProtocol.QUEUE_ADD -> {
                    val ids = args.getLongArray(SessionProtocol.ARG_IDS)?.toList().orEmpty()
                    val next = args.getString(SessionProtocol.ARG_WHERE) == SessionProtocol.WHERE_NEXT
                    val online = SessionProtocol.getOnline(args)
                    return serialized {
                        insert(resolveIds(ids, online).filterNotNull(), next)
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
                SessionProtocol.SET_SHUFFLE -> {
                    val enabled = args.getBoolean(SessionProtocol.ARG_ENABLED)
                    return serialized { player.shuffleModeEnabled = enabled }
                }
                SessionProtocol.SLEEP -> sleep.set(SessionProtocol.getSleep(args))
                SessionProtocol.PREFETCH -> args.getStringArray(SessionProtocol.ARG_VIDEO_IDS)?.forEach { id ->
                    streams.prefetch(com.villamediana.sound.playback.stream.StreamUris.audio(id, settings.audioQuality))
                }
                SessionProtocol.RESTORE -> return serialized { restore() }
                SessionProtocol.SEEK_BLEND -> {
                    if (player.isPlaying) seekBlend.arm(player.currentPosition)
                    player.seekTo(args.getLong(SessionProtocol.ARG_POSITION).coerceAtLeast(0))
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return ok
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future {
            mediaItems.flatMap { item -> queuedOnline(item)?.let(::listOf) ?: browser.resolve(item)?.tracks.orEmpty().map(::mediaItemOf) }.toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val single = mediaItems.singleOrNull()?.takeIf { queuedOnline(it) == null }?.let { browser.resolve(it) }
            if (single != null) {
                applyExternalContext(single.source, single.smart)
                MediaSession.MediaItemsWithStartPosition(single.tracks.map(::mediaItemOf), single.startIndex, 0)
            } else {
                val items = mediaItems.flatMap { queuedOnline(it)?.let(::listOf) ?: browser.resolve(it)?.tracks.orEmpty().map(::mediaItemOf) }
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
                        // Nothing saved (or all of it deleted since). A play press must still play
                        // something: the service may have been started in the foreground for it.
                        val plan = if (isForPlayback) libraryPlan() else null
                        if (plan == null) {
                            result.setException(IllegalStateException("Nothing to resume"))
                        } else {
                            applyExternalContext(plan.source, plan.smart)
                            result.set(MediaSession.MediaItemsWithStartPosition(plan.tracks.map(::mediaItemOf), plan.startIndex, 0))
                        }
                        return@launch
                    }
                    val items = itemsOf(snap, tracks)
                    // Only a play press replaces the queue; the system's resume card just shows the item.
                    if (isForPlayback) {
                        extendJob?.cancel()
                        source = snap.source?.toPlaySource()
                        smart = snap.smart
                        extendedAtCount = -1
                        extendRetries = 0
                        player.repeatMode = snap.repeat.toPlayer()
                        // The shuffle mode goes now, outside any player callback: set from onTimelineChanged,
                        // ExoPlayer would deliver the change after it and the saved order would be reshuffled.
                        suppressShuffleReorder = true
                        try {
                            player.shuffleModeEnabled = snap.shuffle
                        } finally {
                            suppressShuffleReorder = false
                        }
                        pendingRestore = snap.takeIf { it.shuffle }
                        pendingRestoreKeys = items.map { it.mediaId }
                        publishExtras()
                    }
                    // The system's resume card shows the current song's cover: it gets to read it.
                    val shown = if (isForPlayback) items else items.mapIndexed { i, item -> if (i == snap.index) artwork.forController(item, controller.packageName) else item }
                    result.set(MediaSession.MediaItemsWithStartPosition(shown, snap.index, snap.positionMs))
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
            else with(LibraryBrowser) { LibraryResult.ofItemList(artwork.forController(children.page(page, pageSize), browser.packageName), params) }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            val item = queuedOnline(mediaId) ?: this@PlaybackService.browser.item(mediaId)
            if (item == null) LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE)
            else LibraryResult.ofItem(artwork.forController(item, browser.packageName), null)
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
            with(LibraryBrowser) { LibraryResult.ofItemList(artwork.forController(results.page(page, pageSize), browser.packageName), params) }
        }
    }

    /** An online song of the current queue named by a controller (they only know it by its "yt:" id). */
    private fun queuedOnline(item: MediaItem): MediaItem? = queuedOnline(item.mediaId)

    private fun queuedOnline(mediaId: String): MediaItem? {
        if (!mediaId.startsWith(com.villamediana.sound.data.ONLINE_MEDIA_PREFIX)) return null
        return (0 until player.mediaItemCount).map(player::getMediaItemAt).firstOrNull { it.mediaId == mediaId }
    }

    /** A controller outside the app (car, watch, assistant) replaced the queue. */
    private fun applyExternalContext(src: PlaySource?, isSmart: Boolean) {
        extendJob?.cancel()
        source = src
        smart = isSmart
        extendedAtCount = -1
        extendRetries = 0
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
        /** A long song whole (audio is ~1–2 MB a minute). */
        const val BACK_BUFFER_MS = 20 * 60_000
        const val TARGET_BUFFER_BYTES = 48 * 1024 * 1024
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
