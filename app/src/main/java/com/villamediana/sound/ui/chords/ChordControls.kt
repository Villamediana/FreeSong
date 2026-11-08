package com.villamediana.sound.ui.chords

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.villamediana.sound.R
import com.villamediana.sound.chords.ChordShifts
import com.villamediana.sound.chords.MusicKey
import com.villamediana.sound.chords.WrittenSheet
import com.villamediana.sound.ui.theme.SoundColors

/*
 * The chords' own controls live in the lyrics screen's transport row, beside the play button, as
 * the player's header does it: a small spaced capital label over the value ("TOCANDO DE" over the
 * source). Nothing sits between the title and the words — the page reads like the plain lyrics.
 */

/** A small capital label over a value. */
@Composable
private fun MicroLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = SoundColors.Text.copy(alpha = 0.6f),
        maxLines = 1,
    )
}

private val valueStyle: TextStyle
    @Composable get() = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em)

/**
 * "TOM" over the song's key between a half step down and a half step up. Moved away from the
 * original key, how far shows raised next to it (A⁺²), in the lyrics' accent; a tap on the key
 * brings the original back. The name rolls the way it went.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun ChordKey(key: MusicKey?, shift: Int, onShift: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    fun step(to: Int) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onShift(to)
    }
    val reset = stringResource(R.string.chords_transpose_reset)
    val max = ChordShifts.MAX
    Column(modifier.testTag("transposer"), horizontalAlignment = Alignment.CenterHorizontally) {
        MicroLabel(stringResource(R.string.chords_key_label))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(Icons.Rounded.Remove, stringResource(R.string.chords_transpose_down), shift > -max) { step(shift - 1) }
            val shown = key?.let { MusicKey(Math.floorMod(it.tonic + shift, 12), it.minor) }
            val said = (if (shown != null) stringResource(R.string.chords_key, shown.name()) else stringResource(R.string.chords_key_unknown)) +
                if (shift != 0) " " + signed(shift) else ""
            AnimatedContent(
                targetState = shift,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(tween(200)) { if (up) it / 2 else -it / 2 } + fadeIn(tween(200))) togetherWith
                        (slideOutVertically(tween(200)) { if (up) -it / 2 else it / 2 } + fadeOut(tween(160)))
                },
                contentAlignment = Alignment.Center,
                label = "key",
                modifier = Modifier
                    .widthIn(min = 52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = shift != 0, role = Role.Button, onClickLabel = reset) { step(0) }
                    .semantics { contentDescription = said },
            ) { n ->
                val k = key?.let { MusicKey(Math.floorMod(it.tonic + n, 12), it.minor) }
                Text(
                    keyText(k, n),
                    style = valueStyle,
                    color = SoundColors.Text,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
            StepButton(Icons.Rounded.Add, stringResource(R.string.chords_transpose_up), shift < max) { step(shift + 1) }
        }
    }
}

/** "A", or "A" with "+2" raised after it in the accent; no key known: just how far ("+2"), or a dash. */
@Composable
private fun keyText(key: MusicKey?, shift: Int): AnnotatedString {
    val accent = SoundColors.Lyric
    return buildAnnotatedString {
        if (key == null) {
            if (shift == 0) append("—") else withStyle(SpanStyle(color = accent)) { append(signed(shift)) }
            return@buildAnnotatedString
        }
        append(key.name())
        if (shift != 0) {
            withStyle(SpanStyle(color = accent, fontSize = 0.66.em, baselineShift = BaselineShift(0.55f), fontWeight = FontWeight.ExtraBold)) {
                append(signed(shift))
            }
        }
    }
}

private fun signed(n: Int): String = if (n > 0) "+$n" else "−${-n}"

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = SoundColors.Text.copy(alpha = if (enabled) 0.85f else 0.25f), modifier = Modifier.size(18.dp))
    }
}

/**
 * What a written sheet says about how to play it, on the other side of the play button: the capo
 * ("CAPOTRASTE" over "2ª casa"), and whose version it is when it's another artist's.
 */
@Composable
internal fun ChordSheetNote(sheet: WrittenSheet?, modifier: Modifier = Modifier) {
    sheet ?: return
    val capo = sheet.capo > 0
    val version = sheet.versionOf.isNotBlank()
    if (!capo && !version) return
    Column(modifier.widthIn(max = 150.dp).testTag("chordSheetNote"), horizontalAlignment = Alignment.End) {
        MicroLabel(stringResource(if (capo) R.string.chords_capo_label else R.string.chords_version_label))
        // The value on the same line as the key's on the other side.
        Box(Modifier.height(40.dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                if (capo) stringResource(R.string.chords_capo_fret, sheet.capo) else sheet.versionOf,
                style = valueStyle,
                color = SoundColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (capo && version) {
            Text(
                stringResource(R.string.chords_version, sheet.versionOf),
                style = MaterialTheme.typography.labelMedium,
                color = SoundColors.Text.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
