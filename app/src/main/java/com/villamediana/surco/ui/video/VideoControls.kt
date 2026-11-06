package com.villamediana.surco.ui.video

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import com.villamediana.surco.R
import com.villamediana.surco.ui.components.pressScale
import com.villamediana.surco.ui.theme.SurcoColors

// ------------------------------------------------------------------ progress

/** Position of the video, backed by snapshot state: read it in draw lambdas or behind derivedStateOf. */
@Stable
internal interface VideoProgress {
    val positionMs: Long
    val durationMs: Long
    val bufferedMs: Long
}

private object NoProgress : VideoProgress {
    override val positionMs: Long = 0L
    override val durationMs: Long = 0L
    override val bufferedMs: Long = 0L
}

/**
 * Reads the position from the [Player] with media3's tick-interval state: it re-reads on the
 * player's own events and, while playing, only every [TICK_MS] of media time — no frame loop.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun rememberVideoProgress(player: Player?): VideoProgress {
    if (player == null) return NoProgress
    val state = rememberProgressStateWithTickInterval(player, tickIntervalMs = TICK_MS)
    return remember(state) {
        object : VideoProgress {
            override val positionMs: Long get() = state.currentPositionMs
            override val durationMs: Long get() = state.durationMs
            override val bufferedMs: Long get() = state.bufferedPositionMs
        }
    }
}

private const val TICK_MS = 250L

// ------------------------------------------------------------------ seek bar

/**
 * Thin seek bar: paper track, buffered part a little brighter and the played part in the one
 * tangerine accent. Drag or tap to seek; the thumb grows while dragging. Times sit below.
 * Position is read only while drawing (and the times once per second).
 */
@Composable
internal fun VideoSeekBar(
    progress: VideoProgress,
    pending: PendingSeek?,
    onSeek: (Long) -> Unit,
    onScrub: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    timeColor: Color = SurcoColors.TextMuted,
) {
    val drag = remember { mutableStateOf<Float?>(null) }
    val dragging by remember { derivedStateOf { drag.value != null } }
    val grow by animateFloatAsState(if (dragging) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 700f), label = "thumb")
    val seek by rememberUpdatedState(onSeek)
    val scrub by rememberUpdatedState(onScrub)
    val hold by rememberUpdatedState(pending)
    val label = stringResource(R.string.video_position)

    fun shownMs(): Long {
        val total = progress.durationMs
        drag.value?.let { return (it * total.coerceAtLeast(0)).toLong() }
        val reported = progress.positionMs
        return hold?.shown(reported, SystemClock.uptimeMillis()) ?: reported
    }

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(VideoTime.fraction(progress.positionMs, progress.durationMs), 0f..1f)
                    setProgress { f ->
                        val total = progress.durationMs
                        if (total > 0) seek((f.coerceIn(0f, 1f) * total).toLong())
                        total > 0
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        val total = progress.durationMs
                        if (total > 0 && size.width > 0) seek(((o.x / size.width).coerceIn(0f, 1f) * total).toLong())
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o ->
                            if (progress.durationMs > 0 && size.width > 0) {
                                drag.value = (o.x / size.width).coerceIn(0f, 1f)
                                scrub(true)
                            }
                        },
                        onDragEnd = {
                            drag.value?.let { seek((it * progress.durationMs).toLong()) }
                            drag.value = null
                            scrub(false)
                        },
                        onDragCancel = {
                            drag.value = null
                            scrub(false)
                        },
                        onHorizontalDrag = { change, dx ->
                            val f = drag.value ?: return@detectHorizontalDragGestures
                            change.consume()
                            drag.value = (f + dx / size.width).coerceIn(0f, 1f)
                        },
                    )
                }
                .drawBehind {
                    val total = progress.durationMs
                    val f = VideoTime.fraction(shownMs(), total)
                    val buffered = VideoTime.fraction(progress.bufferedMs, total)
                    val cy = size.height / 2f
                    val stroke = (3.dp + 1.5.dp * grow).toPx()
                    drawLine(SurcoColors.Text.copy(alpha = 0.18f), Offset(0f, cy), Offset(size.width, cy), strokeWidth = stroke, cap = StrokeCap.Round)
                    if (buffered > f) {
                        drawLine(SurcoColors.Text.copy(alpha = 0.34f), Offset(0f, cy), Offset(size.width * buffered, cy), strokeWidth = stroke, cap = StrokeCap.Round)
                    }
                    if (f > 0f) drawLine(SurcoColors.Accent, Offset(0f, cy), Offset(size.width * f, cy), strokeWidth = stroke, cap = StrokeCap.Round)
                    if (total > 0) drawCircle(SurcoColors.Accent, radius = (5.dp + 3.5.dp * grow).toPx(), center = Offset(size.width * f, cy))
                },
        )
        SeekTimes(progress, ::shownMs, timeColor)
    }
}

@Composable
private fun SeekTimes(progress: VideoProgress, shownMs: () -> Long, color: Color) {
    val second by remember(progress) { derivedStateOf { shownMs() / 1000 } }
    val total by remember(progress) { derivedStateOf { progress.durationMs } }
    val style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    Row(Modifier.fillMaxWidth()) {
        Text(VideoTime.format(second * 1000, total), style = style, color = color)
        Spacer(Modifier.weight(1f))
        Text(VideoTime.formatTotal(total), style = style, color = color)
    }
}

// ------------------------------------------------------------------ play / pause

/**
 * The big center button: a soft dark disc with a paper glyph that morphs (triangle halves ↔ bars).
 * When the video has ended it turns into "watch again".
 */
@Composable
internal fun VideoPlayPause(
    playing: Boolean,
    ended: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
) {
    val t by animateFloatAsState(if (playing) 1f else 0f, spring(dampingRatio = 0.82f, stiffness = 650f), label = "morph")
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(
        when {
            ended -> R.string.video_replay
            playing -> R.string.video_pause
            else -> R.string.video_play
        },
    )
    Box(
        modifier
            .size(size)
            .pressScale(interaction, 0.88f)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .border(1.dp, SurcoColors.Text.copy(alpha = 0.12f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(ended, animationSpec = tween(220), label = "ended") { done ->
            if (done) {
                Icon(Icons.Rounded.Replay, null, tint = SurcoColors.Text, modifier = Modifier.size(size * 0.46f))
            } else {
                Canvas(Modifier.size(size * 0.42f)) { drawPlayPause(t, SurcoColors.Text) }
            }
        }
    }
}

/** Corners of the two quads (x0,y0 … x3,y3 in a unit square): play halves at 0, pause bars at 1. */
private val PLAY_LEFT = floatArrayOf(0.27f, 0.10f, 0.59f, 0.30f, 0.59f, 0.70f, 0.27f, 0.90f)
private val PLAY_RIGHT = floatArrayOf(0.59f, 0.30f, 0.91f, 0.50f, 0.91f, 0.50f, 0.59f, 0.70f)
private val PAUSE_LEFT = floatArrayOf(0.18f, 0.12f, 0.40f, 0.12f, 0.40f, 0.88f, 0.18f, 0.88f)
private val PAUSE_RIGHT = floatArrayOf(0.60f, 0.12f, 0.82f, 0.12f, 0.82f, 0.88f, 0.60f, 0.88f)

private fun DrawScope.drawPlayPause(t: Float, color: Color) {
    val w = size.width
    val h = size.height
    for ((from, to) in arrayOf(PLAY_LEFT to PAUSE_LEFT, PLAY_RIGHT to PAUSE_RIGHT)) {
        fun x(i: Int) = (from[i] + (to[i] - from[i]) * t) * w
        fun y(i: Int) = (from[i] + (to[i] - from[i]) * t) * h
        val path = Path().apply {
            moveTo(x(0), y(1))
            lineTo(x(2), y(3))
            lineTo(x(4), y(5))
            lineTo(x(6), y(7))
            close()
        }
        drawPath(path, color)
    }
}

// ------------------------------------------------------------------ small buttons

/** Round icon button for the overlay (back). */
@Composable
internal fun VideoIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(48.dp)
            .pressScale(interaction, 0.86f)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = SurcoColors.Text, modifier = Modifier.size(26.dp))
    }
}

/** Pill that sits on top of the video (landscape): translucent black, hairline, paper text. */
@Composable
internal fun StagePill(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(40.dp)
            .pressScale(interaction, 0.96f)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .border(1.dp, SurcoColors.Text.copy(alpha = 0.16f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = SurcoColors.Text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = SurcoColors.Text, maxLines = 1)
    }
}

// ------------------------------------------------------------------ double-tap hint

/**
 * Feedback for a double-tap seek: a soft arc hugging the tapped edge, a ripple from the finger
 * and the size of the jump ("10 s", "20 s"…). Lives as long as the streak does.
 */
@Composable
internal fun BoxScope.SeekHint(streak: SeekStreak?, tap: Offset?) {
    // Keeps the last streak around so the hint can fade out showing it (not state: [streak] drives recomposition).
    val last = remember { arrayOfNulls<SeekStreak>(1) }
    if (streak != null) last[0] = streak
    val shown = last[0] ?: return
    AnimatedVisibility(
        visible = streak != null,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(260)),
        modifier = Modifier.matchParentSize(),
    ) {
        val back = shown.side == SeekSide.BACK
        val ripple = remember { Animatable(0f) }
        LaunchedEffect(shown.taps, shown.side) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(520))
        }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(HINT_WIDTH)
                    .align(if (back) Alignment.CenterStart else Alignment.CenterEnd)
                    .clipToBounds()
                    .drawBehind {
                        // The arc: a circle much taller than the stage, centred past the edge.
                        val r = size.height * 1.15f
                        val cx = if (back) size.width - r else r
                        drawCircle(SurcoColors.Text.copy(alpha = 0.09f), radius = r, center = Offset(cx, size.height / 2f))
                        // The ripple, from the finger when it is inside this half.
                        val p = ripple.value
                        val shift = if (back) 0f else size.width / HINT_WIDTH - size.width
                        val origin = tap?.let { Offset(it.x - shift, it.y) } ?: Offset(size.width / 2f, size.height / 2f)
                        drawCircle(
                            SurcoColors.Text.copy(alpha = 0.16f * (1f - p)),
                            radius = 18.dp.toPx() + 110.dp.toPx() * p,
                            center = origin,
                        )
                    },
            )
            Column(
                Modifier
                    .align(if (back) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxWidth(HINT_WIDTH),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val pop = remember { Animatable(1f) }
                LaunchedEffect(shown.taps) {
                    pop.snapTo(0.82f)
                    pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 700f))
                }
                Icon(
                    if (back) Icons.Rounded.FastRewind else Icons.Rounded.FastForward,
                    null,
                    tint = SurcoColors.Text,
                    modifier = Modifier
                        .size(30.dp)
                        .graphicsLayer {
                            scaleX = pop.value
                            scaleY = pop.value
                        },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.video_seek_seconds, shown.seconds.toInt()),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = SurcoColors.Text,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Share of the stage width the hint covers on its side. */
private const val HINT_WIDTH = 0.42f

/** A small, quiet "please wait" for the stage (no big spinner over the video). */
@Composable
internal fun StageSpinner(visible: Boolean, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.video_loading)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(240, delayMillis = 120)) + scaleIn(initialScale = 0.8f),
        exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.9f),
        modifier = modifier,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier
                .size(34.dp)
                .semantics { contentDescription = label },
            color = SurcoColors.Text.copy(alpha = 0.7f),
            strokeWidth = 2.dp,
            trackColor = Color.Transparent,
            strokeCap = StrokeCap.Round,
        )
    }
}
