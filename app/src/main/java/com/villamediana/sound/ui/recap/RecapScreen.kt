package com.villamediana.sound.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.stats.Recap
import com.villamediana.sound.stats.RecapArtist
import com.villamediana.sound.stats.RecapSong
import com.villamediana.sound.stats.Recaps
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.EmptyState
import com.villamediana.sound.ui.components.PlayingBars
import com.villamediana.sound.ui.components.SectionHeader
import com.villamediana.sound.ui.components.formatLongDuration
import com.villamediana.sound.ui.nav.Chrome
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.format.TextStyle
import java.util.Locale

class RecapViewModel(val year: Int) : ViewModel() {
    private val graph = SoundApplication.graph

    val recap: StateFlow<Recap?> = run {
        val since = Recaps.span(year).first
        combine(graph.library.playEventsSince(since), graph.library.tracks, graph.library.onlinePlaysSince(since)) { events, tracks, online ->
            Recaps.build(year, events, tracks.associateBy { it.id }, online)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Plays the ranking from [index]: library songs from the phone, the others online. */
    fun play(songs: List<RecapSong>, index: Int, title: String) {
        val tracks = songs.mapNotNull { it.track ?: it.remote?.asTransientTrack() }
        if (tracks.isEmpty()) return
        graph.player.play(tracks, index.coerceIn(0, tracks.lastIndex), PlaySource(PlaySource.Type.SONGS, title, "recap"))
        Chrome.openPlayer()
    }
}

@Composable
fun RecapScreen(year: Int) {
    val vm: RecapViewModel = viewModel(key = "recap:$year") { RecapViewModel(year) }
    val recap by vm.recap.collectAsStateWithLifecycle()
    val playback by SoundApplication.graph.player.state.collectAsStateWithLifecycle()
    val nav = navigator()
    val title = stringResource(R.string.recap_title_year, year)

    LazyColumn(
        Modifier.fillMaxSize().background(SoundColors.Ink),
        contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp),
    ) {
        item(key = "top") {
            Column {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = nav::back) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SoundColors.Text)
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.displaySmall,
                    color = SoundColors.Text,
                    modifier = Modifier.padding(horizontal = Dimens.Gutter),
                )
                Spacer(Modifier.height(20.dp))
            }
        }

        val r = recap
        if (r != null) {
            if (r.empty) {
                item(key = "empty") {
                    EmptyState(Icons.Outlined.Insights, stringResource(R.string.recap_empty_title), stringResource(R.string.recap_empty_body))
                }
            } else {
                item(key = "hero") { Hero(r) }
                item(key = "facts") { Facts(r) }
                if (r.topSongs.isNotEmpty()) {
                    item(key = "songs-title") {
                        Column { Spacer(Modifier.height(Dimens.SectionGap - 6.dp)); SectionHeader(stringResource(R.string.recap_top_songs)) }
                    }
                    itemsIndexed(r.topSongs, key = { _, s -> "s:" + s.key }) { i, s ->
                        val current = playback.current?.let { c -> (c.videoId ?: "t:${c.id}") == s.key } == true
                        SongLine(i + 1, s, current, playback.isPlaying, onClick = { vm.play(r.topSongs, i, title) })
                    }
                }
                if (r.topArtists.isNotEmpty()) {
                    item(key = "artists-title") {
                        Column { Spacer(Modifier.height(Dimens.SectionGap - 6.dp)); SectionHeader(stringResource(R.string.recap_top_artists)) }
                    }
                    itemsIndexed(r.topArtists, key = { _, a -> "a:" + a.name.lowercase() }) { i, a ->
                        ArtistLine(i + 1, a, onClick = { nav.openArtist(a.name) })
                    }
                }
            }
        }
    }
}

/** The big number: how long you listened, and to how many songs and artists. */
@Composable
private fun Hero(r: Recap) {
    val accent = SoundColors.Accent
    val deep = lerp(accent, Color.Black, if (SoundColors.isLight) 0.35f else 0.55f)
    val on = Color.White
    Column(
        Modifier
            .padding(horizontal = Dimens.Gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(accent, deep)))
            .padding(horizontal = 24.dp, vertical = 26.dp),
    ) {
        Text(stringResource(R.string.recap_hero_label), style = MaterialTheme.typography.labelLarge, color = on.copy(alpha = 0.8f))
        Spacer(Modifier.height(10.dp))
        Text(
            formatLongDuration(r.listenedMs),
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = on,
            maxLines = 1,
        )
        Text(stringResource(R.string.recap_listening), style = MaterialTheme.typography.titleMedium, color = on.copy(alpha = 0.85f))
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            HeroCount(r.songs, pluralStringResource(R.plurals.recap_songs, r.songs), on)
            HeroCount(r.artists, pluralStringResource(R.plurals.recap_artists, r.artists), on)
            HeroCount(r.plays, pluralStringResource(R.plurals.recap_plays, r.plays), on)
        }
    }
}

@Composable
private fun HeroCount(n: Int, label: String, color: Color) {
    Column {
        Text("$n", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = color.copy(alpha = 0.8f))
    }
}

/** Favorite hour, favorite day, genre of the period. */
@Composable
private fun Facts(r: Recap) {
    val facts = buildList {
        r.favoriteHour?.let { add(stringResource(R.string.recap_fact_hour) to stringResource(R.string.recap_hour, it)) }
        r.favoriteDay?.let {
            add(stringResource(R.string.recap_fact_day) to it.getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { c -> c.titlecase(Locale.getDefault()) })
        }
        r.topGenre?.let { add(stringResource(R.string.recap_fact_genre) to it) }
    }
    if (facts.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.Gutter).padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        facts.forEach { (label, value) ->
            Column(
                Modifier
                    .widthIn(min = 112.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SoundColors.Surface1)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = SoundColors.TextMuted, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text(value, style = MaterialTheme.typography.titleMedium, color = SoundColors.Text, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Rank(n: Int) {
    Text(
        "$n",
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        color = if (n == 1) SoundColors.Accent else SoundColors.TextMuted,
        modifier = Modifier.width(30.dp),
    )
}

@Composable
private fun SongLine(rank: Int, s: RecapSong, current: Boolean, playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Dimens.Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rank(rank)
        Box {
            Cover(s.cover, Modifier.size(52.dp), seed = s.title)
            if (current) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(Dimens.CoverCorner)).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    PlayingBars(playing, Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.titleMedium, color = if (current) SoundColors.Accent else SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.artist, style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text(pluralStringResource(R.plurals.recap_times, s.plays, s.plays), style = MaterialTheme.typography.labelLarge, color = SoundColors.TextMuted)
    }
}

@Composable
private fun ArtistLine(rank: Int, a: RecapArtist, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Dimens.Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rank(rank)
        Cover(a.cover, Modifier.size(52.dp), seed = a.name, shape = CircleShape)
        Spacer(Modifier.width(14.dp))
        Text(a.name, style = MaterialTheme.typography.titleMedium, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(formatLongDuration(a.listenedMs), style = MaterialTheme.typography.labelLarge, color = SoundColors.TextMuted)
    }
}

/** The Início's way in, all through January: the year that ended. */
@Composable
fun RecapEntry(year: Int, listenedMs: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Gutter)
            .clip(RoundedCornerShape(20.dp))
            .background(SoundColors.Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(SoundColors.AccentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Insights, null, tint = SoundColors.Accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.recap_title_year, year), style = MaterialTheme.typography.titleMedium, color = SoundColors.Text)
            Text(
                stringResource(R.string.recap_entry_sub, formatLongDuration(listenedMs)),
                style = MaterialTheme.typography.bodyMedium,
                color = SoundColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
