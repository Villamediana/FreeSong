package com.villamediana.sound.ui.video

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.ParsedLyrics
import com.villamediana.sound.ui.player.rememberLyrics
import com.villamediana.sound.ui.player.LyricsState
import com.villamediana.sound.ui.player.LyricsQuery
import com.villamediana.sound.ui.player.LyricsOrigin
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.scaleIn
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Replay10
import kotlin.math.abs
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.material.icons.rounded.ScreenRotation
import android.content.res.Configuration
import android.view.OrientationEventListener
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
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.playback.VideoPlayback
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.nav.VideoRoute
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.SoundColors
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
    val graph = SoundApplication.graph
    val nav = navigator()
    val deps = remember(graph) { VideoDeps(graph.video, graph.player.state, graph.player.positionMs, com.villamediana.sound.ui.player.PlayerLyrics.cache) }
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
    /** Lyrics under the video (the player's LRCLIB lookups); null = none. */
    val lyrics: com.villamediana.sound.ui.player.OnlineLyricsCache? = null,
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

    fun close(resumeMusic: Boolean = true) {
        if (!active) return
        active = false
        video.close(resumeMusic = resumeMusic)
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
        // Swiping Sound away from Recents finishes the activity: stop the video without
        // restarting the music in the background. Back / "Voltar ao áudio" never finish it.
        onDispose { if (activity?.isChangingConfigurations != true) session.close(resumeMusic = activity?.isFinishing != true) }
    }

    val turn = remember(activity) { VideoTurn(activity) }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    ImmersiveWindow(activity, turn, sideways = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
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
    // Held down: 2× while the finger stays. A single tap answers at once (controls); when a second
    // tap follows, that first tap meant a jump, not the controls — they go back to how they were.
    var holding by remember { mutableStateOf<SeekSide?>(null) }
    var rewindJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var rewoundFromPlaying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var beforeTap by remember { mutableStateOf<OverlayState?>(null) }
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
            override fun rotate() {
                turn.flip()
            }

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
                if (VideoSeek.continues(streak, side, now())) {
                    seekTap(side, at)
                    return
                }
                beforeTap = overlay
                toggleControls()
            }

            override fun doubleTap(side: SeekSide, at: Offset) {
                beforeTap?.let { overlay = it }
                beforeTap = null
                seekTap(side, at)
            }

            override fun hold(active: Boolean, side: SeekSide) {
                if (active == (holding != null)) return
                holding = if (active) side else null
                rewindJob?.cancel()
                rewindJob = null
                if (!active) {
                    video.setSpeed(1f)
                    if (rewoundFromPlaying) video.togglePlayPause()
                    rewoundFromPlaying = false
                    return
                }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (side == SeekSide.FORWARD) {
                    video.setSpeed(FAST_SPEED)
                    return
                }
                // Rewinding: paused, stepping back half a second every quarter second (2× backwards).
                val p = player ?: return
                rewoundFromPlaying = state.playing
                if (state.playing) video.togglePlayPause()
                rewindJob = scope.launch {
                    var at = p.currentPosition
                    while (at > 0) {
                        at = (at - REWIND_STEP_MS).coerceAtLeast(0)
                        video.seekTo(at)
                        pending = PendingSeek(at, now())
                        delay(REWIND_TICK_MS)
                    }
                }
            }

            override fun swipe(up: Boolean) {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                if (up) turn.landscape() else turn.portrait()
            }

            override fun skip(forward: Boolean) {
                val p = player ?: return
                val side = if (forward) SeekSide.FORWARD else SeekSide.BACK
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                seekTo(VideoSeek.target(p.currentPosition, p.duration, side))
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
    // The song's lyrics under the video: its saved file, else LRCLIB (the player's lookups).
    val lyricsOrigin = remember(track, title, artist) {
        track?.lyricsPath?.takeIf { it.isNotBlank() }?.let { LyricsOrigin.File(it) }
            ?: LyricsOrigin.Online(
                LyricsQuery(
                    title = track?.title ?: title,
                    artist = track?.artist ?: artist.orEmpty(),
                    album = track?.album?.takeIf { it.isNotBlank() },
                    durationMs = track?.durationMs?.takeIf { it > 0 },
                ),
            )
    }
    val lyrics by rememberLyrics(lyricsOrigin, online = true, cache = deps.lyrics)
    val controlsVisible = overlay.visible || error != null

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(SoundColors.Ink)
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
                holding = holding,
            )
            if (!landscape) {
                PortraitDetails(
                    title = title,
                    artist = artist,
                    progress = progress,
                    pending = pending,
                    actions = actions,
                    lyrics = lyrics,
                    // Another upload (official clip of an art track) has its own timing: plain text then.
                    synced = state.sameTimeline,
                    track = track,
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
    /** Turns the video the other way (sideways ↔ upright) without turning the phone. */
    fun rotate()
    /** The second tap of a double tap on a side: jump 10 s (the first tap's controls undone). */
    fun doubleTap(side: SeekSide, at: Offset)
    /** Finger held on the video: 2× forward on the right half, rewinding on the left, until it lifts. */
    fun hold(active: Boolean, side: SeekSide = SeekSide.FORWARD)
    /** Swipe on the video: up = sideways (full screen), down = upright. */
    fun swipe(up: Boolean)
    /** The ±10 s buttons of the controls. */
    fun skip(forward: Boolean)
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
    holding: SeekSide? = null,
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
                stageGestures(canSeek = player != null, actions = actions)
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
                            timeColor = SoundColors.Text.copy(alpha = 0.82f),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                                .padding(start = Dimens.Gutter, end = 56.dp)
                                .padding(bottom = 10.dp),
                        )
                    }
                }
                if (error == null && player != null) {
                    // Jump buttons on both sides of play/pause, and full screen in the corner.
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (landscape) 48.dp else 32.dp),
                    ) {
                        VideoIconButton(Icons.Rounded.Replay10, stringResource(R.string.video_back_10), { actions.skip(false) })
                        Spacer(Modifier.size(if (landscape) 80.dp else 68.dp))
                        VideoIconButton(Icons.Rounded.Forward10, stringResource(R.string.video_forward_10), { actions.skip(true) })
                    }
                    VideoIconButton(
                        if (landscape) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        stringResource(if (landscape) R.string.video_exit_fullscreen else R.string.video_fullscreen),
                        actions::rotate,
                        Modifier
                            .align(Alignment.BottomEnd)
                            .then(if (landscape) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)) else Modifier)
                            .padding(end = 4.dp, bottom = if (landscape) 2.dp else 0.dp),
                    )
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
        // Held: a small "2×" tag up top while it lasts (◀◀ rewinding on the left, ▶▶ on the right).
        AnimatedVisibility(
            visible = holding != null,
            enter = fadeIn(tween(150)) + scaleIn(tween(200), initialScale = 0.85f),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
        ) {
            val back = holding == SeekSide.BACK
            Row(
                Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (back) {
                    Icon(Icons.Rounded.FastRewind, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text("2×", style = MaterialTheme.typography.labelLarge, color = Color.White)
                if (!back) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
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
                color = SoundColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist != null) {
                Text(artist, style = MaterialTheme.typography.bodyMedium, color = SoundColors.Text.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Icon(Icons.Rounded.VideocamOff, null, tint = SoundColors.TextMuted, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(12.dp))
        }
        Text(
            stringResource(R.string.video_error_title),
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            color = SoundColors.Text,
            textAlign = TextAlign.Center,
        )
        if (message.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = SoundColors.TextFaint,
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
            color = SoundColors.TextFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(48.dp))
    }
}

/**
 * Under the video, upright: seek bar, title with "Voltar ao áudio", the lyrics following the video
 * (synced when the clip is the song's own recording), and the song's facts.
 */
@Composable
private fun PortraitDetails(
    title: String,
    artist: String?,
    progress: VideoProgress,
    pending: PendingSeek?,
    actions: StageActions,
    lyrics: LyricsState,
    synced: Boolean,
    track: Track?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter)) {
        Spacer(Modifier.height(12.dp))
        VideoSeekBar(progress = progress, pending = pending, onSeek = actions::seekTo, onScrub = actions::scrub)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = SoundColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (artist != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(artist, style = MaterialTheme.typography.titleMedium, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(12.dp))
            PillButton(text = stringResource(R.string.video_back_to_audio), onClick = actions::leave, icon = Icons.Rounded.Headphones)
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(SoundColors.Surface1),
        ) {
            when (lyrics) {
                is LyricsState.Ready -> VideoLyrics(lyrics.lyrics, synced = synced && lyrics.lyrics.synced, positionMs = { progress.positionMs })
                LyricsState.Loading -> Text(
                    stringResource(R.string.video_lyrics_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoundColors.TextFaint,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Lyrics, null, tint = SoundColors.TextFaint, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.video_lyrics_none), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextFaint)
                }
            }
        }
        SongFacts(track, progress.durationMs)
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/**
 * The lyrics in their card: synced, the line being sung is bright and kept in view; otherwise
 * the plain text, free to scroll. Soft fades at the top and bottom edges.
 */
@Composable
private fun VideoLyrics(lyrics: ParsedLyrics, synced: Boolean, positionMs: () -> Long) {
    val list = rememberLazyListState()
    val index by remember(lyrics, synced) { derivedStateOf { if (synced) lyrics.indexAt(positionMs()) else -1 } }
    LaunchedEffect(index) { if (index >= 0) list.animateScrollToItem((index - 1).coerceAtLeast(0)) }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.08f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
        state = list,
        contentPadding = PaddingValues(vertical = 18.dp),
    ) {
        itemsIndexed(lyrics.lines) { i, line ->
            val active = i == index
            val color by animateColorAsState(
                when {
                    !synced -> SoundColors.TextMuted
                    active -> SoundColors.Text
                    i < index -> SoundColors.TextFaint
                    else -> SoundColors.TextMuted
                },
                tween(300),
                label = "line",
            )
            Text(
                line.text.ifBlank { "♪" },
                style = if (synced) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                color = color,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = if (synced) 7.dp else 3.dp),
            )
        }
    }
}

/** Album and year under the lyrics, when known. */
@Composable
private fun SongFacts(track: Track?, durationMs: Long) {
    val facts = buildList {
        track?.album?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.video_fact_album) to it) }
        track?.year?.takeIf { it > 0 }?.let { add(stringResource(R.string.video_fact_year) to it.toString()) }
    }
    if (facts.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        facts.forEach { (label, value) ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SoundColors.Surface1)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = SoundColors.TextFaint, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(value, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

// ------------------------------------------------------------------ player flags

/** Player facts the contract's [com.villamediana.sound.playback.VideoState] doesn't carry. */
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
 * While the video is on screen: no system bars (a swipe brings them back for a moment), the video
 * opens sideways ([VideoTurn]), and the screen stays on. Everything is put back on the way out — but not
 * when the activity is only being recreated for a rotation (the new screen takes over).
 */
@Composable
private fun ImmersiveWindow(activity: Activity?, turn: VideoTurn, sideways: Boolean) {
    val view = LocalView.current
    val previousOrientation = rememberSaveable { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    DisposableEffect(activity, view) {
        val window = activity?.window ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        turn.start()
        onDispose {
            turn.stop()
            if (activity.isChangingConfigurations) return@onDispose
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
            activity.requestedOrientation = previousOrientation
        }
    }
    // Full screen only sideways. Upright the bars stay: hidden, the first back gesture would only
    // bring them back and it would take a second one to leave.
    DisposableEffect(activity, view, sideways) {
        val window = activity?.window ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        if (sideways) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose { }
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

/**
 * How the video screen turns. It opens sideways (the video fills the screen); once the phone is
 * really held sideways, it follows the phone again — so turning it back upright shows the video in
 * portrait. The rotate button does the same by hand: it turns the screen the other way and, once
 * the phone matches, lets the phone decide again.
 */
internal class VideoTurn(private val activity: Activity?) {
    private enum class Want { LANDSCAPE, PORTRAIT, FREE }

    private var want = Want.FREE
    private var listener: OrientationEventListener? = null

    fun start() {
        val host = activity ?: return
        lock(Want.LANDSCAPE)
        listener = object : OrientationEventListener(host) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val sideways = degrees in 60..120 || degrees in 240..300
                val upright = degrees <= 30 || degrees >= 330
                val matches = (want == Want.LANDSCAPE && sideways) || (want == Want.PORTRAIT && upright)
                if (matches) lock(Want.FREE)
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    fun stop() {
        listener?.disable()
        listener = null
    }

    /** Swipe up: sideways now, then the phone decides again once it is turned. */
    fun landscape() = lock(Want.LANDSCAPE)

    /** Swipe down: upright now, then the phone decides again. */
    fun portrait() = lock(Want.PORTRAIT)

    /** The rotate button: the other way from how the screen is now. */
    fun flip() {
        val host = activity ?: return
        val landscapeNow = host.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        lock(if (landscapeNow) Want.PORTRAIT else Want.LANDSCAPE)
    }

    private fun lock(next: Want) {
        want = next
        activity?.requestedOrientation = when (next) {
            Want.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Want.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Want.FREE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
    }
}

private const val FAST_SPEED = 2f
private const val REWIND_STEP_MS = 500L
private const val REWIND_TICK_MS = 250L

/**
 * Taps, double taps, holds and vertical swipes on the video, told apart by hand so a single tap
 * answers at once (no wait for a possible second tap): tap = controls, second tap on a side =
 * jump 10 s, held = 2× until lifted, swipe up/down = sideways/upright.
 */
private suspend fun PointerInputScope.stageGestures(canSeek: Boolean, actions: StageActions) {
    val swipeMin = 56.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        val slop = viewConfiguration.touchSlop
        val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
        var last = down.uptimeMillis
        var dragging = false
        var held = false
        var dy = 0f
        var upAt: Offset? = null
        while (true) {
            val event = if (!dragging && !held) {
                withTimeoutOrNull((deadline - last).coerceAtLeast(1)) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                // Still down, not moving: held — forward on the right half, back on the left.
                held = true
                if (canSeek) actions.hold(true, VideoSeek.sideOf(down.position.x, size.width.toFloat()))
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            last = change.uptimeMillis
            if (!change.pressed) {
                upAt = change.position
                break
            }
            val d = change.position - down.position
            if (!dragging && !held && abs(d.y) > slop && abs(d.y) > abs(d.x)) dragging = true
            if (dragging) {
                dy = d.y
                change.consume()
            }
        }
        when {
            held -> if (canSeek) actions.hold(false)
            dragging -> if (abs(dy) >= swipeMin) actions.swipe(up = dy < 0)
            upAt != null -> {
                val width = size.width.toFloat()
                actions.stageTap(VideoSeek.sideOf(upAt.x, width), upAt)
                if (canSeek) {
                    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
                    if (second != null) {
                        actions.doubleTap(VideoSeek.sideOf(second.position.x, width), second.position)
                        waitForUpOrCancellation()
                    }
                }
            }
        }
    }
}
