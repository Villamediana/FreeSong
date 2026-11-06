package com.villamediana.surco.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.villamediana.surco.ui.theme.SurcoColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Surco mark: one groove spiralling out of the record's center and ending in the needle dot.
 * Same geometry as the launcher icon (res/drawable/ic_launcher_foreground.xml).
 */
@Composable
fun SurcoMark(modifier: Modifier = Modifier, size: Dp = 28.dp, color: Color = SurcoColors.Accent) {
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val maxR = this.size.minDimension * 0.44f
        val stroke = this.size.minDimension * 0.085f
        val path = Path()
        val turns = 2.25f
        val steps = 160
        val start = 0.16f
        var endPoint = c
        for (s in 0..steps) {
            val t = s / steps.toFloat()
            val a = (t * turns * 2 * Math.PI - Math.PI / 2).toFloat()
            val r = maxR * (start + (1 - start) * t)
            val p = Offset(c.x + r * cos(a), c.y + r * sin(a))
            if (s == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            endPoint = p
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, radius = stroke * 0.95f, center = endPoint)
        drawCircle(color, radius = stroke * 0.55f, center = c)
    }
}

/** Mark + "surco" wordmark. */
@Composable
fun SurcoWordmark(modifier: Modifier = Modifier, markSize: Dp = 26.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SurcoMark(size = markSize)
        Spacer(Modifier.width(9.dp))
        Text(
            "surco",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.04).em, fontSize = 25.sp),
            color = SurcoColors.Text,
        )
    }
}
