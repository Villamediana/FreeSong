package com.villamediana.sound.ui.home

import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.ui.nav.searchRevealOrigin
import com.villamediana.sound.ui.nav.Chrome
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.download.DownloadSummary
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.components.PlayPauseButton
import com.villamediana.sound.ui.components.SectionHeader
import com.villamediana.sound.ui.components.keepHorizontalDrags
import com.villamediana.sound.ui.components.SoundMark
import com.villamediana.sound.ui.components.SoundWordmark
import com.villamediana.sound.ui.library.CollectionMenuSheet
import com.villamediana.sound.ui.library.CollectionTarget
import com.villamediana.sound.ui.nav.Navigator
import com.villamediana.sound.ui.nav.SearchRoute
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.sheets.TrackActionsSheet
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.delay
import java.time.LocalTime

private enum class HomePhase { LOADING, EMPTY, PICKER, DISCOVER, FEED }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val vm: HomeViewModel = viewModel()
    val discoverVm: DiscoverViewModel = viewModel()
    val discover by discoverVm.state.collectAsStateWithLifecycle()
    // Every time the Home shows again (back from another screen, the app back from the background):
    // what failed is asked again and what changed since (songs heard, a new day) comes in.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        discoverVm.onShown()
        vm.refreshMixes()
        onPauseOrDispose { }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val recap by vm.recap.collectAsStateWithLifecycle()
    val nav = navigator()

    // Staggered entrance only on the first load; items composed later (scrolling, returning) just appear.
    var intro by remember { mutableStateOf(!vm.introPlayed) }
    LaunchedEffect(state.loaded) {
        if (state.loaded && intro) {
            delay(1_200)
            vm.introPlayed = true
            intro = false
        }
    }

    var songSheet by remember { mutableStateOf<Track?>(null) }

    val phase = when {
        !state.loaded || !discover.ready -> HomePhase.LOADING
        discover.showPicker && (state.empty || discover.editing) -> HomePhase.PICKER
        // Nothing downloaded: the live suggestions (from the picked genres, or from what was streamed).
        state.empty && (discover.favorites.isNotEmpty() || discover.hasContent) -> HomePhase.DISCOVER
        state.empty -> HomePhase.EMPTY
        else -> HomePhase.FEED
    }
    val playDiscover: (List<com.villamediana.sound.online.RemoteTrack>, Int, String, String) -> Unit = { tracks, i, title, id -> discoverVm.play(tracks, i, title, id) }
    // Holding a song of the Home: its actions (queue, playlist, like…), as a streamed song.
    val songMenu: (com.villamediana.sound.online.RemoteTrack) -> Unit = { songSheet = it.asTransientTrack() }
    // A mix card ("Porque você curte", "Seus gêneros"): the tap opens its list, the button plays it.
    val openMix: (com.villamediana.sound.smart.Mix) -> Unit = { nav.openMix(it.id) }
    val playMix: (com.villamediana.sound.smart.Mix) -> Unit = discoverVm::playMix
    // The genre picker is an intro: no mini player over it. Back to normal when it goes (or Home does).
    val picking = phase == HomePhase.PICKER
    DisposableEffect(picking) {
        Chrome.hideMiniPlayer = picking
        onDispose { Chrome.hideMiniPlayer = false }
    }

    // Pull down to refresh: new searches and new draws of the mixes.
    val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    val pullTop = if (LocalHomeEmbedded.current) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = discover.refreshing,
        onRefresh = discoverVm::refresh,
        state = pull,
        modifier = Modifier.fillMaxSize().background(SoundColors.Ink),
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                state = pull,
                isRefreshing = discover.refreshing,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = pullTop),
                containerColor = SoundColors.Surface2,
                color = SoundColors.Accent,
            )
        },
    ) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(180)) },
            label = "homePhase",
        ) { p ->
            when (p) {
                HomePhase.LOADING -> Box(Modifier.fillMaxSize())
                HomePhase.EMPTY -> EmptyHome(downloads, nav, intro)
                HomePhase.PICKER -> GenrePicker(
                    state = discover,
                    downloads = downloads,
                    nav = nav,
                    onToggle = discoverVm::toggle,
                    onContinue = discoverVm::confirm,
                    onSkip = discoverVm::skip,
                )
                HomePhase.DISCOVER -> DiscoverHome(discover, playback, downloads, nav, onPlay = playDiscover, onEdit = discoverVm::edit, onSongMenu = songMenu, onOpenMix = openMix, onPlayMix = playMix)
                HomePhase.FEED -> HomeFeed(
                    state = state,
                    playback = playback,
                    downloads = downloads,
                    vm = vm,
                    nav = nav,
                    intro = intro,
                    recap = recap,
                    extra = { discoverSections(discover, playback, playDiscover, discoverVm::edit, songMenu, openMix, playMix) },
                )
            }
        }
    }

    songSheet?.let { TrackActionsSheet(track = it, onDismiss = { songSheet = null }) }
}

/** Current hour, refreshed when the clock turns. */
@Composable
private fun rememberHour(): Int {
    val hour by produceState(initialValue = LocalTime.now().hour) {
        while (true) {
            val now = LocalTime.now()
            val toNextHour = ((59 - now.minute) * 60L + (60 - now.second)) * 1_000L
            delay(toNextHour.coerceAtLeast(1_000L))
            value = LocalTime.now().hour
        }
    }
    return hour
}

@Composable
internal fun greetingText(): String = stringResource(
    when (greetingFor(rememberHour())) {
        Greeting.MORNING -> R.string.home_greeting_morning
        Greeting.AFTERNOON -> R.string.home_greeting_afternoon
        Greeting.NIGHT -> R.string.home_greeting_night
    },
)

@Composable
internal fun HeaderActions(downloads: DownloadSummary, nav: Navigator) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DownloadsIndicator(downloads, onClick = { nav.openDownloads() })
        com.villamediana.sound.ui.social.SocialButton(onClick = { nav.openSocial() })
        IconButton(onClick = { nav.openSettings() }) {
            Icon(Icons.Outlined.Settings, stringResource(R.string.home_settings), tint = SoundColors.Text, modifier = Modifier.size(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------- feed

/** Inside the main screen (which has its own header): no brand row, no status-bar inset. */
val LocalHomeEmbedded = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
private fun HomeFeed(
    state: HomeUiState,
    playback: PlaybackState,
    downloads: DownloadSummary,
    vm: HomeViewModel,
    nav: Navigator,
    intro: Boolean,
    extra: LazyListScope.() -> Unit = {},
    /** January: last year's retrospective (year, time listened). */
    recap: Pair<Int, Long>? = null,
) {
    val embedded = LocalHomeEmbedded.current
    val top = if (embedded) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = top + 10.dp, bottom = LocalBottomInset.current),
    ) {
        var order = 0

        val headerIndex = order++
        item(key = "header", contentType = "header") {
          Column(Modifier.animateItem().staggerIn(headerIndex, intro).fillMaxWidth()) {
            if (!embedded) Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Dimens.Gutter, end = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SoundWordmark(Modifier.weight(1f))
                HeaderActions(downloads, nav)
            }
            Text(
                greetingText(),
                style = MaterialTheme.typography.headlineMedium,
                color = SoundColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = Dimens.Gutter, end = Dimens.Gutter, bottom = 14.dp),
            )
          }
        }

        // "Pra agora": the DNA's mix for this moment, right above your songs.
        val moment = state.mixes.firstOrNull { it.id == com.villamediana.sound.smart.MadeForYou.MOMENT_ID }
        if (moment != null) {
            val idx = order++
            item(key = "moment", contentType = "moment") {
                val active = playback.hasMedia && playback.isFromMix(moment.id)
                MomentCard(
                    mix = moment,
                    playing = active && (playback.isPlaying || playback.isBuffering),
                    onOpen = { nav.openMix(moment.id) },
                    onPlay = { if (active) vm.togglePlayPause() else vm.playMix(moment.id) },
                    modifier = Modifier.animateItem().staggerIn(idx, intro).padding(bottom = 12.dp),
                )
            }
        }

        val heroIdx = order++
        item(key = "hero", contentType = "hero") {
            val smartActive = playback.hasMedia && playback.isSmartLibrary()
            SmartShuffleHero(
                playing = smartActive && (playback.isPlaying || playback.isBuffering),
                onClick = { if (smartActive) vm.togglePlayPause() else vm.playSmartLibrary() },
                modifier = Modifier.animateItem().staggerIn(heroIdx, intro),
            )
        }

        // January: the year that ended, in music (once there is a minute to tell).
        if (recap != null && recap.second >= 60_000) {
            val idx = order++
            item(key = "recap", contentType = "recap") {
                com.villamediana.sound.ui.recap.RecapEntry(
                    year = recap.first,
                    listenedMs = recap.second,
                    onClick = { nav.openRecap(recap.first) },
                    modifier = Modifier.animateItem().staggerIn(idx, intro).padding(top = 12.dp),
                )
            }
        }

        val dayMixes = state.mixes.filter { it.id != com.villamediana.sound.smart.MadeForYou.MOMENT_ID }
        if (dayMixes.isNotEmpty()) {
            val idx = order++
            item(key = "mixes", contentType = "carousel") {
                Carousel(stringResource(R.string.home_made_for_you), Modifier.animateItem().staggerIn(idx, intro)) {
                    items(dayMixes, key = { it.id }) { mix ->
                        val active = playback.hasMedia && playback.isFromMix(mix.id)
                        MixCard(
                            mix = mix,
                            playing = active && playback.isPlaying,
                            onOpen = { nav.openMix(mix.id) },
                            onPlay = { if (active) vm.togglePlayPause() else vm.playMix(mix.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }

        extra()
    }
}

@Composable
private fun Carousel(title: String, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 18.dp)) {
        // A light line between the Home's sections.
        com.villamediana.sound.ui.components.SectionRule()
        Spacer(Modifier.height(16.dp))
        SectionHeader(title)
        LazyRow(
            // Swiping a carousel scrolls it; it never turns into a tab change at its end.
            modifier = Modifier.keepHorizontalDrags(),
            contentPadding = PaddingValues(horizontal = Dimens.Gutter),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/**
 * "Pra agora": the mix the DNA makes for this moment (the part of the day, the week, the place, the
 * friends along) — its cover, what it is for, its artists; a tap opens it, play plays it.
 */
@Composable
private fun MomentCard(mix: com.villamediana.sound.smart.Mix, playing: Boolean, onOpen: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(SoundColors.Surface1)
                .pressable(onOpen, pressed = 0.98f)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.villamediana.sound.ui.components.Cover(mix.covers.firstOrNull(), Modifier.size(64.dp), seed = mix.title, shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dna_now).uppercase(), style = MaterialTheme.typography.labelSmall, color = SoundColors.Accent, maxLines = 1)
                Text(mix.title, style = MaterialTheme.typography.titleLarge, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(mix.subtitle, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.size(12.dp))
            PlayPauseButton(
                playing = playing,
                onClick = onPlay,
                size = 48.dp,
                container = SoundColors.Accent,
                content = SoundColors.OnAccent,
                contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
            )
        }
    }
}

/** "Suas músicas": the name and play beside it — one tap plays the library, weighted by taste. */
@Composable
private fun SmartShuffleHero(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(SoundColors.SoftTop, SoundColors.SoftBottom)))
                .pressable(onClick, pressed = 0.98f)
                .padding(start = 22.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.home_smart_title),
                style = MaterialTheme.typography.titleLarge,
                color = SoundColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(12.dp))
            PlayPauseButton(
                playing = playing,
                onClick = onClick,
                size = 48.dp,
                container = SoundColors.Mint,
                content = SoundColors.OnMint,
                contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
            )
        }
    }
}

// ---------------------------------------------------------------------------- empty

@Composable
private fun EmptyHome(downloads: DownloadSummary, nav: Navigator, intro: Boolean) {
    val embedded = LocalHomeEmbedded.current
    val top = if (embedded) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize().padding(bottom = LocalBottomInset.current)) {
        if (!embedded) Row(Modifier.align(Alignment.TopEnd).padding(top = top + 6.dp, end = 8.dp)) {
            HeaderActions(downloads, nav)
        }
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SoundMark(
                size = 88.dp,
                modifier = Modifier.staggerIn(0, intro),
            )
            Spacer(Modifier.height(32.dp))
            Text(
                stringResource(R.string.home_empty_line),
                style = MaterialTheme.typography.headlineLarge,
                color = SoundColors.Text,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 300.dp).staggerIn(1, intro),
            )
            Spacer(Modifier.height(28.dp))
            PillButton(
                text = stringResource(R.string.home_empty_action),
                onClick = { nav.openSearchScreen() },
                icon = Icons.Rounded.Search,
                filled = true,
                modifier = Modifier.staggerIn(2, intro).searchRevealOrigin(),
            )
        }
    }
}
