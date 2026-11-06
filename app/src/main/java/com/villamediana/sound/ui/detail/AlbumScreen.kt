package com.villamediana.sound.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.data.db.AlbumKey
import com.villamediana.sound.data.model.AlbumDetail
import com.villamediana.sound.data.model.AlbumSummary
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.songCount
import com.villamediana.sound.ui.nav.AlbumRoute
import com.villamediana.sound.ui.nav.LocalNavigator
import com.villamediana.sound.ui.sheets.ConfirmDialog
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class AlbumViewModel(artist: String, title: String) : ViewModel() {
    private val key = AlbumKey(artist, title)
    private val library = graph.library

    val album: StateFlow<Load<AlbumDetail>> = library.album(key).asLoad().stateIn(this)

    /** Other albums credited to the same album artist, newest first. */
    val moreByArtist: StateFlow<List<AlbumSummary>> = library.albums.map { all ->
        val artistKey = key.artist.trim().lowercase()
        all.filter { it.key.artist.trim().lowercase() == artistKey && it.key.normalized != key.normalized }
            .sortedByDescending { it.year ?: 0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setPinned(key: AlbumKey, pinned: Boolean) {
        viewModelScope.launch { library.setAlbumPinned(key, pinned) }
    }

    fun delete(ids: List<Long>) {
        graph.scope.launch { library.deleteTracks(ids) }
    }
}

@Composable
fun AlbumScreen(route: AlbumRoute) {
    val vm = viewModel { AlbumViewModel(route.artist, route.title) }
    val load by vm.album.collectAsStateWithLifecycle()
    PopWhenGone(load)
    when (val l = load) {
        Load.Loading -> DetailLoading()
        Load.Missing -> NotFound(Icons.Rounded.Album, stringResource(R.string.detail_album_missing), stringResource(R.string.detail_missing_body))
        is Load.Ready -> AlbumContent(l.value, vm)
    }
}

@Composable
private fun AlbumContent(detail: AlbumDetail, vm: AlbumViewModel) {
    val s = detail.summary
    val tracks = detail.tracks
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val res = context.resources
    val haptic = LocalHapticFeedback.current
    val player = graph.player
    val playback by rememberPlaybackState()
    val more by vm.moreByArtist.collectAsStateWithLifecycle()
    val snackbar = rememberDetailSnackbar()
    val sheet = rememberTrackSheetState()
    val sheets = rememberCollectionSheets()
    var confirmDelete by remember { mutableStateOf(false) }
    var shuffleLocal by rememberSaveable { mutableStateOf(false) }

    val source = Sources.album(s.key.artist, s.key.title, s.title)
    val status = playback.statusOf(source)
    val shuffleOn = if (status.isSource) playback.shuffle else shuffleLocal
    val countText = songCount(tracks.size)
    val wash = rememberWash(s.coverPath, s.accentColor, seed = s.title)

    CollectionScaffold(
        header = HeaderSpec(
            overline = stringResource(R.string.detail_overline_album),
            title = s.title,
            owner = s.artist,
            onOwnerClick = { navigator.openArtist(s.artist) },
            meta = MetaFormat.join(s.year?.toString(), MetaFormat.countAndDuration(countText, s.durationMs)),
            wash = wash,
        ),
        art = { m -> Cover(s.coverPath, m, seed = s.title) },
        snackbar = snackbar,
        playing = status.isPlaying,
        onPlay = { playOrToggle(tracks, source, status, shuffleOn) },
        shuffle = shuffleOn,
        onShuffle = { toggleShuffle(status, shuffleOn) { shuffleLocal = it } },
        actions = {
            HeaderIconButton(
                icon = if (s.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                contentDescription = stringResource(if (s.pinned) R.string.detail_unpin else R.string.detail_pin),
                tint = if (s.pinned) SoundColors.Accent else SoundColors.TextMuted,
                active = s.pinned,
                onClick = {
                    val pin = !s.pinned
                    haptic.performHapticFeedback(if (pin) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    vm.setPinned(s.key, pin)
                    snackbar.feedback.show(res.getString(if (pin) R.string.detail_pinned else R.string.detail_unpinned))
                },
            )
            HeaderIconButton(Icons.Rounded.MoreVert, stringResource(R.string.more_options), onClick = { sheets.menuOpen = true })
        },
        sheets = {
            TrackSheetHost(sheet)
            AddAllHost(sheets)
            if (sheets.menuOpen) {
                CollectionMenuSheet(
                    title = s.title,
                    subtitle = s.artist,
                    art = { m -> Cover(s.coverPath, m, seed = s.title) },
                    items = queueMenuItems(tracks, res, snackbar.feedback) { sheets.addToPlaylist = it } + listOf(
                        MenuItem(Icons.Rounded.Person, res.getString(R.string.detail_menu_go_artist)) { navigator.openArtist(s.artist) },
                        MenuItem(Icons.Rounded.DeleteOutline, res.getString(R.string.detail_menu_delete_album), destructive = true) { confirmDelete = true },
                    ),
                    onDismiss = { sheets.menuOpen = false },
                )
            }
            if (confirmDelete) {
                ConfirmDialog(
                    title = stringResource(R.string.detail_delete_album_title),
                    body = pluralStringResource(R.plurals.detail_delete_album_body, tracks.size, tracks.size, s.title),
                    confirm = stringResource(R.string.delete),
                    onConfirm = {
                        confirmDelete = false
                        vm.delete(tracks.map { it.id })
                        toast(context, res.getString(R.string.detail_album_deleted, s.title))
                    },
                    onDismiss = { confirmDelete = false },
                )
            }
        },
    ) {
        itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { i, t ->
            SongRow(
                track = t,
                playback = playback,
                onClick = { player.play(tracks, i, source, shuffleOn) },
                sheet = sheet,
                number = t.trackNumber ?: (i + 1),
                subtitle = t.artist,
                showDuration = true,
                modifier = Modifier.animateItem(),
            )
        }
        item(key = "footer", contentType = "footer") {
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter).padding(top = 18.dp, bottom = 4.dp)) {
                s.year?.let {
                    Text(it.toString(), style = MaterialTheme.typography.titleSmall, color = SoundColors.Text)
                    Spacer(Modifier.height(2.dp))
                }
                SmallCaption(MetaFormat.countAndDuration(countText, s.durationMs))
            }
        }
        if (more.isNotEmpty()) {
            item(key = "more-title", contentType = "section") {
                DetailSection(
                    title = stringResource(R.string.detail_more_by, s.artist),
                    action = stringResource(R.string.detail_see_artist),
                    onAction = { navigator.openArtist(s.artist) },
                )
            }
            item(key = "more", contentType = "carousel") {
                AlbumCarousel(
                    albums = more,
                    caption = { a -> a.year?.toString() ?: songCount(res, a.trackCount) },
                    onOpen = { a -> navigator.openAlbum(a.key) },
                )
            }
        }
    }
}
