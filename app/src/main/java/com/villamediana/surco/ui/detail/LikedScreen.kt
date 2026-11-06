package com.villamediana.surco.ui.detail

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.surco.R
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.ui.components.EmptyState
import com.villamediana.surco.ui.components.LikedArt
import com.villamediana.surco.ui.theme.SurcoColors
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

internal class LikedViewModel : ViewModel() {
    /** Newest liked first (the repository's order). Never "missing": empty is a valid state. */
    val liked: StateFlow<Load<List<Track>>> = graph.library.liked.map { Load.Ready(it) }.stateIn(this)
}

@Composable
fun LikedScreen() {
    val vm = viewModel { LikedViewModel() }
    val load by vm.liked.collectAsStateWithLifecycle()
    when (val l = load) {
        is Load.Ready -> LikedContent(l.value)
        else -> DetailLoading()
    }
}

@Composable
private fun LikedContent(tracks: List<Track>) {
    val res = LocalContext.current.resources
    val player = graph.player
    val playback by rememberPlaybackState()
    val snackbar = rememberDetailSnackbar()
    val sheet = rememberTrackSheetState()
    val sheets = rememberCollectionSheets()
    var shuffleLocal by rememberSaveable { mutableStateOf(false) }

    val title = stringResource(R.string.detail_liked_title)
    val source = Sources.liked(title)
    val status = playback.statusOf(source)
    val shuffleOn = if (status.isSource) playback.shuffle else shuffleLocal
    val duration = remember(tracks) { tracks.sumOf { it.durationMs } }
    val countText = pluralStringResource(R.plurals.n_songs, tracks.size, tracks.size)

    CollectionScaffold(
        header = HeaderSpec(
            overline = stringResource(R.string.detail_overline_playlist),
            title = title,
            meta = MetaFormat.countAndDuration(countText, duration),
            wash = SurcoColors.Accent,
        ),
        art = { m -> LikedArt(m) },
        snackbar = snackbar,
        playing = status.isPlaying,
        onPlay = if (tracks.isEmpty()) null else ({ playOrToggle(tracks, source, status, shuffleOn) }),
        shuffle = if (tracks.isEmpty()) null else shuffleOn,
        onShuffle = { toggleShuffle(status, shuffleOn) { shuffleLocal = it } },
        actions = {
            if (tracks.isNotEmpty()) {
                HeaderIconButton(Icons.Rounded.MoreVert, stringResource(R.string.more_options), onClick = { sheets.menuOpen = true })
            }
        },
        sheets = {
            TrackSheetHost(sheet)
            AddAllHost(sheets)
            if (sheets.menuOpen) {
                CollectionMenuSheet(
                    title = title,
                    subtitle = countText,
                    art = { m -> LikedArt(m) },
                    items = queueMenuItems(tracks, res, snackbar.feedback) { sheets.addToPlaylist = it },
                    onDismiss = { sheets.menuOpen = false },
                )
            }
        },
    ) {
        if (tracks.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptyState(
                    icon = Icons.Rounded.FavoriteBorder,
                    title = stringResource(R.string.detail_liked_empty_title),
                    body = stringResource(R.string.detail_liked_empty_body),
                )
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { i, t ->
            SongRow(
                track = t,
                playback = playback,
                onClick = { player.play(tracks, i, source, shuffleOn) },
                sheet = sheet,
                modifier = Modifier.animateItem(),
            )
        }
    }
}
