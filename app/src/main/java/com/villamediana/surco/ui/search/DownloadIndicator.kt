package com.villamediana.surco.ui.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.surco.R
import com.villamediana.surco.ui.components.pressScale
import com.villamediana.surco.ui.theme.Motion
import com.villamediana.surco.ui.theme.SurcoColors
import kotlin.math.roundToInt

private enum class Glyph { None, Queued, Running, Done, Failed }

private fun ItemDownload.glyph() = when (this) {
    ItemDownload.None -> Glyph.None
    ItemDownload.Queued -> Glyph.Queued
    is ItemDownload.Running -> Glyph.Running
    ItemDownload.Done -> Glyph.Done
    is ItemDownload.Failed -> Glyph.Failed
}

/**
 * The trailing download button of an online song: arrow (not downloaded) → spinning ring (queued)
 * → filling ring (downloading) → accent check (on the phone) → red retry (failed).
 */
@Composable
fun DownloadIndicator(
    state: ItemDownload,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
) {
    val description = when (state) {
        ItemDownload.None -> stringResource(R.string.search_dl_download)
        ItemDownload.Queued -> stringResource(R.string.search_dl_queued)
        is ItemDownload.Running -> stringResource(R.string.search_dl_running, (state.progress * 100).roundToInt())
        ItemDownload.Done -> stringResource(R.string.search_dl_done)
        is ItemDownload.Failed -> stringResource(R.string.search_dl_failed)
    }
    val progress = (state as? ItemDownload.Running)?.progress ?: 0f
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(44.dp)
            .pressScale(interaction, 0.86f)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state.glyph(),
            transitionSpec = {
                (fadeIn(tween(160)) + scaleIn(Motion.bouncy(), initialScale = 0.55f)) togetherWith
                    (fadeOut(tween(110)) + scaleOut(tween(140), targetScale = 0.55f))
            },
            contentAlignment = Alignment.Center,
            label = "downloadGlyph",
        ) { glyph ->
            when (glyph) {
                Glyph.None -> Box(Modifier.size(size).border(1.6.dp, SurcoColors.TextMuted, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ArrowDownward, null, tint = SurcoColors.TextMuted, modifier = Modifier.size(size * 0.6f))
                }
                Glyph.Queued -> SpinningRing(size)
                Glyph.Running -> ProgressRing(progress, size)
                Glyph.Done -> Box(Modifier.size(size).clip(CircleShape).background(SurcoColors.Accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = SurcoColors.OnAccent, modifier = Modifier.size(size * 0.68f))
                }
                Glyph.Failed -> Box(Modifier.size(size).border(1.6.dp, SurcoColors.Danger, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Refresh, null, tint = SurcoColors.Danger, modifier = Modifier.size(size * 0.62f))
                }
            }
        }
    }
}

/** Waiting in the queue: a short accent arc circling a quiet track. */
@Composable
fun SpinningRing(size: Dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "queued")
    val angle = transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 2.2.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(SurcoColors.Hairline, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            rotate(angle.value) {
                drawArc(SurcoColors.Accent, -90f, 80f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Icon(Icons.Rounded.ArrowDownward, null, tint = SurcoColors.Accent.copy(alpha = 0.75f), modifier = Modifier.size(size * 0.48f))
    }
}

/** Downloading: the ring fills clockwise from the top. */
@Composable
fun ProgressRing(progress: Float, size: Dp, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.soft(), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 2.4.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(SurcoColors.AccentSoft, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(SurcoColors.Accent, -90f, 360f * animated.coerceAtLeast(0.02f), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Box(Modifier.size(size * 0.26f).clip(androidx.compose.foundation.shape.RoundedCornerShape(1.5.dp)).background(SurcoColors.Accent))
    }
}

/** Multi-select mark (same footprint as the download button so rows don't jump). */
@Composable
fun SelectMark(selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val description = stringResource(if (selected) R.string.search_dl_selected else R.string.search_dl_not_selected)
    Box(modifier.size(44.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = selected,
            transitionSpec = { (fadeIn(tween(120)) + scaleIn(Motion.bouncy(), initialScale = 0.6f)) togetherWith fadeOut(tween(90)) },
            label = "select",
        ) { on ->
            if (on) {
                Box(Modifier.size(size).clip(CircleShape).background(SurcoColors.Accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = SurcoColors.OnAccent, modifier = Modifier.size(size * 0.7f))
                }
            } else {
                Box(Modifier.size(size).border(1.6.dp, if (enabled) SurcoColors.TextMuted else SurcoColors.Hairline, CircleShape))
            }
        }
    }
}
