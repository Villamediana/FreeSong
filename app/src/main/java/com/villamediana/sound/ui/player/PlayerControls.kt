package com.villamediana.sound.ui.player

import androidx.compose.material3.ripple
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.MutableState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.villamediana.sound.R
import com.villamediana.sound.ui.components.formatDuration
import com.villamediana.sound.ui.components.pressScale
import com.villamediana.sound.ui.search.DownloadIndicator
import com.villamediana.sound.ui.search.ItemDownload
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.launch

/**
 * Thin seek bar with a thumb that grows while dragging, plus elapsed/remaining times. Reads the
 * clock only while drawing (and the times once per second), so playback never recomposes it.
 */
@Composable
internal fun SeekBar(
    clock: PlaybackClock,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    active: Color = SoundColors.Text,
    inactive: Color = SoundColors.Text.copy(alpha = 0.2f),
    timeColor: Color = SoundColors.TextMuted,
    /** Where the finger is (0..1) while dragging; shared so the wave above follows it live. */
    drag: MutableState<Float?> = remember { mutableStateOf(null) },
) {
    val dragging by remember { derivedStateOf { drag.value != null } }
    val grow = animateFloatAsState(if (dragging) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 700f), label = "thumb")
    val duration by rememberUpdatedState(durationMs)
    val seek by rememberUpdatedState(onSeek)

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        if (duration > 0 && size.width > 0) seek(((o.x / size.width).coerceIn(0f, 1f) * duration).toLong())
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> if (duration > 0 && size.width > 0) drag.value = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            drag.value?.let { seek((it * duration).toLong()) }
                            drag.value = null
                        },
                        onDragCancel = { drag.value = null },
                        onHorizontalDrag = { change, dx ->
                            val f = drag.value ?: return@detectHorizontalDragGestures
                            change.consume()
                            drag.value = (f + dx / size.width).coerceIn(0f, 1f)
                        },
                    )
                }
                .drawBehind {
                    val f = drag.value ?: PlayerMath.fraction(clock.positionMs, durationMs)
                    val g = grow.value
                    val cy = size.height / 2f
                    val stroke = (3.dp + 1.dp * g).toPx()
                    drawLine(inactive, Offset(0f, cy), Offset(size.width, cy), strokeWidth = stroke, cap = StrokeCap.Round)
                    if (f > 0f) drawLine(active, Offset(0f, cy), Offset(size.width * f, cy), strokeWidth = stroke, cap = StrokeCap.Round)
                    drawCircle(active, radius = (4.5.dp + 3.5.dp * g).toPx(), center = Offset(size.width * f, cy))
                },
        )
        SeekTimes(clock, durationMs, drag, timeColor)
    }
}

@Composable
private fun SeekTimes(clock: PlaybackClock, durationMs: Long, drag: State<Float?>, color: Color) {
    val second by remember(clock, durationMs) {
        derivedStateOf { (drag.value?.let { (it * durationMs).toLong() } ?: clock.positionMs) / 1000 }
    }
    val style = MaterialTheme.typography.labelMedium
    Row(Modifier.fillMaxWidth()) {
        Text(formatDuration(second * 1000), style = style, color = color)
        Spacer(Modifier.weight(1f))
        Text(PlayerMath.remaining(second * 1000, durationMs), style = style, color = color)
    }
}

/**
 * Decorative sound wave above the seek bar: bar heights are stable per song (from [seed]); the
 * played part is lit. Drawn from the clock in the draw phase (no recomposition per tick).
 */
@Composable
internal fun Waveform(
    seed: String,
    clock: PlaybackClock,
    durationMs: Long,
    modifier: Modifier = Modifier,
    /** The seek bar's drag (0..1): the wave lights up to the finger while it moves. */
    scrub: State<Float?>? = null,
) {
    val heights = remember(seed) {
        val r = java.util.Random(seed.hashCode().toLong())
        FloatArray(34) { i ->
            val wave = (kotlin.math.sin(i * 0.55) * 0.5 + 0.5).toFloat()
            (0.18f + 0.82f * (0.55f * wave + 0.45f * r.nextFloat())).coerceIn(0.12f, 1f)
        }
    }
    Spacer(
        modifier.drawBehind {
            val n = heights.size
            val gap = 4.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            val f = scrub?.value ?: PlayerMath.fraction(clock.positionMs, durationMs)
            for (i in 0 until n) {
                val h = size.height * heights[i]
                val x = i * (w + gap)
                val lit = (i + 0.5f) / n <= f
                drawRoundRect(
                    if (lit) SoundColors.Text else SoundColors.Text.copy(alpha = 0.28f),
                    topLeft = Offset(x, size.height - h),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2),
                )
            }
        },
    )
}

/** Round play/pause whose icon morphs (triangle halves ↔ bars) instead of swapping. */
@Composable
internal fun MorphPlayPause(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    container: Color = SoundColors.Text,
    content: Color = SoundColors.Ink,
    buffering: Boolean = false,
) {
    val t = animateFloatAsState(if (playing) 1f else 0f, spring(dampingRatio = 0.82f, stiffness = 650f), label = "morph")
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val label = stringResource(if (playing) R.string.player_pause else R.string.player_play)
    Box(modifier.size(size + 10.dp), contentAlignment = Alignment.Center) {
        // Loading: a soft halo with a comet-tailed arc going round, in the button's own color (the
        // player's button is see-through, so the ring takes the glyph's color, never black).
        androidx.compose.animation.AnimatedVisibility(
            visible = buffering,
            enter = androidx.compose.animation.fadeIn(tween(220)) + androidx.compose.animation.scaleIn(tween(260), initialScale = 0.85f),
            exit = androidx.compose.animation.fadeOut(tween(200)),
        ) {
            BufferingRing(color = if (container.alpha > 0.5f) container else content, modifier = Modifier.size(size + 10.dp))
        }
        Box(
            Modifier
                .size(size)
                .pressScale(interaction, 0.9f)
                .clip(CircleShape)
                .background(container)
                .clickable(interactionSource = interaction, indication = ripple(bounded = false, radius = size / 2 + 6.dp, color = content)) {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                }
                .semantics {
                    contentDescription = label
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(size * 0.5f)) { drawPlayPause(t.value, content) }
        }
    }
}

/** Loading ring: a faint full circle and a rotating arc that fades into its tail. */
@Composable
internal fun BufferingRing(color: Color, modifier: Modifier = Modifier, stroke: Dp = 3.5.dp) {
    val spin = rememberInfiniteTransition(label = "buffering")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(950, easing = LinearEasing)), label = "angle")
    Canvas(modifier) {
        val w = stroke.toPx()
        val inset = w / 2f
        val arc = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
        val topLeft = Offset(inset, inset)
        drawCircle(color.copy(alpha = 0.14f), radius = (size.minDimension - w) / 2f, style = Stroke(w))
        rotate(angle) {
            drawArc(
                brush = Brush.sweepGradient(0f to color.copy(alpha = 0f), 0.62f to color.copy(alpha = 0.35f), 0.86f to color, 0.9f to color, 0.901f to color.copy(alpha = 0f)),
                startAngle = 0f,
                sweepAngle = 310f,
                useCenter = false,
                topLeft = topLeft,
                size = arc,
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }
    }
}

/** Just the morphing glyph, no container (mini player). */
@Composable
internal fun MorphPlayPauseIcon(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color = SoundColors.Text) {
    val t = animateFloatAsState(if (playing) 1f else 0f, spring(dampingRatio = 0.82f, stiffness = 650f), label = "morph")
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val label = stringResource(if (playing) R.string.player_pause else R.string.player_play)
    Box(
        modifier
            .size(size)
            .pressScale(interaction, 0.86f)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = ripple(bounded = false, radius = size / 2, color = tint)) {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.56f)) { drawPlayPause(t.value, tint) }
    }
}

private fun DrawScope.drawPlayPause(t: Float, color: Color) {
    val (a, b) = PlayerMath.playPauseQuads(t)
    val w = size.width
    val h = size.height
    for (q in arrayOf(a, b)) {
        val path = Path().apply {
            moveTo(q[0] * w, q[1] * h)
            lineTo(q[2] * w, q[3] * h)
            lineTo(q[4] * w, q[5] * h)
            lineTo(q[6] * w, q[7] * h)
            close()
        }
        drawPath(path, color)
    }
}

/** Icon button with press scale, an "on" dot and an optional badge (repeat one). */
@Composable
internal fun PlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 26.dp,
    tint: Color = SoundColors.Text,
    dot: Boolean = false,
    badge: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val color by animateColorAsState(tint, tween(220), label = "tint")
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .size(size)
            .pressScale(interaction, 0.86f)
            .clip(CircleShape)
            // A light tick and a ripple ring in the icon's color: every tap answers.
            .clickable(interactionSource = interaction, indication = ripple(bounded = false, radius = size / 2, color = tint)) {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(iconSize))
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .offset(x = iconSize * 0.42f, y = -iconSize * 0.36f)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Text(badge, color = SoundColors.Ink, fontSize = 9.sp, fontWeight = FontWeight.Black, lineHeight = 9.sp)
            }
        }
        if (dot) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = ((size - iconSize) / 2 - 7.dp).coerceAtLeast(0.dp))
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** Heart with a spring "pop" and a haptic tick when liked. */
@Composable
internal fun LikeButton(
    liked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 26.dp,
    idleTint: Color = SoundColors.TextMuted,
    likedTint: Color = SoundColors.Liked,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(1f) }
    val interaction = remember { MutableInteractionSource() }
    val tint by animateColorAsState(if (liked) likedTint else idleTint, tween(200), label = "heart")
    val label = stringResource(if (liked) R.string.player_unlike else R.string.player_like)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = ripple(bounded = false, radius = size / 2, color = likedTint)) {
                val next = !liked
                haptic.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onToggle(next)
                scope.launch {
                    if (next) {
                        pop.snapTo(0.55f)
                        pop.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 520f))
                    } else {
                        pop.snapTo(0.8f)
                        pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 600f))
                    }
                }
            }
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            null,
            tint = tint,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
        )
    }
}

/** Small cloud before the artist: this song is streamed, not on the phone. */
@Composable
internal fun RowScope.StreamedMark(visible: Boolean, size: Dp, gap: Dp) {
    val label = stringResource(R.string.player_streaming)
    AnimatedVisibility(visible, enter = fadeIn(tween(200)) + expandHorizontally(), exit = fadeOut(tween(160)) + shrinkHorizontally()) {
        Icon(
            Icons.Rounded.CloudQueue,
            label,
            tint = SoundColors.Text.copy(alpha = 0.62f),
            modifier = Modifier.padding(end = gap).size(size),
        )
    }
}

/**
 * The heart, and for a streamed song the download button beside it (arrow → queue → filling
 * ring), as on the rows. With "Curtir já baixa" on the heart alone stands for both. When the
 * download lands the song is in the library and the heart stays alone.
 */
@Composable
internal fun HeartOrDownload(
    stream: StreamUi,
    liked: Boolean,
    onLike: (Boolean) -> Unit,
    onDownload: () -> Unit,
    size: Dp,
    iconSize: Dp,
    ringSize: Dp,
    idleTint: Color,
) {
    val haptic = LocalHapticFeedback.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        LikeButton(liked = liked, onToggle = onLike, size = size, iconSize = iconSize, idleTint = idleTint)
        AnimatedVisibility(
            visible = stream.streamed && !stream.likeDownloads,
            enter = fadeIn(tween(180)) + scaleIn(Motion.bouncy(), initialScale = 0.6f),
            exit = fadeOut(tween(120)) + scaleOut(tween(140), targetScale = 0.6f),
        ) {
            val d = stream.download
            val canStart = d == ItemDownload.None || d is ItemDownload.Failed
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                DownloadIndicator(
                    state = d,
                    onClick = if (canStart) {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onDownload()
                        }
                    } else {
                        null
                    },
                    size = ringSize,
                    idleTint = idleTint,
                    wait = stream.wait,
                )
            }
        }
    }
}
