package com.villamediana.sound.ui.chords

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.villamediana.sound.R
import com.villamediana.sound.chords.Chord
import com.villamediana.sound.chords.ChordChart
import com.villamediana.sound.chords.ChordState
import com.villamediana.sound.chords.ChordSpeech
import com.villamediana.sound.chords.Fingerings
import com.villamediana.sound.chords.Instrument
import com.villamediana.sound.chords.PianoVoicing
import com.villamediana.sound.chords.Spelling
import com.villamediana.sound.ui.components.keepHorizontalDrags
import com.villamediana.sound.ui.sheets.SoundSheet
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How to play a chord, in the lyrics' own language: the chord's name set as it is over the words
 * (Am⁷), how it is said and its notes; the instrument (remembered) as a quiet row of words; where
 * the hands go — a few places on the neck, or the piano's inversions, swiped through — drawn in ink
 * with the root in the lyrics' accent; and the song's other chords at the bottom to go from one to
 * the next. Small spaced capitals label the parts, as "TOM" does by the play button.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ChordSheet(
    chord: Chord,
    spelling: Spelling,
    songChords: List<Chord>,
    instrument: Instrument,
    fingerings: Fingerings,
    onInstrument: (Instrument) -> Unit,
    onChord: (Chord) -> Unit,
    onDismiss: () -> Unit,
) {
    SoundSheet(onDismissRequest = onDismiss) {
      // Scrolls on a short screen; on most phones it all fits.
      Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        val language = LocalConfiguration.current.locales[0]?.language ?: "pt"
        val said = ChordSpeech.say(chord, spelling, language)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp)) {
            Text(
                ChordName.typeset(chord.name(spelling), display = true),
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 52.sp, lineHeight = 58.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
                color = SoundColors.Text,
                modifier = Modifier.testTag("chordSheetName"),
            )
            Text(said, style = MaterialTheme.typography.titleMedium, color = SoundColors.TextMuted)
            Spacer(Modifier.height(6.dp))
            ChordTones(chord, spelling)
        }
        Spacer(Modifier.height(18.dp))
        InstrumentTabs(instrument, onInstrument, Modifier.padding(horizontal = 12.dp))
        Spacer(Modifier.height(10.dp))

        if (instrument.fretted) {
            val shapes = remember(chord, instrument) { fingerings.of(chord, instrument) }
            Variations(count = shapes.size, key = chord to instrument, label = { R.string.chords_position }) { page ->
                FretDiagram(chord, shapes[page], instrument, spelling, said)
            }
        } else {
            val voicings = remember(chord) { PianoVoicing.voicings(chord) }
            Variations(count = voicings.size, key = chord to instrument, label = { if (it == 0) R.string.chords_root_position else R.string.chords_inversion }) { page ->
                PianoDiagram(voicings[page], chord.bass?.takeIf { it != chord.root }?.let { voicings[page].first() }, spelling, said, Modifier.padding(horizontal = 20.dp))
            }
        }

        if (songChords.size > 1) {
            Spacer(Modifier.height(22.dp))
            SheetLabel(stringResource(R.string.chords_in_song), Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(4.dp))
            val listState = rememberLazyListState()
            LaunchedEffect(chord) { songChords.indexOf(chord).takeIf { it >= 0 }?.let { listState.animateScrollToItem((it - 1).coerceAtLeast(0)) } }
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.keepHorizontalDrags(),
            ) {
                items(songChords, key = { it.toString() }) { c ->
                    QuietChoice(on = c == chord, onClick = { onChord(c) }) { ink ->
                        Text(
                            ChordName.typeset(c.name(spelling)),
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                            color = ink,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
      }
    }
}

/** A small spaced capital label ("NESTA MÚSICA"), as the lyrics' "TOM". */
@Composable
private fun SheetLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = SoundColors.Text.copy(alpha = 0.55f), maxLines = 1, modifier = modifier)
}

/** The chord's notes, the root in the lyrics' accent (as on the neck and the keys below). */
@Composable
private fun ChordTones(chord: Chord, spelling: Spelling) {
    val accent = SoundColors.Lyric
    val faint = SoundColors.TextMuted
    val text = androidx.compose.ui.text.buildAnnotatedString {
        chord.tones.forEachIndexed { i, t ->
            if (i > 0) withStyle(androidx.compose.ui.text.SpanStyle(color = faint.copy(alpha = 0.5f))) { append("  ·  ") }
            withStyle(androidx.compose.ui.text.SpanStyle(color = if (t == chord.root) accent else faint)) { append(spelling.name(t)) }
        }
    }
    Text(text, style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.04.em))
}

/**
 * One of a few choices as a word: the chosen one in full ink on a translucent pill (the player's
 * "Letra" button), the rest faint — no box around them all.
 */
@Composable
private fun QuietChoice(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, role: Role = Role.Button, content: @Composable (Color) -> Unit) {
    val bg by animateColorAsState(SoundColors.Text.copy(alpha = if (on) 0.09f else 0f), tween(200), label = "choiceBg")
    val ink by animateColorAsState(if (on) SoundColors.Text else SoundColors.TextMuted, tween(200), label = "choiceInk")
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(role = role, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { content(ink) }
}

/** Violão · Ukulele · Cavaquinho · Piano as words, the chosen one on a soft pill. */
@Composable
private fun InstrumentTabs(selected: Instrument, onSelect: (Instrument) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (instrument in Instrument.entries) {
            val on = instrument == selected
            QuietChoice(on = on, onClick = { onSelect(instrument) }, role = Role.Tab) { ink ->
                Text(
                    stringResource(instrument.label()),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                    color = ink,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The shapes of a chord, swiped through; under them, close together, ‹ "POSIÇÃO 2 DE 4" › with a
 * dot per shape.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Variations(count: Int, key: Any, label: (Int) -> Int, page: @Composable (Int) -> Unit) {
    if (count == 0) {
        Text(
            stringResource(R.string.chords_no_shape),
            style = MaterialTheme.typography.bodyMedium,
            color = SoundColors.TextMuted,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        )
        return
    }
    val pager = rememberPagerState(pageCount = { count })
    LaunchedEffect(key) { pager.scrollToPage(0) }
    val scope = rememberCoroutineScope()
    HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().keepHorizontalDrags(), key = { "$key-$it" }) { p ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { page(p) }
    }
    // Another chord or instrument may have fewer shapes, for the frame before the pager goes back to the first.
    val shown = pager.currentPage.coerceIn(0, count - 1)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val canBack = shown > 0
        val canOn = shown < count - 1
        IconButton(onClick = { scope.launch { pager.animateScrollToPage((shown - 1).coerceAtLeast(0)) } }, enabled = canBack) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.chords_previous_shape), tint = SoundColors.Text.copy(alpha = if (canBack) 0.85f else 0.2f))
        }
        Column(Modifier.widthIn(min = 132.dp).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SheetLabel(stringResource(label(shown), shown + 1, count, shown))
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(count) { i ->
                    val on = i == shown
                    val c by animateColorAsState(SoundColors.Text.copy(alpha = if (on) 0.9f else 0.22f), tween(200), label = "dot")
                    val w by androidx.compose.animation.core.animateDpAsState(if (on) 14.dp else 5.dp, tween(220), label = "dotWidth")
                    Box(Modifier.size(width = w, height = 5.dp).clip(CircleShape).background(c))
                }
            }
        }
        IconButton(onClick = { scope.launch { pager.animateScrollToPage((shown + 1).coerceAtMost(count - 1)) } }, enabled = canOn) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.chords_next_shape), tint = SoundColors.Text.copy(alpha = if (canOn) 0.85f else 0.2f))
        }
    }
}

/** The instrument chosen for the chord shapes (Violão until the setting is read). */
@Composable
internal fun rememberChordInstrument(): Instrument {
    val deps = LocalChords.current ?: return Instrument.VIOLAO
    val instrument by remember(deps) { deps.instrument }.collectAsState(initial = Instrument.VIOLAO)
    return instrument
}

/** The chord sheet for [chord] of [chart], when one is picked; the instrument choice is saved. */
@Composable
internal fun ChordSheetHost(chart: ChordChart, chord: Chord?, instrument: Instrument, onChord: (Chord) -> Unit, onDismiss: () -> Unit) {
    val deps = LocalChords.current ?: return
    // The song's shapes on the chosen instrument worked out ahead, off the main thread, so the
    // sheet opens at once (a few milliseconds each, once).
    LaunchedEffect(chart, instrument) {
        if (instrument.fretted) withContext(Dispatchers.Default) { for (c in chart.distinct) deps.fingerings.of(c, instrument) }
    }
    if (chord == null) return
    val scope = rememberCoroutineScope()
    ChordSheet(
        chord = chord,
        spelling = chart.spelling,
        songChords = remember(chart) { chart.distinct },
        instrument = instrument,
        fingerings = deps.fingerings,
        onInstrument = { scope.launch { deps.setInstrument(it) } },
        onChord = onChord,
        onDismiss = onDismiss,
    )
}

/**
 * Under the lyrics header while the chords are on and not shown yet: looking for a sheet, how far
 * the hearing is, or why it failed. Once they show, the key and the capo sit by the play button
 * ([ChordKey], [ChordSheetNote]).
 */
@Composable
internal fun ChordStatus(state: ChordState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        is ChordState.Working -> Column(modifier.fillMaxWidth().testTag("chordProgress")) {
            Text(
                if (state.looking) stringResource(R.string.chords_looking) else stringResource(R.string.chords_listening, (state.progress * 100).toInt()),
                style = MaterialTheme.typography.labelLarge,
                color = SoundColors.Text.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(SoundColors.Text.copy(alpha = 0.14f))) {
                val shown by androidx.compose.animation.core.animateFloatAsState(state.progress.coerceIn(0.02f, 1f), tween(400), label = "chordProgress")
                Box(Modifier.fillMaxWidth(shown).height(3.dp).clip(CircleShape).background(SoundColors.Mint))
            }
        }
        is ChordState.Failed -> Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    when (state.reason) {
                        ChordState.Reason.OFFLINE -> R.string.chords_failed_offline
                        ChordState.Reason.TOO_LONG -> R.string.chords_failed_too_long
                        ChordState.Reason.NOTHING_HEARD -> R.string.chords_failed_nothing
                        ChordState.Reason.ERROR -> R.string.chords_failed_error
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = SoundColors.Text.copy(alpha = 0.72f),
                modifier = Modifier.weight(1f),
            )
            if (state.reason == ChordState.Reason.OFFLINE || state.reason == ChordState.Reason.ERROR) {
                Text(
                    stringResource(R.string.chords_retry),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = SoundColors.Mint,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onRetry).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        else -> Unit
    }
}
