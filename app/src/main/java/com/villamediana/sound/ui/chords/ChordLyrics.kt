package com.villamediana.sound.ui.chords

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import com.villamediana.sound.ui.player.rememberTranslation
import com.villamediana.sound.ui.player.translationStyle
import kotlinx.coroutines.delay

/**
 * How chord names look over a lyric set in [lyric]: a little over half its size, bold, in the same
 * face — part of the lyrics' type, as the translation is, not a label laid on top.
 */
internal fun chordStyleFor(lyric: TextStyle): TextStyle = lyric.copy(
    fontSize = if (lyric.fontSize.isSpecified) lyric.fontSize * 0.56f else 15.sp,
    lineHeight = TextUnit.Unspecified,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.01.em,
)

/**
 * The lyrics with the chords written over the words — just written, nothing marks the one ringing:
 * whoever plays reads them. Chords are in their line's ink, as the translation is: they dim with
 * the lines already sung and light up with the line being sung (the line, never a chord on its
 * own); the lines to come keep them a little firmer than their words, since whoever plays reads
 * ahead. Synced lyrics follow the song as the plain lyrics do (the line being sung lights up, the
 * list keeps it [linesAbove] rows from the top, a touch pauses that for a few seconds, a tap on a
 * line seeks there); so do the rows of chords alone when there are no lyrics. Lyrics without times
 * stay still, as plain lyrics do: their chords sit at a guessed place, and nothing pretends to know
 * which line is being sung. A player's written sheet puts its chords over the same words it wrote
 * them over. A tap on a chord shows how to play it.
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
    /** Each lyric line's translation, by its text ("Tradução" on); the chords stay over the words. */
    translations: Map<String, String>? = lyrics?.let { rememberTranslationsByText(it) },
) {
    val view = remember(lyrics, chart, durationMs) {
        ChordLayout.view(lyrics ?: ParsedLyrics(false, emptyList()), chart, durationMs)
    }
    val rows = view.rows
    val listState = rememberSaveable(rows, saver = LazyListState.Saver) { LazyListState() }
    // Times known: synced lyrics, or no lyrics at all (the heard chords' own times). Not guessed
    // ones, nor a sheet shown as it is written.
    val timed = view.follows
    val current by remember(rows, clock, timed) { derivedStateOf { if (timed) ChordLayout.rowAt(rows, clock.positionMs + PlayerMath.LYRICS_LEAD_MS) else -1 } }
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
        if (!timed || !resting) return@LaunchedEffect
        if (current < 0) listState.animateScrollToItem(0) else listState.animateScrollToItem((current - linesAbove).coerceAtLeast(0))
    }
    val chordStyle = remember(textStyle) { chordStyleFor(textStyle) }
    val names = remember(chart) { chart.labels().map(ChordName::typeset) }
    val tap: (Int) -> Unit = { index -> chart.chordAt(index)?.let(onChord) }
    val ahead = colors.future.copy(alpha = (colors.future.alpha + AHEAD_LIFT).coerceAtMost(0.92f))

    LazyColumn(state = listState, modifier = modifier.testTag("chordLyrics"), contentPadding = contentPadding) {
        itemsIndexed(rows) { index, row ->
            val target = when {
                !timed -> colors.active.copy(alpha = 0.92f)
                index == current -> colors.active
                index < current -> colors.past
                else -> colors.future
            }
            val color by animateColorAsState(target, tween(LINE_FADE_MS), label = "chordLyric")
            val chordTarget = if (timed && index > current) ahead else target
            val chordColor by animateColorAsState(chordTarget, tween(LINE_FADE_MS), label = "chordInk")
            val rowModifier = Modifier
                .fillMaxWidth()
                .then(
                    if (timed) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSeek(row.startMs) }
                    else Modifier,
                )
                .padding(horizontal = horizontalPadding, vertical = lineGap / 2)
            when (row) {
                is ChordRow.Lyric -> {
                    val translation = translations?.get(row.text.trim())
                    if (translation == null) {
                        ChordedLine(row.text, row.chords, names, textStyle, chordStyle, { color }, { chordColor }, tap, rowModifier)
                    } else {
                        androidx.compose.foundation.layout.Column(rowModifier) {
                            ChordedLine(row.text, row.chords, names, textStyle, chordStyle, { color }, { chordColor }, tap, Modifier.fillMaxWidth())
                            Text(
                                text = translation,
                                style = textStyle.translationStyle(),
                                color = color.copy(alpha = color.alpha * 0.7f),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                is ChordRow.Instrumental -> InstrumentalRow(
                    chords = row.chords,
                    names = names,
                    chordStyle = chordStyle,
                    noteColor = { color },
                    chordColor = { chordColor },
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
    names: List<AnnotatedString>,
    textStyle: TextStyle,
    chordStyle: TextStyle,
    color: () -> Color,
    chordColor: () -> Color,
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
            for (c in chords) ChordChip(names[c.index], chordStyle, chordColor, onClick = { onChord(c.index) })
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
        // The chords sit close over their words (into the line's own leading), so a chord reads as
        // belonging to the line under it, not to the one above.
        val room = (placeables.maxOfOrNull { it.height } ?: 0) - CHORD_TUCK.roundToPx()
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

/**
 * Chords alone (intro, solo, the end): a soft note sign, then the chords at the same size as over
 * the words, spaced as bars would be.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstrumentalRow(
    chords: List<Int>,
    names: List<AnnotatedString>,
    chordStyle: TextStyle,
    noteColor: () -> Color,
    chordColor: () -> Color,
    onChord: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.text.BasicText(
            "♪",
            style = chordStyle.copy(fontWeight = FontWeight.Medium),
            color = { noteColor().let { it.copy(alpha = it.alpha * 0.55f) } },
            modifier = Modifier.padding(end = 2.dp),
        )
        for (k in chords) ChordChip(names[k], chordStyle, chordColor, onClick = { onChord(k) })
    }
}

/**
 * The lyrics' translation while "Tradução" is on, by line text: the chords view moves lines around
 * (rows of chords alone, a sheet's own lines), so each line finds its translation by its words.
 */
@Composable
internal fun rememberTranslationsByText(lyrics: ParsedLyrics): Map<String, String>? {
    val list = rememberTranslation(lyrics) ?: return null
    return remember(lyrics, list) {
        buildMap {
            lyrics.lines.forEachIndexed { i, l ->
                val t = list.getOrNull(i)
                if (t != null && l.text.isNotBlank()) putIfAbsent(l.text.trim(), t)
            }
        }
    }
}

/**
 * A chord name, typeset ([ChordName]), in its line's ink — read at draw time, so a line fading in
 * or out doesn't measure its chords again. Tapping it shows how to play it.
 */
@Composable
internal fun ChordChip(name: AnnotatedString, style: TextStyle, color: () -> Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicText(
        text = name,
        style = style,
        color = { color() },
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = CHIP_PAD, vertical = 1.dp),
    )
}

private val CHIP_PAD = 6.dp
private val CHORD_TUCK = 3.dp
/** How much firmer the chords of the lines to come are than their words. */
private const val AHEAD_LIFT = 0.2f
private const val FOLLOW_PAUSE_MS = 3_000L
private const val LINE_FADE_MS = 260
