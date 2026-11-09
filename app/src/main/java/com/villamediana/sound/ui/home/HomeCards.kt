package com.villamediana.sound.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.sound.R
import com.villamediana.sound.data.model.GenreSummary
import com.villamediana.sound.download.DownloadSummary
import com.villamediana.sound.smart.Mix
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.CoverColors
import com.villamediana.sound.ui.components.MosaicCover
import com.villamediana.sound.ui.components.PlayPauseButton
import com.villamediana.sound.ui.components.PlayingBars
import com.villamediana.sound.ui.components.SoundMark
import com.villamediana.sound.ui.components.pressScale
import com.villamediana.sound.ui.components.seedColor
import com.villamediana.sound.ui.components.songCount
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------- modifiers

/**
 * Click + long-press + the shared press shrink, without a ripple. combinedClickable already
 * fires the long-press haptic (LocalHapticFeedback) on its own.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, pressed: Float = 0.96f): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this
        .pressScale(interaction, pressed)
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
}

/** First-load entrance: fade + rise, staggered by [index]. No-op once the intro has played. */
@Composable
fun Modifier.staggerIn(index: Int, play: Boolean): Modifier {
    val progress = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(index.coerceAtMost(8) * 55L)
            progress.animateTo(1f, tween(durationMillis = 460, easing = Motion.Emphasized))
        }
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 22.dp.toPx()
    }
}

// ---------------------------------------------------------------------------- art

/** Genre color: the genre's cover tint, else a stable hue from its name — always readable under paper text. */
fun genreTint(genre: GenreSummary): Color =
    CoverColors.tune(genre.accentColor?.let { Color(it) } ?: seedColor(genre.name))

/** Tint block with the genre's top cover tilted in the corner. Text (if any) goes on top. */
@Composable
fun GenreArt(genre: GenreSummary, modifier: Modifier = Modifier, coverFraction: Float = 0.62f, corner: Dp = 8.dp) {
    val tint = genreTint(genre)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(corner)).background(tint)) {
        val side = minOf(maxWidth, maxHeight) * coverFraction
        Cover(
            genre.covers.firstOrNull(),
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = side * 0.20f, y = side * 0.10f)
                .size(side)
                .rotate(24f),
            seed = genre.name,
            shape = RoundedCornerShape(4.dp),
        )
    }
}

/** A sleeve with the record sliding out of it; the record turns while [spinning]. */
@Composable
fun SleeveWithRecord(cover: String?, seed: String, spinning: Boolean, modifier: Modifier = Modifier, sleeve: Dp = 92.dp) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(spinning) {
        if (spinning) {
            while (true) {
                rotation.animateTo(rotation.value + 360f, tween(durationMillis = 3600, easing = LinearEasing))
                rotation.snapTo(rotation.value % 360f)
            }
        }
    }
    // Tucked a bit into the sleeve while idle, slides out when it plays.
    val out by animateFloatAsState(if (spinning) 1f else 0f, Motion.bouncy(), label = "recordOut")
    Box(modifier.size(width = sleeve * 1.42f, height = sleeve)) {
        Box(
            Modifier
                .size(sleeve * 0.94f)
                .align(Alignment.CenterEnd)
                .graphicsLayer {
                    translationX = -(1f - out) * sleeve.toPx() * 0.16f
                    rotationZ = rotation.value
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(Color(0xFF050404), radius = r, center = c)
                for (i in 0..6) {
                    drawCircle(
                        SoundColors.Hairline.copy(alpha = 0.55f + (i % 2) * 0.25f),
                        radius = r * (0.46f + i * 0.075f),
                        center = c,
                        style = Stroke(width = 0.8.dp.toPx()),
                    )
                }
                drawCircle(SoundColors.Accent, radius = r * 0.32f, center = c)
            }
            com.villamediana.sound.ui.components.SoundBars(Modifier.size(sleeve * 0.94f * 0.2f), color = SoundColors.OnAccent)
        }
        Cover(cover, Modifier.size(sleeve).align(Alignment.CenterStart), seed = seed)
    }
}

// ---------------------------------------------------------------------------- cards

/** Square cover + title + subtitle, used by the album/song carousels. */
@Composable
fun CoverCard(
    cover: String?,
    seed: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    playing: Boolean? = null,
    size: Dp = Dimens.CardCover,
) {
    Column(modifier.width(size).pressable(onClick, onLongClick)) {
        Box(Modifier.size(size)) {
            Cover(cover, Modifier.fillMaxSize(), seed = seed)
            if (playing != null) LiveBadge(playing, Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        Spacer(Modifier.height(9.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Small dark pill with the live bars: "this is what's playing". */
@Composable
fun LiveBadge(playing: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.size(26.dp).clip(CircleShape).background(SoundColors.Ink.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center,
    ) { PlayingBars(playing, Modifier.size(12.dp)) }
}

@Composable
fun MixCard(mix: Mix, playing: Boolean, onOpen: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier, size: Dp = 156.dp) {
    // The mix's own color (from its covers) glides in when it arrives after the card.
    val tint by animateColorAsState(mix.tint?.let { Color(it) } ?: SoundColors.Accent, tween(420), label = "mixTint")
    // The mix of this moment (it changes with the time, the day, the place, the friends along): the
    // same card, set apart without a word — the accent's light from a corner, rings of sound
    // spreading from it, a little spark, the foot from the accent into mint.
    val moment = mix.kind == Mix.Kind.MOMENT
    Column(modifier.width(size).pressable(onOpen)) {
        Box(Modifier.size(size).clip(RoundedCornerShape(Dimens.CoverCorner))) {
            Crossfade(targetState = mix.covers, animationSpec = tween(320), label = "mixArt") { covers ->
                MosaicCover(covers, Modifier.fillMaxSize(), seed = mix.id, shape = RectangleShape)
            }
            if (moment) {
                MomentLight(Modifier.fillMaxSize())
                MomentSeal(Modifier.align(Alignment.TopStart).padding(9.dp))
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(Brush.horizontalGradient(listOf(SoundColors.Accent, SoundColors.Mint))),
                )
            } else {
                Box(Modifier.fillMaxSize().background(tint.copy(alpha = 0.30f)))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(5.dp).background(tint))
            }
            PlayPauseButton(
                playing = playing,
                onClick = onPlay,
                size = 40.dp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 9.dp, bottom = 14.dp),
                contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(mix.title, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(mix.subtitle, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The accent's light washing in from the top-left corner, with rings of sound spreading from it (the moment's mix). */
@Composable
internal fun MomentLight(modifier: Modifier = Modifier) {
    val accent = SoundColors.Accent
    Box(
        modifier
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.18f), Color.Transparent)))
            .drawBehind {
                val center = androidx.compose.ui.geometry.Offset(-6.dp.toPx(), -6.dp.toPx())
                repeat(4) { i ->
                    drawCircle(
                        Color.White.copy(alpha = 0.16f - i * 0.03f),
                        radius = (34 + 30 * i).dp.toPx(),
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
                    )
                }
            },
    )
}

/** A little spark in a dark circle: this mix is made for right now. */
@Composable
internal fun MomentSeal(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = 0.38f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(size * 0.58f))
    }
}

/** Download icon wrapped in a progress ring, with a count — only while downloads are active or failed. */
@Composable
fun DownloadsIndicator(summary: DownloadSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val visible = summary.active > 0 || summary.failed > 0
    AnimatedVisibility(visible, modifier, enter = fadeIn() + scaleIn(initialScale = 0.6f), exit = fadeOut() + scaleOut(targetScale = 0.6f)) {
        val interaction = remember { MutableInteractionSource() }
        val description = if (summary.active > 0) {
            pluralStringResource(R.plurals.home_downloads_active, summary.active, summary.active)
        } else {
            pluralStringResource(R.plurals.home_downloads_failed, summary.failed, summary.failed)
        }
        Box(
            Modifier
                .size(44.dp)
                .pressScale(interaction)
                .clip(CircleShape)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (summary.active > 0) {
                val p = summary.progress
                if (p != null) {
                    val animated by animateFloatAsState(p.coerceIn(0f, 1f), Motion.soft(), label = "dl")
                    CircularProgressIndicator(
                        progress = { animated },
                        modifier = Modifier.size(32.dp),
                        color = SoundColors.Accent,
                        trackColor = SoundColors.AccentSoft,
                        strokeWidth = 2.5.dp,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = SoundColors.Accent,
                        trackColor = SoundColors.AccentSoft,
                        strokeWidth = 2.5.dp,
                        strokeCap = StrokeCap.Round,
                    )
                }
            } else {
                Box(Modifier.size(32.dp).clip(CircleShape).background(SoundColors.Surface2))
            }
            Icon(Icons.Rounded.ArrowDownward, description, tint = SoundColors.Text, modifier = Modifier.size(17.dp))
            if (summary.active > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-1).dp, y = 3.dp)
                        .height(17.dp)
                        .defaultMinSize(minWidth = 17.dp)
                        .clip(CircleShape)
                        .background(SoundColors.Accent)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (summary.active > 99) "99+" else summary.active.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing),
                        color = SoundColors.OnAccent,
                    )
                }
            } else {
                Box(Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 7.dp).size(9.dp).clip(CircleShape).background(SoundColors.Danger))
            }
        }
    }
}
