package com.villamediana.sound.ui.recap

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.villamediana.sound.R
import com.villamediana.sound.stats.Recap
import com.villamediana.sound.stats.RecapChapter
import com.villamediana.sound.stats.RecapStoryPlan
import com.villamediana.sound.ui.components.SoundMark
import com.villamediana.sound.ui.theme.SoundColors

/** Where the story is: the chapter, how far into it, and whether a finger holds it still. */
@Stable
class StoryState(index: Int = 0, t: Float = 0f) {
    var index by mutableIntStateOf(index)
    /** Seconds into the chapter. */
    var t by mutableFloatStateOf(t)
    /** Seconds since the story opened (the backdrop's drift; keeps going while held). */
    var clock by mutableFloatStateOf(0f)
    var held by mutableStateOf(false)

    fun go(to: Int) {
        index = to
        t = 0f
    }
}

/**
 * The retrospective as a story: one chapter per screen, each told by its own animation, moving on by
 * itself. Tap the right side for the next, the left for the one before, hold to pause. [auto] false
 * keeps the time where [state] says (tests and the film of the story drive it).
 */
@Composable
fun RecapStory(
    recap: Recap,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    state: StoryState = remember { StoryState() },
    auto: Boolean = true,
    soundOn: Boolean = true,
    canSound: Boolean = true,
    onSound: () -> Unit = {},
) {
    val chapters = remember(recap) { if (recap.empty) emptyList() else RecapStoryPlan.chapters(recap) }

    if (auto) {
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(state, chapters, lifecycle) {
            // Frames only while the story is on screen, moving and not held: nothing drawn for nothing.
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                var last = withFrameNanos { it }
                while (true) {
                    if (state.held) {
                        snapshotFlow { state.held }.first { !it }
                        last = withFrameNanos { it }
                    }
                    if (settled(chapters, state)) {
                        // The end is drawn: wait for "Ver de novo" or a tap back.
                        snapshotFlow { settled(chapters, state) }.first { !it }
                        last = withFrameNanos { it }
                    }
                    val now = withFrameNanos { it }
                    // A long gap (the app was away) moves nothing on.
                    val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                    last = now
                    state.clock += dt
                    if (chapters.isNotEmpty()) {
                        val c = chapters[state.index.coerceIn(0, chapters.lastIndex)]
                        state.t += dt
                        if (c.advances && state.t >= c.seconds && state.index < chapters.lastIndex) state.go(state.index + 1)
                    }
                }
            }
        }
    }

    val accent = SoundColors.Accent
    val artistColor = rememberStoryColor(recap.topArtists.firstOrNull()?.cover, hueShift(accent, 35f))
    val songColor = rememberStoryColor(recap.topSongs.firstOrNull()?.cover, hueShift(accent, -45f))
    val firstColor = rememberStoryColor(recap.firstSong?.cover, hueShift(accent, 160f))
    val current = chapters.getOrNull(state.index)
    val glow = remember(current, accent, artistColor, songColor, firstColor) { glowFor(current, accent, artistColor, songColor, firstColor) }

    BoxWithConstraints(modifier.fillMaxSize().background(StoryInk)) {
        ProvideStoryUnit(maxWidth) {
            Backdrop(glow, state)
            // Taps and holds land here, under the scenes (only the last scene's button sits above).
            Box(
                Modifier.fillMaxSize().pointerInput(chapters) {
                    detectTapGestures(
                        onPress = {
                            state.held = true
                            tryAwaitRelease()
                            state.held = false
                        },
                        onLongPress = {},
                        onTap = { at ->
                            if (chapters.isEmpty()) return@detectTapGestures
                            if (at.x < size.width * 0.3f) {
                                if (state.index > 0) state.go(state.index - 1) else state.t = 0f
                            } else if (state.index < chapters.lastIndex) {
                                state.go(state.index + 1)
                            }
                        },
                    )
                },
            )
            if (chapters.isEmpty()) {
                EmptyScene(state.clock)
            } else {
                AnimatedContent(
                    targetState = state.index,
                    transitionSpec = { (fadeIn(tween(420)) + scaleIn(tween(520), initialScale = 1.04f)) togetherWith (fadeOut(tween(260)) + scaleOut(tween(260), targetScale = 0.97f)) },
                    label = "chapter",
                ) { i ->
                    val chapter = chapters[i]
                    // The chapter leaving stays as it ended (its clock no longer moves).
                    val t = if (i == state.index) state.t else (if (chapter.advances) chapter.seconds else 99f)
                    Scene(chapter, recap, t, state.clock, glow, onAgain = { state.go(0) })
                }
            }
            StoryBar(chapters, state, recap.year, soundOn, canSound, onSound, onClose)
        }
    }
}

/** Nothing left to move: the last chapter has finished arriving (or there is nothing to tell). */
private fun settled(chapters: List<RecapChapter>, state: StoryState): Boolean {
    if (chapters.isEmpty()) return state.clock > SETTLE_SECONDS
    val c = chapters.getOrNull(state.index) ?: return true
    return !c.advances && state.t > SETTLE_SECONDS
}

/** How long the last screen (or the empty one) keeps moving before it holds still. */
private const val SETTLE_SECONDS = 4f

@Composable
private fun Scene(chapter: RecapChapter, r: Recap, t: Float, clock: Float, glow: Glow, onAgain: () -> Unit) {
    when (chapter) {
        RecapChapter.INTRO -> IntroScene(r, t, clock, glow)
        RecapChapter.FIRST -> FirstScene(r, t, clock)
        RecapChapter.TIME -> TimeScene(r, t, clock, glow)
        RecapChapter.MONTHS -> MonthsScene(r, t, clock, glow)
        RecapChapter.RHYTHM -> RhythmScene(r, t, clock)
        RecapChapter.GENRE -> GenreScene(r, t, clock)
        RecapChapter.ARTIST -> ArtistScene(r, t, clock, glow)
        RecapChapter.ARTISTS -> ArtistsScene(r, t)
        RecapChapter.SONG -> SongScene(r, t, clock, glow)
        RecapChapter.SONGS -> SongsScene(r, t, clock, glow)
        RecapChapter.OUTRO -> OutroScene(r, t, clock, glow, onAgain)
    }
}

/** Each chapter's light: the app's color turned around the wheel, the covers' own colors where they star. */
private fun glowFor(chapter: RecapChapter?, accent: Color, artist: Color, song: Color, first: Color): Glow = when (chapter) {
    null, RecapChapter.INTRO -> Glow(hueShift(accent, 0f), hueShift(accent, 45f), hueShift(accent, -55f, value = 0.6f))
    RecapChapter.FIRST -> Glow(first, hueShift(first, 50f), hueShift(accent, 0f, value = 0.6f))
    RecapChapter.TIME -> Glow(hueShift(accent, 200f), hueShift(accent, 150f), hueShift(accent, 0f, value = 0.6f))
    RecapChapter.MONTHS -> Glow(hueShift(accent, 110f), hueShift(accent, 160f), hueShift(accent, 60f, value = 0.6f))
    RecapChapter.RHYTHM -> Glow(Color(0xFF2B3A8F), Color(0xFF5B2C8F), Color(0xFF14204F))
    RecapChapter.GENRE -> Glow(hueShift(accent, -70f), hueShift(accent, 25f), hueShift(accent, -130f, value = 0.6f))
    RecapChapter.ARTIST -> Glow(artist, hueShift(artist, 40f), hueShift(artist, -40f, value = 0.55f))
    RecapChapter.ARTISTS -> Glow(hueShift(artist, 20f, value = 0.65f), hueShift(accent, 0f), hueShift(artist, -60f, value = 0.55f))
    RecapChapter.SONG -> Glow(song, hueShift(song, -40f), hueShift(song, 50f, value = 0.55f))
    RecapChapter.SONGS -> Glow(hueShift(song, 15f, value = 0.65f), hueShift(accent, 0f), hueShift(song, 90f, value = 0.55f))
    RecapChapter.OUTRO -> Glow(hueShift(accent, 0f), song, artist)
}

/** A cover's color (a file on the phone or a thumbnail online). */
@Composable
private fun rememberStoryColor(cover: String?, fallback: Color): Color {
    val online = cover?.startsWith("http") == true
    val picked = if (online) {
        com.villamediana.sound.ui.search.rememberRemoteCoverColor(cover, fallback)
    } else {
        com.villamediana.sound.ui.components.rememberCoverColor(cover, fallback = fallback)
    }
    return remember(picked) { hueShift(picked, 0f) }
}

@Composable
private fun StoryBar(
    chapters: List<RecapChapter>,
    state: StoryState,
    year: Int,
    soundOn: Boolean,
    canSound: Boolean,
    onSound: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp).padding(top = 8.dp)) {
        if (chapters.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                chapters.forEachIndexed { i, c ->
                    val fill = when {
                        i < state.index -> 1f
                        i > state.index -> 0f
                        !c.advances -> 1f
                        else -> (state.t / c.seconds).coerceIn(0f, 1f)
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(Color.White.copy(alpha = 0.26f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Color.White))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(4.dp))
            SoundMark(size = 24.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.recap_story_label, year), style = MaterialTheme.typography.labelLarge, color = StoryText.copy(alpha = 0.92f), modifier = Modifier.weight(1f))
            if (canSound) {
                IconButton(onClick = onSound) {
                    Icon(
                        if (soundOn) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                        stringResource(if (soundOn) R.string.recap_sound_off else R.string.recap_sound_on),
                        tint = StoryText,
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, stringResource(R.string.recap_close), tint = StoryText)
            }
        }
    }
}
