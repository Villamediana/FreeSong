package com.villamediana.sound.ui.player

import androidx.media3.common.Player
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.villamediana.sound.playback.video.PlayerCanvas
import com.villamediana.sound.download.DownloadWait
import com.villamediana.sound.ui.nav.Chrome
import com.villamediana.sound.ui.nav.PlayerHooks
import androidx.compose.runtime.MutableState
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.playback.PlayerConnection
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.CoverColors
import com.villamediana.sound.ui.components.rememberCoverColor
import com.villamediana.sound.ui.components.seedColor
import com.villamediana.sound.ui.nav.LocalNavigator
import com.villamediana.sound.ui.search.Connectivity
import com.villamediana.sound.ui.search.DownloadLookup
import com.villamediana.sound.ui.search.ItemDownload
import com.villamediana.sound.ui.search.toRemoteTrack
import com.villamediana.sound.ui.sheets.TrackActionsSheet
import com.villamediana.sound.ui.sheets.rememberSheetFeedback
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** What the player layer needs from the rest of the app (swappable in tests). */
internal class PlayerDeps(
    val player: PlayerConnection,
    val trackFlow: (Long) -> Flow<Track?>,
    val setLiked: (Long, Boolean) -> Unit,
    /** Library row of a song by its YouTube id — a streamed song that is (or just got) downloaded. */
    val libraryTrack: (String) -> Flow<Track?> = { flowOf(null) },
    /** Download state of every song: the "Baixar" button that stands in for the heart of streamed songs. */
    val downloads: Flow<DownloadLookup> = flowOf(DownloadLookup.Empty),
    val download: (RemoteTrack) -> Unit = {},
    /** Lyrics of streamed songs (LRCLIB on demand); null = none. */
    val lyrics: OnlineLyricsCache? = null,
    val online: StateFlow<Boolean> = MutableStateFlow(true),
    /** Video ids in Curtidas: the heart of a streamed song. */
    val likedVideoIds: Flow<Set<String>> = flowOf(emptySet()),
    /** "Curtir já baixa": the heart alone stands for both, as on the rows. */
    val likeDownloads: Flow<Boolean> = flowOf(false),
    /** The heart of a streamed song (AppGraph.likeOnline). */
    val likeOnline: (RemoteTrack, Boolean) -> Unit = { _, _ -> },
    /** What queued downloads wait for (a connection, the Wi-Fi). */
    val downloadWait: Flow<DownloadWait> = flowOf(DownloadWait.NONE),
    /** The silent clip looping behind the cover; null = none (tests, no video support). */
    val canvas: PlayerCanvas? = null,
    /** "Vídeo no player" (Settings): off, the plain cover — no clip, no video from the cover. */
    val videoOn: Flow<Boolean> = flowOf(true),
    /** Loads the artist's page ahead (ArtistPrefetch, only that one); no-op in tests. */
    val prefetchArtist: (String) -> Unit = {},
    /** The artist's picture when it was found before (no waiting), for their page's first frame. */
    val artistPicture: (String) -> String? = { null },
)

/** One lyrics cache for the whole process: reopening the player doesn't ask LRCLIB again. */
internal object PlayerLyrics {
    val cache: OnlineLyricsCache by lazy {
        OnlineLyricsCache(
            find = { q -> SoundApplication.graph.lyrics.find(q.title, q.artist, q.album, q.durationMs) },
            file = java.io.File(SoundApplication.graph.appContext.filesDir, "lyrics-online.json"),
        )
    }
}

/**
 * Mini player docked above the bottom bar + the full-screen player it expands into, with one
 * continuous drag-driven transition (the cover flies from the mini card to the big pager).
 */
@Composable
fun PlayerLayer(
    bottomBarHeight: Dp,
    expandedMemory: MutableState<Boolean>,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val deps = remember {
        val graph = SoundApplication.graph
        PlayerDeps(
            player = graph.player,
            trackFlow = { id -> graph.library.track(id) },
            setLiked = { id, liked -> graph.likeTrack(id, liked) },
            libraryTrack = { videoId -> graph.library.tracks.map { all -> all.firstOrNull { it.videoId == videoId } }.distinctUntilChanged() },
            downloads = combine(graph.library.downloadedVideoIds, graph.downloads.tasks) { done, tasks -> DownloadLookup.build(done, tasks) }
                .flowOn(Dispatchers.Default),
            download = { remote -> graph.scope.launch { runCatching { graph.downloads.download(listOf(remote)) } } },
            lyrics = PlayerLyrics.cache,
            online = Connectivity.online(graph.appContext)
                .stateIn(graph.scope, SharingStarted.WhileSubscribed(5_000), Connectivity.isOnline(graph.appContext)),
            likedVideoIds = graph.library.likedVideoIds,
            likeDownloads = graph.settings.settings.map { it.likeDownloads }.distinctUntilChanged(),
            likeOnline = graph::likeOnline,
            downloadWait = graph.downloads.wait,
            canvas = graph.canvas,
            videoOn = graph.settings.settings.map { it.playerVideo }.distinctUntilChanged(),
            prefetchArtist = graph.artistPrefetch::prefetch,
            artistPicture = { name -> graph.discover.peekArtist(name)?.thumbnailUrl },
        )
    }
    PlayerHost(deps, bottomBarHeight, modifier, expandedMemory, active)
}

/** How far the mini card sinks into the bottom bar's faded top edge. */
private val MiniDockOverlap = 0.dp

/** A song kept on screen this long gets its artist page loaded ahead (not every skip). */
private const val ARTIST_PREFETCH_DELAY_MS = 1_200L

/** How long a tap on a song waits for the player to have it before opening it expanded. */
private const val OPEN_REQUEST_TTL_MS = 5_000L

/** Mini content is gone (not placed) past this expand progress. */
private const val MINI_FADE_END = 0.16f

@Composable
internal fun PlayerHost(
    deps: PlayerDeps,
    bottomBarHeight: Dp,
    modifier: Modifier = Modifier,
    /** Open/closed, kept by the shell: the layer leaves while a full page is on screen. */
    expandedMemory: MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },
    /** False while the layer fades out under a full page: back belongs to the page. */
    active: Boolean = true,
) {
    val player = deps.player
    val state by player.state.collectAsState()
    val sleep by player.sleepTimer.collectAsState()
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current

    var savedExpanded by expandedMemory
    val expand = remember { ExpandState(if (savedExpanded) 1f else 0f, scope) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var sleepOpen by rememberSaveable { mutableStateOf(false) }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    // The song the ⋮ sheet was opened for: it stays on it when the player moves to the next one.
    var actionsFor by remember { mutableStateOf<Track?>(null) }

    LaunchedEffect(expand) { snapshotFlow { expand.target }.collect { savedExpanded = it == 1f } }

    val hasMediaNow by rememberUpdatedState(state.hasMedia)
    DisposableEffect(navigator, expand) {
        val closeSheets = {
            queueOpen = false
            sleepOpen = false
            actionsFor = null
        }
        val hooks = PlayerHooks(
            isOpen = { expand.target == 1f },
            collapse = {
                lyricsOpen = false
                closeSheets()
                if (expand.progress > 0f || expand.target > 0f) expand.animateTo(0f)
            },
            closeSheets = closeSheets,
            expand = { if (hasMediaNow) expand.animateTo(1f) },
        )
        navigator.player = hooks
        // A layer leaving late must not unhook the one that took its place.
        onDispose { if (navigator.player === hooks) navigator.player = PlayerHooks() }
    }

    // A song tapped to play opens the player expanded, as soon as the player has it (the first
    // song of the session needs the service). A request that found no song in 5 s is dropped.
    // A request made just before the layer came (it was hidden, e.g. under the listening screen)
    // still counts; older ones don't.
    var handledOpen by remember {
        val at = Chrome.openPlayerAt
        val age = android.os.SystemClock.uptimeMillis() - at
        mutableStateOf(if (at > 0L && age in 0 until OPEN_REQUEST_TTL_MS) -1L else at)
    }
    val openAt = Chrome.openPlayerAt
    LaunchedEffect(openAt, state.hasMedia) {
        if (openAt != handledOpen && state.hasMedia) {
            handledOpen = openAt
            if (android.os.SystemClock.uptimeMillis() - openAt in 0 until OPEN_REQUEST_TTL_MS) expand.animateTo(1f)
        }
    }

    // Keep the last song so the exit animation still has something to draw.
    var lastTrack by remember { mutableStateOf(state.current) }
    LaunchedEffect(state.current) { state.current?.let { lastTrack = it } }
    LaunchedEffect(state.hasMedia) {
        if (!state.hasMedia) {
            expand.snapTo(0f)
            lyricsOpen = false
            queueOpen = false
            sleepOpen = false
        }
    }
    val shown = state.current ?: lastTrack ?: return
    val online by deps.online.collectAsState()

    // Live row from the library: likes, edits and lyrics that arrive later show up at once. A
    // streamed song is looked up by its YouTube id: once downloaded it becomes a library song.
    val streaming = shown.isOnline
    val live by remember(shown.id, shown.videoId) {
        if (streaming) shown.videoId?.let(deps.libraryTrack) ?: flowOf(null) else deps.trackFlow(shown.id)
    }.collectAsState(initial = null)
    val libraryCopy = live?.takeIf { if (streaming) it.videoId == shown.videoId else it.id == shown.id }
    val playingTrack = libraryCopy ?: shown
    // A video upload streamed: its album cover instead of a frame of the clip (once found).
    val albumArt = com.villamediana.sound.ui.components.rememberSongCover(playingTrack)
    val track = if (albumArt != playingTrack.coverPath) playingTrack.copy(coverPath = albumArt) else playingTrack
    val download by remember(track.id, track.videoId) {
        val videoId = track.videoId
        if (track.isOnline && videoId != null) deps.downloads.map { it.stateOf(videoId) }.distinctUntilChanged() else flowOf(ItemDownload.None)
    }.collectAsState(initial = ItemDownload.None)
    val likeDownloads by deps.likeDownloads.collectAsState(initial = false)
    val downloadWait by deps.downloadWait.collectAsState(initial = DownloadWait.NONE)
    val stream = StreamUi(streamed = track.isOnline, download = download, online = online, likeDownloads = likeDownloads, wait = downloadWait)
    // A streamed song is liked by its video id (Curtidas keeps it without a library row).
    val likedIds by deps.likedVideoIds.collectAsState(initial = emptySet())
    val baseLiked = if (track.isOnline) track.videoId in likedIds else track.liked
    val likeOverrides = remember { mutableStateMapOf<Long, Boolean>() }
    LaunchedEffect(track.id, baseLiked) { if (likeOverrides[track.id] == baseLiked) likeOverrides.remove(track.id) }
    val liked = likeOverrides[track.id] ?: baseLiked

    val seed = track.album ?: track.title
    val fallbackColor = SoundColors.Accent
    // Streamed songs have an http cover: extract its color through the network image cache.
    val washTarget = if (track.coverPath?.startsWith("http") == true) {
        com.villamediana.sound.ui.search.rememberRemoteCoverColor(track.coverPath, fallbackColor)
    } else {
        rememberCoverColor(track.coverPath, track.accentColor, fallbackColor)
    }
    val wash = animateColorAsState(washTarget, tween(700), label = "wash")

    val playing = state.isPlaying || state.isBuffering
    val durationMs = state.durationMs.takeIf { it > 0 } ?: track.durationMs
    val clock = rememberPlaybackClock(player, state.isPlaying, durationMs, state.current?.let { it.videoId ?: it.id })
    val coverScale = animateFloatAsState(if (playing) 1f else 0.88f, spring(dampingRatio = 0.62f, stiffness = 260f), label = "cover")
    // A streamed song that gets downloaded mid-play turns into its library row; keep treating it
    // as streamed for lyrics (LRCLIB) until the song changes, unless the download saved a file.
    var streamedAs by remember { mutableStateOf<Track?>(null) }
    LaunchedEffect(shown.videoId, shown.isOnline) {
        if (shown.isOnline) streamedAs = shown else if (streamedAs?.videoId != shown.videoId) streamedAs = null
    }
    val lyricsSubject = streamedAs?.takeIf { shown.videoId != null && it.videoId == shown.videoId } ?: shown
    val lyrics by rememberLyrics(LyricsOrigin.choose(lyricsSubject, libraryCopy), online, deps.lyrics)
    val output = rememberOutputRoute()
    val feedback = rememberSheetFeedback()

    val latestState by rememberUpdatedState(state)
    val latestTrack by rememberUpdatedState(track)
    val latestOnline by rememberUpdatedState(online)
    val latestDownload by rememberUpdatedState(download)
    val latestWait by rememberUpdatedState(downloadWait)
    val actions = remember(player, navigator, expand) {
        PlayerActions(
            togglePlay = { player.togglePlayPause() },
            next = { player.next() },
            previous = { player.previous() },
            seek = { pos ->
                clock.seek(pos, System.nanoTime())
                player.seekTo(pos)
            },
            skipTo = { player.skipTo(it) },
            toggleShuffle = { player.setShuffle(!latestState.shuffle) },
            cycleRepeat = { player.cycleRepeat() },
            like = { value ->
                val t = latestTrack
                likeOverrides[t.id] = value
                val remote = if (t.isOnline) t.toRemoteTrack() else null
                if (remote != null) deps.likeOnline(remote, value) else deps.setLiked(t.id, value)
            },
            collapse = { expand.animateTo(0f) },
            openQueue = { queueOpen = true },
            openSleep = { sleepOpen = true },
            openLyrics = { lyricsOpen = true },
            openActions = { actionsFor = latestTrack },
            // The artist's page with all their songs, as in the search; offline, a song of yours
            // opens its artist in the library.
            openArtist = { t ->
                val name = com.villamediana.sound.stats.Recaps.mainArtist(t.artist)
                if (latestOnline || t.isOnline) navigator.findArtist(name, deps.artistPicture(name)) else navigator.openLocalArtist(t.artist)
            },
            // A song of yours: its album in the library, which shows the rest of the album too.
            // A streamed one: the album's online page.
            openAlbum = { t ->
                val key = t.albumKey
                when {
                    key == null -> Unit
                    !t.isOnline -> navigator.openAlbum(key)
                    else -> navigator.findAlbum(key.title, com.villamediana.sound.stats.Recaps.mainArtist(key.artist))
                }
            },
            openOutput = { OutputRoutes.openSwitcher(context) },
            download = {
                val t = latestTrack
                val d = latestDownload
                if (t.isOnline && (d == ItemDownload.None || d is ItemDownload.Failed)) {
                    t.toRemoteTrack()?.let { remote ->
                        deps.download(remote)
                        // Offline / waiting for the Wi-Fi: it is queued and starts by itself — say when.
                        feedback.show(
                            when (latestWait) {
                                DownloadWait.NONE -> context.getString(R.string.player_download_started, t.title)
                                DownloadWait.NETWORK -> context.getString(R.string.download_wait_network)
                                DownloadWait.WIFI -> context.getString(R.string.download_wait_wifi)
                            },
                        )
                    }
                }
            },
            openEqualizer = { navigator.openEqualizer() },
            seekLine = { pos ->
                clock.seek(pos, System.nanoTime())
                player.seekBlended(pos)
            },
            openVideo = {
                val t = latestTrack
                val videoId = t.videoId
                when {
                    videoId == null -> Unit
                    !latestOnline -> feedback.show(context.getString(R.string.player_video_offline))
                    else -> navigator.openVideo(videoId, t.title)
                }
            },
        )
    }

    val expandedNow by remember { derivedStateOf { expand.target == 1f } }

    // While the full player is open, the song's artist page loads ahead: a tap on the name opens
    // it at once (it took a few seconds to find and load).
    LaunchedEffect(expandedNow, online, track.artist) {
        if (expandedNow && online && track.artist.isNotBlank()) {
            kotlinx.coroutines.delay(ARTIST_PREFETCH_DELAY_MS)
            deps.prefetchArtist(track.artist)
        }
    }

    // The clip behind the cover: only while the full player is open and on screen (not under a
    // full page, not with the lyrics over it, not in the background), online, for a YouTube song.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifeState by lifecycle.currentStateFlow.collectAsState()
    val canvas = deps.canvas
    val videoOn by deps.videoOn.collectAsState(initial = false)
    val canvasOn = canvas != null && videoOn && expandedNow && active && online && !lyricsOpen &&
        track.videoId != null && lifeState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(canvas, canvasOn, track.videoId) { canvas?.show(if (canvasOn) track.videoId else null) }
    DisposableEffect(canvas) { onDispose { canvas?.show(null) } }
    val canvasPlayer by (canvas?.player ?: remember { MutableStateFlow<Player?>(null) }).collectAsState()
    val canvasFor by (canvas?.videoId ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
    val canvasClip = canvasPlayer?.takeIf { canvasOn && canvasFor == track.videoId }
    // Its second copy, over it: the loop's end melts into the loop's own start.
    val canvasTwin by (canvas?.twin ?: remember { MutableStateFlow<Player?>(null) }).collectAsState()
    // Back goes to the newest enabled handler. The screen under the player (Search clearing its
    // query, a playlist leaving reorder mode) registered its own after ours, so ours are added
    // again whenever the player or the lyrics open: they sit on top while they are showing.
    key(expandedNow, lyricsOpen) {
        PredictiveBackHandler(enabled = active && state.hasMedia && expandedNow && !lyricsOpen) { events ->
            try {
                events.collect { expand.backProgress(it.progress) }
                expand.animateTo(0f)
            } catch (e: CancellationException) {
                expand.animateTo(1f)
                throw e
            }
        }
        BackHandler(enabled = active && lyricsOpen) { lyricsOpen = false }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val layerW = constraints.maxWidth.toFloat()
        val layerH = constraints.maxHeight.toFloat()
        val miniH: Float
        val margin: Float
        val miniCorner: Float
        val miniTop: Float
        val miniCover: Rect
        val coverCornerMini: Float
        val coverCornerBig: Float
        val fallbackBig: Rect
        with(density) {
            miniH = Dimens.MiniPlayerHeight.toPx()
            margin = MiniSideMargin.toPx()
            miniCorner = MiniCardCorner.toPx()
            miniTop = layerH - (bottomBarHeight - MiniDockOverlap).toPx() - miniH
            val inset = MiniCoverInset.toPx()
            val c = MiniCoverSize.toPx()
            miniCover = Rect(margin + inset, miniTop + inset, margin + inset + c, miniTop + inset + c)
            coverCornerMini = MiniCoverCorner.toPx()
            coverCornerBig = BigCoverCorner.toPx()
            // Only used until the big cover has been laid out once.
            val side = PlayerGutter.toPx()
            val size = minOf(layerW - 2 * side, layerH * 0.45f)
            val top = WindowInsets.statusBars.getTop(this) + 76.dp.toPx()
            fallbackBig = Rect(side, top, side + size, top + size)
        }
        val range = miniTop.coerceAtLeast(1f)
        var coverBounds by remember(layerW, layerH) { mutableStateOf<Rect?>(null) }

        // ---- mini player gestures: tap/drag up = expand, horizontal swipe = next/previous
        val swipe = remember { Animatable(0f) }
        val swipeWidth = layerW - 2 * margin
        val canNext by rememberUpdatedState(PlayerMath.hasNext(state))
        val previousIndex by rememberUpdatedState(PlayerMath.previousIndex(state))
        val currentId by rememberUpdatedState(state.current?.id)
        val flingPx = with(density) { 700.dp.toPx() }
        suspend fun slideAndChange(direction: Float, command: () -> Unit) {
            val old = currentId
            swipe.animateTo(direction * swipeWidth, tween(150, easing = FastOutLinearInEasing))
            command()
            withTimeoutOrNull(900) { snapshotFlow { currentId }.first { it != old } }
            swipe.snapTo(-direction * swipeWidth * 0.45f)
            swipe.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f))
        }
        val expandLabel = stringResource(R.string.player_expand)
        val miniGestures = Modifier
            .clickable(interactionSource = null, indication = null, onClickLabel = expandLabel) { expand.animateTo(1f) }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { d -> expand.dragBy(d, range) },
                onDragStarted = { expand.startDrag() },
                onDragStopped = { v -> expand.endDrag(v, range) },
            )
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { d ->
                    val next = swipe.value + d
                    val blocked = (next < 0f && !canNext) || (next > 0f && previousIndex == null)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { swipe.snapTo(swipe.value + if (blocked) d * 0.25f else d) }
                },
                onDragStopped = { v ->
                    when (PlayerMath.miniSwipe(swipe.value, swipeWidth, v, flingPx, canNext, previousIndex != null)) {
                        PlayerMath.Swipe.NEXT -> slideAndChange(-1f) { player.next() }
                        PlayerMath.Swipe.PREVIOUS -> previousIndex?.let { i -> slideAndChange(1f) { player.skipTo(i) } }
                        PlayerMath.Swipe.NONE -> swipe.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 500f))
                    }
                },
            )

        fun frame(p: Float) = PlayerMath.sheetFrame(p, layerW, layerH, miniTop, miniH, margin, miniCorner)

        // The hidden side of the transition stays composed but must not be reachable by TalkBack.
        val fullHidden = remember { derivedStateOf { expand.progress == 0f && !expand.dragging } }
        val miniHidden = remember { derivedStateOf { expand.progress >= MINI_FADE_END && !expand.dragging } }

        // A swipe animation interrupted by expanding must not leave the mini content off-center.
        LaunchedEffect(expandedNow) { if (expandedNow) swipe.snapTo(0f) }

        AnimatedVisibility(
            visible = state.hasMedia,
            enter = fadeIn(tween(220)) + slideInVertically(tween(340)) { (miniH * 0.8f).roundToInt() },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { (miniH * 0.8f).roundToInt() },
        ) {
            Layout(
                content = {
                    // 0 — the card that grows into the full player: mini tint → Ink with the cover's wash
                    Spacer(
                        Modifier.fillMaxSize().drawBehind {
                            val p = expand.progress
                            val f = frame(p)
                            val c = wash.value
                            val miniBg = SoundColors.miniBackground(c)
                            // The full player is the cover's color, deep and saturated (not black).
                            val fullBg = SoundColors.playerBackground(c)
                            val base = lerp(miniBg, fullBg, PlayerMath.segment(p, 0f, 0.6f))
                            val topLeft = Offset(f.left, f.top)
                            val frameSize = Size(f.width, f.height)
                            val corner = CornerRadius(f.corner)
                            drawRoundRect(base, topLeft, frameSize, corner)
                            // Collapsed, the card is glued to the bottom of the screen: the same color
                            // runs from under its rounded top down to the edge (no gap, square bottom).
                            val glue = 1f - PlayerMath.segment(p, 0f, 0.35f)
                            if (glue > 0f) {
                                drawRect(base, Offset(f.left, f.top + f.corner), Size(f.right - f.left, size.height - f.top - f.corner), alpha = glue)
                            }
                            if (p > 0f) {
                                val veil = Brush.verticalGradient(
                                    0f to c.copy(alpha = SoundColors.palette.washAlpha),
                                    0.55f to c.copy(alpha = SoundColors.palette.washAlpha * 0.35f),
                                    1f to c.copy(alpha = 0f),
                                    startY = f.top,
                                    endY = f.top + size.height,
                                )
                                drawRoundRect(veil, topLeft, frameSize, corner, alpha = p)
                            }
                        },
                    )
                    // 1 — full player
                    SemanticsGate(fullHidden) {
                        FullPlayer(
                            state = state,
                            track = track,
                            stream = stream,
                            liked = liked,
                            playing = playing,
                            durationMs = durationMs,
                            clock = clock,
                            lyrics = lyrics,
                            wash = wash,
                            washTarget = washTarget,
                            coverScale = coverScale,
                            expand = expand,
                            rangePx = range,
                            sleep = sleep,
                            output = output,
                            actions = actions,
                            onCoverBounds = { if (it.width > 1f && it.height > 1f) coverBounds = it },
                            clip = canvasClip,
                            clipTwin = canvasTwin.takeIf { canvasClip != null },
                            clipTwinAlpha = canvas?.twinAlpha,
                        )
                    }
                    // 2 — mini player content
                    SemanticsGate(miniHidden) {
                        MiniPlayerBar(
                            track = track,
                            stream = stream,
                            liked = liked,
                            playing = playing,
                            clock = clock,
                            durationMs = durationMs,
                            swipeOffset = { swipe.value },
                            swipeWidth = swipeWidth,
                            onTogglePlay = actions.togglePlay,
                            onPrevious = actions.previous,
                            onNext = actions.next,
                            modifier = miniGestures,
                        )
                    }
                    // 3 — the flying cover: mini slot at 0, big pager slot at 1 (clipped to the card)
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val f = frame(expand.progress)
                            shape = FrameShape(f.left, f.top, f.right, f.bottom, f.corner)
                            clip = true
                        },
                    ) {
                        Box(
                            Modifier
                                .layout { measurable, _ ->
                                    val big = coverBounds ?: fallbackBig
                                    val placeable = measurable.measure(
                                        Constraints.fixed(big.width.roundToInt().coerceAtLeast(1), big.height.roundToInt().coerceAtLeast(1)),
                                    )
                                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                                }
                                .graphicsLayer {
                                    val p = expand.progress
                                    val big = coverBounds ?: fallbackBig
                                    val target = PlayerMath.scaleAroundCenter(big, coverScale.value)
                                    val mini = miniCover.translate(swipe.value * (1f - p), 0f)
                                    val r = PlayerMath.lerpRect(mini, target, p)
                                    val sx = (r.width / big.width).coerceAtLeast(0.001f)
                                    val sy = (r.height / big.height).coerceAtLeast(0.001f)
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    translationX = r.left
                                    translationY = r.top
                                    scaleX = sx
                                    scaleY = sy
                                    shape = RoundedCornerShape(PlayerMath.lerp(coverCornerMini, coverCornerBig, p) / sx)
                                    clip = true
                                    shadowElevation = 20.dp.toPx() * p
                                },
                        ) {
                            Cover(track.coverPath, Modifier.fillMaxSize(), seed = seed, shape = RectangleShape)
                        }
                    }
                },
            ) { measurables, constraints ->
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                val fixed = Constraints.fixed(w, h)
                val bg = measurables[0].measure(fixed)
                val full = measurables[1].measure(fixed)
                val mini = measurables[2].measure(Constraints.fixed((w - 2 * margin).roundToInt().coerceAtLeast(0), miniH.roundToInt()))
                val flying = measurables[3].measure(fixed)
                layout(w, h) {
                    val p = expand.progress
                    val f = frame(p)
                    // A child that started a drag stays placed until the drag ends (detaching it would cancel the gesture).
                    val dragging = expand.dragging
                    bg.place(0, 0)
                    if (p > 0f || dragging) {
                        full.placeWithLayer(0, f.top.roundToInt()) {
                            val pp = expand.progress
                            val ff = frame(pp)
                            alpha = PlayerMath.segment(pp, 0.12f, 0.7f)
                            shape = FrameShape(ff.left, 0f, ff.right, ff.bottom - ff.top, ff.corner)
                            clip = true
                        }
                    }
                    if (p < MINI_FADE_END || dragging) {
                        mini.placeWithLayer(f.left.roundToInt(), f.top.roundToInt()) {
                            alpha = 1f - PlayerMath.segment(expand.progress, 0f, MINI_FADE_END)
                            shape = RoundedCornerShape(MiniCardCorner)
                            clip = true
                        }
                    }
                    if (p < 1f) flying.place(0, 0)
                }
            }
        }

        // ---- discreet error pill: above the mini card, or under the top bar when expanded
        var dismissedError by remember { mutableStateOf<String?>(null) }
        var lastError by remember { mutableStateOf("") }
        LaunchedEffect(state.error) {
            val e = state.error
            if (e == null) {
                dismissedError = null
            } else {
                lastError = e
                delay(5_000)
                dismissedError = e
            }
        }
        val error = state.error?.takeIf { it != dismissedError }
        val pillOnTop by remember { derivedStateOf { expand.progress > 0.5f } }
        AnimatedVisibility(
            visible = error != null && state.hasMedia,
            modifier = if (pillOnTop) {
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 60.dp)
            } else {
                Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBarHeight - MiniDockOverlap + Dimens.MiniPlayerHeight + 10.dp)
            },
            enter = fadeIn(tween(200)) + scaleIn(tween(240), initialScale = 0.92f),
            exit = fadeOut(tween(200)),
        ) {
            ErrorPill(error ?: lastError, onDismiss = { dismissedError = state.error })
        }

        // ---- full-screen lyrics
        AnimatedVisibility(
            visible = lyricsOpen && state.hasMedia,
            enter = fadeIn(tween(240)) + slideInVertically(tween(340)) { it / 10 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(260)) { it / 10 },
        ) {
            LyricsScreen(
                track = track,
                lyrics = lyrics,
                clock = clock,
                wash = wash,
                washTarget = washTarget,
                playing = playing,
                durationMs = durationMs,
                onSeek = actions.seek,
                onLineSeek = actions.seekLine,
                onTogglePlay = actions.togglePlay,
                onClose = { lyricsOpen = false },
                streamed = stream.streamed,
                online = online,
            )
        }
    }

    if (queueOpen && state.hasMedia) {
        QueueSheet(
            state = state,
            onSkipTo = { player.skipTo(it) },
            onMove = { from, to -> player.move(from, to) },
            onRemove = { player.removeAt(it) },
            onDismiss = { queueOpen = false },
        )
    }
    if (sleepOpen) {
        SleepTimerSheet(timer = sleep, onSet = { player.setSleepTimer(it) }, onDismiss = { sleepOpen = false })
    }
    actionsFor?.let { t ->
        TrackActionsSheet(track = t, onDismiss = { actionsFor = null }, showEqualizer = true, onSleepTimer = { sleepOpen = true })
    }
}

/** Removes [content] from the semantics tree while [hidden]; only this wrapper recomposes when it flips. */
@Composable
private fun SemanticsGate(hidden: State<Boolean>, content: @Composable () -> Unit) {
    Box(if (hidden.value) Modifier.clearAndSetSemantics { } else Modifier, propagateMinConstraints = true) { content() }
}

/** Rounded rect at fixed coordinates inside the layer (the growing card). */
private data class FrameShape(val left: Float, val top: Float, val right: Float, val bottom: Float, val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(left, top, right, bottom, CornerRadius(corner)))
}

@Composable
private fun ErrorPill(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 24.dp)
            .clip(CircleShape)
            .background(SoundColors.Surface3)
            .clickable(onClickLabel = stringResource(R.string.player_error_dismiss), onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = SoundColors.Danger, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = SoundColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
