package com.villamediana.surco.ui.player

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.villamediana.surco.R
import com.villamediana.surco.playback.PlaybackState
import com.villamediana.surco.playback.SleepTimer
import com.villamediana.surco.ui.components.TrackRow
import com.villamediana.surco.ui.theme.Dimens
import com.villamediana.surco.ui.theme.SurcoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun SheetGrip() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(SurcoColors.Hairline))
    }
}

@Composable
private fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = SurcoColors.TextFaint, modifier = modifier)
}

// ---------------------------------------------------------------------------------------- queue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(
    state: PlaybackState,
    onSkipTo: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurcoColors.Surface2,
        contentColor = SurcoColors.Text,
        dragHandle = { SheetGrip() },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        QueueContent(state, onSkipTo, onMove, onRemove)
    }
}

/** "Tocando agora" + "A seguir": drag the grip to reorder, swipe a row away to remove it, tap to jump. */
@Composable
internal fun QueueContent(
    state: PlaybackState,
    onSkipTo: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest by rememberUpdatedState(state)
    val upstream = remember(state.queue, state.queueIndex) { queueEntries(state.upNext) }
    var entries by remember { mutableStateOf(upstream) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val reorder = remember(listState) {
        with(density) { ReorderState(listState, scope, edgePx = 64.dp.toPx(), maxStepPx = 18.dp.toPx()) }
    }
    var dragBase by remember { mutableStateOf(-1 to -1) } // (queueIndex, start position) of the drag in progress

    LaunchedEffect(upstream) { if (!reorder.active) entries = upstream }

    reorder.canSwapWith = { key -> key is String && key.contains('#') }
    // Remember where the drag started (in "A seguir" positions) and from which current song.
    reorder.onStart = { key -> dragBase = latest.queueIndex to entries.indexOfFirst { it.key == key } }
    reorder.onSwap = { from, to ->
        val a = entries.indexOfFirst { it.key == from }
        val b = entries.indexOfFirst { it.key == to }
        if (a >= 0 && b >= 0) {
            entries = PlayerMath.moved(entries, a, b)
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
    reorder.onDrop = { key ->
        val (base, start) = dragBase
        val end = entries.indexOfFirst { it.key == key }
        if (base == latest.queueIndex && start >= 0 && end >= 0 && start != end) {
            onMove(PlayerMath.upNextToQueueIndex(base, start), PlayerMath.upNextToQueueIndex(base, end))
        } else if (base != latest.queueIndex) {
            entries = queueEntries(latest.upNext) // the song changed mid-drag: show the real queue
        }
        dragBase = -1 to -1
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // A long queue grows the sheet, but never under the status bar.
    val maxHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } - statusTop - 24.dp
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().heightIn(max = maxHeight.coerceAtLeast(200.dp)),
        contentPadding = PaddingValues(bottom = navBottom + 16.dp),
    ) {
        item(key = "title") {
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter).padding(top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.player_queue), style = MaterialTheme.typography.headlineSmall, color = SurcoColors.Text, modifier = Modifier.weight(1f))
                if (state.smart) SmartBadge()
            }
        }
        state.current?.let { current ->
            item(key = "now-label") { Overline(stringResource(R.string.player_queue_now), Modifier.padding(horizontal = Dimens.Gutter).padding(bottom = 2.dp)) }
            item(key = "now") {
                TrackRow(track = current, onClick = { }, isCurrent = true, isPlaying = state.isPlaying || state.isBuffering)
            }
        }
        item(key = "next-label") {
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter).padding(top = 18.dp, bottom = 6.dp)) {
                Overline(stringResource(R.string.player_queue_next))
                state.source?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.player_queue_from, it.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SurcoColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.smart) {
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.player_queue_smart_hint), style = MaterialTheme.typography.bodySmall, color = SurcoColors.TextFaint)
                }
            }
        }
        if (entries.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 20.dp)) {
                    Text(stringResource(R.string.player_queue_empty), style = MaterialTheme.typography.titleMedium, color = SurcoColors.Text)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.player_queue_empty_body), style = MaterialTheme.typography.bodyMedium, color = SurcoColors.TextMuted)
                }
            }
        }
        itemsIndexed(entries, key = { _, e -> e.key }) { _, entry ->
            val dragging = reorder.draggingKey == entry.key
            val lift by animateFloatAsState(if (dragging) 1f else 0f, label = "lift")
            Box(
                Modifier
                    .zIndex(if (dragging || reorder.settlingKey == entry.key) 1f else 0f)
                    .then(if (dragging || reorder.settlingKey == entry.key) Modifier else Modifier.animateItem())
                    .graphicsLayer {
                        translationY = reorder.translation(entry.key)
                        shadowElevation = 14.dp.toPx() * lift
                        shape = RoundedCornerShape(10.dp)
                        scaleX = 1f + 0.02f * lift
                        scaleY = 1f + 0.02f * lift
                    },
            ) {
                SwipeToRemove(
                    onRemove = {
                        val i = entries.indexOfFirst { it.key == entry.key }
                        if (i >= 0) {
                            entries = entries.filterNot { it.key == entry.key }
                            onRemove(PlayerMath.upNextToQueueIndex(latest.queueIndex, i))
                        }
                    },
                ) {
                    TrackRow(
                        track = entry.track,
                        onClick = {
                            val i = entries.indexOfFirst { it.key == entry.key }
                            if (i >= 0) onSkipTo(PlayerMath.upNextToQueueIndex(latest.queueIndex, i))
                        },
                        contentPadding = PaddingValues(start = Dimens.Gutter, end = 4.dp),
                        trailing = {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .reorderHandle(reorder, entry.key),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.DragHandle, stringResource(R.string.player_queue_reorder), tint = SurcoColors.TextFaint)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SmartBadge() {
    Row(
        Modifier.clip(CircleShape).background(SurcoColors.AccentSoft).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = SurcoColors.Accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(stringResource(R.string.player_queue_smart), style = MaterialTheme.typography.labelMedium, color = SurcoColors.Accent)
    }
}

// ---------------------------------------------------------------------------------------- sleep

internal val SleepOptionsMinutes = listOf(5, 15, 30, 45, 60)

/** Live elapsedRealtime while [active] (ticks twice a second), for countdowns. */
@Composable
internal fun rememberElapsedRealtime(active: Boolean, periodMs: Long = 500): Long {
    val now by produceState(SystemClock.elapsedRealtime(), active) {
        value = SystemClock.elapsedRealtime()
        while (active) {
            delay(periodMs)
            value = SystemClock.elapsedRealtime()
        }
    }
    return now
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepTimerSheet(timer: SleepTimer, onSet: (SleepTimer) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val choose: (SleepTimer) -> Unit = { t ->
        onSet(t)
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurcoColors.Surface2,
        contentColor = SurcoColors.Text,
        dragHandle = { SheetGrip() },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        SleepTimerContent(timer, choose)
    }
}

@Composable
internal fun SleepTimerContent(timer: SleepTimer, onChoose: (SleepTimer) -> Unit, modifier: Modifier = Modifier) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier.fillMaxWidth().padding(bottom = navBottom + 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter).padding(top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (timer == SleepTimer.Off) SurcoColors.Surface3 else SurcoColors.AccentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Bedtime, null, tint = if (timer == SleepTimer.Off) SurcoColors.TextMuted else SurcoColors.Accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.player_sleep), style = MaterialTheme.typography.headlineSmall, color = SurcoColors.Text)
                SleepStatus(timer)
            }
        }
        SleepOptionsMinutes.forEach { minutes ->
            val ms = minutes * 60_000L
            SleepOption(
                text = pluralStringResource(R.plurals.player_sleep_minutes, minutes, minutes),
                selected = timer is SleepTimer.At && timer.totalMs == ms,
                onClick = { onChoose(SleepTimer.At(SystemClock.elapsedRealtime() + ms, ms)) },
            )
        }
        SleepOption(
            text = stringResource(R.string.player_sleep_end_of_track),
            selected = timer == SleepTimer.EndOfTrack,
            onClick = { onChoose(SleepTimer.EndOfTrack) },
        )
        if (timer != SleepTimer.Off) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onChoose(SleepTimer.Off) }
                    .padding(horizontal = Dimens.Gutter, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Close, null, tint = SurcoColors.TextMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.player_sleep_off), style = MaterialTheme.typography.titleMedium, color = SurcoColors.TextMuted)
            }
        }
    }
}

@Composable
private fun SleepStatus(timer: SleepTimer) {
    val style = MaterialTheme.typography.bodyMedium
    when (timer) {
        is SleepTimer.At -> {
            val now = rememberElapsedRealtime(active = true, periodMs = 250)
            Text(stringResource(R.string.player_sleep_ends_in, PlayerMath.countdown(timer.endsAtElapsedMs - now)), style = style, color = SurcoColors.Accent)
        }
        SleepTimer.EndOfTrack -> Text(stringResource(R.string.player_sleep_at_end), style = style, color = SurcoColors.Accent)
        SleepTimer.Off -> Text(stringResource(R.string.player_sleep_hint), style = style, color = SurcoColors.TextMuted)
    }
}

@Composable
private fun SleepOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.Gutter, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (selected) SurcoColors.Accent else SurcoColors.Text)
        if (selected) Icon(Icons.Rounded.Check, null, tint = SurcoColors.Accent, modifier = Modifier.size(20.dp))
    }
}
