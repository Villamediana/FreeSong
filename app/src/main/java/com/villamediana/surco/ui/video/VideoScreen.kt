package com.villamediana.surco.ui.video

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.navigation.NavDestination.Companion.hasRoute
import coil3.compose.AsyncImage
import com.villamediana.surco.R
import com.villamediana.surco.SurcoApplication
import com.villamediana.surco.playback.PlaybackState
import com.villamediana.surco.playback.VideoPlayback
import com.villamediana.surco.ui.components.PillButton
import com.villamediana.surco.ui.nav.VideoRoute
import com.villamediana.surco.ui.nav.navigator
import com.villamediana.surco.ui.theme.Dimens
import com.villamediana.surco.ui.theme.Motion
import com.villamediana.surco.ui.theme.SurcoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * "Ver o vídeo": the music video on a black stage. Portrait shows the video on top with the seek
 * bar, title and artist below; landscape is the video alone, full screen. Controls float over the
 * video, hide after a few seconds and come back with a tap; double-tap a side to jump 10 s.
 * Leaving (back, "Voltar ao áudio" or any other way) closes the video and the music resumes.
 */
@Composable
fun VideoScreen(route: VideoRoute) {
    val graph = SurcoApplication.graph
    val nav = navigator()
    val deps = remember(graph) { VideoDeps(graph.video, graph.player.state, graph.player.positionMs) }
    VideoHost(
        route = route,
        deps = deps,
        onLeave = {
            // Only pop ourselves (a second tap during the exit animation must not pop the screen below).
            if (nav.controller.currentBackStackEntry?.destination?.hasRoute(VideoRoute::class) == true) nav.back()
        },
    )
}

/** What the video screen needs from the app (a fake in tests). */
internal class VideoDeps(
    val video: VideoPlayback,
    val music: StateFlow<PlaybackState>,
    val musicPositionMs: Flow<Long>,
)

/** Opens the video for this visit and closes it — music back on — exactly once on the way out. */
private class VideoSession(private val video: VideoPlayback) {
    private var active = false
    var startMs = 0L
        private set

    fun open(videoId: String, title: String?, startAtMs: Long) {
        active = true
        startMs = startAtMs
        video.open(videoId, title, startAtMs)
    }

    /** The activity was recreated (rotation) while the video kept playing: carry on with it. */
    fun adopt() {
        active = true
    }

    fun close() {
        if (!active) return
        active = false
        video.close(resumeMusic = true)
    }
}

private fun now(): Long = SystemClock.uptimeMillis()

@OptIn(UnstableApi::class)
@Composable
internal fun VideoHost(route: VideoRoute, deps: VideoDeps, onLeave: () -> Unit) {
    val video = deps.video
    val state by video.state.collectAsState()
    val player by video.player.collectAsState()
    val music by deps.music.collectAsState()
    val activity = LocalActivity.current
    val haptic = LocalHapticFeedback.current

    // When the song playing is this very video: the clip starts where the song is, and borrows its
    // artist and cover (shown dimmed until the first frame).
    val track = music.current?.takeIf { it.videoId == route.videoId }

    val session = remember(video) { VideoSession(video) }
    var opened by rememberSaveable(route.videoId) { mutableStateOf(false) }
    LaunchedEffect(route.videoId) {
        val live = opened && video.player.value != null && video.state.value.videoId == route.videoId
        if (live) {
            session.adopt()
            return@LaunchedEffect
        }
        val current = deps.music.value.current
        val musicPos = if (current?.videoId == route.videoId) withTimeoutOrNull(500) { deps.musicPositionMs.first() } ?: 0L else 0L
        session.open(route.videoId, route.title, videoStartMs(current?.videoId, route.videoId, musicPos))
        opened = true
    }
    DisposableEffect(session) {
        onDispose { if (activity?.isChangingConfigurations != true) session.close() }
    }

    ImmersiveWindow(activity)
    PauseWhileInBackground(activity, video)

    val leaveNow by rememberUpdatedState(onLeave)

    val flags = rememberPlayerFlags(player)
    val progress = rememberVideoProgress(player)
    val error = state.error
    val loading = error == null && (state.loading || flags.buffering)

    var overlay by remember { mutableStateOf(OverlayState(visible = true, touchedAtMs = now())) }
    var scrubbing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingSeek?>(null) }
    var streak by remember { mutableStateOf<SeekStreak?>(null) }
    var tapPoint by remember { mutableStateOf<Offset?>(null) }
    val pinned = scrubbing || loading || error != null

    LaunchedEffect(overlay, state.playing, pinned) {
        val at = overlay.hideAtMs(state.playing, pinned) ?: return@LaunchedEffect
        delay((at - now()).coerceAtLeast(0))
        overlay = overlay.hide()
    }
    LaunchedEffect(streak) {
        if (streak == null) return@LaunchedEffect
        delay(VideoSeek.STREAK_WINDOW_MS + 120)
        streak = null
    }
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        delay(PendingSeek.HOLD_MS + 100)
        pending = null
    }

    val actions = remember(video, route) {
        object : StageActions {
            override fun toggleControls() {
                overlay = overlay.toggle(now())
            }

            override fun playPause() {
                overlay = overlay.touch(now())
                if (flags.ended) {
                    video.seekTo(0)
                    if (player?.playWhenReady == false) video.togglePlayPause()
                } else {
                    video.togglePlayPause()
                }
            }

            override fun seekTo(positionMs: Long) {
                video.seekTo(positionMs)
                pending = PendingSeek(positionMs, now())
                overlay = overlay.touch(now())
            }

            override fun scrub(active: Boolean) {
                scrubbing = active
                overlay = overlay.touch(now())
            }

            override fun seekTap(side: SeekSide, at: Offset) {
                val p = player ?: return
                val t = now()
                val next = VideoSeek.tap(streak, side, t, p.currentPosition, p.duration)
                streak = next
                tapPoint = at
                video.seekTo(next.targetMs)
                pending = PendingSeek(next.targetMs, t)
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                if (overlay.visible) overlay = overlay.touch(t)
            }

            override fun stageTap(side: SeekSide, at: Offset) {
                if (VideoSeek.continues(streak, side, now())) seekTap(side, at) else toggleControls()
            }

            override fun retry() {
                overlay = overlay.touch(now())
                val from = player?.currentPosition?.takeIf { it > 0 } ?: session.startMs
                session.open(route.videoId, route.title, from)
            }

            override fun leave() {
                session.close()
                leaveNow()
            }
        }
    }

    val title = route.title?.takeIf { it.isNotBlank() } ?: state.title?.takeIf { it.isNotBlank() } ?: track?.title.orEmpty()
    val artist = track?.artist?.takeIf { it.isNotBlank() }
    val controlsVisible = overlay.visible || error != null

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(SurcoColors.Ink)
            .keepScreenOn(),
    ) {
        val landscape = maxWidth > maxHeight
        val ratio by animateFloatAsState(safeAspectRatio(state.aspectRatio), Motion.soft(), label = "ratio")
        val portraitStage = min(maxWidth / ratio, maxHeight * 0.62f)

        Column(Modifier.fillMaxSize()) {
            if (!landscape) PortraitTopBar(onBack = actions::leave)
            // Same call site in both orientations, so the video surface survives a rotation.
            Stage(
                player = player,
                ratio = ratio,
                poster = track?.coverPath,
                landscape = landscape,
                controlsVisible = controlsVisible,
                playing = state.playing,
                ended = flags.ended,
                loading = loading,
                error = error,
                title = title,
                artist = artist,
                progress = progress,
                pending = pending,
                streak = streak,
                tapPoint = tapPoint,
                actions = actions,
                modifier = if (landscape) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(portraitStage),
            )
            if (!landscape) {
                PortraitDetails(
                    title = title,
                    artist = artist,
                    progress = progress,
                    pending = pending,
                    actions = actions,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Everything the stage and the details can ask for. */
internal interface StageActions {
    fun toggleControls()
    fun playPause()
    fun seekTo(positionMs: Long)
    fun scrub(active: Boolean)
    fun seekTap(side: SeekSide, at: Offset)
    fun stageTap(side: SeekSide, at: Offset)
    fun retry()
    fun leave()
}

// ------------------------------------------------------------------ stage

@OptIn(UnstableApi::class)
@Composable
private fun Stage(
    player: Player?,
    ratio: Float,
    poster: String?,
    landscape: Boolean,
    controlsVisible: Boolean,
    playing: Boolean,
    ended: Boolean,
    loading: Boolean,
    error: String?,
    title: String,
    artist: String?,
    progress: VideoProgress,
    pending: PendingSeek?,
    streak: SeekStreak?,
    tapPoint: Offset?,
    actions: StageActions,
    modifier: Modifier = Modifier,
) {
    val toggleLabel = stringResource(if (controlsVisible) R.string.video_hide_controls else R.string.video_show_controls)
    val interactive = error == null
    Box(
        modifier
            .background(Color.Black)
            .clipToBounds()
            .semantics {
                onClick(label = toggleLabel) {
                    actions.toggleControls()
                    true
                }
            }
            .pointerInput(interactive, player != null) {
                if (!interactive) return@pointerInput
                val canSeek = player != null
                detectTapGestures(
                    onTap = { o -> actions.stageTap(VideoSeek.sideOf(o.x, size.width.toFloat()), o) },
                    onDoubleTap = if (canSeek) { o -> actions.seekTap(VideoSeek.sideOf(o.x, size.width.toFloat()), o) } else null,
                )
            },
    ) {
        val coverSurface = rememberCoverSurface(player)
        // The video, letterboxed on black (and kept clear of a camera cutout in landscape).
        Box(
            Modifier
                .matchParentSize()
                .then(if (landscape) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.aspectRatio(ratio)) {
                if (player != null) PlayerSurface(player = player, modifier = Modifier.fillMaxSize())
                Poster(poster, visible = coverSurface && error == null)
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(320)),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                if (landscape) {
                    LandscapeTopBar(title, artist, actions, Modifier.align(Alignment.TopCenter))
                    if (error == null) {
                        VideoSeekBar(
                            progress = progress,
                            pending = pending,
                            onSeek = actions::seekTo,
                            onScrub = actions::scrub,
                            timeColor = SurcoColors.Text.copy(alpha = 0.82f),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                                .padding(horizontal = Dimens.Gutter)
                                .padding(bottom = 10.dp),
                        )
                    }
                }
                if (error == null && !loading) {
                    VideoPlayPause(
                        playing = playing,
                        ended = ended,
                        onClick = actions::playPause,
                        modifier = Modifier.align(Alignment.Center),
                        size = if (landscape) 80.dp else 68.dp,
                    )
                }
            }
        }

        SeekHint(streak, tapPoint)
        StageSpinner(loading, Modifier.align(Alignment.Center))
        if (error != null) StageError(error, compact = !landscape, onRetry = actions::retry, modifier = Modifier.align(Alignment.Center))
    }
}

/** The song's cover, dimmed, while the first frame is on its way. */
@Composable
private fun BoxScope.Poster(model: String?, visible: Boolean) {
    if (model.isNullOrBlank()) return
    val data = remember(model) { if (model.startsWith("http")) model else File(model) }
    AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(420)), modifier = Modifier.matchParentSize()) {
        AsyncImage(
            model = data,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.38f },
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun rememberCoverSurface(player: Player?): Boolean {
    if (player == null) return true
    return rememberPresentationState(player).coverSurface
}

@Composable
private fun LandscapeTopBar(title: String, artist: String?, actions: StageActions, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 8.dp, end = Dimens.Gutter, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.video_back), actions::leave)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = SurcoColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist != null) {
                Text(artist, style = MaterialTheme.typography.bodyMedium, color = SurcoColors.Text.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        StagePill(stringResource(R.string.video_back_to_audio), Icons.Rounded.Headphones, actions::leave)
    }
}

@Composable
private fun StageError(message: String, compact: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 28.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!compact) {
            Icon(Icons.Rounded.VideocamOff, null, tint = SurcoColors.TextMuted, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(12.dp))
        }
        Text(
            stringResource(R.string.video_error_title),
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            color = SurcoColors.Text,
            textAlign = TextAlign.Center,
        )
        if (message.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = SurcoColors.TextFaint,
                textAlign = TextAlign.Center,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(if (compact) 12.dp else 18.dp))
        StagePill(stringResource(R.string.video_retry), Icons.Rounded.Refresh, onRetry)
    }
}

// ------------------------------------------------------------------ portrait

@Composable
private fun PortraitTopBar(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(56.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.video_back), onBack)
        Text(
            stringResource(R.string.video_overline).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = SurcoColors.TextFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun PortraitDetails(
    title: String,
    artist: String?,
    progress: VideoProgress,
    pending: PendingSeek?,
    actions: StageActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.Gutter),
    ) {
        Spacer(Modifier.height(14.dp))
        VideoSeekBar(progress = progress, pending = pending, onSeek = actions::seekTo, onScrub = actions::scrub)
        Spacer(Modifier.height(22.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            color = SurcoColors.Text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (artist != null) {
            Spacer(Modifier.height(4.dp))
            Text(artist, style = MaterialTheme.typography.titleMedium, color = SurcoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(24.dp))
        PillButton(
            text = stringResource(R.string.video_back_to_audio),
            onClick = actions::leave,
            icon = Icons.Rounded.Headphones,
        )
        Spacer(Modifier.height(28.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

// ------------------------------------------------------------------ player flags

/** Player facts the contract's [com.villamediana.surco.playback.VideoState] doesn't carry. */
private class PlayerFlags {
    var buffering by mutableStateOf(false)
    var ended by mutableStateOf(false)

    fun read(player: Player?) {
        val s = player?.playbackState
        buffering = s == Player.STATE_BUFFERING
        ended = s == Player.STATE_ENDED
    }
}

@Composable
private fun rememberPlayerFlags(player: Player?): PlayerFlags {
    val flags = remember { PlayerFlags() }
    DisposableEffect(player) {
        flags.read(player)
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = flags.read(p)
        }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }
    return flags
}

// ------------------------------------------------------------------ window

/**
 * While the video is on screen: no system bars (a swipe brings them back for a moment), the phone
 * may turn to landscape, and the screen stays on. Everything is put back on the way out — but not
 * when the activity is only being recreated for a rotation (the new screen takes over).
 */
@Composable
private fun ImmersiveWindow(activity: Activity?) {
    val view = LocalView.current
    val previousOrientation = rememberSaveable { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    DisposableEffect(activity, view) {
        val window = activity?.window ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            if (activity.isChangingConfigurations) return@onDispose
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
            activity.requestedOrientation = previousOrientation
        }
    }
}

/**
 * The video has no notification or lock-screen controls: it pauses when the app goes to the
 * background and picks up again when it comes back.
 */
@Composable
private fun PauseWhileInBackground(activity: Activity?, video: VideoPlayback) {
    val host = activity ?: return
    val owner = host as? LifecycleOwner ?: return
    DisposableEffect(owner, video) {
        var pausedByUs = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (!host.isChangingConfigurations && video.state.value.playing) {
                    video.togglePlayPause()
                    pausedByUs = true
                }
                Lifecycle.Event.ON_START -> if (pausedByUs) {
                    pausedByUs = false
                    if (!video.state.value.playing) video.togglePlayPause()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
