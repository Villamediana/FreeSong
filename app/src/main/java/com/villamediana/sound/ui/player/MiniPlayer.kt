package com.villamediana.sound.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.ui.theme.SoundColors
import kotlin.math.abs

internal val MiniCoverSize = 48.dp
internal val MiniCoverInset = 8.dp
internal val MiniCoverCorner = 6.dp
internal val MiniCardCorner = 12.dp
internal val MiniSideMargin = 8.dp

/**
 * The mini player's content (the card itself and the cover are drawn by the host, so the cover
 * can fly into the full player). Title + artist, heart, play/pause and the 2 dp progress line.
 */
@Composable
internal fun MiniPlayerBar(
    track: Track,
    stream: StreamUi,
    liked: Boolean,
    playing: Boolean,
    clock: PlaybackClock,
    durationMs: Long,
    swipeOffset: () -> Float,
    swipeWidth: Float,
    onTogglePlay: () -> Unit,
    onLike: (Boolean) -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                val inset = 10.dp.toPx()
                val y = size.height - 1.dp.toPx()
                val stroke = 2.dp.toPx()
                val end = size.width - inset
                drawLine(SoundColors.Text.copy(alpha = 0.14f), Offset(inset, y), Offset(end, y), strokeWidth = stroke, cap = StrokeCap.Round)
                val f = PlayerMath.fraction(clock.positionMs, durationMs)
                if (f > 0f) drawLine(SoundColors.Text, Offset(inset, y), Offset(inset + (end - inset) * f, y), strokeWidth = stroke, cap = StrokeCap.Round)
            },
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = MiniCoverInset + MiniCoverSize + 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .graphicsLayer {
                        val o = swipeOffset()
                        translationX = o
                        alpha = 1f - (if (swipeWidth > 0f) abs(o) / swipeWidth else 0f).coerceIn(0f, 1f) * 0.9f
                    },
            ) {
                AnimatedContent(
                    targetState = track,
                    contentKey = { it.videoId ?: it.id },
                    transitionSpec = { fadeIn(tween(220, delayMillis = 40)) togetherWith fadeOut(tween(120)) },
                    label = "mini-title",
                ) { t ->
                    Column {
                        Text(
                            t.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = SoundColors.Text,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000, repeatDelayMillis = 3_000),
                        )
                        Spacer(Modifier.height(1.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StreamedMark(visible = t.isOnline, size = 13.dp, gap = 4.dp)
                            Text(t.artist, style = MaterialTheme.typography.bodySmall, color = SoundColors.Text.copy(alpha = 0.66f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            HeartOrDownload(
                stream = stream,
                liked = liked,
                onLike = onLike,
                onDownload = onDownload,
                size = 40.dp,
                iconSize = 22.dp,
                ringSize = 22.dp,
                idleTint = SoundColors.Text.copy(alpha = 0.8f),
            )
            MorphPlayPauseIcon(playing = playing, onClick = onTogglePlay, size = 44.dp)
        }
    }
}
