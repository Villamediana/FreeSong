package com.villamediana.sound.ui.chords

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.villamediana.sound.chords.ChordEngine
import com.villamediana.sound.chords.ChordState
import com.villamediana.sound.chords.ChordSubject
import com.villamediana.sound.chords.Fingerings
import com.villamediana.sound.chords.Instrument
import com.villamediana.sound.data.db.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** What the chords need from the app: the engine, the shapes, the two settings and each song's key. */
class ChordsDeps(
    val engine: ChordEngine,
    val fingerings: Fingerings,
    val enabled: Flow<Boolean>,
    val instrument: Flow<Instrument>,
    val setEnabled: suspend (Boolean) -> Unit,
    val setInstrument: suspend (Instrument) -> Unit,
    val shifts: com.villamediana.sound.chords.ChordShifts = com.villamediana.sound.chords.ChordShifts(null),
)

/** None in previews and in tests that don't give one: no chords button then. */
val LocalChords = staticCompositionLocalOf<ChordsDeps?> { null }

/** Chords on or off (Ajustes keeps it). */
@Composable
internal fun rememberChordsEnabled(): Boolean {
    val deps = LocalChords.current
    val on by (deps?.enabled ?: remember { flowOf(false) }).collectAsState(initial = false)
    return deps != null && on
}

/**
 * The chords of [track]. Only while [listen] (the lyrics open with the chords button on) is the song
 * heard — once, then kept — and heard again when the network comes back after it was missing; it
 * stops as soon as that ends. Otherwise chords heard before are just read back, and nothing runs
 * for a song never asked for.
 */
@Composable
internal fun rememberChordState(track: Track, listen: Boolean, online: Boolean): ChordState {
    val deps = LocalChords.current ?: return ChordState.Idle
    val subject = remember(track.id, track.videoId, track.filePath) { ChordSubject.of(track) }
    val key = subject?.key ?: ""
    val state by remember(key) { deps.engine.state(key) }.collectAsState(initial = deps.engine.current(key))
    val enabled = rememberChordsEnabled()
    LaunchedEffect(key, listen, online, enabled) {
        if (subject == null || !enabled) return@LaunchedEffect
        if (!listen) {
            deps.engine.recall(subject)
            return@LaunchedEffect
        }
        val now = deps.engine.current(key)
        deps.engine.request(subject, force = online && now == ChordState.Failed(ChordState.Reason.OFFLINE))
    }
    // The button turned off, the lyrics closed or another song: a hearing half done stops there.
    DisposableEffect(key, listen && enabled) {
        val listening = listen && enabled
        onDispose { if (listening) deps.engine.stop(key) }
    }
    return if (subject == null) ChordState.Failed(ChordState.Reason.ERROR) else state
}

/**
 * How far [track]'s chords are transposed (semitones) and how to change it; kept per song, the
 * same for heard chords and a written sheet.
 */
@Composable
internal fun rememberChordShift(track: Track): Pair<Int, (Int) -> Unit> {
    val deps = LocalChords.current ?: return 0 to {}
    val key = remember(track.id, track.videoId, track.filePath) { ChordSubject.of(track)?.key }
        ?: return 0 to {}
    val shift by remember(key) { deps.shifts.of(key) }.collectAsState(initial = deps.shifts.current(key))
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return shift to { n: Int -> scope.launch { deps.shifts.set(key, n) }; Unit }
}

/** A chord box: the top of a fretboard with two fingers on it — the "Acordes" button. */
internal val ChordsIcon: ImageVector by lazy {
    ImageVector.Builder("Chords", 24.dp, 24.dp, 24f, 24f).apply {
        val stroke = SolidColor(androidx.compose.ui.graphics.Color.White)
        // Nut and frame.
        path(stroke = stroke, strokeLineWidth = 2.6f, strokeLineCap = StrokeCap.Round) {
            moveTo(5f, 4.5f); lineTo(19f, 4.5f)
        }
        path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
            moveTo(5f, 4.5f); lineTo(5f, 20f)
            moveTo(9.67f, 4.5f); lineTo(9.67f, 20f)
            moveTo(14.33f, 4.5f); lineTo(14.33f, 20f)
            moveTo(19f, 4.5f); lineTo(19f, 20f)
            moveTo(5f, 9.7f); lineTo(19f, 9.7f)
            moveTo(5f, 14.9f); lineTo(19f, 14.9f)
        }
        // Two fingers.
        path(fill = stroke) {
            for ((cx, cy) in listOf(9.67f to 12.3f, 14.33f to 17.5f)) {
                val r = 2.3f
                moveTo(cx - r, cy)
                arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
                arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
                close()
            }
        }
    }.build()
}

/** How the instruments are called on screen. */
internal fun Instrument.label(): Int = when (this) {
    Instrument.VIOLAO -> com.villamediana.sound.R.string.chords_instrument_violao
    Instrument.UKULELE -> com.villamediana.sound.R.string.chords_instrument_ukulele
    Instrument.CAVAQUINHO -> com.villamediana.sound.R.string.chords_instrument_cavaquinho
    Instrument.PIANO -> com.villamediana.sound.R.string.chords_instrument_piano
}
