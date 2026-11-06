package com.villamediana.sound.ui.detail

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.playback.SmartSeed
import com.villamediana.sound.ui.nav.GenreRoute
import com.villamediana.sound.ui.components.songCount
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

internal class GenreViewModel(name: String) : ViewModel() {
    val tracks: StateFlow<Load<List<Track>>> =
        graph.library.genreTracks(name).map { it.ifEmpty { null } }.asLoad().stateIn(this)
}

@Composable
fun GenreScreen(route: GenreRoute) {
    val vm = viewModel { GenreViewModel(route.name) }
    val load by vm.tracks.collectAsStateWithLifecycle()
    PopWhenGone(load)
    when (val l = load) {
        Load.Loading -> DetailLoading()
        Load.Missing -> NotFound(Icons.Rounded.Category, stringResource(R.string.detail_genre_missing), stringResource(R.string.detail_missing_body))
        is Load.Ready -> GenreContent(route.name, l.value)
    }
}

@Composable
private fun GenreContent(routeName: String, tracks: List<Track>) {
    val res = LocalContext.current.resources
    val player = graph.player
    val playback by rememberPlaybackState()
    val snackbar = rememberDetailSnackbar()
    val sheet = rememberTrackSheetState()
    val sheets = rememberCollectionSheets()

    // The spelling most songs use ("Rock" even if the route said "rock").
    val name = remember(tracks, routeName) {
        tracks.mapNotNull { it.genre?.trim() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: routeName
    }
    val covers = remember(tracks) { topCovers(tracks) }
    val tint = remember(tracks, name) { artTint(tracks, name) }
    val artists = remember(tracks) { MetaFormat.topArtists(tracks) }
    val duration = remember(tracks) { tracks.sumOf { it.durationMs } }
    val source = Sources.genre(name)
    val status = playback.statusOf(source)
    val countText = songCount(tracks.size)
    val artistsText = when {
        artists.isEmpty() -> null
        artists.size == 1 -> artists.first()
        else -> stringResource(R.string.detail_artists_and_more, artists.joinToString(", "))
    }

    CollectionScaffold(
        header = HeaderSpec(
            overline = stringResource(R.string.detail_overline_genre),
            title = name,
            meta = MetaFormat.join(artistsText, MetaFormat.countAndDuration(countText, duration)),
            wash = tint,
        ),
        art = { m -> GeneratedArt(covers, tint, name, m) },
        snackbar = snackbar,
        playing = status.isPlaying,
        // Play = the smart genre session (genre smart-shuffled, flowing into neighbours).
        onPlay = { if (status.isSource) player.togglePlayPause() else player.playSmart(SmartSeed.Genre(name)) },
        actions = {
            HeaderIconButton(Icons.Rounded.MoreVert, stringResource(R.string.more_options), onClick = { sheets.menuOpen = true })
        },
        sheets = {
            TrackSheetHost(sheet)
            AddAllHost(sheets)
            if (sheets.menuOpen) {
                CollectionMenuSheet(
                    title = name,
                    subtitle = countText,
                    art = { m -> GeneratedArt(covers, tint, "", m) },
                    items = queueMenuItems(tracks, res, snackbar.feedback) { sheets.addToPlaylist = it },
                    onDismiss = { sheets.menuOpen = false },
                )
            }
        },
    ) {
        itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { i, t ->
            SongRow(
                track = t,
                playback = playback,
                onClick = { player.play(tracks, i, source) },
                sheet = sheet,
                modifier = Modifier.animateItem(),
            )
        }
    }
}
