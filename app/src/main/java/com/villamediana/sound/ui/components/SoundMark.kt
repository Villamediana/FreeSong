package com.villamediana.sound.ui.components

import com.villamediana.sound.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.villamediana.sound.ui.theme.SoundColors

/** Bar heights of the sound-bars glyph (placeholders, hero card). */
private val MarkBars = floatArrayOf(0.40f, 0.74f, 1.0f, 0.62f, 0.30f)

/** Just the sound-wave bars, [color] on transparent. */
@Composable
fun SoundBars(modifier: Modifier = Modifier, color: Color = SoundColors.Mint) {
    Canvas(modifier) {
        val n = MarkBars.size
        val w = size.width / (n + (n - 1) * 0.6875f)
        val gap = w * 0.6875f
        var x = 0f
        MarkBars.forEach { h ->
            val bh = (size.height * h).coerceAtLeast(w)
            drawRoundRect(color, Offset(x, (size.height - bh) / 2), Size(w, bh), CornerRadius(w / 2, w / 2))
            x += w + gap
        }
    }
}

/** The app mark: the FreeSong icon (headphones with wings on graphite). */
@Composable
fun SoundMark(modifier: Modifier = Modifier, size: Dp = 30.dp) {
    Image(
        painterResource(R.drawable.freesong_mark),
        contentDescription = null,
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.23f)),
    )
}

/** Mark + "FreeSong" wordmark (the main header). */
@Composable
fun SoundWordmark(modifier: Modifier = Modifier, markSize: Dp = 34.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SoundMark(size = markSize)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em, fontSize = 28.sp),
            color = SoundColors.Text,
        )
    }
}
