package com.villamediana.sound.ui.components

import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Album art. [model] may be an absolute file path, a File, or an http(s) url; null draws the
 * placeholder: a dark sleeve with a groove spiral tinted by [seed] (so every song without art
 * still gets its own, stable look).
 */
@Composable
fun Cover(
    model: Any?,
    modifier: Modifier = Modifier,
    seed: String = "",
    shape: Shape = RoundedCornerShape(Dimens.CoverCorner),
    contentDescription: String? = null,
) {
    val data = remember(model) {
        when (model) {
            is String -> if (model.startsWith("http")) model else File(model)
            else -> model
        }
    }
    Box(modifier.clip(shape).background(SoundColors.Surface2)) {
        if (data == null) {
            CoverPlaceholder(seed, Modifier.fillMaxSize())
        } else {
            SubcomposeAsyncImage(
                model = data,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { Box(Modifier.fillMaxSize().background(SoundColors.Surface2)) },
                error = { CoverPlaceholder(seed, Modifier.fillMaxSize()) },
            )
        }
    }
}

/** 2×2 mosaic for playlists without a custom cover (falls back to a single cover / placeholder). */
@Composable
fun MosaicCover(
    covers: List<String>,
    modifier: Modifier = Modifier,
    seed: String = "",
    shape: Shape = RoundedCornerShape(Dimens.CoverCorner),
) {
    if (covers.size < 4) {
        Cover(covers.firstOrNull(), modifier, seed, shape)
        return
    }
    Column(modifier.clip(shape)) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Cover(covers[0], Modifier.weight(1f).aspectRatio(1f), shape = RoundedCornerShape(0.dp))
            Cover(covers[1], Modifier.weight(1f).aspectRatio(1f), shape = RoundedCornerShape(0.dp))
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Cover(covers[2], Modifier.weight(1f).aspectRatio(1f), shape = RoundedCornerShape(0.dp))
            Cover(covers[3], Modifier.weight(1f).aspectRatio(1f), shape = RoundedCornerShape(0.dp))
        }
    }
}

/** Stable pleasant hue for a string. */
fun seedColor(seed: String): Color {
    val palette = listOf(
        Color(0xFF2341C9), Color(0xFF5B3FD6), Color(0xFF0E8C8C), Color(0xFFB0397A),
        Color(0xFF2F7BE0), Color(0xFF7A3FC2), Color(0xFF1E9E6E), Color(0xFFC2563A),
    )
    return palette[abs(seed.hashCode()) % palette.size]
}

@Composable
private fun CoverPlaceholder(seed: String, modifier: Modifier) {
    // A colored tile with the Sound bars: every song without art still gets its own, stable look.
    val tint = seedColor(seed)
    Box(
        modifier.background(Brush.linearGradient(listOf(tint, lerp(tint, Color.Black, 0.45f)))),
        contentAlignment = Alignment.Center,
    ) {
        SoundBars(Modifier.fillMaxSize(0.34f), color = Color.White.copy(alpha = 0.85f))
    }
}

/** Concentric grooves (the record) drawn faintly over a flat color. */
@Composable
fun Grooves(color: Color, modifier: Modifier = Modifier, rings: Int = 7) {
    Canvas(modifier) {
        val c = Offset(size.width * 0.5f, size.height * 0.5f)
        val maxR = size.minDimension * 0.62f
        for (i in 1..rings) {
            drawCircle(color, radius = maxR * i / rings, center = c, style = Stroke(width = size.minDimension * 0.004f))
        }
    }
}

/** "Curtidas" everywhere (Home, Library, its page): a tangerine sleeve with grooves and a big ink heart. */
@Composable
fun LikedArt(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(Dimens.CoverCorner)) {
    Box(modifier.clip(shape).background(SoundColors.Accent), contentAlignment = Alignment.Center) {
        Grooves(SoundColors.OnAccent.copy(alpha = 0.10f), Modifier.fillMaxSize())
        Icon(Icons.Rounded.Favorite, null, tint = SoundColors.OnAccent, modifier = Modifier.fillMaxSize(0.42f))
    }
}

@Composable
fun CoverSized(model: Any?, size: Dp, seed: String = "", modifier: Modifier = Modifier) =
    Cover(model, modifier.size(size), seed)
