package com.villamediana.sound.ui.chords

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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
 * capitals for labels, no boxes). The chord ringing is read from the clock behind derivedStateOf:
 * a change recomposes, a frame doesn't — nothing here is drawn per frame. No timeline: the hand
 * keeps its own time (Miguel: a line running with the clock can be off, better the player leads).
 * To take it out: this file, chords/PlayAlong.kt, the "ringing" parameter of ChordLyricsLines and
 * the button and branch in LyricsScreen.
 */

/**
 * On top, two places that never move: the chord ringing lit — full size, in the sung line's color,
 * with where the hands go on the chosen instrument — and the one coming, faint, beside it; at each
 * change the light moves over ([ChordPair]). Under them the line being sung with its chords, the one
 * ringing lit, and the lines to come. A tap on a chord shows how to play it (the chord sheet).
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
        // Another song (or key) is another lane: its chords come in fresh, nothing slides over from the last one.
        key(lane) {
            if (maxWidth <= maxHeight) {
                val diagram = (maxHeight * 0.34f).coerceIn(110.dp, 216.dp)
                Column(Modifier.fillMaxSize()) {
                    ChordPair(
                        lane, labels, chart, now, instrument, fingerings, diagram, nameSize = 60.sp, onChord,
                        Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(top = 6.dp),
                    )
                    StageLines(lyrics, chart, view, durationMs, clock, colors, ringing, onSeek, onChord, Modifier.padding(top = 12.dp).weight(1f).fillMaxWidth())
                }
            } else {
                // Lying down: the chords on the left, the words on the right.
                val diagram = (maxHeight - 96.dp).coerceIn(64.dp, 216.dp)
                Row(Modifier.fillMaxSize()) {
                    ChordPair(
                        lane, labels, chart, now, instrument, fingerings, diagram, nameSize = 46.sp, onChord,
                        Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp),
                    )
                    StageLines(lyrics, chart, view, durationMs, clock, colors, ringing, onSeek, onChord, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/**
 * An acoustic guitar leaning up to the right: the "Tocar junto" button. One silhouette (two bouts
 * and a sound hole), the neck and the head as strokes — drawn like the chords button.
 */
internal val PlayAlongIcon: ImageVector by lazy {
    ImageVector.Builder("PlayAlong", 24.dp, 24.dp, 24f, 24f).apply {
        val ink = SolidColor(Color.White)
        fun androidx.compose.ui.graphics.vector.PathBuilder.circle(cx: Float, cy: Float, r: Float, clockwise: Boolean) {
            moveTo(cx - r, cy)
            arcToRelative(r, r, 0f, true, clockwise, 2 * r, 0f)
            arcToRelative(r, r, 0f, true, clockwise, -2 * r, 0f)
            close()
        }
        // The neck and the head.
        path(stroke = ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(11.5f, 12.5f); lineTo(18.6f, 5.4f)
        }
        path(stroke = ink, strokeLineWidth = 3.4f, strokeLineCap = StrokeCap.Round) {
            moveTo(18.9f, 5.1f); lineTo(20.6f, 3.4f)
        }
        // The body: the lower and upper bouts as one, the sound hole cut out (wound the other way).
        path(fill = ink) {
            circle(7.9f, 16.1f, 5.1f, clockwise = true)
            circle(11.7f, 12.3f, 3.5f, clockwise = true)
            circle(8f, 16f, 1.5f, clockwise = false)
        }
    }.build()
}

/** "TOCAR" over a guitar, by the play button across from the key: full ink when on, faint when off. */
@Composable
internal fun PlayAlongToggle(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val said = stringResource(if (on) R.string.chords_play_along_off else R.string.chords_play_along_on)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val ink by animateColorAsState(if (on) SoundColors.Text else SoundColors.Text.copy(alpha = 0.4f), tween(200), label = "playAlongInk")
    Column(modifier.testTag("playAlongToggle"), horizontalAlignment = Alignment.CenterHorizontally) {
        StageLabel(stringResource(R.string.chords_play_along_label))
        Box(
            Modifier
                .size(40.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(role = Role.Switch, onClickLabel = said) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick)
                    onToggle()
                }
                .semantics {
                    contentDescription = said
                    selected = on
                },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(PlayAlongIcon, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
        }
    }
}

// ---- Now and next ----

/** What a chord on the stage shows: its name as written, its shape and how it's said. */
private class Shown(val name: String, val chord: Chord?, val said: String)

@Composable
private fun rememberShown(chart: ChordChart, labels: List<String>, index: Int): Shown? {
    val language = LocalConfiguration.current.locales[0]?.language ?: "pt"
    return remember(chart, labels, index, language) {
        labels.getOrNull(index)?.let { name ->
            val chord = chart.chordAt(index)
            Shown(name, chord, chord?.let { ChordSpeech.say(it, chart.spelling, language) } ?: name)
        }
    }
}

/**
 * The chord ringing and the one coming, in two places that never move. A chord takes its place
 * while it's still coming — faint and a little smaller — and lights up right there, full size in
 * the sung line's color, when it rings; the place it leaves fades and takes the chord after next.
 * The eye stays put: only the light moves from one place to the other. (In 1.33 the next chord slid
 * over into the "now" place and a new one filled its own: too much moving to follow while playing.)
 */
@Composable
private fun ChordPair(
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
) {
    val at = now.value
    // The change the hand is on (a chord written again is the same one); −1 before the first.
    val run = if (at >= 0) lane.run[at] else -1
    val starts = remember(lane) { lane.chords.indices.filter { it == 0 || lane.run[it] != lane.run[it - 1] } }
    // The keys need less height than a neck.
    val room = if (instrument.fretted) diagram else min(diagram, 118.dp)
    BoxWithConstraints(modifier) {
        // The sheet's drawing keeps its proportions: as tall as its place's width makes it, so the
        // words below come right after it.
        val place = (maxWidth - COLUMN_GAP) / 2
        val aspect = if (instrument.strings.size >= 6) 1.08f else 0.95f
        val fitted = if (instrument.fretted) min(room, (place + BIG_MARGIN) / aspect - BIG_LIFT) else room
        Row(Modifier.fillMaxWidth()) {
            for (side in 0..1) {
                if (side == 1) Spacer(Modifier.width(COLUMN_GAP))
                // Changes take turns: even ones on the left, odd ones on the right.
                val shownRun = when {
                    run < 0 -> side
                    run % 2 == side -> run
                    else -> run + 1
                }
                val index = starts.getOrNull(shownRun)?.let { lane.chords[it].index } ?: -1
                ChordPlace(
                    index = index,
                    lit = run >= 0 && run % 2 == side,
                    chart = chart,
                    labels = labels,
                    instrument = instrument,
                    fingerings = fingerings,
                    shapeHeight = fitted,
                    nameSize = nameSize,
                    onChord = onChord,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One of the two places: the chord in it, lit while it rings (full size, the sung line's color) or
 * waiting (faint, a little smaller, shrunk toward its name's baseline so the names stay on one line
 * and nothing moves). A new chord comes in only while the place is dark, with a soft cross-fade.
 */
@Composable
private fun ChordPlace(
    index: Int,
    lit: Boolean,
    chart: ChordChart,
    labels: List<String>,
    instrument: Instrument,
    fingerings: Fingerings?,
    shapeHeight: Dp,
    nameSize: TextUnit,
    onChord: (Chord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val light by androidx.compose.animation.core.animateFloatAsState(
        if (lit) 1f else 0f,
        tween(LIGHT_MS, easing = FastOutSlowInEasing),
        label = "chordLight",
    )
    val ink = SoundColors.Text
    val accent = SoundColors.Lyric
    val nowSaid = stringResource(R.string.chords_now)
    val nextSaid = stringResource(R.string.chords_up_next)
    Box(
        modifier.graphicsLayer {
            val scale = WAITING_SCALE + (1f - WAITING_SCALE) * light
            scaleX = scale
            scaleY = scale
            alpha = WAITING_ALPHA + (1f - WAITING_ALPHA) * light
            // Shrunk toward the name's baseline (its left end): both names stay on one line, the
            // smaller shape tucked under it.
            val baseline = nameSize.toPx() * NAME_BASELINE_EM
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, if (size.height > 0f) (baseline / size.height).coerceIn(0f, 1f) else 0f)
        },
    ) {
        androidx.compose.animation.Crossfade(targetState = index, animationSpec = tween(SWAP_MS), label = "chordPlace") { k ->
            val shown = rememberShown(chart, labels, k)
            if (shown == null) {
                Spacer(Modifier.fillMaxWidth().height(shapeHeight))
                return@Crossfade
            }
            Column(Modifier.fillMaxWidth()) {
                StageName(
                    shown,
                    nameSize,
                    ink = { androidx.compose.ui.graphics.lerp(ink, accent, light) },
                    onChord = onChord,
                    say = (if (lit) nowSaid else nextSaid) + ": " + shown.said,
                )
                ChordShape(shown, chart.spelling, instrument, fingerings, big = true, onChord, Modifier.fillMaxWidth().height(shapeHeight).padding(top = 8.dp))
            }
        }
    }
}

/** A small spaced capital label, as "TOM" by the play button. */
@Composable
private fun StageLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = SoundColors.Text.copy(alpha = 0.6f),
        maxLines = 1,
        modifier = modifier,
    )
}

/** A chord's name set as over the words, big; a tap shows how to play it. A long one gets smaller rather than cut. */
@Composable
private fun StageName(shown: Shown, size: TextUnit, ink: () -> Color, onChord: (Chord) -> Unit, say: String = shown.said) {
    val style = MaterialTheme.typography.displayMedium.copy(
        fontSize = size,
        lineHeight = size * 1.1f,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.02).em,
    )
    BasicText(
        ChordName.typeset(shown.name, display = true),
        style = style,
        color = ink,
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = size * 0.5f, maxFontSize = size),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = null, indication = null, enabled = shown.chord != null, role = Role.Button, onClickLabel = shown.said) {
                shown.chord?.let(onChord)
            }
            .semantics { contentDescription = say },
    )
}

/**
 * Where the hands go, as big as [modifier]'s room lets it be: the chord sheet's own drawing for the
 * one ringing ([big]) when there's room for its numbers, the small one otherwise. A tap shows how
 * to play it; it says nothing itself (its name, right above it, does).
 */
@Composable
private fun ChordShape(shown: Shown, spelling: Spelling, instrument: Instrument, fingerings: Fingerings?, big: Boolean, onChord: (Chord) -> Unit, modifier: Modifier) {
    val chord = shown.chord ?: return
    fingerings ?: return
    BoxWithConstraints(
        modifier
            .clearAndSetSemantics { }
            .pointerInput(chord) { detectTapGestures { onChord(chord) } },
    ) {
        if (instrument.fretted) {
            val shape = remember(chord, instrument) { fingerings.of(chord, instrument).firstOrNull() } ?: return@BoxWithConstraints
            val wide = instrument.strings.size >= 6
            if (big && maxHeight >= FULL_DIAGRAM - BIG_LIFT && maxWidth + BIG_MARGIN >= FULL_DIAGRAM) {
                // The sheet's drawing; its margins (the first fret's number on the left, ○ and ×
                // on top) hang out into the page's, so the strings start under the name.
                val aspect = if (wide) 1.08f else 0.95f
                val w = min(maxWidth + BIG_MARGIN, (maxHeight + BIG_LIFT) * aspect)
                FretDiagram(chord, shape, instrument, spelling, shown.said, Modifier.offset(x = -BIG_MARGIN, y = -BIG_LIFT).width(w))
            } else {
                val ratio = if (wide) 0.92f else 0.74f
                val w = min(maxWidth, maxHeight * ratio)
                MiniFret(chord, shape, instrument, Modifier.width(w).height(min(maxHeight, w / ratio)))
            }
        } else {
            val notes = remember(chord) { PianoVoicing.voicings(chord).firstOrNull() } ?: return@BoxWithConstraints
            val bass = chord.bass?.takeIf { it != chord.root }?.let { notes.first() }
            StageKeys(notes, bass, spelling, labels = big && maxHeight >= 72.dp, Modifier.fillMaxSize())
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

private val COLUMN_GAP = 18.dp
/** A chord waiting for its turn: this much of its size, this much of its ink. */
private const val WAITING_SCALE = 0.8f
private const val WAITING_ALPHA = 0.4f
/** Where a name's baseline sits under the top of its line, in its own size. */
private const val NAME_BASELINE_EM = 0.86f
/** How long the light takes to move over, and a dark place to take its next chord. */
private const val LIGHT_MS = 260
private const val SWAP_MS = 220
/** The sheet's chord box keeps this much room on its left (for the first fret's number)… */
private val BIG_MARGIN = 22.dp
/** …and on top (for ○ and ×), more than the marks need. */
private val BIG_LIFT = 10.dp
/** Under this, the sheet's chord box is too small for its numbers: the small drawing then. */
private val FULL_DIAGRAM = 150.dp
