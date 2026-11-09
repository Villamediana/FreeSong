package com.villamediana.sound.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.sound.R
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.sc

/**
 * The round Play of a page (album, playlist, artist, Músicas…): accent, icon only; pauses when
 * that page is what is playing. The same on library and online pages.
 */
@Composable
fun PagePlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 56.dp,
    label: String = stringResource(if (playing) R.string.pause else R.string.play),
    /** Starting (the song is still loading): Pause shows at once, with a ring turning around it. */
    loading: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        AnimatedVisibility(loading, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            CircularProgressIndicator(
                modifier = Modifier.requiredSize(size + 10.dp),
                color = sc.Accent,
                strokeWidth = 2.5.dp,
                strokeCap = StrokeCap.Round,
                trackColor = Color.Transparent,
            )
        }
    Box(
        Modifier
            .size(size)
            .pressScale(interaction, 0.9f)
            .clip(CircleShape)
            .background(if (enabled) sc.Accent else sc.Surface3)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // The app's soft play/pause, morphing (the same as the player's).
        PlayPauseGlyph(playing = playing, tint = if (enabled) sc.OnAccent else sc.TextFaint, modifier = Modifier.size(size * 0.54f))
    }
    }
}

/**
 * The outlined Shuffle beside it. [toggle]: an on/off switch for the next Play (library pages,
 * TalkBack hears it as a switch); otherwise a button that plays shuffled (online pages).
 */
@Composable
fun PageShuffleButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    toggle: Boolean = false,
    label: String = stringResource(R.string.shuffle),
) {
    val interaction = remember { MutableInteractionSource() }
    val tint = when {
        !enabled -> sc.TextFaint
        active -> sc.Accent
        else -> sc.Text
    }
    val click = if (toggle) {
        Modifier.toggleable(value = active, interactionSource = interaction, indication = null, enabled = enabled, role = Role.Switch, onValueChange = { onClick() })
    } else {
        Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
    }
    Box(
        modifier
            .size(48.dp)
            .pressScale(interaction, 0.9f)
            .clip(CircleShape)
            .border(1.dp, if (active && enabled) sc.Accent.copy(alpha = 0.5f) else sc.Hairline, CircleShape)
            .then(click)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Shuffle, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
