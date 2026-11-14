package com.villamediana.sound.ui.chords

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.villamediana.sound.R
import com.villamediana.sound.chords.Chord
import com.villamediana.sound.chords.ChordChart
import com.villamediana.sound.chords.ChordLayout
import com.villamediana.sound.chords.ChordSpeech
import com.villamediana.sound.chords.Fingering
import com.villamediana.sound.chords.Fingerings
import com.villamediana.sound.chords.Instrument
import com.villamediana.sound.chords.PianoVoicing
import com.villamediana.sound.chords.PlayAlong
import com.villamediana.sound.chords.PlayAlongLane
import com.villamediana.sound.chords.Spelling
import com.villamediana.sound.data.ParsedLyrics
import com.villamediana.sound.ui.player.LyricColors
import com.villamediana.sound.ui.player.PlaybackClock
import com.villamediana.sound.ui.theme.Outfit
import com.villamediana.sound.ui.theme.SoundColors
import kotlin.math.max

/*
 * "Tocar junto": the lyrics screen as a stage for whoever plays while singing. Everything here is
 * in the chords' own language (the type of the lyrics, ink and the sung line's color, small spaced
 * capitals for labels, no boxes) and follows the song only while it plays: the chord is read from
 * the clock behind derivedStateOf (a change recomposes, a frame doesn't) and the lane is drawn
 * straight from the clock. To take it out: this file, chords/PlayAlong.kt, the "ringing" parameter
 * of ChordLyricsLines and the button and branch in LyricsScreen.
 */

/**
 * On top, the chord ringing now — big, in the sung line's color, with where the hands go on the
 * chosen instrument — and the one coming next beside it, smaller. Under them a lane where the
 * chords come in from the right and cross a still line as they ring, so the change is seen coming.
 * Under that the line being sung with its chords, the one ringing lit, and the lines to come.
 * A tap on a chord shows how to play it (the chord sheet).
 */
@Composable
internal fun PlayAlongStage(
    lyrics: ParsedLyrics?,
    chart: ChordChart,
    view: ChordLayout.View,
    lane: PlayAlongLane,
    durationMs: Long,
    clock: PlaybackClock,
    instrument: Instrument,
    colors: LyricColors,
    onSeek: (Long) -> Unit,
    onChord: (Chord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fingerings = LocalChords.current?.fingerings
    val labels = remember(chart) { chart.labels() }
    val now = remember(lane, clock) { derivedStateOf { lane.at(clock.positionMs + PlayAlong.LEAD_MS) } }
    val ringing: () -> Int = remember(lane, now) { { lane.chords.getOrNull(now.value)?.index ?: -1 } }
    BoxWithConstraints(modifier.testTag("playAlong")) {
        if (maxWidth <= maxHeight) {
            val diagram = (maxHeight * 0.3f).coerceIn(104.dp, 196.dp)
            Column(Modifier.fillMaxSize()) {
                NowAndNext(
                    lane, labels, chart, now, instrument, fingerings, diagram, nameSize = 60.sp, onChord,
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(top = 2.dp),
                )
                ChordLane(lane, labels, clock, Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 2.dp).height(LANE_HEIGHT))
                StageLines(lyrics, chart, view, durationMs, clock, colors, ringing, onSeek, onChord, Modifier.weight(1f).fillMaxWidth())
            }
        } else {
            // Lying down: the chords on the left (each name beside its shape), the words on the right.
            val diagram = (maxHeight - LANE_HEIGHT - 40.dp).coerceIn(64.dp, 200.dp)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    NowAndNext(
                        lane, labels, chart, now, instrument, fingerings, diagram, nameSize = 46.sp, onChord,
                        Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                        beside = true,
                    )
                    ChordLane(lane, labels, clock, Modifier.fillMaxWidth().padding(top = 10.dp).height(LANE_HEIGHT))
                }
                StageLines(lyrics, chart, view, durationMs, clock, colors, ringing, onSeek, onChord, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** A guitar pick: the "Tocar junto" button. */
internal val PlayAlongIcon: ImageVector by lazy {
    ImageVector.Builder("PlayAlong", 24.dp, 24.dp, 24f, 24f).apply {
        val ink = SolidColor(Color.White)
        path(stroke = ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round) {
            moveTo(12f, 21.2f)
            curveTo(8.4f, 18.4f, 4.2f, 13.2f, 4.2f, 8.6f)
            curveTo(4.2f, 5.1f, 7.6f, 3.2f, 12f, 3.2f)
            curveTo(16.4f, 3.2f, 19.8f, 5.1f, 19.8f, 8.6f)
            curveTo(19.8f, 13.2f, 15.6f, 18.4f, 12f, 21.2f)
            close()
        }
        // Three short strings across it: what the pick is for.
        path(stroke = ink, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round) {
            moveTo(9f, 8.2f); lineTo(15f, 8.2f)
            moveTo(9.6f, 11.4f); lineTo(14.4f, 11.4f)
            moveTo(10.6f, 14.6f); lineTo(13.4f, 14.6f)
        }
    }.build()
}

// ---- Now and next ----

@Composable
private fun NowAndNext(
    lane: PlayAlongLane,
    labels: List<String>,
    chart: ChordChart,
    now: State<Int>,
    instrument: Instrument,
    fingerings: Fingerings?,
    diagram: Dp,
    nameSize: TextUnit,
    onChord: (Chord) -> Unit,
    modifier: Modifier = Modifier,
    beside: Boolean = false,
) {
    val at = now.value
    // Before the song's first chord, that one shows, faint: the one to get ready for.
    val shown = at.coerceAtLeast(0)
    val run = lane.runStart(shown)
    val next = lane.next(shown)
    val nowInk by animateColorAsState(
        if (at >= 0) SoundColors.Lyric else SoundColors.Text.copy(alpha = 0.42f),
        tween(240),
        label = "nowInk",
    )
    val nextInk = SoundColors.Text.copy(alpha = 0.78f)
    Row(modifier, verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1.4f)) {
            StageLabel(stringResource(R.string.chords_now))
            AnimatedContent(targetState = run, transitionSpec = { flow() }, contentAlignment = Alignment.TopStart, label = "nowChord") { i ->
                val index = lane.chords[i].index
                ChordFace(labels[index], chart.chordAt(index), chart.spelling, instrument, fingerings, diagram, nameSize, big = true, beside = beside, ink = { nowInk }, onChord = onChord)
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            StageLabel(stringResource(R.string.chords_up_next))
            AnimatedContent(targetState = next, transitionSpec = { flow() }, contentAlignment = Alignment.TopStart, label = "nextChord") { j ->
                if (j < 0) {
                    Spacer(Modifier.fillMaxWidth())
                } else {
                    val index = lane.chords[j].index
                    ChordFace(labels[index], chart.chordAt(index), chart.spelling, instrument, fingerings, diagram, nameSize * 0.56f, big = false, beside = beside, ink = { nextInk }, onChord = onChord)
                }
            }
        }
    }
}

/** The chords go by the way the lane runs: the new one in from the right, the old one out to the left (back when seeking back). */
private fun AnimatedContentTransitionScope<Int>.flow(): ContentTransform {
    val forward = targetState > initialState
    return (
        slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { if (forward) it / 3 else -it / 3 } +
            fadeIn(tween(220, delayMillis = 50))
        ) togetherWith (
        slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { if (forward) -it / 3 else it / 3 } +
            fadeOut(tween(150))
        ) using SizeTransform(clip = false)
}

/** A small spaced capital label, as "TOM" by the play button. */
@Composable
private fun StageLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = SoundColors.Text.copy(alpha = 0.6f),
        maxLines = 1,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

/**
 * A chord's name set as over the words, and under it (beside it, lying down) where the hands go:
 * the chord sheet's own drawing for the one ringing ([big]) when there's room for it, a small one
 * for the next. A name that isn't a chord ("N.C.") keeps the room of its drawing, so nothing jumps.
 */
@Composable
private fun ChordFace(
    name: String,
    chord: Chord?,
    spelling: Spelling,
    instrument: Instrument,
    fingerings: Fingerings?,
    diagram: Dp,
    nameSize: TextUnit,
    big: Boolean,
    beside: Boolean,
    ink: () -> Color,
    onChord: (Chord) -> Unit,
) {
    val language = LocalConfiguration.current.locales[0]?.language ?: "pt"
    val said = remember(chord, spelling, language) { chord?.let { ChordSpeech.say(it, spelling, language) } ?: name }
    val style = MaterialTheme.typography.displayMedium.copy(
        fontSize = nameSize,
        lineHeight = nameSize * 1.12f,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.02).em,
    )
    // The keys need less height than a neck.
    val room = when {
        !instrument.fretted -> if (big) min(diagram, 118.dp) else min(diagram * 0.6f, 66.dp)
        big -> diagram
        else -> diagram * (if (beside) 0.8f else 0.6f)
    }
    val face = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .clickable(enabled = chord != null, role = Role.Button, onClickLabel = said) { chord?.let(onChord) }
        .semantics(mergeDescendants = true) { contentDescription = said }
    val title: @Composable (Modifier) -> Unit = { m ->
        BasicText(
            ChordName.typeset(name, display = true),
            style = style,
            color = ink,
            maxLines = 1,
            softWrap = false,
            // A long name ("F#m7(b5)/C") gets smaller rather than cut.
            autoSize = TextAutoSize.StepBased(minFontSize = nameSize * 0.5f, maxFontSize = nameSize),
            modifier = m,
        )
    }
    if (beside) {
        Row(face.height(room)) {
            title(Modifier.weight(0.85f))
            Spacer(Modifier.width(8.dp))
            ChordShape(chord, spelling, instrument, fingerings, big, said, Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(face) {
            title(Modifier.fillMaxWidth())
            ChordShape(chord, spelling, instrument, fingerings, big, said, Modifier.fillMaxWidth().height(room).padding(top = 6.dp))
        }
    }
}

/** Where the hands go, as big as [modifier]'s room lets it be. */
@Composable
private fun ChordShape(chord: Chord?, spelling: Spelling, instrument: Instrument, fingerings: Fingerings?, big: Boolean, said: String, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        if (chord == null || fingerings == null) return@BoxWithConstraints
        if (instrument.fretted) {
            val shape = remember(chord, instrument) { fingerings.of(chord, instrument).firstOrNull() } ?: return@BoxWithConstraints
            val wide = instrument.strings.size >= 6
            if (big && maxHeight >= FULL_DIAGRAM) {
                // The sheet's drawing; its margins (the first fret's number, ○ and × on top) hang
                // out, so the strings start under the name and close to it.
                val aspect = if (wide) 1.08f else 0.95f
                val w = min(maxWidth + BIG_MARGIN, (maxHeight + BIG_LIFT) * aspect)
                FretDiagram(chord, shape, instrument, spelling, said, Modifier.offset(x = -BIG_MARGIN, y = -BIG_LIFT).width(w))
            } else {
                val w = min(maxWidth, maxHeight * (if (wide) 0.92f else 0.74f))
                MiniFret(chord, shape, instrument, Modifier.width(w).fillMaxHeight())
            }
        } else {
            val notes = remember(chord) { PianoVoicing.voicings(chord).firstOrNull() } ?: return@BoxWithConstraints
            val bass = chord.bass?.takeIf { it != chord.root }?.let { notes.first() }
            StageKeys(notes, bass, spelling, labels = big && maxHeight >= 72.dp, Modifier.fillMaxWidth().fillMaxHeight())
        }
    }
}

/**
 * The next chord's shape, small: the strings and frets, the fingers as dots (the root in the sung
 * line's color, as in the sheet), the barre, ○ and × over open and silent strings, the first fret's
 * number when it isn't the nut. No notes or finger numbers: that's for the big one.
 */
@Composable
private fun MiniFret(chord: Chord, f: Fingering, instrument: Instrument, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val ink = SoundColors.Text
    val root = SoundColors.Lyric
    Canvas(modifier) {
        val strings = instrument.strings.size
        val marks = 12.dp.toPx()
        val side = 16.dp.toPx()
        val gridW = size.width - side - 4.dp.toPx()
        val gridH = size.height - marks - 3.dp.toPx()
        if (gridW <= 0f || gridH <= 0f) return@Canvas
        val dx = gridW / (strings - 1)
        val rows = f.rows
        val dy = gridH / rows
        fun x(s: Int) = side + s * dx
        fun yMid(fret: Int) = marks + (fret - f.firstFret + 0.5f) * dy
        val dot = minOf(dx, dy) * 0.36f
        for (r in 0..rows) drawLine(ink.copy(alpha = 0.22f), Offset(side, marks + r * dy), Offset(side + gridW, marks + r * dy), 1.dp.toPx())
        for (s in 0 until strings) drawLine(ink.copy(alpha = 0.42f), Offset(x(s), marks), Offset(x(s), marks + gridH), 1.2.dp.toPx())
        if (f.firstFret == 1) {
            drawLine(ink.copy(alpha = 0.9f), Offset(side - 0.6.dp.toPx(), marks), Offset(side + gridW + 0.6.dp.toPx(), marks), 3.dp.toPx(), cap = StrokeCap.Round)
        } else {
            val number = measurer.measure("${f.firstFret}", TextStyle(fontFamily = Outfit, fontSize = 10.sp, fontWeight = FontWeight.Medium))
            drawText(number, color = ink.copy(alpha = 0.6f), topLeft = Offset(side - dot - 3.dp.toPx() - number.size.width, yMid(f.firstFret) - number.size.height / 2f))
        }
        for (b in f.barres) {
            val y = yMid(b.fret)
            drawRoundRect(ink, Offset(x(b.from) - dot, y - dot * 0.8f), Size(x(b.to) - x(b.from) + dot * 2, dot * 1.6f), CornerRadius(dot, dot))
        }
        for (s in 0 until strings) {
            val fret = f.frets[s]
            val isRoot = fret >= 0 && (instrument.strings[s] + fret) % 12 == chord.root
            val mark = Offset(x(s), marks - 6.dp.toPx())
            when {
                fret < 0 -> {
                    val r = 3.dp.toPx()
                    drawLine(ink.copy(alpha = 0.5f), Offset(mark.x - r, mark.y - r), Offset(mark.x + r, mark.y + r), 1.4.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(ink.copy(alpha = 0.5f), Offset(mark.x - r, mark.y + r), Offset(mark.x + r, mark.y - r), 1.4.dp.toPx(), cap = StrokeCap.Round)
                }
                fret == 0 -> drawCircle(if (isRoot) root else ink.copy(alpha = 0.8f), radius = 3.4.dp.toPx(), center = mark, style = Stroke(1.4.dp.toPx()))
                else -> {
                    val inBarre = f.barres.any { it.fret == fret && s in it.from..it.to }
                    if (!inBarre || isRoot) drawCircle(if (isRoot) root else ink, radius = dot, center = Offset(x(s), yMid(fret)))
                }
            }
        }
    }
}

/**
 * The piano for the stage: just the octave or so the chord needs (not the sheet's two octaves), its keys in the sung line's color, a slash chord's bass deeper; with their
 * names on the big one.
 */
@Composable
private fun StageKeys(notes: List<Int>, bass: Int?, spelling: Spelling, labels: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val keyLine = SoundColors.TextMuted.copy(alpha = 0.5f)
    val white = Color(0xFFF7F7F4)
    val black = Color(0xFF1B1C22)
    val hit = SoundColors.Lyric
    val deep = androidx.compose.ui.graphics.lerp(hit, Color.Black, 0.32f)
    // The chord in the middle, a key or two of air on each side, nine white keys at least.
    val low = notes.minOrNull() ?: 60
    val high = notes.maxOrNull() ?: 64
    var start = low - 2
    while (blackKey(start)) start--
    var end = high + 2
    while (blackKey(end)) end++
    var right = true
    while ((start..end).count { !blackKey(it) } < 9) {
        if (right) { end++; while (blackKey(end)) end++ } else { start--; while (blackKey(start)) start-- }
        right = !right
    }
    val whites = (start..end).filter { !blackKey(it) }
    Canvas(modifier) {
        val w = size.width / whites.size
        val h = size.height
        val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        val inset = 0.8.dp.toPx()
        whites.forEachIndexed { i, n ->
            val on = n in notes
            val color = if (!on) white else if (n == bass) deep else hit
            drawRoundRect(color, Offset(i * w + inset, 0f), Size(w - inset * 2, h), radius)
            drawRoundRect(keyLine, Offset(i * w + inset, 0f), Size(w - inset * 2, h), radius, style = Stroke(1.dp.toPx()))
            if (on && labels) keyName(measurer, spelling.name(n % 12), Offset(i * w + w / 2, h - 14.dp.toPx()), inkOn(color), 11f)
        }
        val bw = w * 0.62f
        val bh = h * 0.6f
        for (n in start..end) {
            if (!blackKey(n)) continue
            val left = whites.indexOf(n - 1)
            if (left < 0) continue
            val cx = (left + 1) * w
            val on = n in notes
            val color = if (!on) black else if (n == bass) deep else hit
            drawRoundRect(color, Offset(cx - bw / 2, 0f), Size(bw, bh), CornerRadius(3.dp.toPx(), 3.dp.toPx()))
            if (on && labels) keyName(measurer, spelling.name(n % 12), Offset(cx, bh - 11.dp.toPx()), inkOn(color), 9f)
        }
    }
}

private fun blackKey(n: Int) = Math.floorMod(n, 12) in intArrayOf(1, 3, 6, 8, 10)

private fun inkOn(c: Color): Color = if (c.luminance() > 0.45f) Color(0xFF1B1C22) else Color.White

private fun DrawScope.keyName(measurer: androidx.compose.ui.text.TextMeasurer, text: String, center: Offset, color: Color, sizeSp: Float) {
    val layout = measurer.measure(text, TextStyle(fontFamily = Outfit, fontSize = sizeSp.sp, fontWeight = FontWeight.Bold))
    drawText(layout, color = color, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

// ---- The lane ----

/** A stretch of the lane where the hand holds one chord (the same chord written again is one). */
private class LaneRun(val startMs: Long, val endMs: Long, val label: Int)

/**
 * The chords on a lane, coming in from the right and crossing a still line (now) as they ring:
 * each one a bar as long as it lasts, its name over its start. The one ringing fills in the sung
 * line's color up to the line — what's left of it is how long until the change — and its name
 * stays by the line until the next one pushes it out. Past ones fade to the left. Drawn straight
 * from the clock: while the song plays it moves every frame without recomposing, paused it's still.
 */
@Composable
private fun ChordLane(lane: PlayAlongLane, labels: List<String>, clock: PlaybackClock, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
    val names = remember(labels, style) {
        labels.distinct().associateWith { measurer.measure(ChordName.typeset(it), style, softWrap = false, maxLines = 1) }
    }
    val runs = remember(lane) {
        buildList {
            var i = 0
            while (i < lane.chords.size) {
                val first = i
                while (i + 1 < lane.chords.size && lane.run[i + 1] == lane.run[first]) i++
                add(LaneRun(lane.chords[first].startMs, lane.chords[i].endMs, lane.chords[first].index))
                i++
            }
        }
    }
    val ink = SoundColors.Text
    val accent = SoundColors.Lyric
    val description = stringResource(R.string.chords_lane)
    Canvas(
        modifier
            .semantics { contentDescription = description }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // Both ends fade out: chords come in from nowhere and leave the same way.
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, 0.1f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        val t = clock.positionMs + PlayAlong.LEAD_MS
        val head = size.width * HEAD
        val perMs = (size.width - head) / AHEAD_MS
        val barH = 6.dp.toPx()
        val barY = size.height - barH - 6.dp.toPx()
        val nameBottom = barY - 7.dp.toPx()
        val gap = 3.dp.toPx()
        val radius = CornerRadius(barH / 2, barH / 2)
        val firstMs = t - (head / perMs).toLong() - 2_000
        val lastMs = t + AHEAD_MS + 2_000
        var lastRight = Float.NEGATIVE_INFINITY
        for (r in runs) {
            if (r.endMs < firstMs) continue
            if (r.startMs > lastMs) break
            val xs = head + (r.startMs - t) * perMs
            val xe = head + (r.endMs - t) * perMs
            val ringing = r.startMs <= t && t < r.endMs
            val past = r.endMs <= t
            val left = xs + gap / 2
            val right = xe - gap / 2
            if (right > left) {
                if (ringing) {
                    drawRoundRect(accent.copy(alpha = 0.26f), Offset(left, barY), Size(right - left, barH), radius)
                    val filled = (head - left).coerceIn(0f, right - left)
                    if (filled > 0f) drawRoundRect(accent, Offset(left, barY), Size(filled, barH), radius)
                } else {
                    drawRoundRect(ink.copy(alpha = if (past) 0.13f else 0.24f), Offset(left, barY), Size(right - left, barH), radius)
                }
            }
            val text = names[labels[r.label]] ?: continue
            val tw = text.size.width
            // By its start; while it rings, held just past the line until the next one pushes it out.
            val x = max(xs + 2.dp.toPx(), minOf(head + 8.dp.toPx(), xe - tw - gap * 2))
            // No room (quick changes): the name waits for the cards above; the bar still shows.
            if (!ringing && x < lastRight + 6.dp.toPx()) continue
            val color = when {
                ringing -> accent
                past -> ink.copy(alpha = 0.32f)
                else -> ink.copy(alpha = 0.86f)
            }
            drawText(text, color = color, topLeft = Offset(x, nameBottom - text.size.height))
            lastRight = x + tw
        }
        // The still line: now.
        drawLine(accent, Offset(head, 2.dp.toPx()), Offset(head, size.height - 1.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(accent, radius = 5.dp.toPx(), center = Offset(head, barY + barH / 2))
    }
}

// ---- The words ----

/** The line being sung at the top, its ringing chord lit, the next ones under it. */
@Composable
private fun StageLines(
    lyrics: ParsedLyrics?,
    chart: ChordChart,
    view: ChordLayout.View,
    durationMs: Long,
    clock: PlaybackClock,
    colors: LyricColors,
    ringing: () -> Int,
    onSeek: (Long) -> Unit,
    onChord: (Chord) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChordLyricsLines(
        lyrics = lyrics,
        chart = chart,
        durationMs = durationMs,
        clock = clock,
        onSeek = onSeek,
        onChord = onChord,
        textStyle = MaterialTheme.typography.displaySmall.copy(fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
        colors = colors,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // On top, what's left of the line just sung stays hidden until it's gone.
                val top = 16.dp.toPx()
                val bottom = 64.dp.toPx()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.62f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = top),
                    size = Size(size.width, top),
                    blendMode = BlendMode.DstIn,
                )
                drawRect(
                    Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - bottom, endY = size.height),
                    topLeft = Offset(0f, size.height - bottom),
                    size = Size(size.width, bottom),
                    blendMode = BlendMode.DstIn,
                )
            },
        linesAbove = 0,
        contentPadding = PaddingValues(top = 10.dp, bottom = 360.dp),
        horizontalPadding = 28.dp,
        lineGap = 14.dp,
        view = view,
        ringing = ringing,
    )
}

private val LANE_HEIGHT = 58.dp
/** Where the still line stands, of the lane's width. */
private const val HEAD = 0.26f
/** How far ahead the lane shows. */
private const val AHEAD_MS = 6_500L
/** The sheet's chord box keeps this much room on its left (for the first fret's number)… */
private val BIG_MARGIN = 22.dp
/** …and on top (for ○ and ×), more than the marks need. */
private val BIG_LIFT = 10.dp
/** Under this, the sheet's chord box is too small for its numbers: the small drawing then. */
private val FULL_DIAGRAM = 150.dp
