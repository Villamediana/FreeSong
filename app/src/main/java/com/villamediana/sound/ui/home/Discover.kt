package com.villamediana.sound.ui.home

import com.villamediana.sound.ui.nav.Chrome
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.PlayArrow
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.online.RemoteTrack
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.villamediana.sound.R
import com.villamediana.sound.discover.GenreCatalog
import com.villamediana.sound.discover.GenreDef
import com.villamediana.sound.download.DownloadSummary
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.components.PlayingBars
import com.villamediana.sound.ui.components.SectionHeader
import com.villamediana.sound.ui.components.keepHorizontalDrags
import com.villamediana.sound.ui.components.SoundWordmark
import com.villamediana.sound.ui.components.pressScale
import com.villamediana.sound.ui.nav.Navigator
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.SoundColors

/** "O que você curte?": genre cards with real song covers, multi-select, then the Home fills itself. */
@Composable
internal fun GenrePicker(
    state: DiscoverUiState,
    downloads: DownloadSummary,
    nav: Navigator,
    onToggle: (String) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = LocalBottomInset.current
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Dimens.Gutter, end = Dimens.Gutter, top = top + 10.dp, bottom = bottom + 76.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(2) }, key = "header") {
                Column {
                    // An intro: just the brand, no settings/downloads buttons.
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                        SoundWordmark()
                    }
                    Text(stringResource(R.string.discover_pick_title), style = MaterialTheme.typography.displaySmall, color = SoundColors.Text)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.discover_pick_subtitle), style = MaterialTheme.typography.bodyLarge, color = SoundColors.TextMuted)
                    Spacer(Modifier.height(10.dp))
                }
            }
            items(GenreCatalog.all, key = { it.id }) { genre ->
                GenreCard(
                    genre = genre,
                    covers = state.covers[genre.id].orEmpty(),
                    selected = genre.id in state.selection,
                    onToggle = { onToggle(genre.id) },
                )
            }
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to SoundColors.Ink.copy(alpha = 0f), 0.35f to SoundColors.Ink.copy(alpha = 0.94f), 1f to SoundColors.Ink))
                .padding(start = Dimens.Gutter, end = Dimens.Gutter, top = 34.dp, bottom = bottom - 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(stringResource(R.string.discover_skip), onSkip, big = true, modifier = Modifier.weight(1f))
            PillButton(
                stringResource(R.string.discover_continue),
                onContinue,
                filled = true,
                big = true,
                enabled = state.selection.isNotEmpty(),
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}

@Composable
private fun GenreCard(genre: GenreDef, covers: List<String>, selected: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val border by animateColorAsState(if (selected) SoundColors.Accent else Color.Transparent, label = "genreBorder")
    val check by animateFloatAsState(if (selected) 1f else 0f, label = "genreCheck")
    val tint = Color(genre.tint)
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.1f)
            .pressScale(interaction, 0.96f)
            .clip(shape)
            .background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.2f), tint)))
            .border(3.dp, border, shape)
            .toggleable(value = selected, interactionSource = interaction, indication = null, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        // One cover per genre: cleaner than a mosaic.
        covers.firstOrNull()?.let { Cover(it, Modifier.fillMaxSize(), seed = genre.id, shape = RoundedCornerShape(0.dp)) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Black.copy(alpha = 0f), 1f to Color.Black.copy(alpha = 0.72f))))
        Text(
            stringResource(genre.label),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
        )
        Box(
            Modifier.align(Alignment.TopEnd).padding(10.dp).size(28.dp).scale(check).clip(CircleShape).background(SoundColors.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, stringResource(R.string.discover_selected), tint = SoundColors.OnAccent, modifier = Modifier.size(18.dp))
        }
    }
}

/** Everything live on the Home: "porque você ouviu", artists, playlists, genre shelves. */
internal fun LazyListScope.discoverSections(
    state: DiscoverUiState,
    playback: PlaybackState,
    onPlay: (tracks: List<RemoteTrack>, index: Int, title: String, id: String) -> Unit,
    onArtist: (String) -> Unit,
    onPlaylist: (RemoteCollection) -> Unit,
    onEdit: () -> Unit,
) {
    if (state.because.isNotEmpty() && state.becauseTitle != null) {
        item(key = "d-because", contentType = "row") {
            val title = stringResource(R.string.discover_because, state.becauseTitle)
            SongRow(title, state.because, playback) { i -> onPlay(state.because, i, title, "because"); Chrome.openPlayer() }
        }
    }
    if (state.artists.isNotEmpty()) {
        item(key = "d-artists", contentType = "row") {
            DiscoverRow(stringResource(R.string.discover_artists)) {
                items(state.artists, key = { "a:" + it.name.lowercase() }) { a -> ArtistBubble(a, onClick = { onArtist(a.name) }) }
            }
        }
    }
    if (state.playlists.isNotEmpty()) {
        item(key = "d-playlists", contentType = "row") {
            DiscoverRow(stringResource(R.string.discover_playlists)) {
                items(state.playlists, key = { it.url }) { c -> PlaylistCard(c, onClick = { onPlaylist(c) }) }
            }
        }
    }
    state.shelves.forEach { shelf ->
        item(key = "shelf:" + shelf.genre.id, contentType = "row") {
            val title = shelfTitle(shelf.genre)
            SongRow(title, shelf.tracks, playback) { i -> onPlay(shelf.tracks, i, title, "genre:" + shelf.genre.id); Chrome.openPlayer() }
        }
    }
    // No genres yet (songs came in before the picker was answered): the way to choose them.
    item(key = "d-edit", contentType = "footer") {
        Box(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
            val label = if (state.favorites.isNotEmpty()) R.string.discover_edit_genres else R.string.discover_pick_genres
            PillButton(stringResource(label), onEdit, icon = Icons.Rounded.Tune)
        }
    }
}

@Composable
private fun DiscoverRow(title: String, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Dimens.SectionGap - 10.dp)) {
        SectionHeader(title)
        LazyRow(modifier = Modifier.keepHorizontalDrags(), contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun SongRow(title: String, tracks: List<RemoteTrack>, playback: PlaybackState, onPlay: (Int) -> Unit) {
    DiscoverRow(title) {
        itemsIndexed(tracks, key = { _, t -> t.videoId }) { i, t -> SongCard(t, playback, onClick = { onPlay(i) }) }
    }
}

@Composable
private fun SongCard(t: RemoteTrack, playback: PlaybackState, onClick: () -> Unit) {
    val current = playback.current?.videoId == t.videoId
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.width(Dimens.CardCover).pressScale(interaction, 0.97f).clickable(interactionSource = interaction, indication = null, onClick = onClick)) {
        Box {
            Cover(com.villamediana.sound.ui.components.rememberSongCover(t), Modifier.size(Dimens.CardCover), seed = t.title, shape = RoundedCornerShape(16.dp))
            if (current) {
                Box(
                    Modifier.size(Dimens.CardCover).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) { PlayingBars(playback.isPlaying, Modifier.size(28.dp), color = Color.White, barWidth = 5.dp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(t.title, style = MaterialTheme.typography.titleSmall, color = if (current) SoundColors.Accent else SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.artist, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ArtistBubble(a: ArtistPick, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.width(108.dp).pressScale(interaction, 0.96f).clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cover(a.imageUrl, Modifier.size(108.dp), seed = a.name, shape = CircleShape)
        Spacer(Modifier.height(8.dp))
        Text(a.name, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlaylistCard(c: RemoteCollection, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.width(Dimens.CardCover).pressScale(interaction, 0.97f).clickable(interactionSource = interaction, indication = null, onClick = onClick)) {
        Cover(c.thumbnailUrl, Modifier.size(Dimens.CardCover), seed = c.title, shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(8.dp))
        Text(c.title, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        c.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Empty library, genres chosen: the Home is the live suggestions. */
@Composable
internal fun DiscoverHome(
    state: DiscoverUiState,
    playback: PlaybackState,
    downloads: DownloadSummary,
    nav: Navigator,
    onPlay: (List<RemoteTrack>, Int, String, String) -> Unit,
    onArtist: (String) -> Unit,
    onPlaylist: (RemoteCollection) -> Unit,
    onEdit: () -> Unit,
) {
    val embedded = LocalHomeEmbedded.current
    val top = if (embedded) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top + 10.dp, bottom = LocalBottomInset.current)) {
        item(key = "header") {
            Column {
                if (!embedded) Row(Modifier.fillMaxWidth().padding(start = Dimens.Gutter, end = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    SoundWordmark(Modifier.weight(1f))
                    HeaderActions(downloads, nav)
                }
                Text(
                    greetingText(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = SoundColors.Text,
                    modifier = Modifier.padding(horizontal = Dimens.Gutter).padding(bottom = 6.dp),
                )
            }
        }
        if (!state.hasContent && state.loading) {
            item(key = "loading") { Box(Modifier.fillMaxWidth().height(240.dp)) }
        }
        discoverSections(state, playback, onPlay, onArtist, onPlaylist, onEdit)
    }
}

@Composable
internal fun shelfTitle(genre: GenreDef): String = stringResource(R.string.discover_for_you, stringResource(genre.label))
