package com.villamediana.surco.ui.player

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
import com.villamediana.surco.R
import com.villamediana.surco.ui.components.formatDuration
import com.villamediana.surco.ui.components.pressScale
import com.villamediana.surco.ui.search.DownloadIndicator
import com.villamediana.surco.ui.search.ItemDownload
import com.villamediana.surco.ui.theme.Motion
import com.villamediana.surco.ui.theme.SurcoColors
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
    active: Color = SurcoColors.Text,
    inactive: Color = SurcoColors.Text.copy(alpha = 0.2f),
    timeColor: Color = SurcoColors.TextMuted,
) {
    val drag = remember { mutableStateOf<Float?>(null) }
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

/** Round play/pause whose icon morphs (triangle halves ↔ bars) instead of swapping. */
@Composable
internal fun MorphPlayPause(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    container: Color = SurcoColors.Text,
    content: Color = SurcoColors.Ink,
    buffering: Boolean = false,
) {
    val t = animateFloatAsState(if (playing) 1f else 0f, spring(dampingRatio = 0.82f, stiffness = 650f), label = "morph")
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(if (playing) R.string.player_pause else R.string.player_play)
    Box(modifier.size(size + 10.dp), contentAlignment = Alignment.Center) {
        if (buffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(size + 10.dp),
                color = container.copy(alpha = 0.7f),
                strokeWidth = 2.dp,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Round,
            )
        }
        Box(
            Modifier
                .size(size)
                .pressScale(interaction, 0.9f)
                .clip(CircleShape)
                .background(container)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
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

/** Just the morphing glyph, no container (mini player). */
@Composable
internal fun MorphPlayPauseIcon(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color = SurcoColors.Text) {
    val t = animateFloatAsState(if (playing) 1f else 0f, spring(dampingRatio = 0.82f, stiffness = 650f), label = "morph")
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(if (playing) R.string.player_pause else R.string.player_play)
    Box(
        modifier
            .size(size)
            .pressScale(interaction, 0.86f)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
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
    tint: Color = SurcoColors.Text,
    dot: Boolean = false,
    badge: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val color by animateColorAsState(tint, tween(220), label = "tint")
    Box(
        modifier
            .size(size)
            .pressScale(interaction, 0.86f)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
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
                Text(badge, color = SurcoColors.Ink, fontSize = 9.sp, fontWeight = FontWeight.Black, lineHeight = 9.sp)
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
    idleTint: Color = SurcoColors.TextMuted,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(1f) }
    val interaction = remember { MutableInteractionSource() }
    val tint by animateColorAsState(if (liked) SurcoColors.Liked else idleTint, tween(200), label = "heart")
    val label = stringResource(if (liked) R.string.player_unlike else R.string.player_like)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null) {
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
            tint = SurcoColors.Text.copy(alpha = 0.62f),
            modifier = Modifier.padding(end = gap).size(size),
        )
    }
}

/**
 * The heart — or, for a streamed song, the download button in its place (arrow → queue → filling
 * ring). When the download lands the song is in the library and the heart springs back.
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
    AnimatedContent(
        targetState = stream.streamed,
        transitionSpec = {
            (fadeIn(tween(180)) + scaleIn(Motion.bouncy(), initialScale = 0.6f)) togetherWith
                (fadeOut(tween(120)) + scaleOut(tween(140), targetScale = 0.6f))
        },
        contentAlignment = Alignment.Center,
        label = "heartOrDownload",
    ) { streamed ->
        if (streamed) {
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
                )
            }
        } else {
            LikeButton(liked = liked, onToggle = onLike, size = size, iconSize = iconSize, idleTint = idleTint)
        }
    }
}
