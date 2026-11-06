package com.villamediana.surco.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.villamediana.surco.R
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.ui.theme.SurcoColors

/** Background of everything lyrics: the cover color, deepened so paper-white text reads well. */
internal fun lyricsBackground(cover: Color): Color = lerp(cover, SurcoColors.Ink, 0.36f)

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
) {
    val colors = LyricColors.over(lyricsBackground(washTarget))
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
            Text(stringResource(R.string.player_lyrics), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = SurcoColors.Text, modifier = Modifier.weight(1f))
            PlayerIconButton(Icons.Rounded.OpenInFull, stringResource(R.string.player_lyrics_open), onOpen, size = 44.dp, iconSize = 18.dp)
        }
        when (lyrics) {
            LyricsState.Loading -> Spacer(Modifier.height(140.dp))
            LyricsState.None -> NoLyrics(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 26.dp))
            is LyricsState.Ready -> LyricsLines(
                lyrics = lyrics.lyrics,
                clock = clock,
                onSeek = onSeek,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em),
                colors = colors,
                modifier = Modifier.fillMaxWidth().height(CARD_LYRICS_HEIGHT),
                linesAbove = 1,
                contentPadding = PaddingValues(top = 2.dp, bottom = 28.dp),
            )
        }
    }
}

@Composable
private fun NoLyrics(modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(R.string.player_lyrics_none_title), style = MaterialTheme.typography.headlineSmall, color = SurcoColors.Text)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.player_lyrics_none_body), style = MaterialTheme.typography.bodyMedium, color = SurcoColors.Text.copy(alpha = 0.7f))
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
) {
    val colors = LyricColors.over(lyricsBackground(washTarget))
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
                        color = SurcoColors.Text,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_600, repeatDelayMillis = 2_400),
                    )
                    Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = SurcoColors.Text.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
                when (lyrics) {
                    LyricsState.Loading -> Unit
                    LyricsState.None -> NoLyrics(Modifier.padding(horizontal = 28.dp, vertical = 24.dp))
                    is LyricsState.Ready -> LyricsLines(
                        lyrics = lyrics.lyrics,
                        clock = clock,
                        onSeek = onSeek,
                        textStyle = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold),
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
                SeekBar(clock = clock, durationMs = durationMs, onSeek = onSeek, timeColor = SurcoColors.Text.copy(alpha = 0.7f))
                Box(Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
                    MorphPlayPause(playing = playing, onClick = onTogglePlay, size = 60.dp)
                }
            }
        }
    }
}

private val CARD_LYRICS_HEIGHT = 330.dp
