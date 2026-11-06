package com.villamediana.surco.ui.detail

import android.content.res.Resources
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.surco.R
import com.villamediana.surco.playback.SmartSeed
import com.villamediana.surco.smart.Mix
import com.villamediana.surco.smart.MixLabels
import com.villamediana.surco.smart.SmartMixes
import com.villamediana.surco.ui.components.CoverColors
import com.villamediana.surco.ui.nav.MixRoute
import com.villamediana.surco.ui.theme.SurcoColors
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
    val mix: StateFlow<Load<Mix>> = flow { emit(buildTaste()) }
        .flatMapLatest { taste ->
            var frozen: Mix? = null
            graph.library.tracks.map { all ->
                val base = frozen ?: SmartMixes.byId(id, all, taste, labels)?.also { frozen = it } ?: return@map null
                val byId = all.associateBy { it.id }
                base.copy(tracks = base.tracks.mapNotNull { byId[it.id] }.distinctBy { it.id })
            }
        }
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

    val tint = remember(mix.tint) { mix.tint?.let { Color(it) } ?: CoverColors.tune(SurcoColors.Accent) }
    val duration = remember(tracks) { tracks.sumOf { it.durationMs } }
    val source = Sources.mix(mix.id, mix.title)
    val status = playback.statusOf(source)
    val countText = pluralStringResource(R.plurals.n_songs, tracks.size, tracks.size)

    CollectionScaffold(
        header = HeaderSpec(
            overline = stringResource(R.string.detail_overline_mix),
            title = mix.title,
            description = mix.subtitle,
            meta = MetaFormat.countAndDuration(countText, duration),
            wash = tint,
        ),
        art = { m -> GeneratedArt(mix.covers, tint, mix.title, m) },
        snackbar = snackbar,
        playing = status.isPlaying,
        onPlay = if (tracks.isEmpty()) null else {
            { if (status.isSource) player.togglePlayPause() else player.playSmart(SmartSeed.Mix(mix.id)) }
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
