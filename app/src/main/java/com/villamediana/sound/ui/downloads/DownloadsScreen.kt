package com.villamediana.sound.ui.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.DownloadTask
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.EmptyState
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.components.SectionHeader
import com.villamediana.sound.ui.components.formatBytes
import com.villamediana.sound.ui.components.pressScale
import com.villamediana.sound.ui.nav.SearchRoute
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.search.ProgressRing
import com.villamediana.sound.ui.search.shimmer
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Every download: overall progress, album/playlist batches, loose songs, cancel/retry/clear. */
@Composable
fun DownloadsScreen() {
    val vm: DownloadsViewModel = viewModel { DownloadsViewModel.create() }
    val navigator = navigator()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bottomInset = LocalBottomInset.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirmCancelAll by remember { mutableStateOf(false) }
    val looseTitle = stringResource(R.string.dl_loose_title)

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is DownloadsEvent.OpenPlaylist -> navigator.openPlaylist(event.id)
                is DownloadsEvent.OpenAlbum -> navigator.openAlbum(event.key)
                is DownloadsEvent.Message -> {
                    snackbar.currentSnackbarData?.dismiss()
                    scope.launch { snackbar.showSnackbar(context.getString(event.text)) }
                }
            }
        }
    }

    val listState = rememberLazyListState()
    val titleAlpha by remember { derivedStateOf { if (listState.firstVisibleItemIndex > 0) 1f else 0f } }
    val animatedTitleAlpha by animateFloatAsState(titleAlpha, tween(180), label = "dlTitle")

    Box(Modifier.fillMaxSize().background(SoundColors.Ink)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = bottomInset),
        ) {
            item(key = "title") {
                Text(
                    stringResource(R.string.dl_title),
                    style = MaterialTheme.typography.displaySmall,
                    color = SoundColors.Text,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = Dimens.Gutter, end = Dimens.Gutter, top = 64.dp, bottom = 18.dp),
                )
            }
            val state = ui
            when {
                state == null -> items(5, key = { "skeleton-$it" }) {
                    Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = Dimens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(Dimens.RowCover).shimmer(RoundedCornerShape(Dimens.CoverCorner)))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth(0.6f).height(13.dp).shimmer())
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.fillMaxWidth(0.35f).height(11.dp).shimmer())
                        }
                    }
                }
                state.groups.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.DownloadDone,
                        title = stringResource(R.string.dl_empty_title),
                        body = stringResource(R.string.dl_empty_body),
                        modifier = Modifier.padding(top = 40.dp),
                        action = { PillButton(stringResource(R.string.dl_empty_action), { navigator.switchTab(SearchRoute) }, icon = Icons.Rounded.Search, filled = true) },
                    )
                }
                else -> {
                    item(key = "overview") { OverviewCard(state.overview, Modifier.animateItem()) }
                    item(key = "actions") {
                        HeaderActions(
                            overview = state.overview,
                            onRetryFailed = vm::retryAllFailed,
                            onClear = vm::clearFinished,
                            onCancelAll = { confirmCancelAll = true },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    state.groups.forEach { group ->
                        val collapsed = vm.isCollapsed(group)
                        if (group.isBatch) {
                            item(key = group.key, contentType = "batch") {
                                BatchHeader(
                                    group = group,
                                    collapsed = collapsed,
                                    onToggle = { vm.toggle(group) },
                                    onOpen = { vm.openBatch(group) },
                                    onCancel = { vm.cancelBatch(group) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        } else {
                            item(key = group.key, contentType = "looseHeader") {
                                SectionHeader(stringResource(R.string.dl_loose_title), Modifier.animateItem().padding(top = 6.dp))
                            }
                        }
                        if (!collapsed) {
                            items(group.tasks, key = { "task:${it.id}" }, contentType = { "task" }) { task ->
                                TaskRow(
                                    task = task,
                                    onPlay = { vm.play(task, group, looseTitle) },
                                    onCancel = { vm.cancel(task) },
                                    onRetry = { vm.retry(task) },
                                    indent = group.isBatch,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                        item(key = "${group.key}:gap") { Spacer(Modifier.height(14.dp)) }
                    }
                }
            }
        }

        // Fixed top bar: back, and the small title once the big one scrolled away.
        Row(
            Modifier
                .fillMaxWidth()
                .background(SoundColors.Ink.copy(alpha = 0.94f * animatedTitleAlpha))
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = navigator::back) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SoundColors.Text)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.dl_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = SoundColors.Text,
                modifier = Modifier.graphicsLayer { alpha = animatedTitleAlpha },
            )
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = (bottomInset - 8.dp).coerceAtLeast(12.dp)),
        ) { data ->
            Text(
                data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = SoundColors.Text,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SoundColors.Surface3)
                    .padding(horizontal = 18.dp, vertical = 15.dp),
            )
        }
    }

    if (confirmCancelAll) {
        AlertDialog(
            onDismissRequest = { confirmCancelAll = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancelAll = false
                    vm.cancelAll()
                }) { Text(stringResource(R.string.dl_cancel_all), color = SoundColors.Danger, style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancelAll = false }) {
                    Text(stringResource(R.string.dl_keep), color = SoundColors.Text, style = MaterialTheme.typography.labelLarge)
                }
            },
            title = { Text(stringResource(R.string.dl_cancel_all_title), style = MaterialTheme.typography.headlineSmall) },
            text = { Text(stringResource(R.string.dl_cancel_all_body), style = MaterialTheme.typography.bodyMedium) },
            containerColor = SoundColors.Surface2,
            titleContentColor = SoundColors.Text,
            textContentColor = SoundColors.TextMuted,
            shape = RoundedCornerShape(24.dp),
        )
    }
}

// ---------------------------------------------------------------------------- header

@Composable
private fun OverviewCard(o: DownloadOverview, modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(horizontal = Dimens.Gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SoundColors.Surface1)
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        if (o.isActive) {
            Text(stringResource(R.string.dl_overline_active).uppercase(), style = MaterialTheme.typography.labelSmall, color = SoundColors.Accent)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${o.waveDone}", style = MaterialTheme.typography.displayMedium, color = SoundColors.Text)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.dl_of_total, o.waveTotal),
                    style = MaterialTheme.typography.headlineSmall,
                    color = SoundColors.TextMuted,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.dl_state_percent, ((o.fraction ?: 0f) * 100).roundToInt()),
                    style = MaterialTheme.typography.titleMedium,
                    color = SoundColors.Accent,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Bar(o.fraction ?: 0f, height = 6.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                listOfNotNull(
                    o.running.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.dl_running, it, it) },
                    o.queued.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.dl_queued, it, it) },
                    o.failed.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.dl_failed, it, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = SoundColors.TextMuted,
            )
        } else {
            Text(stringResource(R.string.dl_overline_idle).uppercase(), style = MaterialTheme.typography.labelSmall, color = SoundColors.TextFaint)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(if (o.failed > 0) SoundColors.Danger.copy(alpha = 0.18f) else SoundColors.AccentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (o.failed > 0) Icons.Rounded.Refresh else Icons.Rounded.Check,
                        null,
                        tint = if (o.failed > 0) SoundColors.Danger else SoundColors.Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(if (o.failed > 0) R.string.dl_idle_title_failed else R.string.dl_idle_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = SoundColors.Text,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                listOfNotNull(
                    o.done.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.dl_done, it, it) },
                    o.failed.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.dl_failed, it, it) },
                    o.canceled.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.dl_canceled, it, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = SoundColors.TextMuted,
            )
        }
    }
}

@Composable
private fun HeaderActions(
    overview: DownloadOverview,
    onRetryFailed: () -> Unit,
    onClear: () -> Unit,
    onCancelAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasAny = overview.failed > 0 || overview.finished > 0 || overview.isActive
    if (!hasAny) {
        Spacer(modifier.height(18.dp))
        return
    }
    FlowRow(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Gutter, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (overview.failed > 0) PillButton(stringResource(R.string.dl_retry_failed), onRetryFailed, icon = Icons.Rounded.Refresh)
        if (overview.finished > 0) PillButton(stringResource(R.string.dl_clear_done), onClear, icon = Icons.Rounded.ClearAll)
        if (overview.isActive) PillButton(stringResource(R.string.dl_cancel_all), onCancelAll, icon = Icons.Rounded.Close)
    }
}

@Composable
private fun Bar(fraction: Float, height: Dp, modifier: Modifier = Modifier, color: Color = SoundColors.Accent) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(450), label = "bar")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(SoundColors.Surface3)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(animated).clip(CircleShape).background(color))
    }
}

// ---------------------------------------------------------------------------- batches

@Composable
private fun BatchHeader(
    group: DownloadGroup,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = group.title ?: stringResource(R.string.dl_batch_untitled)
    val s = group.stats
    val arrow by animateFloatAsState(if (collapsed) 0f else 180f, tween(240), label = "arrow")
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(if (collapsed) R.string.dl_batch_expand else R.string.dl_batch_collapse), onClick = onToggle)
            .padding(start = Dimens.Gutter, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(group.coverUrl, Modifier.size(56.dp), seed = title)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            val line = when {
                s.isActive -> stringResource(R.string.dl_batch_progress, s.done, s.wanted)
                else -> listOfNotNull(
                    s.done.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.dl_done, it, it) },
                    s.failed.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.dl_failed, it, it) },
                    s.canceled.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.dl_canceled, it, it) },
                ).joinToString(" · ")
            }
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = if (s.isActive) SoundColors.Accent else if (s.failed > 0) SoundColors.Danger else SoundColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AnimatedVisibility(s.isActive, enter = fadeIn(), exit = fadeOut()) {
                Bar(s.fraction, height = 3.dp, modifier = Modifier.padding(top = 7.dp, end = 8.dp))
            }
        }
        if (s.isActive) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, stringResource(R.string.dl_batch_cancel, title), tint = SoundColors.TextMuted, modifier = Modifier.size(20.dp))
            }
        } else if (s.done > 0) {
            IconButton(onClick = onOpen) {
                Icon(Icons.Rounded.LibraryMusic, stringResource(R.string.dl_batch_open), tint = SoundColors.TextMuted, modifier = Modifier.size(20.dp))
            }
        }
        Icon(
            Icons.Rounded.ExpandMore,
            null,
            tint = SoundColors.TextFaint,
            modifier = Modifier.padding(end = 8.dp).size(22.dp).rotate(arrow),
        )
    }
}

// ---------------------------------------------------------------------------- tasks

@Composable
private fun TaskRow(
    task: DownloadTask,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    indent: Boolean,
    modifier: Modifier = Modifier,
) {
    val active = task.state == DownloadState.QUEUED || task.state == DownloadState.RUNNING
    if (!active) {
        TaskRowContent(task, onPlay, onCancel, onRetry, indent, modifier)
        return
    }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // A fresh swipe state per task state: once canceled the row stops being swipeable.
    key(task.id, task.state) {
        val swipe = rememberSwipeToDismissBoxState()
        SwipeToDismissBox(
            state = swipe,
            modifier = modifier,
            enableDismissFromStartToEnd = false,
            onDismiss = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onCancel()
                scope.launch { swipe.reset() }
            },
            backgroundContent = {
                val reveal by animateColorAsState(
                    if (swipe.targetValue == SwipeToDismissBoxValue.EndToStart) SoundColors.Danger.copy(alpha = 0.85f) else SoundColors.Danger.copy(alpha = 0.35f),
                    tween(160),
                    label = "swipeBg",
                )
                Row(
                    Modifier.fillMaxSize().background(reveal).padding(horizontal = Dimens.Gutter),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(stringResource(R.string.dl_swipe_cancel), style = MaterialTheme.typography.labelLarge, color = SoundColors.Text)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.Close, null, tint = SoundColors.Text, modifier = Modifier.size(20.dp))
                }
            },
        ) {
            TaskRowContent(task, onPlay, onCancel, onRetry, indent, Modifier)
        }
    }
}

@Composable
private fun TaskRowContent(
    task: DownloadTask,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    indent: Boolean,
    modifier: Modifier,
) {
    val done = task.state == DownloadState.DONE
    val canceled = task.state == DownloadState.CANCELED
    Row(
        modifier
            .fillMaxWidth()
            .background(SoundColors.Ink)
            .height(70.dp)
            .clickable(enabled = done, onClick = onPlay)
            .padding(start = if (indent) Dimens.Gutter + 4.dp else Dimens.Gutter, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Cover(
                task.thumbnailUrl ?: task.albumCoverUrl,
                Modifier.size(if (indent) 44.dp else Dimens.RowCover).graphicsLayer { alpha = if (canceled) 0.45f else 1f },
                seed = task.album ?: task.title,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (canceled) SoundColors.TextFaint else SoundColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            StatusLine(task)
        }
        Spacer(Modifier.width(4.dp))
        TaskTrailing(task, onCancel, onRetry)
    }
}

@Composable
private fun StatusLine(task: DownloadTask) {
    val artist = task.artist.takeIf { it.isNotBlank() }
    when (task.state) {
        DownloadState.RUNNING -> Column {
            val percent = stringResource(R.string.dl_state_percent, (task.progress.coerceIn(0f, 1f) * 100).roundToInt())
            val label = when {
                task.bytesTotal > 0 -> percent + " · " + stringResource(R.string.dl_state_bytes, formatBytes(task.bytesDone), formatBytes(task.bytesTotal))
                task.progress > 0f -> percent
                else -> stringResource(R.string.dl_state_starting)
            }
            Text(label, style = MaterialTheme.typography.bodySmall, color = SoundColors.Accent, maxLines = 1)
            Bar(task.progress, height = 3.dp, modifier = Modifier.padding(top = 6.dp, end = 10.dp))
        }
        DownloadState.QUEUED -> StateText(listOfNotNull(stringResource(R.string.dl_state_queued), artist).joinToString(" · "), SoundColors.TextMuted)
        DownloadState.DONE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Check, null, tint = SoundColors.Success, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            StateText(listOfNotNull(stringResource(R.string.dl_state_done), artist).joinToString(" · "), SoundColors.TextMuted)
        }
        DownloadState.FAILED -> StateText(
            listOfNotNull(stringResource(R.string.dl_state_failed), task.error?.takeIf { it.isNotBlank() } ?: artist).joinToString(" · "),
            SoundColors.Danger,
        )
        DownloadState.CANCELED -> StateText(listOfNotNull(stringResource(R.string.dl_state_canceled), artist).joinToString(" · "), SoundColors.TextFaint)
    }
}

@Composable
private fun StateText(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun TaskTrailing(task: DownloadTask, onCancel: () -> Unit, onRetry: () -> Unit) {
    when (task.state) {
        DownloadState.QUEUED, DownloadState.RUNNING -> {
            // The ring doubles as the cancel button: progress with a stop square, or a quiet × while queued.
            val interaction = remember { MutableInteractionSource() }
            val description = stringResource(R.string.dl_cancel)
            Box(
                Modifier
                    .size(48.dp)
                    .pressScale(interaction, 0.88f)
                    .clip(CircleShape)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onCancel)
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                if (task.state == DownloadState.RUNNING) {
                    ProgressRing(task.progress, 28.dp)
                } else {
                    Box(Modifier.size(28.dp).border(1.6.dp, SoundColors.Hairline, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, null, tint = SoundColors.TextMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        DownloadState.FAILED -> IconButton(onClick = onRetry) {
            Icon(Icons.Rounded.Refresh, stringResource(R.string.dl_retry), tint = SoundColors.Text)
        }
        DownloadState.CANCELED -> IconButton(onClick = onRetry) {
            Icon(Icons.Rounded.Download, stringResource(R.string.dl_redownload), tint = SoundColors.TextMuted)
        }
        DownloadState.DONE -> Spacer(Modifier.width(12.dp))
    }
}
