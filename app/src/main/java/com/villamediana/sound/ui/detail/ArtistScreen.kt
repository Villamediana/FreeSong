package com.villamediana.sound.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.model.ArtistDetail
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.playback.SmartSeed
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.components.songCount
import com.villamediana.sound.ui.nav.ArtistRoute
import com.villamediana.sound.ui.nav.LocalNavigator
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.flow.StateFlow

internal class ArtistViewModel(name: String) : ViewModel() {
    val artist: StateFlow<Load<ArtistDetail>> = graph.library.artist(name).asLoad().stateIn(this)
}

private const val TOP_COLLAPSED = 5

@Composable
fun ArtistScreen(route: ArtistRoute) {
    val vm = viewModel { ArtistViewModel(route.name) }
    val load by vm.artist.collectAsStateWithLifecycle()
    PopWhenGone(load)
    when (val l = load) {
        Load.Loading -> DetailLoading()
        Load.Missing -> NotFound(Icons.Rounded.Person, stringResource(R.string.detail_artist_missing), stringResource(R.string.detail_missing_body))
        is Load.Ready -> ArtistContent(l.value)
    }
}

@Composable
private fun ArtistContent(detail: ArtistDetail) {
    val s = detail.summary
    val navigator = LocalNavigator.current
    val res = LocalContext.current.resources
    val player = graph.player
    val playback by rememberPlaybackState()
    val snackbar = rememberDetailSnackbar()
    val sheet = rememberTrackSheetState()
    val sheets = rememberCollectionSheets()
    var shuffleLocal by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    val source = Sources.artist(s.name)
    val status = playback.statusOf(source)
    val shuffleOn = if (status.isSource) playback.shuffle else shuffleLocal
    val playOrder = PlayOrder.artist(detail.topTracks, detail.allTracks)
    val wash = rememberWash(s.coverPath, null, seed = s.name)
    val top = detail.topTracks.take(if (expanded) 10 else TOP_COLLAPSED)
    val single = stringResource(R.string.detail_single)

    val countText = songCount(s.trackCount)
    val albumsText = if (s.albumCount > 0) pluralStringResource(R.plurals.detail_n_albums, s.albumCount, s.albumCount) else null

    CollectionScaffold(
        header = HeaderSpec(
            overline = stringResource(R.string.detail_overline_artist),
            title = s.name,
            meta = MetaFormat.join(countText, albumsText),
            wash = wash,
        ),
        art = { m -> ArtistArt(s.coverPath, s.name, m) },
        artShape = ArtShape.Circle,
        snackbar = snackbar,
        playing = status.isPlaying,
        loading = status.isLoading,
        onPlay = { playOrToggle(playOrder, source, status, shuffleOn) },
        shuffle = shuffleOn,
        onShuffle = { toggleShuffle(status, shuffleOn) { shuffleLocal = it } },
        actions = {
            HeaderIconButton(
                Icons.Rounded.Sensors,
                stringResource(R.string.detail_radio_short),
                onClick = {
                    detail.topTracks.firstOrNull()?.let { seed ->
                        player.playSmart(SmartSeed.Radio(seed))
                        snackbar.feedback.show(res.getString(R.string.detail_artist_radio_started, s.name))
                    }
                },
            )
            HeaderIconButton(Icons.Rounded.MoreVert, stringResource(R.string.more_options), onClick = { sheets.menuOpen = true })
        },
        sheets = {
            TrackSheetHost(sheet)
            AddAllHost(sheets)
            if (sheets.menuOpen) {
                CollectionMenuSheet(
                    title = s.name,
                    subtitle = countText,
                    art = { m -> ArtistArt(s.coverPath, s.name, m) },
                    items = listOf(
                        MenuItem(Icons.Rounded.Sensors, res.getString(R.string.detail_artist_radio)) {
                            detail.topTracks.firstOrNull()?.let {
                                player.playSmart(SmartSeed.Radio(it))
                                snackbar.feedback.show(res.getString(R.string.detail_artist_radio_started, s.name))
                            }
                        },
                    ) + queueMenuItems(playOrder, res, snackbar.feedback) { sheets.addToPlaylist = it },
                    onDismiss = { sheets.menuOpen = false },
                )
            }
        },
    ) {
        if (top.isNotEmpty()) {
            item(key = "top-title", contentType = "section") { DetailSection(stringResource(R.string.detail_popular)) }
            itemsIndexed(top, key = { _, t -> "top-${t.id}" }, contentType = { _, _ -> "ranked" }) { i, t ->
                RankedRow(
                    rank = i + 1,
                    track = t,
                    playback = playback,
                    sheet = sheet,
                    subtitle = t.album?.takeIf { it.isNotBlank() } ?: single,
                    onClick = { player.play(detail.topTracks, i, source, shuffleOn) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (detail.topTracks.size > TOP_COLLAPSED) {
                item(key = "top-more", contentType = "toggle") {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter - 8.dp, vertical = 4.dp)) {
                        Text(
                            stringResource(if (expanded) R.string.detail_show_less else R.string.detail_show_more),
                            style = MaterialTheme.typography.labelLarge,
                            color = SoundColors.TextMuted,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { expanded = !expanded }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        if (detail.albums.isNotEmpty()) {
            item(key = "albums-title", contentType = "section") { DetailSection(stringResource(R.string.detail_albums)) }
            item(key = "albums", contentType = "carousel") {
                AlbumCarousel(
                    albums = detail.albums,
                    caption = { a -> a.year?.toString() ?: songCount(res, a.trackCount) },
                    onOpen = { a -> navigator.openAlbum(a.key) },
                )
            }
        }
        if (detail.singles.isNotEmpty()) {
            item(key = "singles-title", contentType = "section") { DetailSection(stringResource(R.string.detail_singles)) }
            itemsIndexed(detail.singles, key = { _, t -> "single-${t.id}" }, contentType = { _, _ -> "track" }) { i, t ->
                SongRow(
                    track = t,
                    playback = playback,
                    onClick = { player.play(detail.singles, i, source, shuffleOn) },
                    sheet = sheet,
                    subtitle = MetaFormat.join(single, t.year?.toString()),
                    modifier = Modifier.animateItem(),
                )
            }
        }
        item(key = "all-title", contentType = "section") { DetailSection(stringResource(R.string.detail_all_songs)) }
        itemsIndexed(detail.allTracks, key = { _, t -> "all-${t.id}" }, contentType = { _, _ -> "track" }) { i, t ->
            SongRow(
                track = t,
                playback = playback,
                onClick = { player.play(detail.allTracks, i, source, shuffleOn) },
                sheet = sheet,
                subtitle = t.album?.takeIf { it.isNotBlank() } ?: single,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/** "Populares": rank number, then the usual row with cover. */
@Composable
private fun RankedRow(
    rank: Int,
    track: Track,
    playback: PlaybackState,
    sheet: TrackSheetState,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$rank",
            style = MaterialTheme.typography.titleSmall,
            color = if (playback.current?.id == track.id) SoundColors.Accent else SoundColors.TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = Dimens.Gutter - 6.dp).width(22.dp),
        )
        SongRow(
            track = track,
            playback = playback,
            onClick = onClick,
            sheet = sheet,
            subtitle = subtitle,
            contentPadding = PaddingValues(start = 12.dp, end = Dimens.Gutter),
            modifier = Modifier.weight(1f),
        )
    }
}
