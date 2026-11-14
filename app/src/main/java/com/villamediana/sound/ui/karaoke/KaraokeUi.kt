package com.villamediana.sound.ui.karaoke

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MicExternalOn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.villamediana.sound.R
import com.villamediana.sound.chords.ChordSubject
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.karaoke.KaraokeState
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the karaoke button by the lyrics needs from the app. */
class KaraokeDeps(
    /** The microphone is on (for this run of the app). */
    val on: StateFlow<Boolean>,
    val setOn: (Boolean) -> Unit,
    /** Where a song's karaoke stands, by the song's key. */
    val state: (key: String) -> StateFlow<KaraokeState>,
    /** False on a phone the model doesn't run on: no button there. */
    val supported: Boolean = true,
)

/** None in previews and in tests that don't give one: no karaoke button then. */
val LocalKaraoke = staticCompositionLocalOf<KaraokeDeps?> { null }

/** A hand microphone: the "Karaokê" button. */
internal val KaraokeIcon: ImageVector get() = Icons.Rounded.MicExternalOn

/** The microphone by the lyrics is on. */
@Composable
internal fun rememberKaraokeOn(): Boolean {
    val deps = LocalKaraoke.current?.takeIf { it.supported } ?: return false
    val on by deps.on.collectAsState()
    return on
}

/** Where [track]'s karaoke stands (its voice being taken out, ready, failed…). */
@Composable
internal fun rememberKaraokeState(track: Track): KaraokeState {
    val deps = LocalKaraoke.current ?: return KaraokeState.Idle
    val key = remember(track.id, track.videoId, track.filePath) { ChordSubject.of(track)?.key }
    val flow = remember(key, deps) { key?.let(deps.state) ?: MutableStateFlow(KaraokeState.Idle) }
    val state by flow.collectAsState()
    return state
}

/**
 * Under the lyrics header while the karaoke is on and the song's voice is not all out yet: the
 * model on its way (the first time), how far the song is, or why it failed. The voice goes as soon
 * as the part being played is done — this only says how the rest is coming along.
 */
@Composable
internal fun KaraokeStatus(state: KaraokeState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        is KaraokeState.Fetching, is KaraokeState.Working -> {
            val progress = if (state is KaraokeState.Fetching) state.progress else (state as KaraokeState.Working).progress
            Column(modifier.fillMaxWidth().testTag("karaokeProgress")) {
                Text(
                    stringResource(if (state is KaraokeState.Fetching) R.string.karaoke_fetching else R.string.karaoke_working, (progress * 100).toInt()),
                    style = MaterialTheme.typography.labelLarge,
                    color = SoundColors.Text.copy(alpha = 0.72f),
                )
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(SoundColors.Text.copy(alpha = 0.14f))) {
                    val shown by animateFloatAsState(progress.coerceIn(0.02f, 1f), tween(400), label = "karaokeProgress")
                    Box(Modifier.fillMaxWidth(shown).height(3.dp).clip(CircleShape).background(SoundColors.Mint))
                }
            }
        }
        is KaraokeState.Failed -> Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    when (state.reason) {
                        KaraokeState.Reason.OFFLINE -> R.string.karaoke_failed_offline
                        KaraokeState.Reason.TOO_LONG -> R.string.karaoke_failed_too_long
                        KaraokeState.Reason.UNSUPPORTED -> R.string.karaoke_failed_unsupported
                        KaraokeState.Reason.ERROR -> R.string.karaoke_failed_error
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = SoundColors.Text.copy(alpha = 0.72f),
                modifier = Modifier.weight(1f),
            )
            if (state.reason == KaraokeState.Reason.OFFLINE || state.reason == KaraokeState.Reason.ERROR) {
                Text(
                    stringResource(R.string.chords_retry),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = SoundColors.Mint,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onRetry).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        else -> Unit
    }
}
