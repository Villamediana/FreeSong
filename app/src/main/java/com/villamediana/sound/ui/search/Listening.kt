package com.villamediana.sound.ui.search

import com.villamediana.sound.ui.theme.sc
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.villamediana.sound.R
import com.villamediana.sound.recognition.MicSongListener
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.theme.SoundColors

/** The microphone beside the search field: names the song playing nearby. */
@Composable
internal fun MicButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    // Just the microphone (no box), in the page's text color, with a ripple when tapped.
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = ripple(bounded = false, radius = 24.dp, color = sc.Text)) {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Mic, stringResource(R.string.hear_button), tint = sc.Text, modifier = Modifier.size(26.dp))
    }
}

/**
 * Listening, full screen in the search's own color: an orb that breathes with what the microphone
 * hears, rings rippling out of it and the seconds going round it; then the song found (cover,
 * title, artist — it plays right after), or what went wrong with a "try again".
 */
@Composable
internal fun ListeningScreen(
    hearing: SearchViewModel.Hearing,
    level: Float,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(sc.Ink)
            .pointerInput(Unit) { detectTapGestures { } } // nothing beneath takes taps
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Icon(Icons.Rounded.Close, stringResource(R.string.hear_cancel), tint = sc.Text)
        }
        AnimatedContent(
            targetState = hearing.kind(),
            transitionSpec = { (fadeIn(tween(260)) + scaleIn(tween(320), initialScale = 0.92f)) togetherWith fadeOut(tween(160)) },
            contentAlignment = Alignment.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            label = "hearing",
        ) { kind ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                when (kind) {
                    Kind.LISTENING -> {
                        val seconds = (hearing as? SearchViewModel.Hearing.Listening)?.seconds ?: 0f
                        HearingOrb(level, seconds / MicSongListener.MAX_SECONDS)
                        Spacer(Modifier.height(28.dp))
                        Text(stringResource(R.string.hear_listening), style = MaterialTheme.typography.headlineSmall, color = sc.Text)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.hear_listening_hint), style = MaterialTheme.typography.bodyMedium, color = sc.TextMuted, textAlign = TextAlign.Center)
                    }
                    Kind.FOUND -> {
                        val match = (hearing as? SearchViewModel.Hearing.Found)?.match
                        Text(stringResource(R.string.hear_found), style = MaterialTheme.typography.labelLarge, color = sc.Accent)
                        Spacer(Modifier.height(16.dp))
                        Cover(
                            match?.cover?.ifBlank { null },
                            Modifier.size(220.dp).shadow(24.dp, RoundedCornerShape(20.dp)),
                            seed = match?.title ?: "",
                            shape = RoundedCornerShape(20.dp),
                        )
                        Spacer(Modifier.height(22.dp))
                        Text(match?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = sc.Text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(match?.artist.orEmpty(), style = MaterialTheme.typography.titleMedium, color = sc.TextMuted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(14.dp))
                        Text(stringResource(R.string.hear_playing_now), style = MaterialTheme.typography.labelLarge, color = sc.TextFaint)
                    }
                    Kind.NOT_FOUND -> Outcome(Icons.Rounded.MusicOff, stringResource(R.string.hear_not_found), stringResource(R.string.hear_not_found_hint), onRetry)
                    Kind.OFFLINE -> Outcome(Icons.Rounded.WifiOff, stringResource(R.string.hear_offline), null, onRetry)
                    Kind.NO_MIC -> Outcome(Icons.Rounded.MicOff, stringResource(R.string.hear_no_mic), null, onRetry)
                    Kind.IDLE -> Spacer(Modifier.size(1.dp))
                }
            }
        }
    }
}

private enum class Kind { IDLE, LISTENING, FOUND, NOT_FOUND, OFFLINE, NO_MIC }

private fun SearchViewModel.Hearing.kind(): Kind = when (this) {
    SearchViewModel.Hearing.Idle -> Kind.IDLE
    is SearchViewModel.Hearing.Listening -> Kind.LISTENING
    is SearchViewModel.Hearing.Found -> Kind.FOUND
    SearchViewModel.Hearing.NotFound -> Kind.NOT_FOUND
    is SearchViewModel.Hearing.Failed -> if (noMicrophone) Kind.NO_MIC else Kind.OFFLINE
}

@Composable
private fun Outcome(icon: ImageVector, title: String, hint: String?, onRetry: () -> Unit) {
    Box(Modifier.size(96.dp).clip(CircleShape).background(sc.Surface2), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = sc.TextMuted, modifier = Modifier.size(40.dp))
    }
    Spacer(Modifier.height(22.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, color = sc.Text, textAlign = TextAlign.Center)
    if (hint != null) {
        Spacer(Modifier.height(6.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = sc.TextMuted, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(26.dp))
    PillButton(text = stringResource(R.string.hear_retry), onClick = onRetry, icon = Icons.Rounded.Refresh, filled = true)
}

/** The orb: breathes with [level], rings ripple out of it, [progress] of the listening goes round. */
@Composable
private fun HearingOrb(level: Float, progress: Float) {
    val colors = sc
    val loud by animateFloatAsState(level, spring(dampingRatio = 0.7f, stiffness = 320f), label = "loud")
    val turn by animateFloatAsState(progress.coerceIn(0f, 1f), tween(250), label = "progress")
    val ripple = rememberInfiniteTransition(label = "ripple")
    val phase by ripple.animateFloat(0f, 1f, infiniteRepeatable(tween(2_200, easing = LinearEasing)), label = "phase")
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val core = 60.dp.toPx()
            val far = size.minDimension / 2
            // Three rings leaving the orb one after the other, wider and brighter when it's loud.
            for (i in 0 until 3) {
                val p = (phase + i / 3f) % 1f
                val r = core + (far - core) * p
                drawCircle(colors.Accent, radius = r, center = center, alpha = (1f - p) * (0.18f + 0.42f * loud), style = Stroke(width = (2.dp + 3.dp * loud).toPx()))
            }
            // The seconds heard, around the orb.
            val ring = core + 18.dp.toPx()
            drawCircle(colors.Text.copy(alpha = 0.12f), radius = ring, center = center, style = Stroke(width = 4.dp.toPx()))
            drawArc(
                colors.Accent,
                startAngle = -90f,
                sweepAngle = 360f * turn,
                useCenter = false,
                topLeft = Offset(center.x - ring, center.y - ring),
                size = androidx.compose.ui.geometry.Size(ring * 2, ring * 2),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Box(
            Modifier
                .size(120.dp)
                .scale(1f + 0.14f * loud)
                .shadow(18.dp, CircleShape)
                .clip(CircleShape)
                .background(colors.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Mic, null, tint = colors.OnAccent, modifier = Modifier.size(48.dp))
        }
    }
}
