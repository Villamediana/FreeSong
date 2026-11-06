package com.villamediana.surco.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.surco.R
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.ui.theme.Dimens
import com.villamediana.surco.ui.theme.SurcoColors

/**
 * The standard song row: cover (or track number), title, artist · album, optional trailing
 * duration and the ⋮ button. The row of the song that is playing gets accent title + live bars.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    /** Show the track number instead of the cover (album screens). */
    number: Int? = null,
    subtitle: String = listOfNotNull(track.artist, track.album?.takeIf { it.isNotBlank() }).joinToString(" · "),
    showDuration: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = Dimens.Gutter),
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(Dimens.RowHeight)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick ?: onMore)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                if (isCurrent) PlayingBars(isPlaying, Modifier.size(14.dp))
                else Text("$number", style = MaterialTheme.typography.bodyMedium, color = SurcoColors.TextMuted)
            }
        } else {
            Box {
                Cover(track.coverPath, Modifier.size(Dimens.RowCover), seed = track.album ?: track.title)
                if (isCurrent) {
                    Box(
                        Modifier.size(Dimens.RowCover).clip(RoundedCornerShape(Dimens.CoverCorner)).background(Color(0x99000000)),
                        contentAlignment = Alignment.Center,
                    ) { PlayingBars(isPlaying, Modifier.size(16.dp)) }
                }
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) SurcoColors.Accent else SurcoColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.liked) {
                    Icon(Icons.Rounded.Favorite, null, tint = SurcoColors.Liked, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SurcoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showDuration) {
            Text(formatDuration(track.durationMs), style = MaterialTheme.typography.bodySmall, color = SurcoColors.TextFaint, modifier = Modifier.padding(start = 8.dp))
        }
        trailing?.invoke()
        if (onMore != null) {
            IconButton(onClick = onMore, modifier = Modifier.padding(start = 2.dp).size(40.dp)) {
                Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options), tint = SurcoColors.TextMuted)
            }
        }
    }
}

/** Three little bars bouncing — "this one is playing". Frozen when paused. */
@Composable
fun PlayingBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = SurcoColors.Accent, barWidth: Dp = 3.dp) {
    // The infinite transition only exists while playing: it asks for a frame on every vsync for as
    // long as it lives, even when nobody reads its values.
    if (playing) BouncingBars(modifier, color, barWidth) else Bars(PausedBars, modifier, color, barWidth)
}

private val PausedBars = listOf(0.45f, 0.7f, 0.35f)

@Composable
private fun BouncingBars(modifier: Modifier, color: Color, barWidth: Dp) {
    val transition = rememberInfiniteTransition(label = "bars")
    val a by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "a")
    val b by transition.animateFloat(1f, 0.25f, infiniteRepeatable(tween(560), RepeatMode.Reverse), label = "b")
    val c by transition.animateFloat(0.5f, 0.9f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "c")
    Bars(listOf(a, b, c), modifier, color, barWidth)
}

@Composable
private fun Bars(heights: List<Float>, modifier: Modifier, color: Color, barWidth: Dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(barWidth / 1.5f), verticalAlignment = Alignment.Bottom) {
        heights.forEach { h ->
            Box(Modifier.width(barWidth).fillMaxHeight(h).clip(RoundedCornerShape(1.dp)).background(color))
        }
    }
}
