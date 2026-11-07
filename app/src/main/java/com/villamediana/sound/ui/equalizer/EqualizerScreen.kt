package com.villamediana.sound.ui.equalizer

import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Nature
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.runtime.remember
import com.villamediana.sound.ui.components.pressScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.playback.EqPreset
import com.villamediana.sound.playback.EqState
import com.villamediana.sound.ui.components.FilterPill
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.SoundColors

@Composable
fun EqualizerScreen() {
    val vm: EqualizerViewModel = viewModel { EqualizerViewModel.create() }
    val state by vm.state.collectAsStateWithLifecycle()
    val hasMedia by vm.hasMedia.collectAsStateWithLifecycle()
    val nav = navigator()
    val playback by com.villamediana.sound.SoundApplication.graph.player.state.collectAsStateWithLifecycle()
    val settingsStore = com.villamediana.sound.SoundApplication.graph.settings
    val crossfade by androidx.compose.runtime.remember { settingsStore.settings.map { it.crossfadeSeconds } }.collectAsStateWithLifecycle(5)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    EqualizerContent(
        crossfadeSeconds = crossfade,
        onCrossfade = { v -> scope.launch { settingsStore.setCrossfadeSeconds(v) } },
        state = state,
        hasMedia = hasMedia,
        nowPlaying = playback.current?.let { t ->
            { NowPlayingStrip(t, playback.isPlaying, onToggle = { com.villamediana.sound.SoundApplication.graph.player.togglePlayPause() }) }
        },
        onBack = nav::back,
        onEnabled = vm::setEnabled,
        onPreset = vm::selectPreset,
        onCustom = vm::selectCustom,
        onBand = vm::setBand,
        onBassBoost = vm::setBassBoost,
        onVirtualizer = vm::setVirtualizer,
        onReset = vm::reset,
    )
}

@Composable
internal fun EqualizerContent(
    state: EqState,
    hasMedia: Boolean,
    onBack: () -> Unit,
    /** The song playing, shown on top (cover, title, play/pause) like a player header. */
    nowPlaying: (@Composable () -> Unit)? = null,
    onEnabled: (Boolean) -> Unit,
    onPreset: (EqPreset) -> Unit,
    onCustom: () -> Unit,
    onBand: (Int, Int) -> Unit,
    onBassBoost: (Int) -> Unit,
    onVirtualizer: (Int) -> Unit,
    onReset: () -> Unit,
    /** Crossfade between songs (seconds, 0 = off): not an effect, but it lives with the sound settings. */
    crossfadeSeconds: Int = 0,
    onCrossfade: ((Int) -> Unit)? = null,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val active = state.enabled && state.available

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SoundColors.SoftTop, SoundColors.SoftBottom)))
            .verticalScroll(rememberScrollState())
            .padding(top = top),
    ) {
        if (nowPlaying != null) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = Dimens.Gutter, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SoundColors.Text) }
                Box(Modifier.weight(1f)) { nowPlaying() }
            }
            Spacer(Modifier.height(14.dp))
        }
        // The equalizer as a panel under the song (a sheet look, with its handle).
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(SoundColors.Text.copy(alpha = if (SoundColors.isLight) 0.035f else 0.05f)),
        ) {
        if (nowPlaying != null) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(SoundColors.Text.copy(alpha = 0.18f)))
            }
        }
        TopBar(enabled = state.enabled, onBack = onBack, onEnabled = onEnabled, showBack = nowPlaying == null)
        Spacer(Modifier.height(18.dp))

        if (state.available) {
            EqBars(
                bands = state.bands,
                minMb = state.minLevelMb,
                maxMb = state.maxLevelMb,
                enabled = active,
                onBandChange = onBand,
            )
            Spacer(Modifier.height(30.dp))
            PresetGrid(
                selected = state.preset,
                hasCustom = state.hasCustomCurve,
                enabled = active,
                onPreset = onPreset,
                onCustom = onCustom,
            )
        } else {
            Unavailable(stringResource(if (hasMedia) R.string.eq_unavailable_device else R.string.eq_unavailable_idle))
        }

        if (state.bassBoostAvailable || state.virtualizerAvailable) {
            Spacer(Modifier.height(Dimens.SectionGap))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (state.bassBoostAvailable) {
                    EffectKnob(
                        label = stringResource(R.string.eq_bass_boost),
                        value = state.bassBoost,
                        enabled = state.enabled,
                        onChange = onBassBoost,
                        testTag = "eq_bass_boost",
                    )
                }
                if (state.virtualizerAvailable) {
                    EffectKnob(
                        label = stringResource(R.string.eq_virtualizer),
                        value = state.virtualizer,
                        enabled = state.enabled,
                        onChange = onVirtualizer,
                        testTag = "eq_virtualizer",
                    )
                }
            }
        }

        if (onCrossfade != null) {
            Spacer(Modifier.height(Dimens.SectionGap))
            CrossfadeRow(crossfadeSeconds, onCrossfade)
        }

        if (state.available) {
            Spacer(Modifier.height(22.dp))
            Text(
                stringResource(R.string.eq_reset),
                style = MaterialTheme.typography.labelLarge,
                color = SoundColors.Text.copy(alpha = if (state.enabled && canReset(state)) 0.85f else 0.35f),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(CircleShape)
                    .clickable(enabled = state.enabled && canReset(state), onClick = onReset)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        Spacer(Modifier.height(LocalBottomInset.current))
        }
    }
}

/** "Crossfade · 5 s": the slider sets how long one song melts into the next (0 = off). */
@Composable
private fun CrossfadeRow(seconds: Int, onChange: (Int) -> Unit) {
    var dragging by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    val shown = dragging?.let { kotlin.math.round(it).toInt() } ?: seconds
    val label = stringResource(R.string.eq_crossfade)
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.eq_crossfade), style = MaterialTheme.typography.titleMedium, color = SoundColors.Text, modifier = Modifier.weight(1f))
            Text(
                if (shown == 0) stringResource(R.string.eq_crossfade_off) else stringResource(R.string.eq_crossfade_seconds, shown),
                style = MaterialTheme.typography.labelLarge,
                color = if (shown == 0) SoundColors.TextMuted else SoundColors.Accent,
            )
        }
        androidx.compose.material3.Slider(
            value = dragging ?: seconds.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onChange(kotlin.math.round(it).toInt()) }
                dragging = null
            },
            valueRange = 0f..com.villamediana.sound.data.MAX_CROSSFADE_SECONDS.toFloat(),
            steps = com.villamediana.sound.data.MAX_CROSSFADE_SECONDS - 1,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = SoundColors.Accent,
                activeTrackColor = SoundColors.Accent,
                inactiveTrackColor = SoundColors.Text.copy(alpha = 0.14f),
                activeTickColor = SoundColors.OnAccent.copy(alpha = 0.5f),
                inactiveTickColor = SoundColors.Text.copy(alpha = 0.25f),
            ),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/** Cover, title, artist and play/pause of the song playing (top of the equalizer). */
@Composable
private fun NowPlayingStrip(track: com.villamediana.sound.data.db.Track, playing: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        com.villamediana.sound.ui.components.Cover(track.coverPath, Modifier.size(64.dp), seed = track.album ?: track.title, shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.titleLarge, color = SoundColors.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        com.villamediana.sound.ui.components.PlayPauseButton(playing = playing, onClick = onToggle, size = 48.dp, container = SoundColors.Mint, content = SoundColors.OnMint)
    }
}

@Composable
private fun TopBar(enabled: Boolean, onBack: () -> Unit, onEnabled: (Boolean) -> Unit, showBack: Boolean = true) {
    val haptic = LocalHapticFeedback.current
    val switchLabel = stringResource(R.string.eq_enable)
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(start = if (showBack) 6.dp else Dimens.Gutter - 4.dp, end = Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SoundColors.Text)
        }
        Text(
            stringResource(R.string.eq_title),
            style = MaterialTheme.typography.headlineMedium,
            color = SoundColors.Text,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        Switch(
            checked = enabled,
            onCheckedChange = {
                haptic.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onEnabled(it)
            },
            modifier = Modifier.testTag("eq_switch").semantics { contentDescription = switchLabel },
            colors = SwitchDefaults.colors(
                checkedThumbColor = SoundColors.OnMint,
                checkedTrackColor = SoundColors.Mint,
                checkedBorderColor = SoundColors.Mint,
                uncheckedThumbColor = SoundColors.Text.copy(alpha = 0.7f),
                uncheckedTrackColor = SoundColors.Text.copy(alpha = 0.12f),
                uncheckedBorderColor = SoundColors.Text.copy(alpha = 0.3f),
            ),
        )
    }
}

/** What's on now, big: the preset's name (or "Personalizado"), and the way back to flat. */
@Composable
private fun Hero(state: EqState, onReset: () -> Unit) {
    val nameColor by animateColorAsState(if (state.enabled) SoundColors.Text else SoundColors.TextMuted, Motion.enter(), label = "name")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter).padding(top = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.eq_profile).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = SoundColors.TextFaint,
            )
            Spacer(Modifier.height(4.dp))
            AnimatedContent(
                targetState = state.preset,
                transitionSpec = {
                    (fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { it / 3 }) togetherWith
                        (fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { -it / 3 })
                },
                label = "preset",
            ) { preset ->
                Text(
                    stringResource(presetLabel(preset)),
                    style = MaterialTheme.typography.displaySmall,
                    color = nameColor,
                    maxLines = 1,
                )
            }
        }
        PillButton(
            text = stringResource(R.string.eq_reset),
            onClick = onReset,
            icon = Icons.Rounded.RestartAlt,
            enabled = state.enabled && canReset(state),
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }
}

/** Presets as cards (two per row), "Personalizado" first when a custom curve exists. */
@Composable
private fun PresetGrid(
    selected: EqPreset?,
    hasCustom: Boolean,
    enabled: Boolean,
    onPreset: (EqPreset) -> Unit,
    onCustom: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val dim by animateFloatAsState(if (enabled) 1f else 0.4f, Motion.enter(), label = "presets")
    val showCustom = selected == null || hasCustom
    val cards: List<EqPreset?> = (if (showCustom) listOf<EqPreset?>(null) else emptyList()) + EqPreset.entries
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter).alpha(dim),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        cards.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { preset ->
                    val on = preset == selected
                    PresetCard(
                        preset = preset,
                        selected = on,
                        enabled = enabled,
                        onClick = {
                            if (!on) {
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                if (preset == null) onCustom() else onPreset(preset)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PresetCard(preset: EqPreset?, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (selected) SoundColors.Mint else SoundColors.Text.copy(alpha = 0f), Motion.enter(), label = "presetBg")
    val fg = if (selected) SoundColors.OnMint else SoundColors.Text
    Column(
        modifier
            .height(104.dp)
            .pressScale(interaction, 0.96f)
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .then(if (selected) Modifier else Modifier.border(1.dp, SoundColors.Text.copy(alpha = 0.22f), RoundedCornerShape(22.dp)))
            .selectable(selected = selected, interactionSource = interaction, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(presetIcon(preset), null, tint = if (selected) SoundColors.OnMint else SoundColors.Text.copy(alpha = 0.85f), modifier = Modifier.size(26.dp))
        Text(stringResource(presetLabel(preset)), style = MaterialTheme.typography.titleMedium, color = fg, maxLines = 2)
    }
}

private fun presetIcon(preset: EqPreset?): ImageVector = when (preset) {
    null -> Icons.Rounded.GraphicEq
    EqPreset.FLAT -> Icons.Rounded.SentimentSatisfied
    EqPreset.BASS -> Icons.Rounded.Speaker
    EqPreset.TREBLE -> Icons.Rounded.SurroundSound
    EqPreset.VOCAL -> Icons.Rounded.Mic
    EqPreset.ROCK -> Icons.Rounded.Bolt
    EqPreset.POP -> Icons.Rounded.MusicNote
    EqPreset.ELECTRONIC -> Icons.Rounded.Equalizer
    EqPreset.HIPHOP -> Icons.Rounded.Headphones
    EqPreset.LATIN -> Icons.Rounded.Celebration
    EqPreset.JAZZ -> Icons.Rounded.Piano
    EqPreset.CLASSICAL -> Icons.Rounded.LibraryMusic
    EqPreset.ACOUSTIC -> Icons.Rounded.Nature
    EqPreset.LATE_NIGHT -> Icons.Rounded.Bedtime
}

@Composable
private fun Unavailable(message: String) {
    Column(
        Modifier.fillMaxWidth().height(220.dp).padding(horizontal = Dimens.Gutter + 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.GraphicEq, null, tint = SoundColors.TextFaint, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(14.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted, textAlign = TextAlign.Center)
    }
}
