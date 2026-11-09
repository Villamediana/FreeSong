package com.villamediana.sound.ui.detail

import androidx.compose.ui.unit.dp
import com.villamediana.sound.ui.search.toRemoteTrack
import com.villamediana.sound.data.isOnline
import android.content.res.Resources
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import com.villamediana.sound.ui.theme.Dimens
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.playback.SmartSeed
import com.villamediana.sound.smart.Mix
import com.villamediana.sound.smart.MixLabels
import com.villamediana.sound.smart.SmartMixes
import com.villamediana.sound.ui.components.CoverColors
import com.villamediana.sound.ui.components.songCount
import com.villamediana.sound.ui.nav.MixRoute
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Rebuilds the mix once (same algorithm and taste as the Home card) and then keeps its song list
 * fixed while the library changes — likes and edits show up, deleted songs drop out, but the order
 * doesn't reshuffle under your finger.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MixViewModel(private val id: String, private val labels: MixLabels) : ViewModel() {
    val mix: StateFlow<Load<Mix>> = (
        if (id.startsWith(com.villamediana.sound.discover.HomeMixes.PREFIX)) {
            // A card of the Home ("Porque você curte", "Seus gêneros"): the very list of the card;
            // a song downloaded since shows as its library song.
            kotlinx.coroutines.flow.combine(graph.homeMixes.mixes, graph.library.tracks) { mixes, all ->
                val base = mixes[id] ?: return@combine null
                val byVideo = all.filter { it.videoId != null }.associateBy { it.videoId!! }
                base.copy(tracks = base.tracks.map { t -> byVideo[t.videoId] ?: t }.distinctBy { it.videoId ?: "t:${it.id}" })
            }
        } else if (id.startsWith(com.villamediana.sound.smart.MadeForYou.PREFIX)) {
            // "Feitos pra você": the very mix of the card (your songs + new ones). A new song that
            // got downloaded since shows as its library song.
            kotlinx.coroutines.flow.combine(graph.madeForYou.mixes, graph.library.tracks) { mixes, all ->
                val base = mixes.firstOrNull { it.id == id } ?: return@combine null
                val byId = all.associateBy { it.id }
                val byVideo = all.filter { it.videoId != null }.associateBy { it.videoId!! }
                base.copy(
                    tracks = base.tracks.mapNotNull { t ->
                        if (t.isOnline) byVideo[t.videoId] ?: t else byId[t.id]
                    }.distinctBy { it.videoId ?: "t:${it.id}" },
                )
            }.also { graph.madeForYou.refresh() }
        } else {
            flow { emit(buildTaste()) }.flatMapLatest { taste ->
                var frozen: Mix? = null
                graph.library.tracks.map { all ->
                    val base = frozen ?: SmartMixes.byId(id, all, taste, labels)?.also { frozen = it } ?: return@map null
                    val byId = all.associateBy { it.id }
                    base.copy(tracks = base.tracks.mapNotNull { byId[it.id] }.distinctBy { it.id })
                }
            }
        }
        )
        .flowOn(Dispatchers.Default)
        .asLoad()
        .stateIn(this)

    companion object {
        fun create(id: String, res: Resources) = MixViewModel(id, mixLabels(res))
    }
}

@Composable
fun MixScreen(route: MixRoute) {
    val res = LocalContext.current.resources
    val vm = viewModel { MixViewModel.create(route.id, res) }
    val load by vm.mix.collectAsStateWithLifecycle()
    when (val l = load) {
        Load.Loading -> DetailLoading()
        Load.Missing -> NotFound(Icons.Rounded.AutoAwesome, stringResource(R.string.detail_mix_missing), stringResource(R.string.detail_mix_missing_body))
        is Load.Ready -> MixContent(l.value)
    }
}

@Composable
private fun MixContent(mix: Mix) {
    val res = LocalContext.current.resources
    val player = graph.player
    val playback by rememberPlaybackState()
    val snackbar = rememberDetailSnackbar()
    val sheet = rememberTrackSheetState()
    val sheets = rememberCollectionSheets()
    val tracks = mix.tracks
    val likedIds by graph.library.likedVideoIds.collectAsStateWithLifecycle(emptySet())

    val tint = remember(mix.tint) { mix.tint?.let { Color(it) } ?: CoverColors.tune(SoundColors.Accent) }
    val duration = remember(tracks) { tracks.sumOf { it.durationMs } }
    val source = Sources.mix(mix.id, mix.title)
    val status = playback.statusOf(source)
    val countText = songCount(tracks.size)

    CollectionScaffold(
        header = HeaderSpec(
            overline = stringResource(R.string.detail_overline_mix),
            title = mix.title,
            description = mix.subtitle,
            meta = MetaFormat.countAndDuration(countText, duration),
            wash = tint,
        ),
        art = { m ->
            if (mix.kind == com.villamediana.sound.smart.Mix.Kind.MOMENT) {
                // The mix of this moment wears its light and spark here too.
                androidx.compose.foundation.layout.Box(m) {
                    GeneratedArt(mix.covers, tint, mix.title, Modifier.matchParentSize())
                    com.villamediana.sound.ui.home.MomentLight(Modifier.matchParentSize().clip(RoundedCornerShape(Dimens.CoverCorner)))
                    com.villamediana.sound.ui.home.MomentSeal(Modifier.align(Alignment.TopStart).padding(12.dp), size = 36.dp)
                }
            } else {
                GeneratedArt(mix.covers, tint, mix.title, m)
            }
        },
        snackbar = snackbar,
        playing = status.isPlaying,
        loading = status.isLoading,
        onPlay = if (tracks.isEmpty()) null else {
            { if (status.isSource) player.togglePlayPause() else player.play(tracks, 0, source) }
        },
        actions = {
            HeaderIconButton(Icons.Rounded.MoreVert, stringResource(R.string.more_options), onClick = { sheets.menuOpen = true })
        },
        sheets = {
            TrackSheetHost(sheet)
            AddAllHost(sheets)
            if (sheets.menuOpen) {
                CollectionMenuSheet(
                    title = mix.title,
                    subtitle = mix.subtitle,
                    art = { m -> GeneratedArt(mix.covers, tint, "", m) },
                    items = queueMenuItems(tracks, res, snackbar.feedback) { sheets.addToPlaylist = it },
                    onDismiss = { sheets.menuOpen = false },
                )
            }
        },
    ) {
        itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { i, t ->
            // A heart on every song: the new ones you like go to your Músicas (and download with
            // "Curtir já baixa").
            val liked = if (t.isOnline) t.videoId in likedIds else t.liked
            SongRow(
                track = t,
                playback = playback,
                onClick = { player.play(tracks, i, source) },
                sheet = sheet,
                modifier = Modifier.animateItem(),
                trailing = {
                    com.villamediana.sound.ui.player.LikeButton(
                        liked = liked,
                        onToggle = { v ->
                            if (t.isOnline) t.toRemoteTrack()?.let { graph.likeOnline(it, v) } else graph.likeTrack(t.id, v)
                        },
                        size = 40.dp,
                        iconSize = 22.dp,
                    )
                },
                likedMark = false,
            )
        }
    }
}
