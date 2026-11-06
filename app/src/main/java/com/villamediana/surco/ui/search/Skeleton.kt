package com.villamediana.surco.ui.search

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.surco.ui.theme.Dimens
import com.villamediana.surco.ui.theme.SurcoColors

/**
 * Loading placeholder: a low-contrast highlight sweeping across Surface2. The animated value is
 * only read while drawing, so skeletons never recompose.
 */
@Composable
fun Modifier.shimmer(shape: Shape = RoundedCornerShape(4.dp)): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val sweep = transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    return this
        .clip(shape)
        .drawBehind {
            val x = size.width * sweep.value
            val band = size.width.coerceAtLeast(240f) * 0.6f
            drawRect(SurcoColors.Surface2)
            drawRect(
                Brush.linearGradient(
                    0f to SurcoColors.Surface2,
                    0.5f to SurcoColors.Surface3,
                    1f to SurcoColors.Surface2,
                    start = Offset(x - band, 0f),
                    end = Offset(x + band, size.height),
                ),
            )
        }
}

/** Skeleton of a song/collection row while results load. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier, round: Boolean = false, coverSize: Dp = Dimens.RowCover, widthFraction: Float = 0.62f) {
    Row(
        modifier.fillMaxWidth().height(Dimens.RowHeight).padding(horizontal = Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(coverSize).shimmer(if (round) CircleShape else RoundedCornerShape(Dimens.CoverCorner)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(widthFraction).height(13.dp).shimmer())
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(widthFraction * 0.6f).height(11.dp).shimmer())
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.size(24.dp).shimmer(CircleShape))
    }
}

/** Varying widths so a column of skeleton rows does not look stamped. */
fun skeletonWidth(index: Int): Float = listOf(0.66f, 0.5f, 0.74f, 0.58f, 0.7f, 0.46f)[index % 6]
