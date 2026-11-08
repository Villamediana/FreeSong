package com.villamediana.sound.ui.chords

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.villamediana.sound.chords.Chord
import com.villamediana.sound.chords.Fingering
import com.villamediana.sound.chords.Instrument
import com.villamediana.sound.chords.Spelling
import com.villamediana.sound.ui.theme.Outfit
import com.villamediana.sound.ui.theme.SoundColors

/**
 * A chord box: the strings standing up, the nut (or the fret it starts at) on top, a dot where
 * each finger goes with its number, the barre across, ○ and × over open and silent strings, and
 * under each string the note it sounds. Drawn in ink, as the lyrics are; the root in the lyrics'
 * accent (the sung line's color).
 */
@Composable
internal fun FretDiagram(chord: Chord, fingering: Fingering, instrument: Instrument, spelling: Spelling, description: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val ink = SoundColors.Text
    val faint = SoundColors.TextFaint
    val line = SoundColors.TextMuted.copy(alpha = 0.55f)
    val fretLine = SoundColors.TextMuted.copy(alpha = 0.3f)
    val root = SoundColors.Lyric
    val onRoot = onColor(root)
    val other = SoundColors.Text
    val onOther = SoundColors.Surface2
    val strings = instrument.strings.size
    Canvas(
        modifier
            .widthIn(max = if (strings >= 6) 272.dp else 220.dp)
            .fillMaxWidth()
            .aspectRatio(if (strings >= 6) 1.08f else 0.95f)
            .semantics { contentDescription = description },
    ) {
        val top = 34.dp.toPx()
        val bottom = 30.dp.toPx()
        val side = 34.dp.toPx()
        val gridW = size.width - side * 2
        val gridH = size.height - top - bottom
        val dx = gridW / (strings - 1)
        val rows = fingering.rows
        val dy = gridH / rows
        val first = fingering.firstFret
        fun x(s: Int) = side + s * dx
        fun yMid(fret: Int) = top + (fret - first + 0.5f) * dy
        val dot = minOf(dx, dy) * 0.36f

        // Frets, then strings (the low ones a little thicker on a guitar).
        for (r in 0..rows) {
            val y = top + r * dy
            drawLine(fretLine, Offset(side, y), Offset(side + gridW, y), strokeWidth = 1.4.dp.toPx())
        }
        if (first == 1) {
            drawLine(ink, Offset(side - 1.dp.toPx(), top), Offset(side + gridW + 1.dp.toPx(), top), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        } else {
            label(measurer, "${first}ª", Offset(side - dot - 6.dp.toPx(), top + dy / 2), faint, 13f, alignRight = true)
        }
        for (s in 0 until strings) {
            val w = if (strings >= 6) (2.2f - s * 0.25f).coerceAtLeast(1.1f) else 1.6f
            drawLine(line, Offset(x(s), top), Offset(x(s), top + gridH), strokeWidth = w.dp.toPx())
        }

        // Barres under the dots, their finger in the middle.
        for (b in fingering.barres) {
            val y = yMid(b.fret)
            drawRoundRect(
                color = other,
                topLeft = Offset(x(b.from) - dot, y - dot * 0.82f),
                size = Size(x(b.to) - x(b.from) + dot * 2, dot * 1.64f),
                cornerRadius = CornerRadius(dot, dot),
            )
            val finger = fingering.fingers.getOrNull(b.from) ?: 0
            if (finger > 0) label(measurer, "$finger", Offset((x(b.from) + x(b.to)) / 2, y), onOther, 13f, center = true, bold = true)
        }

        for (s in 0 until strings) {
            val fret = fingering.frets[s]
            val pc = if (fret >= 0) (instrument.strings[s] + fret) % 12 else -1
            val isRoot = pc == chord.root
            when {
                fret < 0 -> cross(Offset(x(s), top - 16.dp.toPx()), 5.dp.toPx(), faint)
                fret == 0 -> drawCircle(if (isRoot) root else ink, radius = 5.5.dp.toPx(), center = Offset(x(s), top - 16.dp.toPx()), style = Stroke(1.8.dp.toPx()))
                else -> {
                    val c = Offset(x(s), yMid(fret))
                    val inBarre = fingering.barres.any { it.fret == fret && s in it.from..it.to }
                    if (!inBarre || isRoot) drawCircle(if (isRoot) root else other, radius = dot, center = c)
                    val finger = fingering.fingers.getOrNull(s) ?: 0
                    if (finger > 0 && !inBarre) label(measurer, "$finger", c, if (isRoot) onRoot else onOther, 13f, center = true, bold = true)
                }
            }
            // The note each string sounds.
            if (pc >= 0) label(measurer, spelling.name(pc), Offset(x(s), top + gridH + 16.dp.toPx()), if (isRoot) root else faint, 12f, center = true, bold = isRoot)
        }
    }
}

/**
 * Two octaves of keys (more for a wide chord), the chord's keys in the lyrics' accent with their
 * names; a slash chord's bass in a deeper shade of it.
 */
@Composable
internal fun PianoDiagram(notes: List<Int>, bass: Int?, spelling: Spelling, description: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val keyLine = SoundColors.TextMuted.copy(alpha = 0.5f)
    val white = Color(0xFFF7F7F4)
    val black = Color(0xFF1B1C22)
    val hit = SoundColors.Lyric
    val onHit = onColor(hit)
    val bassColor = androidx.compose.ui.graphics.lerp(hit, Color.Black, 0.32f)
    val onBass = onColor(bassColor)
    val low = notes.minOrNull() ?: 60
    val high = notes.maxOrNull() ?: 72
    val startC = low - Math.floorMod(low, 12)
    var endB = high + (11 - Math.floorMod(high, 12))
    while (whiteCount(startC, endB) < 14) endB += 12
    val whites = (startC..endB).filter { !isBlack(it) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .semantics { contentDescription = description },
    ) {
        val w = size.width / whites.size
        val h = size.height - 4.dp.toPx()
        val radius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
        whites.forEachIndexed { i, n ->
            val on = n in notes
            val color = when {
                !on -> white
                n == bass -> bassColor
                else -> hit
            }
            drawRoundRect(color, Offset(i * w + 1.dp.toPx(), 0f), Size(w - 2.dp.toPx(), h), radius)
            drawRoundRect(keyLine, Offset(i * w + 1.dp.toPx(), 0f), Size(w - 2.dp.toPx(), h), radius, style = Stroke(1.dp.toPx()))
            if (on) label(measurer, spelling.name(n % 12), Offset(i * w + w / 2, h - 16.dp.toPx()), if (n == bass) onBass else onHit, 12f, center = true, bold = true)
        }
        val bw = w * 0.62f
        val bh = h * 0.6f
        for (n in startC..endB) {
            if (!isBlack(n)) continue
            val leftWhite = whites.indexOf(n - 1)
            if (leftWhite < 0) continue
            val cx = (leftWhite + 1) * w
            val on = n in notes
            val color = when {
                !on -> black
                n == bass -> bassColor
                else -> hit
            }
            drawRoundRect(color, Offset(cx - bw / 2, 0f), Size(bw, bh), CornerRadius(4.dp.toPx(), 4.dp.toPx()))
            if (on) label(measurer, spelling.name(n % 12), Offset(cx, bh - 13.dp.toPx()), if (n == bass) onBass else onHit, 10f, center = true, bold = true)
        }
    }
}

/** Ink that reads on [c]: near-black on a light color, white on a deep one. */
private fun onColor(c: Color): Color = if (c.luminance() > 0.45f) Color(0xFF1B1C22) else Color.White

private fun isBlack(n: Int) = Math.floorMod(n, 12) in setOf(1, 3, 6, 8, 10)

private fun whiteCount(from: Int, to: Int) = (from..to).count { !isBlack(it) }

private fun DrawScope.cross(c: Offset, r: Float, color: Color) {
    val w = 1.8.dp.toPx()
    drawLine(color, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), strokeWidth = w, cap = StrokeCap.Round)
    drawLine(color, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y - r), strokeWidth = w, cap = StrokeCap.Round)
}

private fun DrawScope.label(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    color: Color,
    sizeSp: Float,
    center: Boolean = false,
    alignRight: Boolean = false,
    bold: Boolean = false,
) {
    val layout = measurer.measure(text, TextStyle(fontFamily = Outfit, fontSize = sizeSp.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, color = color))
    val x = when {
        center -> at.x - layout.size.width / 2f
        alignRight -> at.x - layout.size.width
        else -> at.x
    }
    drawText(layout, topLeft = Offset(x, at.y - layout.size.height / 2f))
}
