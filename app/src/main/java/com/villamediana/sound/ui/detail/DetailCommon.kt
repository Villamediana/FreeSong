package com.villamediana.sound.ui.detail

import com.villamediana.sound.ui.nav.Chrome
import android.content.Context
import android.content.res.Resources
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.villamediana.sound.AppGraph
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.smart.MixLabels
import com.villamediana.sound.smart.Taste
import com.villamediana.sound.ui.components.EmptyState
import com.villamediana.sound.ui.components.SectionHeader
import com.villamediana.sound.ui.components.TrackRow
import com.villamediana.sound.ui.nav.LocalNavigator
import com.villamediana.sound.ui.sheets.AddToPlaylistSheet
import com.villamediana.sound.ui.sheets.PlaylistContext
import com.villamediana.sound.ui.sheets.TrackActionsSheet
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal val graph: AppGraph get() = SoundApplication.graph

/** Screen data: still loading, gone (deleted / renamed away) or ready. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data object Missing : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

internal fun <T : Any> Flow<T?>.asLoad(): Flow<Load<T>> = map { v -> if (v == null) Load.Missing else Load.Ready(v) }

internal fun <T> Flow<Load<T>>.stateIn(vm: ViewModel): StateFlow<Load<T>> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5_000), Load.Loading)

@Composable
internal fun rememberPlaybackState(): State<PlaybackState> = graph.player.state.collectAsStateWithLifecycle()

/** Whether [source] is what the player is on, and whether it is actually sounding. */
/** [isPlaying] counts a song that is loading to play ([isLoading]): the button shows Pause at once. */
internal data class SourceStatus(val isSource: Boolean, val isPlaying: Boolean, val isLoading: Boolean = false)

internal fun PlaybackState.statusOf(source: PlaySource): SourceStatus {
    val mine = Sources.matches(this.source, source)
    return SourceStatus(mine, mine && (isPlaying || isBuffering), mine && isBuffering)
}

/**
 * Pops the screen when what it shows stops existing after having been shown (album emptied by an
 * edit, playlist deleted…). Only while this entry is the resumed one, so a screen that is already
 * leaving never pops a second time.
 */
@Composable
internal fun PopWhenGone(load: Load<*>) {
    val navigator = LocalNavigator.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(load) {
        when (load) {
            is Load.Ready -> seen = true
            Load.Missing -> if (seen && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navigator.back()
            Load.Loading -> Unit
        }
    }
}

/** Empty, quiet "not found" page with the back button. */
@Composable
internal fun NotFound(icon: ImageVector, title: String, body: String) {
    Box(Modifier.fillMaxSize().background(SoundColors.Ink)) {
        EmptyState(icon = icon, title = title, body = body, modifier = Modifier.align(Alignment.Center))
        PlainTopBar()
    }
}

/** While the first emission arrives (usually one frame): ink + back button, no flashing spinner. */
@Composable
internal fun DetailLoading() {
    Box(Modifier.fillMaxSize().background(SoundColors.Ink)) { PlainTopBar() }
}

/** Which song's action sheet is open (and from which playlist row). */
@Stable
internal class TrackSheetState {
    var track by mutableStateOf<Track?>(null)
        private set
    var context by mutableStateOf<PlaylistContext?>(null)
        private set

    fun open(track: Track, context: PlaylistContext? = null) {
        this.context = context
        this.track = track
    }

    fun close() {
        track = null
        context = null
    }
}

@Composable
internal fun rememberTrackSheetState(): TrackSheetState = remember { TrackSheetState() }

@Composable
internal fun TrackSheetHost(state: TrackSheetState) {
    val t = state.track ?: return
    key(t.id, state.context) {
        TrackActionsSheet(track = t, onDismiss = state::close, playlistContext = state.context)
    }
}

/** The collection ⋮ menu and the "add all to playlist" picker it can open. */
@Stable
internal class CollectionSheetsState {
    var menuOpen by mutableStateOf(false)
    var addToPlaylist by mutableStateOf<List<Long>?>(null)
}

@Composable
internal fun rememberCollectionSheets(): CollectionSheetsState = remember { CollectionSheetsState() }

@Composable
internal fun AddAllHost(state: CollectionSheetsState) {
    val ids = state.addToPlaylist ?: return
    AddToPlaylistSheet(trackIds = ids, onDismiss = { state.addToPlaylist = null })
}

/** Long-press on a row: haptic tick, then the sheet. */
internal fun HapticFeedback.longPress() = performHapticFeedback(HapticFeedbackType.LongPress)

@Composable
internal fun SmallCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint, modifier = modifier)
}

// ------------------------------------------------------------------ playback helpers

/**
 * Big play button of a collection: pauses/resumes when this collection is already the source,
 * otherwise starts it (from a random song when shuffle is on).
 */
internal fun playOrToggle(tracks: List<Track>, source: PlaySource, status: SourceStatus, shuffle: Boolean) {
    val player = graph.player
    if (status.isSource) {
        player.togglePlayPause()
        return
    }
    if (tracks.isEmpty()) return
    val start = if (shuffle) PlayOrder.shuffleStart(tracks.size, System.nanoTime()) else 0
    player.play(tracks, start, source, shuffle)
}

/** Shuffle toggle: drives the player when this collection is playing, else just the next Play. */
internal fun toggleShuffle(status: SourceStatus, current: Boolean, setLocal: (Boolean) -> Unit) {
    if (status.isSource) graph.player.setShuffle(!current) else setLocal(!current)
}

/** Library taste for the smart mixes: plays of the last 30 days, songs heard in the last 3 hours. */
internal suspend fun buildTaste(now: Long = System.currentTimeMillis()): Taste {
    val library = graph.library
    val recent = library.playCountsSince(now - 30L * 24 * 3_600_000)
    val justPlayed = library.recentEvents(200).filter { it.playedAt >= now - 3 * 3_600_000L }.mapTo(HashSet()) { it.trackId }
    return Taste(recentPlays = recent, justPlayed = justPlayed, now = now)
}

/** Localized mix titles (same ids as the Home cards). */
internal fun mixLabels(res: Resources) = com.villamediana.sound.smart.soundMixLabels(res)

/** For confirmations of actions that close the screen (its snackbar leaves with it). */
internal fun toast(context: Context, message: String) {
    Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
}

// ------------------------------------------------------------------ rows

internal fun defaultSubtitle(track: Track): String =
    listOfNotNull(track.artist, track.album?.takeIf { it.isNotBlank() }).joinToString(MetaFormat.SEP)

/** TrackRow wired to the player highlight, the long-press haptic and the actions sheet. */
@Composable
internal fun SongRow(
    track: Track,
    playback: PlaybackState,
    onClick: () -> Unit,
    sheet: TrackSheetState,
    modifier: Modifier = Modifier,
    context: PlaylistContext? = null,
    number: Int? = null,
    subtitle: String = defaultSubtitle(track),
    showDuration: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = Dimens.Gutter),
) {
    val haptic = LocalHapticFeedback.current
    TrackRow(
        track = track,
        onClick = {
            onClick()
            Chrome.openPlayer()
        },
        modifier = modifier,
        onLongClick = {
            haptic.longPress()
            sheet.open(track, context)
        },
        onMore = { sheet.open(track, context) },
        isCurrent = playback.current?.id == track.id,
        isPlaying = playback.isPlaying,
        number = number,
        subtitle = subtitle,
        showDuration = showDuration,
        contentPadding = contentPadding,
    )
}

/** Section title with the standard 28 dp breathing room above it. */
@Composable
internal fun DetailSection(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column {
        Spacer(Modifier.height(Dimens.SectionGap - 8.dp))
        SectionHeader(title = title, action = action, onAction = onAction)
    }
}
