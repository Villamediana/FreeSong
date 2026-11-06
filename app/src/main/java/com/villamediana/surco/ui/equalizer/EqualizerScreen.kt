package com.villamediana.surco.ui.equalizer

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
import com.villamediana.surco.R
import com.villamediana.surco.playback.EqPreset
import com.villamediana.surco.playback.EqState
import com.villamediana.surco.ui.components.FilterPill
import com.villamediana.surco.ui.components.PillButton
import com.villamediana.surco.ui.nav.navigator
import com.villamediana.surco.ui.theme.Dimens
import com.villamediana.surco.ui.theme.LocalBottomInset
import com.villamediana.surco.ui.theme.Motion
import com.villamediana.surco.ui.theme.SurcoColors

@Composable
fun EqualizerScreen() {
    val vm: EqualizerViewModel = viewModel { EqualizerViewModel.create() }
    val state by vm.state.collectAsStateWithLifecycle()
    val hasMedia by vm.hasMedia.collectAsStateWithLifecycle()
    val nav = navigator()
    EqualizerContent(
        state = state,
        hasMedia = hasMedia,
        onBack = nav::back,
        onEnabled = vm::setEnabled,
        onPreset = vm::selectPreset,
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
    onEnabled: (Boolean) -> Unit,
    onPreset: (EqPreset) -> Unit,
    onBand: (Int, Int) -> Unit,
    onBassBoost: (Int) -> Unit,
    onVirtualizer: (Int) -> Unit,
    onReset: () -> Unit,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val active = state.enabled && state.available

    Column(
        Modifier
            .fillMaxSize()
            .background(SurcoColors.Ink)
            .verticalScroll(rememberScrollState())
            .padding(top = top),
    ) {
        TopBar(enabled = state.enabled, onBack = onBack, onEnabled = onEnabled)
        Hero(state, onReset)
        Spacer(Modifier.height(4.dp))

        if (state.available) {
            EqCurveGraph(
                bands = state.bands,
                minMb = state.minLevelMb,
                maxMb = state.maxLevelMb,
                enabled = active,
                onBandChange = onBand,
            )
            Spacer(Modifier.height(18.dp))
            PresetRow(selected = state.preset, enabled = active, onPreset = onPreset)
        } else {
            Unavailable(stringResource(if (hasMedia) R.string.eq_unavailable_device else R.string.eq_unavailable_idle))
        }

        if (state.bassBoostAvailable || state.virtualizerAvailable) {
            Spacer(Modifier.height(Dimens.SectionGap + 4.dp))
            Text(
                stringResource(R.string.eq_effects).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = SurcoColors.TextFaint,
                modifier = Modifier.padding(horizontal = Dimens.Gutter),
            )
            Spacer(Modifier.height(16.dp))
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

        Spacer(Modifier.height(LocalBottomInset.current))
    }
}

@Composable
private fun TopBar(enabled: Boolean, onBack: () -> Unit, onEnabled: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val switchLabel = stringResource(R.string.eq_enable)
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SurcoColors.Text)
        }
        Text(
            stringResource(R.string.eq_title),
            style = MaterialTheme.typography.titleLarge,
            color = SurcoColors.Text,
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
                checkedThumbColor = SurcoColors.OnAccent,
                checkedTrackColor = SurcoColors.Accent,
                checkedBorderColor = SurcoColors.Accent,
                uncheckedThumbColor = SurcoColors.TextMuted,
                uncheckedTrackColor = SurcoColors.Surface3,
                uncheckedBorderColor = SurcoColors.Hairline,
            ),
        )
    }
}

/** What's on now, big: the preset's name (or "Personalizado"), and the way back to flat. */
@Composable
private fun Hero(state: EqState, onReset: () -> Unit) {
    val nameColor by animateColorAsState(if (state.enabled) SurcoColors.Text else SurcoColors.TextMuted, Motion.enter(), label = "name")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter).padding(top = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.eq_profile).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = SurcoColors.TextFaint,
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

@Composable
private fun PresetRow(selected: EqPreset?, enabled: Boolean, onPreset: (EqPreset) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val list = rememberLazyListState()
    val dim by animateFloatAsState(if (enabled) 1f else 0.4f, Motion.enter(), label = "presets")
    // Open with the current preset in view (with its neighbour on the left as context).
    LaunchedEffect(Unit) {
        val index = if (selected == null) 0 else EqPreset.entries.indexOf(selected)
        if (index > 1) list.scrollToItem(index - 1)
    }
    LazyRow(
        state = list,
        modifier = Modifier.fillMaxWidth().alpha(dim),
        contentPadding = PaddingValues(horizontal = Dimens.Gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selected == null) {
            item(key = "custom") {
                FilterPill(stringResource(R.string.eq_preset_custom), selected = true, onClick = {}, modifier = Modifier.animateItem())
            }
        }
        items(EqPreset.entries, key = { it.name }) { preset ->
            FilterPill(
                text = stringResource(presetLabel(preset)),
                selected = preset == selected,
                onClick = {
                    if (enabled && preset != selected) {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onPreset(preset)
                    }
                },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun Unavailable(message: String) {
    Column(
        Modifier.fillMaxWidth().height(220.dp).padding(horizontal = Dimens.Gutter + 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.GraphicEq, null, tint = SurcoColors.TextFaint, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(14.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = SurcoColors.TextMuted, textAlign = TextAlign.Center)
    }
}
