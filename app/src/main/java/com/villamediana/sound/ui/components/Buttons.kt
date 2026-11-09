package com.villamediana.sound.ui.components

import com.villamediana.sound.ui.theme.sc
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.sound.ui.theme.SoundColors

/** Shrinks a little while pressed — tactile feedback for every custom button. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.92f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (isPressed) pressed else 1f, label = "press")
    return this.scale(s)
}

/** The big round accent play/pause button. */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    container: Color = sc.Accent,
    content: Color = sc.OnAccent,
    contentDescription: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .pressScale(interaction)
            .clip(CircleShape)
            .background(container)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The app's soft play/pause, morphing (the same as the player's).
        PlayPauseGlyph(
            playing = playing,
            tint = content,
            modifier = Modifier.size(size * 0.5f).then(
                if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier,
            ),
        )
    }
}

/** Pill button: filled (accent) or outlined (hairline). */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
    enabled: Boolean = true,
    /** Taller, centered, bigger type — the Tocar / Aleatório pair on collection pages. */
    big: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val bg = if (filled) sc.Accent else sc.Surface1
    val fg = if (filled) sc.OnAccent else sc.Accent
    Row(
        modifier
            .height(if (big) 54.dp else 44.dp)
            .pressScale(interaction, 0.96f)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (big) 20.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (big) Arrangement.Center else Arrangement.Start,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(if (big) 24.dp else 18.dp))
            Spacer(Modifier.width(if (big) 10.dp else 8.dp))
        }
        Text(
            text,
            style = if (big) MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else MaterialTheme.typography.labelLarge,
            color = fg.copy(alpha = if (enabled) 1f else 0.4f),
            maxLines = 1,
        )
    }
}

/**
 * Selectable chip used for filters (search filters, library tabs, equalizer presets). Exposes its
 * selected state to TalkBack; disabled, it neither reacts nor shrinks on touch.
 */
@Composable
fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .height(38.dp)
            .pressScale(interaction, 0.95f)
            .clip(CircleShape)
            .background(if (selected) sc.Accent else sc.Surface1)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) sc.OnAccent else sc.TextMuted)
    }
}
