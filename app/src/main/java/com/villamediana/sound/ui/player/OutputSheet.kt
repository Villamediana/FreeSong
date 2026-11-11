package com.villamediana.sound.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.playback.Outputs
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.rememberSongCover
import com.villamediana.sound.ui.sheets.SheetHeader
import com.villamediana.sound.ui.sheets.SoundSheet
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import kotlin.math.roundToInt

/**
 * Where the music comes out, in FreeSong's own sheet (instead of the system's switcher): the song,
 * the media volume as one wide pill, the connected outputs (earphones, speakers, the phone) with
 * the one playing marked, and "Conectar dispositivo" for the Bluetooth settings. Picking an output
 * sends FreeSong's sound there ([com.villamediana.sound.playback.OutputChoice]); the sheet stays
 * open for the volume.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun OutputSheet(track: Track?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val outputs = rememberOutputs()
    val volume = rememberMediaVolume()
    SoundSheet(onDismissRequest = onDismiss) {
        if (track != null) {
            val cover = rememberSongCover(track)
            SheetHeader(
                title = track.title,
                subtitle = track.artist,
                art = { Cover(cover, Modifier.fillMaxSize(), seed = track.album ?: track.title) },
            )
        }
        OutputContent(
            outputs = outputs,
            volume = volume,
            onPick = { option ->
                SoundApplication.graph.outputChoice.prefer(Outputs.preference(option, outputs.options, outputs.systemDefault))
            },
            onConnect = { OutputRoutes.openBluetooth(context) },
        )
    }
}

/** The media volume, live (the phone's buttons move it too); null where it is fixed (some USB/HDMI outputs). */
internal class MediaVolume(val level: Int, val max: Int, val set: (Int) -> Unit)

@Composable
private fun rememberMediaVolume(): MediaVolume? {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager } ?: return null
    if (audio.isVolumeFixed) return null
    val max = remember(audio) { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var level by remember(audio) { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    DisposableEffect(audio) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(VOLUME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return MediaVolume(level, max) { v ->
        val to = v.coerceIn(0, max)
        level = to
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, to, 0) }
    }
}

/** The sheet's body without the system around it (tested and drawn on its own). */
@Composable
internal fun OutputContent(
    outputs: OutputsState,
    volume: MediaVolume?,
    onPick: (Outputs.Option) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)) {
        if (volume != null) {
            VolumePill(volume, Modifier.padding(horizontal = Dimens.Gutter))
            Spacer(Modifier.height(22.dp))
        }
        Text(
            stringResource(R.string.player_output_title).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = SoundColors.TextFaint,
            modifier = Modifier.padding(horizontal = Dimens.Gutter).padding(bottom = 6.dp),
        )
        outputs.options.forEach { option ->
            OutputRow(option, selected = option.id == outputs.current?.id, onClick = { onPick(option) })
        }
        ConnectRow(onConnect)
    }
}

/** The volume as one wide pill: the fill is the level, the speaker icon rides at its start. Drag or tap anywhere on it. */
@Composable
private fun VolumePill(volume: MediaVolume, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    val target = dragging ?: (volume.level.toFloat() / volume.max)
    val shown by animateFloatAsState(target, if (dragging != null) tween(0) else spring(dampingRatio = 0.85f, stiffness = 500f), label = "volume")
    val current by rememberUpdatedState(volume)
    val label = stringResource(R.string.player_output_volume)
    var lastLevel by remember { mutableIntStateOf(-1) }
    fun setFraction(f: Float) {
        val v = current
        val clamped = f.coerceIn(0f, 1f)
        dragging = clamped
        val level = (clamped * v.max).roundToInt()
        if (level != v.level) {
            if ((level == 0 || level == v.max) && level != lastLevel) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            lastLevel = level
            v.set(level)
        }
    }
    val icon = when {
        volume.level == 0 -> Icons.AutoMirrored.Rounded.VolumeMute
        volume.level * 2 < volume.max -> Icons.AutoMirrored.Rounded.VolumeDown
        else -> Icons.AutoMirrored.Rounded.VolumeUp
    }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .background(SoundColors.Text.copy(alpha = 0.07f))
            .testTag("output-volume")
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(volume.level.toFloat(), 0f..volume.max.toFloat(), steps = (volume.max - 1).coerceAtLeast(0))
                setProgress { v ->
                    current.set(v.roundToInt())
                    true
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> setFraction(o.x / size.width) },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        setFraction(change.position.x / size.width)
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    setFraction(o.x / size.width)
                    dragging = null
                }
            },
    ) {
        val pill = maxHeight
        // Never thinner than the round end that carries the icon, so the level reads even at zero.
        val fill = (maxWidth * shown).coerceAtLeast(pill)
        Box(
            Modifier
                .width(fill)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(SoundColors.Accent),
        )
        Box(Modifier.size(pill), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = SoundColors.OnAccent, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun OutputRow(option: Outputs.Option, selected: Boolean, onClick: () -> Unit) {
    val chip by animateColorAsState(if (selected) SoundColors.AccentSoft else SoundColors.Text.copy(alpha = 0.07f), tween(220), label = "chip")
    val tint by animateColorAsState(if (selected) SoundColors.Accent else SoundColors.TextMuted, tween(220), label = "tint")
    val nameColor by animateColorAsState(if (selected) SoundColors.Accent else SoundColors.Text, tween(220), label = "name")
    val check by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "check")
    val name = outputName(option)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = Dimens.Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(chip), contentAlignment = Alignment.Center) {
            Icon(outputIcon(option.kind), null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = nameColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(outputKindLabel(option.kind), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1)
        }
        if (check > 0.01f) {
            Icon(
                Icons.Rounded.Check,
                null,
                tint = SoundColors.Accent,
                modifier = Modifier.size(22.dp * check),
            )
        }
    }
}

@Composable
private fun ConnectRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Dimens.Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(SoundColors.Text.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, null, tint = SoundColors.TextMuted, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(stringResource(R.string.player_output_connect), style = MaterialTheme.typography.titleMedium, color = SoundColors.TextMuted)
    }
}

@Composable
private fun outputName(option: Outputs.Option): String = when (option.kind) {
    Outputs.Kind.PHONE -> stringResource(R.string.player_output_phone)
    Outputs.Kind.BLUETOOTH -> option.name ?: stringResource(R.string.player_output_bluetooth)
    Outputs.Kind.WIRED -> stringResource(R.string.player_output_wired)
    Outputs.Kind.USB -> option.name ?: stringResource(R.string.player_output_usb)
}

@Composable
private fun outputKindLabel(kind: Outputs.Kind): String = stringResource(
    when (kind) {
        Outputs.Kind.PHONE -> R.string.player_output_kind_speaker
        Outputs.Kind.BLUETOOTH -> R.string.player_output_bluetooth
        Outputs.Kind.WIRED -> R.string.player_output_kind_wired
        Outputs.Kind.USB -> R.string.player_output_kind_usb
    },
)

internal fun outputIcon(kind: Outputs.Kind): ImageVector = when (kind) {
    Outputs.Kind.PHONE -> Icons.Rounded.Smartphone
    Outputs.Kind.BLUETOOTH -> Icons.Rounded.BluetoothAudio
    Outputs.Kind.WIRED -> Icons.Rounded.Headphones
    Outputs.Kind.USB -> Icons.Rounded.Headset
}

private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
