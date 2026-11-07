package com.villamediana.sound.ui.player

import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material.icons.rounded.MusicVideo
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredSize
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.PlayerSurface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.MarqueeSpacing
import com.villamediana.sound.download.DownloadWait
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.villamediana.sound.R
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.playback.RepeatMode
import com.villamediana.sound.playback.SleepTimer
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.pressScale
import com.villamediana.sound.ui.search.ItemDownload
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.launch
import kotlin.math.abs

internal val PlayerGutter = 24.dp
internal val BigCoverCorner = 8.dp

/** Everything the player can do, as stable callbacks (built once by the host). */
internal class PlayerActions(
    val togglePlay: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val seek: (Long) -> Unit,
    val skipTo: (Int) -> Unit,
    val toggleShuffle: () -> Unit,
    val cycleRepeat: () -> Unit,
    val like: (Boolean) -> Unit,
    val collapse: () -> Unit,
    val openQueue: () -> Unit,
    val openSleep: () -> Unit,
    val openLyrics: () -> Unit,
    val openActions: () -> Unit,
    val openArtist: (String) -> Unit,
    val openAlbum: (Track) -> Unit,
    val openOutput: () -> Unit,
    /** Streamed song: keep it on the phone. */
    val download: () -> Unit = {},
    /** "Ver vídeo" (needs network). */
    val openVideo: () -> Unit = {},
    val openEqualizer: () -> Unit = {},
)

/**
 * A streamed song (not on the phone): a small cloud next to the artist and "Baixar" in place of
 * the heart, following the download until the song is in the library.
 */
@Immutable
internal data class StreamUi(
    val streamed: Boolean,
    val download: ItemDownload,
    val online: Boolean,
    val likeDownloads: Boolean = false,
    /** What a queued download waits for: its button says so. */
    val wait: DownloadWait = DownloadWait.NONE,
) {
    companion object {
        val Local = StreamUi(streamed = false, download = ItemDownload.None, online = true)
    }
}

/**
 * Drag-down-to-collapse for the scrolling full player: once the content is at the top, pulling
 * further moves the shared expand progress; pushing back up lifts it again before scrolling.
 */
internal class CollapseConnection(private val expand: ExpandState, private val range: () -> Float) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || !expand.dragging) return Offset.Zero
        if (available.y < 0f && expand.progress < 1f) {
            expand.dragBy(available.y, range())
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        expand.dragBy(available.y, range())
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!expand.dragging) return Velocity.Zero
        expand.endDrag(available.y, range())
        return available
    }
}

@Composable
internal fun FullPlayer(
    state: PlaybackState,
    track: Track,
    stream: StreamUi,
    liked: Boolean,
    playing: Boolean,
    durationMs: Long,
    clock: PlaybackClock,
    lyrics: LyricsState,
    wash: State<Color>,
    washTarget: Color,
    coverScale: State<Float>,
    expand: ExpandState,
    rangePx: Float,
    sleep: SleepTimer,
    output: OutputRoute,
    actions: PlayerActions,
    onCoverBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    /** The song's music video, silent and looping, to show behind the cover; null = the plain backdrop. */
    clip: Player? = null,
) {
    val root = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val range by rememberUpdatedState(rangePx)
    val scroll = rememberScrollState()
    val connection = remember(expand) { CollapseConnection(expand) { range } }
    val reportCover: (LayoutCoordinates) -> Unit = remember(onCoverBounds) {
        { c -> root[0]?.takeIf { it.isAttached && c.isAttached }?.let { r -> onCoverBounds(r.localBoundingBoxOf(c, clipBounds = false)) } }
    }

    // A drag that ends without a fling still has to settle.
    LaunchedEffect(scroll, expand) {
        snapshotFlow { scroll.isScrollInProgress }.collect { if (!it && expand.dragging) expand.endDrag(0f, range) }
    }
    // Back at the top once collapsed, so the next expand starts on the cover.
    LaunchedEffect(scroll, expand) {
        snapshotFlow { expand.progress == 0f }.collect { if (it) scroll.scrollTo(0) }
    }

    BoxWithConstraints(modifier.fillMaxSize().onPlaced { root[0] = it }) {
        val landscape = maxWidth > maxHeight
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val sectionHeight = maxHeight - navBottom
        Column(Modifier.fillMaxSize().nestedScroll(connection).verticalScroll(scroll)) {
            val section = Modifier.fillMaxWidth().height(sectionHeight).padding(top = statusTop)
            val visible = { expand.progress >= 1f }
            if (landscape) {
                Column(section.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                    TopBar(state.source, actions, hasVideo = track.videoId != null, online = stream.online)
                    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BoxWithConstraints(Modifier.weight(0.46f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            val size = minOf(maxWidth - PlayerGutter * 2, maxHeight - 16.dp).coerceAtLeast(96.dp)
                            CoverPager(state, track, size, maxWidth, coverScale, visible, actions.skipTo, reportCover)
                        }
                        Column(Modifier.weight(0.54f).padding(end = PlayerGutter), verticalArrangement = Arrangement.Center) {
                            Controls(state, track, stream, liked, playing, durationMs, clock, sleep, output, lyrics, actions)
                        }
                    }
                }
            } else {
              Box(Modifier.fillMaxWidth().height(sectionHeight)) {
                // The music video looping behind the top bar; once its first frame is up the cover
                // steps aside and the clip is the picture (the cover stays offline / without a video).
                var clipShowing by remember { mutableStateOf(false) }
                if (clip == null) clipShowing = false
                ClipLayer(clip, visible, Modifier.fillMaxWidth().fillMaxHeight(0.7f), onShowing = { clipShowing = it })
                Column(Modifier.fillMaxSize().padding(top = statusTop)) {
                    TopBar(state.source, actions, hasVideo = track.videoId != null, online = stream.online, onClip = clip != null)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val size = minOf(maxWidth - PlayerGutter * 2, maxHeight - 20.dp).coerceAtLeast(96.dp)
                        CoverPager(
                            state, track, size, maxWidth, coverScale, visible, actions.skipTo, reportCover,
                            onOpenVideo = actions.openVideo.takeIf { clip != null },
                            coverHidden = clipShowing,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Column(Modifier.padding(horizontal = PlayerGutter)) {
                        Controls(state, track, stream, liked, playing, durationMs, clock, sleep, output, lyrics, actions)
                    }
                    Spacer(Modifier.height(10.dp))
                }
              }
            }
            // The lyrics wait below the fold: the player fills the screen and they show up only when
            // you scroll down (no edge peeking at the bottom).
            LyricsCard(
                lyrics = lyrics,
                clock = clock,
                wash = wash,
                washTarget = washTarget,
                onSeek = actions.seek,
                onOpen = actions.openLyrics,
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                streamed = stream.streamed,
            )
            Spacer(Modifier.height(navBottom + 32.dp))
        }
    }
}

@Composable
private fun TopBar(source: PlaySource?, actions: PlayerActions, hasVideo: Boolean, online: Boolean, onClip: Boolean = false) {
    // Over the darkened clip the bar is always light, whatever the theme.
    val ink = if (onClip) Color.White else SoundColors.Text
    // The source stays centered whatever sits on the right (⋮, and "Ver vídeo" when the song has one).
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        PlayerIconButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.player_collapse), actions.collapse, Modifier.align(Alignment.CenterStart), iconSize = 30.dp, tint = ink)
        Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(if (source != null) R.string.player_playing_from else R.string.player_now_playing).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = ink.copy(alpha = 0.72f),
                maxLines = 1,
            )
            if (source != null) {
                Spacer(Modifier.height(1.dp))
                // Long sources ("Porque você ouviu …") scroll instead of being cut, fading at the edges.
                Box(Modifier.fillMaxWidth().fadedEdges(), contentAlignment = Alignment.Center) {
                    Text(
                        source.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = ink,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_800, repeatDelayMillis = 2_600, spacing = MarqueeSpacing(36.dp)),
                    )
                }
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            PlayerIconButton(Icons.Rounded.MoreVert, stringResource(R.string.more_options), actions.openActions, iconSize = 24.dp, tint = ink)
        }
    }
}

/**
 * The big cover over the queue: swipe to change song; follows the queue index when the song
 * changes elsewhere. Hidden (the floating cover stands in) until the player is fully open.
 */
@Composable
private fun CoverPager(
    state: PlaybackState,
    track: Track,
    coverSize: Dp,
    containerWidth: Dp,
    coverScale: State<Float>,
    visible: () -> Boolean,
    onSkipTo: (Int) -> Unit,
    onCurrentPlaced: (LayoutCoordinates) -> Unit,
    /** The song has a music video: a tap on its cover (or its "Vídeo" tag) opens it. */
    onOpenVideo: (() -> Unit)? = null,
    /** The clip is playing behind: the current cover steps aside (fades, no shadow). */
    coverHidden: Boolean = false,
) {
    val coverAlpha by animateFloatAsState(if (coverHidden) 0f else 1f, tween(600), label = "coverAlpha")
    val inQueue = state.queueIndex in state.queue.indices
    val pages = if (inQueue) state.queue else listOf(track)
    val index = if (inQueue) state.queueIndex else 0
    val pagerState = rememberPagerState(initialPage = index) { pages.size }
    val currentIndex by rememberUpdatedState(index)
    val skip by rememberUpdatedState(if (inQueue) onSkipTo else { _: Int -> })

    LaunchedEffect(index, pages.size) {
        if (index !in 0 until pages.size || pagerState.currentPage == index) return@LaunchedEffect
        if (visible() && abs(pagerState.currentPage - index) == 1) {
            pagerState.animateScrollToPage(index, animationSpec = spring(dampingRatio = 0.9f, stiffness = 380f))
        } else {
            pagerState.scrollToPage(index)
        }
    }
    LaunchedEffect(pagerState) {
        var userDragged = false
        launch { pagerState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userDragged = true } }
        snapshotFlow { pagerState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && userDragged) {
                userDragged = false
                val page = pagerState.currentPage
                if (page != currentIndex) skip(page)
            }
        }
    }

    val side = ((containerWidth - coverSize) / 2).coerceAtLeast(0.dp)
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = if (visible()) 1f else 0f },
        contentPadding = PaddingValues(horizontal = side),
        pageSpacing = side + 24.dp,
        beyondViewportPageCount = 1,
        verticalAlignment = Alignment.CenterVertically,
    ) { page ->
        val t = pages.getOrNull(page) ?: return@HorizontalPager
        Box(
            Modifier
                .size(coverSize)
                .then(if (page == index) Modifier.onGloballyPositioned(onCurrentPlaced) else Modifier)
                .graphicsLayer {
                    val s = coverScale.value
                    scaleX = s
                    scaleY = s
                    val fade = if (page == index) coverAlpha else 1f
                    shadowElevation = 20.dp.toPx() * fade
                    shape = RoundedCornerShape(BigCoverCorner)
                    clip = true
                },
        ) {
            val openVideo = onOpenVideo?.takeIf { page == index }
            Cover(
                t.coverPath,
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (page == index) coverAlpha else 1f }
                    .then(if (openVideo != null) Modifier.clickable(onClickLabel = stringResource(R.string.sheet_video), onClick = openVideo) else Modifier),
                seed = t.album ?: t.title,
                shape = RectangleShape,
                contentDescription = stringResource(R.string.player_cover, t.title),
            )
            AnimatedVisibility(
                visible = openVideo != null,
                enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.9f),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            ) { VideoTag(onClick = { openVideo?.invoke() }) }
        }
    }
}

/** "Vídeo" on the cover: the song has a music video, a tap opens it (sideways). */
@Composable
private fun VideoTag(onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.MusicVideo, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.player_video_tag), style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/** [clip] fading in when it comes and out when it goes (the last one stays while it fades). */
@Composable
private fun ClipLayer(clip: Player?, visible: () -> Boolean, modifier: Modifier = Modifier, onShowing: (Boolean) -> Unit = {}) {
    var last by remember { mutableStateOf(clip) }
    if (clip != null) last = clip
    AnimatedVisibility(visible = clip != null, enter = fadeIn(tween(600)), exit = fadeOut(tween(400)), modifier = modifier) {
        last?.let { CanvasBackdrop(it, visible, Modifier.fillMaxSize(), onShowing) }
    }
}

/**
 * The clip filling its box (cropped, like a cover), darkened, and fading out toward the bottom
 * into the player's own backdrop, so the title and the controls stay on calm ground.
 */
@OptIn(UnstableApi::class)
@Composable
private fun CanvasBackdrop(player: Player, visible: () -> Boolean, modifier: Modifier = Modifier, onShowing: (Boolean) -> Unit = {}) {
    val presentation = rememberPresentationState(player)
    val shown by animateFloatAsState(if (presentation.coverSurface) 0f else 1f, tween(700), label = "clip")
    val showing = !presentation.coverSurface
    LaunchedEffect(showing) { onShowing(showing) }
    DisposableEffect(Unit) { onDispose { onShowing(false) } }
    BoxWithConstraints(
        modifier
            .clipToBounds()
            .graphicsLayer {
                alpha = shown * (if (visible()) 1f else 0f)
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(Color.Black.copy(alpha = 0.3f))
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, 0.62f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        val video = presentation.videoSizeDp
        val ratio = video?.let { if (it.height > 0f) it.width / it.height else null } ?: (16f / 9f)
        // Cover the box: as wide as needed for its height, or as tall as needed for its width.
        val boxRatio = maxWidth / maxHeight
        val (w, h) = if (ratio > boxRatio) (maxHeight * ratio) to maxHeight else maxWidth to (maxWidth / ratio)
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            modifier = Modifier.requiredSize(w, h).align(Alignment.Center),
        )
    }
}

@Composable
private fun Controls(
    state: PlaybackState,
    track: Track,
    stream: StreamUi,
    liked: Boolean,
    playing: Boolean,
    durationMs: Long,
    clock: PlaybackClock,
    sleep: SleepTimer,
    output: OutputRoute,
    lyrics: LyricsState,
    actions: PlayerActions,
) {
    TrackHeader(track, stream, liked, actions)
    Spacer(Modifier.height(18.dp))
    // The wave follows the finger while the seek bar is dragged, not only once it is let go.
    val scrub = remember { mutableStateOf<Float?>(null) }
    Waveform(seed = track.videoId ?: track.id.toString(), clock = clock, durationMs = durationMs, modifier = Modifier.fillMaxWidth().height(34.dp), scrub = scrub)
    SeekBar(clock = clock, durationMs = durationMs, onSeek = actions.seek, timeColor = SoundColors.Text.copy(alpha = 0.7f), drag = scrub)
    Spacer(Modifier.height(6.dp))
    Transport(state, playing, actions)
    Spacer(Modifier.height(14.dp))
    BottomRow(state.outputName, output, sleep, lyrics, actions)
}

@Composable
private fun TrackHeader(track: Track, stream: StreamUi, liked: Boolean, actions: PlayerActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(
            targetState = track,
            // A streamed song that gets downloaded is the same song: no title transition.
            contentKey = { it.videoId ?: it.id },
            transitionSpec = {
                (fadeIn(tween(260, delayMillis = 60)) + slideInVertically(tween(320)) { it / 3 }) togetherWith fadeOut(tween(120))
            },
            modifier = Modifier.weight(1f),
            label = "title",
        ) { t ->
            Column {
                Text(
                    t.title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
                    color = SoundColors.Mint,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_800, repeatDelayMillis = 2_600),
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StreamedMark(visible = t.isOnline, size = 16.dp, gap = 6.dp)
                    // One line that scrolls when long (artist · album), both parts still tappable.
                    val dim = SoundColors.Text.copy(alpha = 0.7f)
                    val line = buildAnnotatedString {
                        val style = TextLinkStyles(SpanStyle(color = dim))
                        if (t.isOnline) append(t.artist) else withLink(LinkAnnotation.Clickable("artist", style) { actions.openArtist(t.artist) }) { append(t.artist) }
                        if (t.hasAlbum) {
                            withStyle(SpanStyle(color = SoundColors.Text.copy(alpha = 0.4f))) { append("  ·  ") }
                            if (t.isOnline) append(t.album.orEmpty()) else withLink(LinkAnnotation.Clickable("album", style) { actions.openAlbum(t) }) { append(t.album.orEmpty()) }
                        }
                    }
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyLarge,
                        color = dim,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_200, repeatDelayMillis = 2_600),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        HeartOrDownload(
            stream = stream,
            liked = liked,
            onLike = actions.like,
            onDownload = actions.download,
            size = 48.dp,
            iconSize = 28.dp,
            ringSize = 27.dp,
            idleTint = SoundColors.Text.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun Transport(state: PlaybackState, playing: Boolean, actions: PlayerActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        PlayerIconButton(
            Icons.Rounded.Shuffle,
            stringResource(if (state.shuffle) R.string.player_shuffle_disable else R.string.player_shuffle_enable),
            actions.toggleShuffle,
            modifier = Modifier.padding(start = 0.dp),
            iconSize = 24.dp,
            tint = if (state.shuffle) SoundColors.Mint else SoundColors.Text.copy(alpha = 0.7f),
            dot = state.shuffle,
        )
        PlayerIconButton(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous), actions.previous, size = 64.dp, iconSize = 50.dp, tint = SoundColors.Mint)
        MorphPlayPause(playing = playing, onClick = actions.togglePlay, size = 84.dp, container = Color.Transparent, content = SoundColors.Mint, buffering = state.isBuffering)
        PlayerIconButton(Icons.Rounded.SkipNext, stringResource(R.string.player_next), actions.next, size = 64.dp, iconSize = 50.dp, tint = SoundColors.Mint)
        PlayerIconButton(
            Icons.Rounded.Repeat,
            stringResource(
                when (state.repeat) {
                    RepeatMode.OFF -> R.string.player_repeat_off
                    RepeatMode.ALL -> R.string.player_repeat_all
                    RepeatMode.ONE -> R.string.player_repeat_one
                },
            ),
            actions.cycleRepeat,
            iconSize = 24.dp,
            tint = if (state.repeat != RepeatMode.OFF) SoundColors.Mint else SoundColors.Text.copy(alpha = 0.7f),
            dot = state.repeat != RepeatMode.OFF,
            badge = if (state.repeat == RepeatMode.ONE) "1" else null,
        )
    }
}

@Composable
private fun BottomRow(outputName: String?, output: OutputRoute, sleep: SleepTimer, lyrics: LyricsState, actions: PlayerActions) {
    // Equalizer · "Letra" in the middle · timer and queue; the output line only for headphones/car.
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            PlayerIconButton(
                Icons.Rounded.Tune,
                stringResource(R.string.player_equalizer),
                actions.openEqualizer,
                Modifier.align(Alignment.CenterStart),
                size = 48.dp,
                iconSize = 26.dp,
                tint = SoundColors.Text.copy(alpha = 0.85f),
            )
            LyricsPill(lyrics, actions.openLyrics, Modifier.align(Alignment.Center))
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                PlayerIconButton(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.player_queue), actions.openQueue, size = 48.dp, iconSize = 26.dp, tint = SoundColors.Text.copy(alpha = 0.85f))
            }
        }
        if (output.kind != OutputKind.PHONE) {
            Spacer(Modifier.height(10.dp))
            OutputChip(outputName, output, actions.openOutput, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/**
 * "Letra": one tap to the full-screen lyrics. Lit when there are lyrics (with a tangerine dot when
 * they follow the song), quiet otherwise; the screen itself explains when there are none.
 */
@Composable
private fun LyricsPill(lyrics: LyricsState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ready = lyrics as? LyricsState.Ready
    val tint by animateColorAsState(SoundColors.Text.copy(alpha = if (ready != null) 1f else 0.55f), tween(240), label = "lyricsPill")
    val fill by animateColorAsState(SoundColors.Text.copy(alpha = if (ready != null) 0.14f else 0.07f), tween(240), label = "lyricsPillBg")
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(R.string.player_lyrics_open)
    Row(
        modifier
            .height(44.dp)
            .pressScale(interaction, 0.94f)
            .clip(CircleShape)
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(Icons.Rounded.Lyrics, null, tint = if (ready != null) SoundColors.Mint else tint, modifier = Modifier.size(22.dp))
            if (ready?.lyrics?.synced == true) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-1).dp).size(6.dp).clip(CircleShape).background(SoundColors.Lyric))
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(stringResource(R.string.player_lyrics), style = MaterialTheme.typography.titleMedium, color = tint, maxLines = 1)
    }
}

@Composable
private fun OutputChip(outputName: String?, route: OutputRoute, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val external = route.kind != OutputKind.PHONE
    val label = outputName?.takeIf { it.isNotBlank() } ?: when (route.kind) {
        OutputKind.BLUETOOTH -> route.deviceName ?: stringResource(R.string.player_output_bluetooth)
        OutputKind.WIRED -> stringResource(R.string.player_output_wired)
        OutputKind.USB -> route.deviceName ?: stringResource(R.string.player_output_usb)
        OutputKind.PHONE -> stringResource(R.string.player_output_phone)
    }
    val icon = when (route.kind) {
        OutputKind.BLUETOOTH -> Icons.Rounded.BluetoothAudio
        OutputKind.WIRED -> Icons.Rounded.Headphones
        OutputKind.USB -> Icons.Rounded.Headset
        OutputKind.PHONE -> Icons.AutoMirrored.Rounded.VolumeUp
    }
    val tint = if (external) SoundColors.Accent else SoundColors.Text.copy(alpha = 0.8f)
    val interaction = remember { MutableInteractionSource() }
    val choose = stringResource(R.string.player_output_choose)
    Row(
        modifier
            .height(34.dp)
            .pressScale(interaction, 0.95f)
            .clip(CircleShape)
            .background(SoundColors.Text.copy(alpha = 0.08f))
            .clickable(interactionSource = interaction, indication = null, onClickLabel = choose, onClick = onClick)
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SleepButton(timer: SleepTimer, onClick: () -> Unit) {
    val label = stringResource(R.string.player_sleep)
    when (timer) {
        SleepTimer.Off -> PlayerIconButton(Icons.Rounded.Bedtime, label, onClick, size = 44.dp, iconSize = 22.dp, tint = SoundColors.Text.copy(alpha = 0.85f))
        SleepTimer.EndOfTrack -> PlayerIconButton(Icons.Rounded.Bedtime, label, onClick, size = 44.dp, iconSize = 22.dp, tint = SoundColors.Accent, dot = true)
        is SleepTimer.At -> {
            val now = rememberElapsedRealtime(active = true, periodMs = 1_000)
            val minutes = PlayerMath.sleepMinutesLeft(timer.endsAtElapsedMs - now)
            val interaction = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .height(44.dp)
                    .pressScale(interaction, 0.9f)
                    .clip(CircleShape)
                    .clickable(interactionSource = interaction, indication = null, onClickLabel = label, onClick = onClick)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Bedtime, label, tint = SoundColors.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.player_sleep_minutes_short, minutes), style = MaterialTheme.typography.labelMedium, color = SoundColors.Accent)
            }
        }
    }
}

/** Fades the left and right edges of what is drawn (a scrolling line comes and goes softly). */
private fun Modifier.fadedEdges(edge: Float = 0.1f): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, edge to Color.Black, 1f - edge to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
