package com.villamediana.sound.ui.player

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
import androidx.compose.foundation.lazy.LazyItemScope
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
import com.villamediana.sound.R
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.playback.SleepTimer
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.ui.components.TrackRow
import com.villamediana.sound.ui.sheets.TrackActionsSheet
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun SheetGrip() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(SoundColors.Hairline))
    }
}

@Composable
private fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = SoundColors.TextFaint, modifier = modifier)
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
        containerColor = SoundColors.Surface2,
        contentColor = SoundColors.Text,
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
    val upstream = remember(state.queue, state.queueIndex, state.suggested) {
        queueEntries(state.upNext) { i -> state.queueIndex + 1 + i in state.suggested }
    }
    var entries by remember { mutableStateOf(upstream) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val reorder = remember(listState) {
        with(density) { ReorderState(listState, scope, edgePx = 64.dp.toPx(), maxStepPx = 18.dp.toPx()) }
    }
    var dragBase by remember { mutableStateOf(-1 to -1) } // (queueIndex, start position) of the drag in progress
    var actionsFor by remember { mutableStateOf<Track?>(null) }
    val openActions: (Track) -> Unit = { t ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        actionsFor = t
    }

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
            entries = queueEntries(latest.upNext) { i -> latest.queueIndex + 1 + i in latest.suggested } // the song changed mid-drag: show the real queue
        }
        dragBase = -1 to -1
    }

    val entryRow: @Composable LazyItemScope.(QueueEntry) -> Unit = { entry ->
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
                    onLongClick = { openActions(entry.track) },
                    contentPadding = PaddingValues(start = Dimens.Gutter, end = 4.dp),
                    trailing = {
                        Box(
                            Modifier
                                .size(44.dp)
                                .reorderHandle(reorder, entry.key),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.DragHandle, stringResource(R.string.player_queue_reorder), tint = SoundColors.TextFaint)
                        }
                    },
                )
            }
        }
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
                Text(stringResource(R.string.player_queue), style = MaterialTheme.typography.headlineSmall, color = SoundColors.Text, modifier = Modifier.weight(1f))
                if (state.smart) SmartBadge()
            }
        }
        state.current?.let { current ->
            item(key = "now-label") { Overline(stringResource(R.string.player_queue_now), Modifier.padding(horizontal = Dimens.Gutter).padding(bottom = 2.dp)) }
            item(key = "now") {
                TrackRow(
                    track = current,
                    onClick = { },
                    onLongClick = { openActions(current) },
                    isCurrent = true,
                    isPlaying = state.isPlaying || state.isBuffering,
                )
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
                        color = SoundColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.smart) {
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.player_queue_smart_hint), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint)
                }
            }
        }
        if (entries.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 20.dp)) {
                    // A smart queue (repeat off) is about to get more songs; any other ends with this one.
                    val more = state.smart && state.repeat == com.villamediana.sound.playback.RepeatMode.OFF
                    Text(
                        stringResource(if (more) R.string.player_queue_more else R.string.player_queue_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = SoundColors.Text,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(if (more) R.string.player_queue_more_body else R.string.player_queue_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SoundColors.TextMuted,
                    )
                }
            }
        }
        // Yours first, then (under their own heading) the ones the app added to keep going.
        val split = entries.indexOfFirst { it.suggested }.let { if (it < 0) entries.size else it }
        itemsIndexed(entries.subList(0, split), key = { _, e -> e.key }) { _, entry -> entryRow(entry) }
        if (split < entries.size) {
            item(key = "suggested-label") {
                Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter).padding(top = 18.dp, bottom = 6.dp)) {
                    Overline(stringResource(R.string.player_queue_suggested))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.player_queue_suggested_hint), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint)
                }
            }
            itemsIndexed(entries.subList(split, entries.size), key = { _, e -> e.key }) { _, entry -> entryRow(entry) }
        }
    }
    actionsFor?.let { t -> TrackActionsSheet(track = t, onDismiss = { actionsFor = null }) }
}

@Composable
private fun SmartBadge() {
    Row(
        Modifier.clip(CircleShape).background(SoundColors.AccentSoft).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = SoundColors.Accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(stringResource(R.string.player_queue_smart), style = MaterialTheme.typography.labelMedium, color = SoundColors.Accent)
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
        containerColor = SoundColors.Surface2,
        contentColor = SoundColors.Text,
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
                Modifier.size(44.dp).clip(CircleShape).background(if (timer == SleepTimer.Off) SoundColors.Surface3 else SoundColors.AccentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Bedtime, null, tint = if (timer == SleepTimer.Off) SoundColors.TextMuted else SoundColors.Accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.player_sleep), style = MaterialTheme.typography.headlineSmall, color = SoundColors.Text)
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
                Icon(Icons.Rounded.Close, null, tint = SoundColors.TextMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.player_sleep_off), style = MaterialTheme.typography.titleMedium, color = SoundColors.TextMuted)
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
            Text(stringResource(R.string.player_sleep_ends_in, PlayerMath.countdown(timer.endsAtElapsedMs - now)), style = style, color = SoundColors.Accent)
        }
        SleepTimer.EndOfTrack -> Text(stringResource(R.string.player_sleep_at_end), style = style, color = SoundColors.Accent)
        SleepTimer.Off -> Text(stringResource(R.string.player_sleep_hint), style = style, color = SoundColors.TextMuted)
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
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (selected) SoundColors.Accent else SoundColors.Text)
        if (selected) Icon(Icons.Rounded.Check, null, tint = SoundColors.Accent, modifier = Modifier.size(20.dp))
    }
}
