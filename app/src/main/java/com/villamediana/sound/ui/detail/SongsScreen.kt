package com.villamediana.sound.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.model.TrackSort
import com.villamediana.sound.ui.components.EmptyState
import com.villamediana.sound.ui.components.pressScale
import com.villamediana.sound.ui.components.songCount
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

/** Sorted list plus the sort it was sorted by (so labels never mix two orders). */
internal data class SortedSongs(val sort: TrackSort, val tracks: List<Track>)

@OptIn(ExperimentalCoroutinesApi::class)
internal class SongsViewModel(private val saved: SavedStateHandle) : ViewModel() {
    val sort: StateFlow<TrackSort> = saved.getStateFlow(KEY_SORT, TrackSort.TITLE.name)
        .map { runCatching { TrackSort.valueOf(it) }.getOrDefault(TrackSort.TITLE) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TrackSort.TITLE)

    val songs: StateFlow<Load<SortedSongs>> = sort
        .flatMapLatest { s -> graph.library.sortedTracks(s).map { Load.Ready(SortedSongs(s, it)) } }
        .stateIn(this)

    /** Newest song — its cover tints the header. */
    val newest: StateFlow<Track?> = graph.library.recentlyAdded(1).map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setSort(sort: TrackSort) {
        saved[KEY_SORT] = sort.name
    }

    private companion object {
        const val KEY_SORT = "sort"
    }
}

@Composable
fun SongsScreen() {
    val vm = viewModel { SongsViewModel(createSavedStateHandle()) }
    val load by vm.songs.collectAsStateWithLifecycle()
    when (val l = load) {
        is Load.Ready -> SongsContent(l.value, vm)
        else -> DetailLoading()
    }
}

@Composable
private fun SongsContent(songs: SortedSongs, vm: SongsViewModel) {
    val res = LocalContext.current.resources
    val player = graph.player
    val playback by rememberPlaybackState()
    val newest by vm.newest.collectAsStateWithLifecycle()
    val snackbar = rememberDetailSnackbar()
    val sheet = rememberTrackSheetState()
    val sheets = rememberCollectionSheets()
    val listState = rememberLazyListState()
    var shuffleLocal by rememberSaveable { mutableStateOf(false) }
    val locale: Locale = ConfigurationCompat.getLocales(LocalConfiguration.current).get(0) ?: Locale.getDefault()

    val tracks = songs.tracks
    val title = stringResource(R.string.detail_songs_title)
    val source = Sources.songs(title)
    val status = playback.statusOf(source)
    val shuffleOn = if (status.isSource) playback.shuffle else shuffleLocal
    val duration = remember(tracks) { tracks.sumOf { it.durationMs } }
    val countText = songCount(tracks.size)
    val wash = rememberWash(newest?.coverPath, newest?.accentColor, seed = newest?.let { it.album ?: it.title } ?: title)

    // New sort → back to the top so the first letter is what you see.
    var lastSort by remember { mutableStateOf(songs.sort) }
    LaunchedEffect(songs.sort) {
        if (songs.sort != lastSort) {
            lastSort = songs.sort
            listState.scrollToItem(0)
        }
    }

    CollectionScaffold(
        header = HeaderSpec(
            overline = stringResource(R.string.detail_overline_library),
            title = title,
            meta = MetaFormat.countAndDuration(countText, duration),
            wash = wash,
        ),
        art = null,
        listState = listState,
        snackbar = snackbar,
        playing = status.isPlaying,
        loading = status.isLoading,
        onPlay = if (tracks.isEmpty()) null else ({ playOrToggle(tracks, source, status, shuffleOn) }),
        shuffle = if (tracks.isEmpty()) null else shuffleOn,
        onShuffle = { toggleShuffle(status, shuffleOn) { shuffleLocal = it } },
        actions = {
            Spacer(Modifier.width(10.dp))
            SortButton(songs.sort, vm::setSort)
            if (tracks.isNotEmpty()) {
                HeaderIconButton(Icons.Rounded.MoreVert, stringResource(R.string.more_options), onClick = { sheets.menuOpen = true })
            }
        },
        overlay = {
            FastScroller(
                listState = listState,
                firstItemIndex = 1,
                itemCount = tracks.size,
                label = { i -> tracks.getOrNull(i)?.let { ScrollIndex.label(it, songs.sort, locale) } ?: "" },
                topPadding = collectionTopInset(),
                bottomPadding = LocalBottomInset.current,
            )
        },
        sheets = {
            TrackSheetHost(sheet)
            AddAllHost(sheets)
            if (sheets.menuOpen) {
                CollectionMenuSheet(
                    title = title,
                    subtitle = countText,
                    art = { m ->
                        Box(m.clip(RoundedCornerShape(6.dp)).background(SoundColors.Surface3), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.LibraryMusic, null, tint = SoundColors.TextMuted)
                        }
                    },
                    items = queueMenuItems(tracks, res, snackbar.feedback) { sheets.addToPlaylist = it },
                    onDismiss = { sheets.menuOpen = false },
                )
            }
        },
    ) {
        if (tracks.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptyState(
                    icon = Icons.Rounded.LibraryMusic,
                    title = stringResource(R.string.detail_songs_empty_title),
                    body = stringResource(R.string.detail_songs_empty_body),
                )
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { i, t ->
            SongRow(
                track = t,
                playback = playback,
                onClick = { player.play(tracks, i, source, shuffleOn) },
                sheet = sheet,
                showDuration = songs.sort == TrackSort.DURATION,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

private val sortLabels = listOf(
    TrackSort.TITLE to R.string.detail_sort_title,
    TrackSort.ARTIST to R.string.detail_sort_artist,
    TrackSort.ALBUM to R.string.detail_sort_album,
    TrackSort.RECENT to R.string.detail_sort_recent,
    TrackSort.MOST_PLAYED to R.string.detail_sort_most_played,
    TrackSort.DURATION to R.string.detail_sort_duration,
)

@Composable
private fun SortButton(sort: TrackSort, onSort: (TrackSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Box {
        Row(
            Modifier
                .height(36.dp)
                .pressScale(interaction, 0.96f)
                .clip(CircleShape)
                .background(SoundColors.Surface2.copy(alpha = 0.9f))
                .clickable(interactionSource = interaction, indication = null) { open = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.Sort, null, tint = SoundColors.TextMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(sortLabels.first { it.first == sort }.second),
                style = MaterialTheme.typography.labelLarge,
                color = SoundColors.Text,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = SoundColors.Surface2,
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                stringResource(R.string.detail_sort_by),
                style = MaterialTheme.typography.labelSmall,
                color = SoundColors.TextFaint,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            sortLabels.forEach { (value, label) ->
                val selected = value == sort
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(label),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) SoundColors.Accent else SoundColors.Text,
                        )
                    },
                    trailingIcon = if (selected) ({ Icon(Icons.Rounded.Check, null, tint = SoundColors.Accent) }) else null,
                    onClick = {
                        open = false
                        onSort(value)
                    },
                    modifier = Modifier.semantics { this.selected = selected },
                    colors = MenuDefaults.itemColors(),
                )
            }
        }
    }
}
