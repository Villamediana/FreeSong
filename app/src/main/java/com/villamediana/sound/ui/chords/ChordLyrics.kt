package com.villamediana.sound.ui.chords

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.villamediana.sound.chords.Chord
import com.villamediana.sound.chords.ChordChart
import com.villamediana.sound.chords.ChordLayout
import com.villamediana.sound.chords.ChordRow
import com.villamediana.sound.chords.PlacedChord
import com.villamediana.sound.data.ParsedLyrics
import com.villamediana.sound.ui.player.LyricColors
import com.villamediana.sound.ui.player.PlaybackClock
import com.villamediana.sound.ui.player.PlayerMath
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.delay

/** How chord names look over a lyric set in [lyric]: about half its size, bold, a little apart. */
internal fun chordStyleFor(lyric: TextStyle): TextStyle = lyric.copy(
    fontSize = if (lyric.fontSize.isSpecified) lyric.fontSize * 0.52f else 15.sp,
    lineHeight = TextUnit.Unspecified,
    fontWeight = FontWeight.ExtraBold,
    letterSpacing = 0.01.em,
)

/**
 * The lyrics with the chords over the words, following the song like the plain lyrics do: the
 * line being sung lights up, the chord ringing now is filled in, and the list keeps the current
 * line [linesAbove] rows from the top (a touch pauses that for a few seconds). Rows of chords alone
 * hold the intro, solos and the end. A tap on a line seeks there; a tap on a chord shows it.
 */
@Composable
internal fun ChordLyricsLines(
    lyrics: ParsedLyrics?,
    chart: ChordChart,
    durationMs: Long,
    clock: PlaybackClock,
    onSeek: (Long) -> Unit,
    onChord: (Chord) -> Unit,
    textStyle: TextStyle,
    colors: LyricColors,
    modifier: Modifier = Modifier,
    linesAbove: Int = 1,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalPadding: Dp = 20.dp,
    lineGap: Dp = 10.dp,
) {
    val rows = remember(lyrics, chart, durationMs) {
        ChordLayout.rows(lyrics ?: ParsedLyrics(false, emptyList()), chart, durationMs)
    }
    val listState = rememberSaveable(rows, saver = LazyListState.Saver) { LazyListState() }
    val current by remember(rows, clock) { derivedStateOf { ChordLayout.rowAt(rows, clock.positionMs + PlayerMath.LYRICS_LEAD_MS) } }
    val ringing by remember(chart, clock) { derivedStateOf { chart.indexAt(clock.positionMs) } }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var resting by remember { mutableStateOf(true) }
    LaunchedEffect(dragged) {
        if (dragged) {
            resting = false
        } else if (!resting) {
            delay(FOLLOW_PAUSE_MS)
            resting = true
        }
    }
    LaunchedEffect(rows, current, resting) {
        if (!resting) return@LaunchedEffect
        if (current < 0) listState.animateScrollToItem(0) else listState.animateScrollToItem((current - linesAbove).coerceAtLeast(0))
    }
    val chordStyle = remember(textStyle) { chordStyleFor(textStyle) }
    val names = remember(chart) { chart.chords.indices.map { chart.name(it) } }
    val tap: (Int) -> Unit = { index -> onChord(chart.chords[index].chord) }

    LazyColumn(state = listState, modifier = modifier.testTag("chordLyrics"), contentPadding = contentPadding) {
        itemsIndexed(rows) { index, row ->
            val target = when {
                index == current -> colors.active
                index < current -> colors.past
                else -> colors.future
            }
            val color by animateColorAsState(target, tween(LINE_FADE_MS), label = "chordLyric")
            val rowModifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSeek(row.startMs) }
                .padding(horizontal = horizontalPadding, vertical = lineGap / 2)
            when (row) {
                is ChordRow.Lyric -> ChordedLine(
                    text = row.text,
                    chords = row.chords,
                    names = names,
                    textStyle = textStyle,
                    chordStyle = chordStyle,
                    color = { color },
                    ringing = { ringing },
                    onChord = tap,
                    modifier = rowModifier,
                )
                is ChordRow.Instrumental -> InstrumentalRow(
                    chords = row.chords,
                    names = names,
                    chordStyle = chordStyle,
                    noteColor = color,
                    ringing = { ringing },
                    onChord = tap,
                    modifier = rowModifier,
                )
            }
        }
    }
}

/**
 * A lyric line with its chords over the words. The line may wrap: room for chords is made only
 * above the lines that have some, each chord sits over the character it belongs to, and chords that
 * would touch are pushed apart.
 */
@Composable
private fun ChordedLine(
    text: String,
    chords: List<PlacedChord>,
    names: List<String>,
    textStyle: TextStyle,
    chordStyle: TextStyle,
    color: () -> Color,
    ringing: () -> Int,
    onChord: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    // Untrimmed line boxes: a line measured alone sits exactly as it does in the paragraph.
    val style = remember(textStyle) { textStyle.copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)) }
    // What is drawn: each line of the text where it ended up.
    val drawn = remember(text, style) { mutableListOf<Pair<TextLayoutResult, Float>>() }
    Layout(
        content = {
            for (c in chords) ChordChip(names[c.index], chordStyle, active = { ringing() == c.index }, onClick = { onChord(c.index) })
        },
        modifier = modifier
            .semantics { contentDescription = text }
            .drawBehind {
                val ink = color()
                for ((line, y) in drawn) drawText(line, color = ink, topLeft = androidx.compose.ui.geometry.Offset(0f, y))
            },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val layout = measurer.measure(AnnotatedString(text), style, constraints = Constraints(maxWidth = width))
        val placeables = measurables.map { it.measure(Constraints()) }
        val pad = CHIP_PAD.roundToPx()
        val gap = 4.dp.roundToPx()
        val lastLine = layout.lineCount - 1
        val lineOf = chords.map { c -> if (c.at >= text.length) lastLine else layout.getLineForOffset(c.at) }
        val room = (placeables.maxOfOrNull { it.height } ?: 0) + 2.dp.roundToPx()
        // Each line goes down by the room made above it and above every line before it.
        val tops = IntArray(layout.lineCount)
        var shift = 0
        for (l in 0 until layout.lineCount) {
            if (l in lineOf) shift += room
            tops[l] = layout.getLineTop(l).toInt() + shift
        }
        drawn.clear()
        for (l in 0 until layout.lineCount) {
            val part = text.substring(layout.getLineStart(l), layout.getLineEnd(l, visibleEnd = true))
            drawn += measurer.measure(AnnotatedString(part), style, softWrap = false, maxLines = 1) to tops[l].toFloat()
        }
        val xs = IntArray(chords.size)
        for (i in chords.indices) {
            val c = chords[i]
            val x = if (c.at >= text.length) layout.getLineRight(lastLine) + gap * 2 else layout.getHorizontalPosition(c.at, true)
            // The name's first letter lines up with the word's (the chip's padding hangs out to the left).
            var at = x.toInt() - pad
            val prev = (i - 1 downTo 0).firstOrNull { lineOf[it] == lineOf[i] }
            if (prev != null) at = maxOf(at, xs[prev] + placeables[prev].width + gap)
            xs[i] = at.coerceAtLeast(-pad)
        }
        // A crowded line's last chords that ran past the edge come back in, pushing the ones before
        // them left instead of landing on top of each other.
        for (l in lineOf.distinct()) {
            var limit = width
            for (i in chords.indices.reversed()) {
                if (lineOf[i] != l) continue
                if (xs[i] + placeables[i].width > limit) xs[i] = maxOf(-pad, limit - placeables[i].width)
                limit = xs[i] - gap
            }
        }
        val height = layout.size.height + shift
        layout(width, height) {
            placeables.forEachIndexed { i, p -> p.place(xs[i], tops[lineOf[i]] - room) }
        }
    }
}

/** Chords alone (intro, solo, the end), after a note sign. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstrumentalRow(
    chords: List<Int>,
    names: List<String>,
    chordStyle: TextStyle,
    noteColor: Color,
    ringing: () -> Int,
    onChord: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val big = chordStyle.copy(fontSize = chordStyle.fontSize * 1.18f)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("♪", style = big, color = noteColor, modifier = Modifier.padding(end = 4.dp))
        for (k in chords) ChordChip(names[k], big, active = { ringing() == k }, onClick = { onChord(k) })
    }
}

/** A chord name: in the "playing" color, filled in while it rings. Tapping it shows how to play it. */
@Composable
internal fun ChordChip(name: String, style: TextStyle, active: () -> Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val on = active()
    val fill by animateColorAsState(if (on) SoundColors.Mint else SoundColors.Mint.copy(alpha = 0f), tween(180), label = "chordFill")
    val ink by animateColorAsState(if (on) SoundColors.OnMint else SoundColors.Mint, tween(180), label = "chordInk")
    Text(
        text = name,
        style = style,
        color = ink,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = CHIP_PAD, vertical = 1.dp),
    )
}

private val CHIP_PAD = 6.dp
private const val FOLLOW_PAUSE_MS = 3_000L
private const val LINE_FADE_MS = 260
