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
 * How to play a chord: the instrument on top (remembered), the chord's name, how it is said and
 * its notes, then where the hands go — a few places on the neck, or the piano's inversions,
 * swiped through — and the song's other chords at the bottom to go from one to the next.
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
        InstrumentTabs(instrument, onInstrument, Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp))
        val language = LocalConfiguration.current.locales[0]?.language ?: "pt"
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(
                chord.name(spelling),
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
                color = SoundColors.Text,
                modifier = Modifier.testTag("chordSheetName"),
            )
            Text(ChordSpeech.say(chord, spelling, language), style = MaterialTheme.typography.titleMedium, color = SoundColors.TextMuted)
            Spacer(Modifier.height(2.dp))
            Text(chord.tones.joinToString("  ·  ") { spelling.name(it) }, style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextFaint)
        }
        Spacer(Modifier.height(12.dp))

        val description = ChordSpeech.say(chord, spelling, language)
        if (instrument.fretted) {
            val shapes = remember(chord, instrument) { fingerings.of(chord, instrument) }
            Variations(count = shapes.size, key = chord to instrument, label = { R.string.chords_position }) { page ->
                FretDiagram(chord, shapes[page], instrument, spelling, description)
            }
        } else {
            val voicings = remember(chord) { PianoVoicing.voicings(chord) }
            Variations(count = voicings.size, key = chord to instrument, label = { if (it == 0) R.string.chords_root_position else R.string.chords_inversion }) { page ->
                PianoDiagram(voicings[page], chord.bass?.takeIf { it != chord.root }?.let { voicings[page].first() }, spelling, description, Modifier.padding(horizontal = 20.dp))
            }
        }

        if (songChords.size > 1) {
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.chords_in_song),
                style = MaterialTheme.typography.labelLarge,
                color = SoundColors.TextMuted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            val listState = rememberLazyListState()
            LaunchedEffect(chord) { songChords.indexOf(chord).takeIf { it >= 0 }?.let { listState.animateScrollToItem((it - 1).coerceAtLeast(0)) } }
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.keepHorizontalDrags(),
            ) {
                items(songChords, key = { it.toString() }) { c ->
                    val on = c == chord
                    val bg by animateColorAsState(if (on) SoundColors.Mint else SoundColors.Surface3, tween(180), label = "songChord")
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(bg)
                            .clickable(role = Role.Button) { onChord(c) }
                            .semantics { selected = on }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(c.name(spelling), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = if (on) SoundColors.OnMint else SoundColors.Text)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
      }
    }
}

/** Violão · Ukulele · Cavaquinho · Piano, the chosen one filled in. */
@Composable
private fun InstrumentTabs(selected: Instrument, onSelect: (Instrument) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SoundColors.Surface3)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (instrument in Instrument.entries) {
            val on = instrument == selected
            val bg by animateColorAsState(if (on) SoundColors.Surface2 else SoundColors.Surface3, tween(200), label = "tab")
            val ink by animateColorAsState(if (on) SoundColors.Text else SoundColors.TextMuted, tween(200), label = "tabInk")
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(bg)
                    .clickable(role = Role.Tab) { onSelect(instrument) }
                    .semantics { this.selected = on }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(instrument.label()),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                    color = ink,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The shapes of a chord, swiped through, with arrows and "Posição 2 de 4" under them. */
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { scope.launch { pager.animateScrollToPage((shown - 1).coerceAtLeast(0)) } }, enabled = shown > 0) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.chords_previous_shape), tint = if (shown > 0) SoundColors.Text else SoundColors.TextFaint.copy(alpha = 0.4f))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(label(shown), shown + 1, count, shown),
                style = MaterialTheme.typography.labelLarge,
                color = SoundColors.TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(count) { i ->
                    val c by animateColorAsState(if (i == shown) SoundColors.Text else SoundColors.TextFaint.copy(alpha = 0.45f), tween(200), label = "dot")
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c))
                }
            }
        }
        IconButton(onClick = { scope.launch { pager.animateScrollToPage((shown + 1).coerceAtMost(count - 1)) } }, enabled = shown < count - 1) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.chords_next_shape), tint = if (shown < count - 1) SoundColors.Text else SoundColors.TextFaint.copy(alpha = 0.4f))
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
