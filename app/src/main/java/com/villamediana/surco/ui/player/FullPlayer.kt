package com.villamediana.surco.ui.player

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
import com.villamediana.surco.R
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.data.isOnline
import com.villamediana.surco.playback.PlaySource
import com.villamediana.surco.playback.PlaybackState
import com.villamediana.surco.playback.RepeatMode
import com.villamediana.surco.playback.SleepTimer
import com.villamediana.surco.ui.components.Cover
import com.villamediana.surco.ui.components.pressScale
import com.villamediana.surco.ui.search.ItemDownload
import com.villamediana.surco.ui.theme.SurcoColors
import kotlinx.coroutines.launch
import kotlin.math.abs

internal val PlayerGutter = 24.dp
internal val BigCoverCorner = 8.dp
private val LyricsPeek = 20.dp

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
)

/**
 * A streamed song (not on the phone): a small cloud next to the artist and "Baixar" in place of
 * the heart, following the download until the song is in the library.
 */
@Immutable
internal data class StreamUi(val streamed: Boolean, val download: ItemDownload, val online: Boolean) {
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
        val sectionHeight = maxHeight - navBottom - LyricsPeek
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
                Column(section) {
                    TopBar(state.source, actions, hasVideo = track.videoId != null, online = stream.online)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val size = minOf(maxWidth - PlayerGutter * 2, maxHeight - 20.dp).coerceAtLeast(96.dp)
                        CoverPager(state, track, size, maxWidth, coverScale, visible, actions.skipTo, reportCover)
                    }
                    Spacer(Modifier.height(14.dp))
                    Column(Modifier.padding(horizontal = PlayerGutter)) {
                        Controls(state, track, stream, liked, playing, durationMs, clock, sleep, output, lyrics, actions)
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
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
private fun TopBar(source: PlaySource?, actions: PlayerActions, hasVideo: Boolean, online: Boolean) {
    // The source stays centered whatever sits on the right (⋮, and "Ver vídeo" when the song has one).
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        PlayerIconButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.player_collapse), actions.collapse, Modifier.align(Alignment.CenterStart), iconSize = 30.dp)
        Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(if (source != null) R.string.player_playing_from else R.string.player_now_playing).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = SurcoColors.Text.copy(alpha = 0.72f),
                maxLines = 1,
            )
            if (source != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    source.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = SurcoColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(hasVideo, enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.7f), exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.7f)) {
                PlayerIconButton(
                    Icons.Rounded.SmartDisplay,
                    stringResource(R.string.player_video),
                    actions.openVideo,
                    iconSize = 24.dp,
                    tint = SurcoColors.Text.copy(alpha = if (online) 0.9f else 0.4f),
                )
            }
            PlayerIconButton(Icons.Rounded.MoreVert, stringResource(R.string.more_options), actions.openActions, iconSize = 24.dp)
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
) {
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
                    shadowElevation = 20.dp.toPx()
                    shape = RoundedCornerShape(BigCoverCorner)
                    clip = true
                },
        ) {
            Cover(
                t.coverPath,
                Modifier.fillMaxSize(),
                seed = t.album ?: t.title,
                shape = RectangleShape,
                contentDescription = stringResource(R.string.player_cover, t.title),
            )
        }
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
    Spacer(Modifier.height(14.dp))
    SeekBar(clock = clock, durationMs = durationMs, onSeek = actions.seek, timeColor = SurcoColors.Text.copy(alpha = 0.66f))
    Spacer(Modifier.height(4.dp))
    Transport(state, playing, actions)
    Spacer(Modifier.height(10.dp))
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
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.015).em),
                    color = SurcoColors.Text,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_800, repeatDelayMillis = 2_600),
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val link = MaterialTheme.typography.bodyLarge
                    val dim = SurcoColors.Text.copy(alpha = 0.7f)
                    StreamedMark(visible = t.isOnline, size = 16.dp, gap = 6.dp)
                    Text(
                        t.artist,
                        style = link,
                        color = dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // A streamed song's artist/album aren't in the library: no link to follow.
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clickable(enabled = !t.isOnline, interactionSource = null, indication = null) { actions.openArtist(t.artist) },
                    )
                    if (t.hasAlbum) {
                        Text("  ·  ", style = link, color = SurcoColors.Text.copy(alpha = 0.4f))
                        Text(
                            t.album.orEmpty(),
                            style = link,
                            color = dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clickable(enabled = !t.isOnline, interactionSource = null, indication = null) { actions.openAlbum(t) },
                        )
                    }
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
            idleTint = SurcoColors.Text.copy(alpha = 0.8f),
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
            tint = if (state.shuffle) SurcoColors.Accent else SurcoColors.Text.copy(alpha = 0.85f),
            dot = state.shuffle,
        )
        PlayerIconButton(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous), actions.previous, size = 56.dp, iconSize = 40.dp)
        MorphPlayPause(playing = playing, onClick = actions.togglePlay, size = 68.dp, buffering = state.isBuffering)
        PlayerIconButton(Icons.Rounded.SkipNext, stringResource(R.string.player_next), actions.next, size = 56.dp, iconSize = 40.dp)
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
            tint = if (state.repeat != RepeatMode.OFF) SurcoColors.Accent else SurcoColors.Text.copy(alpha = 0.85f),
            dot = state.repeat != RepeatMode.OFF,
            badge = if (state.repeat == RepeatMode.ONE) "1" else null,
        )
    }
}

@Composable
private fun BottomRow(outputName: String?, output: OutputRoute, sleep: SleepTimer, lyrics: LyricsState, actions: PlayerActions) {
    // The chip takes what it needs (up to all the free space); the buttons stay pinned to the end.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        OutputChip(outputName, output, actions.openOutput, Modifier.weight(1f, fill = false).padding(end = 12.dp))
        BottomButtons(sleep, lyrics, actions)
    }
}

@Composable
private fun BottomButtons(sleep: SleepTimer, lyrics: LyricsState, actions: PlayerActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LyricsPill(lyrics, actions.openLyrics)
        Spacer(Modifier.width(4.dp))
        SleepButton(sleep, actions.openSleep)
        PlayerIconButton(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.player_queue), actions.openQueue, size = 44.dp, iconSize = 24.dp, tint = SurcoColors.Text.copy(alpha = 0.85f))
    }
}

/**
 * "Letra": one tap to the full-screen lyrics. Lit when there are lyrics (with a tangerine dot when
 * they follow the song), quiet otherwise; the screen itself explains when there are none.
 */
@Composable
private fun LyricsPill(lyrics: LyricsState, onClick: () -> Unit) {
    val ready = lyrics as? LyricsState.Ready
    val tint by animateColorAsState(SurcoColors.Text.copy(alpha = if (ready != null) 0.95f else 0.5f), tween(240), label = "lyricsPill")
    val fill by animateColorAsState(SurcoColors.Text.copy(alpha = if (ready != null) 0.12f else 0.06f), tween(240), label = "lyricsPillBg")
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(R.string.player_lyrics_open)
    Row(
        Modifier
            .height(34.dp)
            .pressScale(interaction, 0.94f)
            .clip(CircleShape)
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(start = 10.dp, end = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(Icons.Rounded.Lyrics, null, tint = tint, modifier = Modifier.size(18.dp))
            if (ready?.lyrics?.synced == true) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-1).dp).size(6.dp).clip(CircleShape).background(SurcoColors.Accent))
            }
        }
        Spacer(Modifier.width(7.dp))
        Text(stringResource(R.string.player_lyrics), style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
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
    val tint = if (external) SurcoColors.Accent else SurcoColors.Text.copy(alpha = 0.8f)
    val interaction = remember { MutableInteractionSource() }
    val choose = stringResource(R.string.player_output_choose)
    Row(
        modifier
            .height(34.dp)
            .pressScale(interaction, 0.95f)
            .clip(CircleShape)
            .background(SurcoColors.Text.copy(alpha = 0.08f))
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
        SleepTimer.Off -> PlayerIconButton(Icons.Rounded.Bedtime, label, onClick, size = 44.dp, iconSize = 22.dp, tint = SurcoColors.Text.copy(alpha = 0.85f))
        SleepTimer.EndOfTrack -> PlayerIconButton(Icons.Rounded.Bedtime, label, onClick, size = 44.dp, iconSize = 22.dp, tint = SurcoColors.Accent, dot = true)
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
                Icon(Icons.Rounded.Bedtime, label, tint = SurcoColors.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.player_sleep_minutes_short, minutes), style = MaterialTheme.typography.labelMedium, color = SurcoColors.Accent)
            }
        }
    }
}
