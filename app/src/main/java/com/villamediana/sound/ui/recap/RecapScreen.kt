package com.villamediana.sound.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
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
import com.villamediana.sound.stats.RecapStoryPlan
import com.villamediana.sound.stats.Recaps
import com.villamediana.sound.ui.components.formatLongDuration
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

class RecapViewModel(val year: Int) : ViewModel() {
    private val graph = SoundApplication.graph

    val recap: StateFlow<Recap?> = run {
        val since = Recaps.span(year).first
        combine(graph.library.playEventsSince(since), graph.library.tracks, graph.library.onlinePlaysSince(since)) { events, tracks, online ->
            Recaps.build(year, events, tracks.associateBy { it.id }, online)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Which of the top songs the story put on (null: none yet). */
    private var playing: Int? = null

    /** A jam is on: the story stays silent (the queue is everyone's). */
    val canSound: Boolean get() = !graph.player.shared

    /**
     * The year's songs behind the story: [index] of the top songs ([RecapStoryPlan.soundtrack]).
     * Sound off pauses what the story put on; on again picks it back up.
     */
    fun soundtrack(r: Recap, index: Int?, on: Boolean, title: String) {
        val player = graph.player
        if (player.shared) return
        if (!on) {
            if (playing != null && player.state.value.isPlaying) player.pauseHere()
            return
        }
        if (index == null) return
        val songs = r.topSongs.mapNotNull { it.track ?: it.remote?.asTransientTrack() }
        if (index !in songs.indices) return
        if (playing == index) {
            if (!player.state.value.isPlaying) player.togglePlayPause()
            return
        }
        playing = index
        player.play(songs, index, PlaySource(PlaySource.Type.SONGS, title, "recap"))
    }
}

/**
 * Your year in music, told as a story (full screen, chapter after chapter) with your top songs
 * playing behind it. [soundtrack] false keeps it silent and [auto] false holds the time where
 * [story] says (tests).
 */
@Composable
fun RecapScreen(year: Int, soundtrack: Boolean = true, auto: Boolean = true, story: StoryState = remember { StoryState() }) {
    val vm: RecapViewModel = viewModel(key = "recap:$year") { RecapViewModel(year) }
    val recap by vm.recap.collectAsStateWithLifecycle()
    val nav = navigator()
    val title = stringResource(R.string.recap_title_year, year)
    var soundOn by rememberSaveable { mutableStateOf(true) }
    // Light icons on the story's night, whatever the theme (and back on the way out).
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        bars?.isAppearanceLightStatusBars = false
        onDispose { bars?.isAppearanceLightStatusBars = SoundColors.isLight }
    }
    val r = recap
    if (r == null) {
        Box(Modifier.fillMaxSize().background(StoryInk))
        return
    }
    if (soundtrack && !r.empty) {
        val chapters = remember(r) { RecapStoryPlan.chapters(r) }
        val song = RecapStoryPlan.soundtrack(r, chapters, story.index)
        LaunchedEffect(song, soundOn) { vm.soundtrack(r, song, soundOn, title) }
    }
    RecapStory(
        recap = r,
        onClose = nav::back,
        state = story,
        auto = auto,
        soundOn = soundOn,
        canSound = soundtrack && vm.canSound && r.topSongs.isNotEmpty(),
        onSound = { soundOn = !soundOn },
    )
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
