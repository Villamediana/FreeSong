package com.villamediana.surco.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.surco.data.Lrc
import com.villamediana.surco.data.ParsedLyrics
import com.villamediana.surco.ui.theme.SurcoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

internal sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Ready(val lyrics: ParsedLyrics) : LyricsState
}

internal object LyricsFiles {
    /** Reads and parses a .lrc/.txt lyrics file; null when missing, unreadable or empty. */
    fun read(path: String?): ParsedLyrics? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull()?.removePrefix("﻿") ?: return null
        val parsed = Lrc.parse(text)
        return parsed.takeIf { lyrics -> lyrics.lines.any { it.text.isNotBlank() } }
    }

    suspend fun load(path: String?): ParsedLyrics? = withContext(Dispatchers.IO) { read(path) }
}

@Composable
internal fun rememberLyrics(path: String?): State<LyricsState> =
    produceState<LyricsState>(LyricsState.Loading, path) {
        value = LyricsFiles.load(path)?.let { LyricsState.Ready(it) } ?: LyricsState.None
    }

/** Colors for lyric lines drawn over [background]: the sung line, lines already sung, lines to come. */
internal data class LyricColors(val active: Color, val past: Color, val future: Color) {
    companion object {
        fun over(background: Color): LyricColors = LyricColors(
            active = SurcoColors.Text,
            past = SurcoColors.Text.copy(alpha = 0.58f),
            // Like a printed sleeve: upcoming lines in ink when the color is light enough to carry it.
            future = if (background.luminance() > 0.11f) SurcoColors.Ink.copy(alpha = 0.72f) else SurcoColors.Text.copy(alpha = 0.34f),
        )
    }
}

/**
 * Lyric lines. Synced lyrics follow the song (the sung line stays [linesAbove] rows from the top,
 * others dimmed) and a tap on a line seeks there; plain lyrics just scroll. Touching the list
 * pauses the auto-follow for a few seconds.
 */
@Composable
internal fun LyricsLines(
    lyrics: ParsedLyrics,
    clock: PlaybackClock,
    onSeek: (Long) -> Unit,
    textStyle: TextStyle,
    colors: LyricColors,
    modifier: Modifier = Modifier,
    linesAbove: Int = 1,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalPadding: Dp = 20.dp,
    lineGap: Dp = 10.dp,
) {
    val listState = rememberLazyListState()
    val current by remember(lyrics, clock) {
        derivedStateOf { if (lyrics.synced) lyrics.indexAt(clock.positionMs + PlayerMath.LYRICS_LEAD_MS) else -1 }
    }
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
    LaunchedEffect(lyrics, current, resting) {
        if (!lyrics.synced || !resting) return@LaunchedEffect
        val target = (current - linesAbove).coerceAtLeast(0)
        if (current < 0) listState.animateScrollToItem(0) else listState.animateScrollToItem(target)
    }

    LazyColumn(state = listState, modifier = modifier, contentPadding = contentPadding) {
        itemsIndexed(lyrics.lines) { index, line ->
            val target = when {
                !lyrics.synced -> colors.active.copy(alpha = 0.92f)
                index == current -> colors.active
                index < current -> colors.past
                else -> colors.future
            }
            val color by animateColorAsState(target, tween(LINE_FADE_MS), label = "lyric")
            Text(
                text = line.text.ifBlank { MUSIC_NOTE },
                style = textStyle,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (lyrics.synced) {
                            Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSeek(line.timeMs) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = horizontalPadding, vertical = lineGap / 2),
            )
        }
    }
}

private const val FOLLOW_PAUSE_MS = 3_000L
private const val LINE_FADE_MS = 260
private const val MUSIC_NOTE = "♪"
