package com.villamediana.sound.ui.player

import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.villamediana.sound.R
import com.villamediana.sound.chords.ChordState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.ui.theme.SoundColors

/** Background of everything lyrics: the cover color, deepened so paper-white text reads well. */
internal fun lyricsBackground(cover: Color): Color = SoundColors.lyricsBackground(cover)

/** The "Letra" card at the bottom of the full player (revealed by scrolling down). */
@Composable
internal fun LyricsCard(
    lyrics: LyricsState,
    clock: PlaybackClock,
    wash: State<Color>,
    washTarget: Color,
    onSeek: (Long) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    streamed: Boolean = false,
    /**
     * The song, for its chords: shown here when they are on and were already heard. The card never
     * hears a song itself — that happens only with the lyrics open and the chords button on.
     */
    track: Track? = null,
    durationMs: Long = 0,
) {
    val colors = LyricColors.over(lyricsBackground(washTarget))
    val chordsOn = com.villamediana.sound.ui.chords.rememberChordsEnabled() && track != null
    val chordState = if (track != null) com.villamediana.sound.ui.chords.rememberChordState(track, listen = false, online = true) else ChordState.Idle
    // In the key the song was last played in.
    val shift = if (track != null) com.villamediana.sound.ui.chords.rememberChordShift(track).first else 0
    val found = (chordState as? ChordState.Ready)?.chart?.takeIf { chordsOn }
    val chart = remember(found, shift) { found?.transposed(shift) }
    // The chord picked in this song: another song closes the sheet (and doesn't reopen it when its chords come).
    var sheetChord by remember(track?.id) { mutableStateOf<com.villamediana.sound.chords.Chord?>(null) }
    val instrument = com.villamediana.sound.ui.chords.rememberChordInstrument()
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawBehind { drawRect(lyricsBackground(wash.value)) },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable(interactionSource = null, indication = null, onClick = onOpen)
                .padding(start = 20.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.player_lyrics), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = SoundColors.Text, modifier = Modifier.weight(1f))
            PlayerIconButton(Icons.Rounded.OpenInFull, stringResource(R.string.player_lyrics_open), onOpen, size = 44.dp, iconSize = 18.dp)
        }
        val cardStyle = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em)
        when {
            chart != null && lyrics != LyricsState.Loading -> com.villamediana.sound.ui.chords.ChordLyricsLines(
                lyrics = (lyrics as? LyricsState.Ready)?.lyrics,
                chart = chart,
                durationMs = durationMs,
                clock = clock,
                onSeek = onSeek,
                onChord = { sheetChord = it },
                textStyle = cardStyle,
                colors = colors,
                modifier = Modifier.fillMaxWidth().height(CARD_LYRICS_HEIGHT),
                linesAbove = 1,
                contentPadding = PaddingValues(top = 2.dp, bottom = 28.dp),
            )
            lyrics == LyricsState.Loading -> LyricsLoading(lineHeight = 20.dp, Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 30.dp))
            lyrics == LyricsState.None || lyrics == LyricsState.Offline -> NoLyrics(lyrics, streamed, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 26.dp))
            lyrics is LyricsState.Ready -> LyricsLines(
                lyrics = lyrics.lyrics,
                clock = clock,
                onSeek = onSeek,
                textStyle = cardStyle,
                colors = colors,
                modifier = Modifier.fillMaxWidth().height(CARD_LYRICS_HEIGHT),
                linesAbove = 1,
                contentPadding = PaddingValues(top = 2.dp, bottom = 28.dp),
            )
        }
    }
    if (chart != null) {
        com.villamediana.sound.ui.chords.ChordSheetHost(chart, sheetChord, instrument, onChord = { sheetChord = it }, onDismiss = { sheetChord = null })
    }
}

@Composable
private fun NoLyrics(state: LyricsState, streamed: Boolean, modifier: Modifier = Modifier) {
    val (title, body) = when {
        state == LyricsState.Offline -> R.string.player_lyrics_offline_title to R.string.player_lyrics_offline_body
        streamed -> R.string.player_lyrics_none_title to R.string.player_lyrics_none_online
        else -> R.string.player_lyrics_none_title to R.string.player_lyrics_none_body
    }
    Column(modifier) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, color = SoundColors.Text)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = SoundColors.Text.copy(alpha = 0.7f))
    }
}

/** Looking the lyrics up online: a few soft lines breathing where the words will be. */
@Composable
private fun LyricsLoading(lineHeight: Dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "lyricsLoading")
    val pulse by transition.animateFloat(0.07f, 0.15f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val label = stringResource(R.string.player_lyrics_loading)
    Column(modifier.fillMaxWidth().semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(lineHeight * 0.7f)) {
        listOf(0.82f, 0.64f, 0.74f, 0.48f).forEach { w ->
            Box(Modifier.fillMaxWidth(w).height(lineHeight).clip(RoundedCornerShape(lineHeight / 2)).drawBehind { drawRect(SoundColors.Text.copy(alpha = pulse)) })
        }
    }
}

/** Full-screen lyrics: big type, line by line, on the cover's color, with a compact transport. */
@Composable
internal fun LyricsScreen(
    track: Track,
    lyrics: LyricsState,
    clock: PlaybackClock,
    wash: State<Color>,
    washTarget: Color,
    playing: Boolean,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    streamed: Boolean = false,
    online: Boolean = true,
) {
    val colors = LyricColors.over(lyricsBackground(washTarget))
    val chordsDeps = com.villamediana.sound.ui.chords.LocalChords.current
    val chordsOn = com.villamediana.sound.ui.chords.rememberChordsEnabled()
    val chordState = com.villamediana.sound.ui.chords.rememberChordState(track, listen = chordsOn, online = online)
    val (shift, setShift) = com.villamediana.sound.ui.chords.rememberChordShift(track)
    val found = (chordState as? ChordState.Ready)?.chart?.takeIf { chordsOn }
    val chart = remember(found, shift) { found?.transposed(shift) }
    var sheetChord by remember(track.id) { mutableStateOf<com.villamediana.sound.chords.Chord?>(null) }
    val instrument = com.villamediana.sound.ui.chords.rememberChordInstrument()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Box(
        modifier
            .fillMaxSize()
            .drawBehind { drawRect(lyricsBackground(wash.value)) }
            // Being a hit target is enough to keep touches from reaching the player underneath.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 8.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerIconButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.player_lyrics_close), onClose, iconSize = 30.dp)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = SoundColors.Text,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_600, repeatDelayMillis = 2_400),
                    )
                    Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = SoundColors.Text.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (chordsDeps != null) {
                    PlayerIconButton(
                        com.villamediana.sound.ui.chords.ChordsIcon,
                        stringResource(if (chordsOn) R.string.lyrics_chords_hide else R.string.lyrics_chords_show),
                        onClick = { scope.launch { chordsDeps.setEnabled(!chordsOn) } },
                        size = 44.dp,
                        iconSize = 22.dp,
                        tint = if (chordsOn) SoundColors.Mint else SoundColors.Text.copy(alpha = 0.4f),
                    )
                }
                // Chords and translation go together: the chords over a line, its translation under it.
                if (lyrics is LyricsState.Ready) TranslateToggle()
            }
            if (chordsOn) {
                com.villamediana.sound.ui.chords.ChordStatus(
                    state = chordState,
                    onRetry = { com.villamediana.sound.chords.ChordSubject.of(track)?.let { chordsDeps?.engine?.request(it, force = true) } },
                    modifier = Modifier.padding(start = 24.dp, end = 28.dp).padding(bottom = 6.dp),
                    shift = shift,
                    onShift = setShift,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .drawWithContent {
                        drawContent()
                        val bg = lyricsBackground(wash.value)
                        val fade = 36.dp.toPx()
                        drawRect(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0f)), startY = 0f, endY = fade))
                        drawRect(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg), startY = size.height - fade * 1.6f, endY = size.height))
                    },
            ) {
                when {
                    chart != null && lyrics != LyricsState.Loading -> com.villamediana.sound.ui.chords.ChordLyricsLines(
                        lyrics = (lyrics as? LyricsState.Ready)?.lyrics,
                        chart = chart,
                        durationMs = durationMs,
                        clock = clock,
                        onSeek = onSeek,
                        onChord = { sheetChord = it },
                        // A touch smaller than the plain lyrics: each line carries its chords too.
                        textStyle = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold),
                        colors = colors,
                        modifier = Modifier.fillMaxSize(),
                        linesAbove = 2,
                        contentPadding = PaddingValues(top = 24.dp, bottom = 160.dp),
                        horizontalPadding = 28.dp,
                        lineGap = 10.dp,
                    )
                    lyrics == LyricsState.Loading -> LyricsLoading(lineHeight = 28.dp, Modifier.padding(horizontal = 28.dp, vertical = 32.dp))
                    lyrics == LyricsState.None || lyrics == LyricsState.Offline -> NoLyrics(lyrics, streamed, Modifier.padding(horizontal = 28.dp, vertical = 24.dp))
                    lyrics is LyricsState.Ready -> LyricsLines(
                        lyrics = lyrics.lyrics,
                        clock = clock,
                        onSeek = onSeek,
                        textStyle = MaterialTheme.typography.displaySmall.copy(fontSize = 31.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold),
                        colors = colors,
                        modifier = Modifier.fillMaxSize(),
                        linesAbove = 2,
                        contentPadding = PaddingValues(top = 28.dp, bottom = 160.dp),
                        horizontalPadding = 28.dp,
                        lineGap = 14.dp,
                    )
                }
            }
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 28.dp).padding(bottom = 12.dp)) {
                SeekBar(clock = clock, durationMs = durationMs, onSeek = onSeek, timeColor = SoundColors.Text.copy(alpha = 0.7f))
                Box(Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
                    MorphPlayPause(playing = playing, onClick = onTogglePlay, size = 60.dp)
                }
            }
        }
    }
    if (chart != null) {
        com.villamediana.sound.ui.chords.ChordSheetHost(chart, sheetChord, instrument, onChord = { sheetChord = it }, onDismiss = { sheetChord = null })
    }
}

private val CARD_LYRICS_HEIGHT = 330.dp

/** "Tradução" on/off for lyrics in another language (remembered in the settings). */
@Composable
private fun TranslateToggle() {
    val translator = LocalLyricsTranslator.current ?: return
    val on by translator.enabled.collectAsState(initial = false)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    PlayerIconButton(
        Icons.Rounded.Translate,
        stringResource(R.string.lyrics_translate),
        onClick = { scope.launch { com.villamediana.sound.SoundApplication.graph.settings.setTranslateLyrics(!on) } },
        size = 44.dp,
        iconSize = 22.dp,
        tint = if (on) SoundColors.Text else SoundColors.Text.copy(alpha = 0.4f),
    )
}
