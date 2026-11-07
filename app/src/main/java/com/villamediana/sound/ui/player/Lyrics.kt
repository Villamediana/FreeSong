package com.villamediana.sound.ui.player

import kotlinx.serialization.builtins.serializer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.sound.data.Lrc
import com.villamediana.sound.data.ParsedLyrics
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.online.Lyrics
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    /** An online song's lyrics can't be looked up without network. */
    data object Offline : LyricsState
    data class Ready(val lyrics: ParsedLyrics) : LyricsState
}

/** What to look up on LRCLIB for a song that is not on the phone. */
internal data class LyricsQuery(val title: String, val artist: String, val album: String?, val durationMs: Long?) {
    /** Cache key: the same song always asks the same question. */
    val key: String get() = listOf(title.trim().lowercase(), artist.trim().lowercase(), album?.trim()?.lowercase().orEmpty(), (durationMs ?: 0) / 1000).joinToString("\u0001")
}

/** Where the player's lyrics come from. */
internal sealed interface LyricsOrigin {
    data class File(val path: String) : LyricsOrigin
    data class Online(val query: LyricsQuery) : LyricsOrigin
    data object None : LyricsOrigin

    companion object {
        /**
         * [playing] is what the player is on (maybe a streamed, transient song); [libraryCopy] its row
         * in the library, when there is one. A saved lyrics file always wins; a streamed song without
         * one asks LRCLIB (even right after it got downloaded); a song from the phone without a file
         * simply has none.
         */
        fun choose(playing: Track, libraryCopy: Track?): LyricsOrigin {
            val path = libraryCopy?.lyricsPath?.takeIf { it.isNotBlank() }
                ?: playing.lyricsPath?.takeIf { it.isNotBlank() && !playing.isOnline }
            return when {
                path != null -> File(path)
                playing.isOnline -> Online(
                    LyricsQuery(
                        title = playing.title,
                        artist = playing.artist,
                        album = playing.album?.takeIf { it.isNotBlank() },
                        durationMs = playing.durationMs.takeIf { it > 0 },
                    ),
                )
                else -> None
            }
        }
    }
}

/**
 * Lyrics of streamed songs, fetched on demand and kept in memory for the session (the card and
 * the full-screen view share one lookup; skipping back and forth doesn't ask again). "Not found"
 * is remembered for a while only — LyricsSource can't tell it apart from a failed request.
 */
internal class OnlineLyricsCache(
    private val find: suspend (LyricsQuery) -> Lyrics?,
    private val now: () -> Long = System::currentTimeMillis,
    private val capacity: Int = 60,
    private val missTtlMs: Long = 10 * 60_000L,
    /** Found lyrics also kept on disk (raw text): a song heard again another day doesn't ask again. */
    private val file: java.io.File? = null,
) {
    private val mutex = Mutex()
    private var stored: LinkedHashMap<String, String>? = null

    /** Lyrics text kept on disk, by query key (oldest first; capped). Called under [mutex]. */
    private fun disk(): LinkedHashMap<String, String> = stored ?: LinkedHashMap<String, String>().also { map ->
        file?.takeIf { it.isFile }?.let { f ->
            runCatching {
                map.putAll(diskJson.decodeFromString(diskSerializer, f.readText()))
            }
        }
        stored = map
    }

    private fun parse(text: String): ParsedLyrics? =
        Lrc.parse(text.removePrefix("\uFEFF")).takeIf { lyrics -> lyrics.lines.any { it.text.isNotBlank() } }
    private val found = object : LinkedHashMap<String, ParsedLyrics>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedLyrics>?): Boolean = size > capacity
    }
    private val missing = HashMap<String, Long>()

    /** Forgets every lyrics, in memory and on disk (Ajustes › Armazenamento). */
    suspend fun clear() = mutex.withLock {
        found.clear()
        missing.clear()
        stored = LinkedHashMap()
        runCatching { file?.delete() }
    }

    fun bytes(): Long = file?.takeIf { it.isFile }?.length() ?: 0L

    /** Already known: the lyrics, or [MISSING] when recently not found; null = never asked. */
    suspend fun peek(query: LyricsQuery): ParsedLyrics? = mutex.withLock {
        found[query.key]
            ?: (if (file != null) disk()[query.key]?.let(::parse)?.also { found[query.key] = it } else null)
            ?: missing[query.key]?.takeIf { now() - it < missTtlMs }?.let { MISSING }
    }

    suspend fun get(query: LyricsQuery): ParsedLyrics? {
        peek(query)?.let { return it.takeUnless { l -> l === MISSING } }
        val text = runCatching { find(query) }.getOrNull()?.text
        val parsed = text?.let(::parse)
        mutex.withLock {
            if (parsed != null) {
                found[query.key] = parsed
                missing.remove(query.key)
                val f = file
                if (f != null && text != null) {
                    val map = disk()
                    map[query.key] = text
                    while (map.size > DISK_ENTRIES) map.remove(map.keys.first())
                    runCatching {
                        f.parentFile?.mkdirs()
                        f.writeText(diskJson.encodeToString(diskSerializer, map))
                    }
                }
            } else {
                missing[query.key] = now()
            }
        }
        return parsed
    }

    companion object {
        val MISSING = ParsedLyrics(synced = false, lines = emptyList())
        private const val DISK_ENTRIES = 400
        private val diskJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        private val diskSerializer: kotlinx.serialization.KSerializer<Map<String, String>> =
            kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer())
    }
}

/** The player's lyrics for [origin]; online lookups wait for the network and retry when it returns. */
@Composable
internal fun rememberLyrics(origin: LyricsOrigin, online: Boolean, cache: OnlineLyricsCache?): State<LyricsState> =
    produceState<LyricsState>(LyricsState.Loading, origin, online, cache) {
        value = when (origin) {
            is LyricsOrigin.File -> LyricsFiles.load(origin.path)?.let { LyricsState.Ready(it) } ?: LyricsState.None
            LyricsOrigin.None -> LyricsState.None
            is LyricsOrigin.Online -> {
                val known = cache?.peek(origin.query)
                when {
                    cache == null -> LyricsState.None
                    known != null -> if (known === OnlineLyricsCache.MISSING) LyricsState.None else LyricsState.Ready(known)
                    !online -> LyricsState.Offline
                    else -> {
                        value = LyricsState.Loading
                        cache.get(origin.query)?.let { LyricsState.Ready(it) } ?: LyricsState.None
                    }
                }
            }
        }
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


/** Translates lyrics in another language (none in previews and tests). */
val LocalLyricsTranslator = androidx.compose.runtime.staticCompositionLocalOf<com.villamediana.sound.online.LyricsTranslator?> { null }

/** The translation of [lyrics] (one entry per line, null where none) while "Tradução" is on; null otherwise. */
@Composable
internal fun rememberTranslation(lyrics: ParsedLyrics): List<String?>? {
    val translator = LocalLyricsTranslator.current ?: return null
    val enabled by translator.enabled.collectAsState(initial = false)
    val result by produceState<List<String?>?>(null, lyrics, enabled) {
        value = if (enabled) translator.translate(lyrics.lines.map { it.text }) else null
    }
    return result
}

/** Colors for lyric lines drawn over [background]: the sung line, lines already sung, lines to come. */
internal data class LyricColors(val active: Color, val past: Color, val future: Color) {
    companion object {
        fun over(background: Color): LyricColors = LyricColors(
            // The sung line in yellow (as in karaoke screens), the rest in soft white.
            active = SoundColors.Lyric,
            past = SoundColors.Text.copy(alpha = 0.5f),
            future = SoundColors.Text.copy(alpha = if (background.luminance() > 0.2f) 0.75f else 0.6f),
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
    /** Under each line, its translation (lyrics in another language, "Tradução" on). */
    translations: List<String?>? = rememberTranslation(lyrics),
) {
    // One scroll per song's lyrics: the next song starts at its first line, not where the last one was.
    val listState = rememberSaveable(lyrics, saver = LazyListState.Saver) { LazyListState() }
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
            val translation = translations?.getOrNull(index)
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (lyrics.synced) {
                            Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSeek(line.timeMs) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = horizontalPadding, vertical = lineGap / 2),
            ) {
                Text(text = line.text.ifBlank { MUSIC_NOTE }, style = textStyle, color = color)
                if (translation != null) {
                    Text(
                        text = translation,
                        style = textStyle.translationStyle(),
                        color = color.copy(alpha = color.alpha * 0.7f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

private const val FOLLOW_PAUSE_MS = 3_000L
private const val LINE_FADE_MS = 260
private const val MUSIC_NOTE = "♪"

/** The translation under a line: the same face, smaller and lighter. */
internal fun TextStyle.translationStyle(): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) fontSize * 0.62f else 15.sp,
    lineHeight = if (lineHeight.isSpecified) lineHeight * 0.62f else lineHeight,
    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
    letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
)
