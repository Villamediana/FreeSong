package com.villamediana.surco.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.surco.BuildConfig
import com.villamediana.surco.R
import com.villamediana.surco.data.AudioQuality
import com.villamediana.surco.data.Settings
import com.villamediana.surco.data.model.LibraryStats
import com.villamediana.surco.ui.components.SurcoWordmark
import com.villamediana.surco.ui.components.formatBytes
import com.villamediana.surco.ui.nav.navigator
import com.villamediana.surco.ui.theme.Dimens
import com.villamediana.surco.ui.theme.LocalBottomInset
import com.villamediana.surco.ui.theme.Motion
import com.villamediana.surco.ui.theme.SurcoColors

private const val URL_NEWPIPE = "https://github.com/TeamNewPipe/NewPipeExtractor"
private const val URL_ITUNES = "https://performance-partners.apple.com/search-api"
private const val URL_LRCLIB = "https://lrclib.net"

@Composable
fun SettingsScreen() {
    val vm: SettingsViewModel = viewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val usedBytes by vm.usedBytes.collectAsStateWithLifecycle()
    val cleared by vm.historyCleared.collectAsStateWithLifecycle()
    val nav = navigator()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        Modifier.fillMaxSize().background(SurcoColors.Ink),
        contentPadding = PaddingValues(top = top, bottom = LocalBottomInset.current),
    ) {
        item(key = "top") {
            Column {
                IconButton(onClick = { nav.back() }, modifier = Modifier.padding(start = 6.dp, top = 4.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SurcoColors.Text)
                }
                Text(
                    stringResource(R.string.home_settings),
                    style = MaterialTheme.typography.displaySmall,
                    color = SurcoColors.Text,
                    modifier = Modifier.padding(horizontal = Dimens.Gutter).padding(top = 4.dp),
                )
            }
        }

        val s = settings ?: return@LazyColumn

        item(key = "downloads") {
            Column {
                GroupLabel(stringResource(R.string.home_settings_group_downloads))
                Group {
                    QualityPicker(s.audioQuality, vm::setQuality)
                }
                Spacer(Modifier.height(10.dp))
                Group {
                    SwitchRow(
                        title = stringResource(R.string.home_settings_wifi),
                        subtitle = stringResource(R.string.home_settings_wifi_sub),
                        checked = s.wifiOnly,
                        onChange = vm::setWifiOnly,
                    )
                    Hairline()
                    ParallelRow(s.parallelDownloads, vm::setParallel)
                    Hairline()
                    SwitchRow(
                        title = stringResource(R.string.home_settings_lyrics),
                        subtitle = stringResource(R.string.home_settings_lyrics_sub),
                        checked = s.fetchLyrics,
                        onChange = vm::setFetchLyrics,
                    )
                }
            }
        }

        item(key = "playback") {
            Column {
                GroupLabel(stringResource(R.string.home_settings_group_playback))
                PlaybackGroup(s, vm)
            }
        }

        item(key = "storage") {
            Column {
                GroupLabel(stringResource(R.string.home_settings_group_storage))
                Group {
                    StorageBlock(usedBytes, stats)
                    Hairline()
                    SettingRow(
                        title = stringResource(R.string.home_settings_clear_history),
                        subtitle = null,
                        onClick = { confirmClear = true },
                        titleColor = SurcoColors.Danger,
                        subtitleContent = {
                            AnimatedContent(
                                targetState = cleared,
                                transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
                                label = "cleared",
                            ) { done ->
                                Text(
                                    stringResource(if (done) R.string.home_settings_history_cleared else R.string.home_settings_clear_history_sub),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (done) SurcoColors.Success else SurcoColors.TextMuted,
                                )
                            }
                        },
                    )
                }
            }
        }

        item(key = "about") {
            Column {
                GroupLabel(stringResource(R.string.home_settings_group_about))
                Group { About() }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = SurcoColors.Surface2,
            shape = RoundedCornerShape(22.dp),
            title = { Text(stringResource(R.string.home_settings_clear_title), style = MaterialTheme.typography.titleLarge, color = SurcoColors.Text) },
            text = { Text(stringResource(R.string.home_settings_clear_body), style = MaterialTheme.typography.bodyMedium, color = SurcoColors.TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearHistory()
                }) { Text(stringResource(R.string.home_settings_clear_confirm), color = SurcoColors.Danger, style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel), color = SurcoColors.TextMuted, style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

@Composable
private fun PlaybackGroup(s: Settings, vm: SettingsViewModel) {
    Group {
        SwitchRow(
            title = stringResource(R.string.home_settings_smart_shuffle),
            subtitle = stringResource(R.string.home_settings_smart_shuffle_sub),
            checked = s.smartShuffle,
            onChange = vm::setSmartShuffle,
        )
        Hairline()
        SwitchRow(
            title = stringResource(R.string.home_settings_autoplay),
            subtitle = stringResource(R.string.home_settings_autoplay_sub),
            checked = s.autoplaySimilar,
            onChange = vm::setAutoplay,
        )
    }
}

// ---------------------------------------------------------------------------- building blocks

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = SurcoColors.TextFaint,
        modifier = Modifier.padding(start = Dimens.Gutter + 4.dp, end = Dimens.Gutter, top = Dimens.SectionGap, bottom = 10.dp),
    )
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = Dimens.Gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurcoColors.Surface1),
        content = content,
    )
}

@Composable
private fun Hairline() = HorizontalDivider(color = SurcoColors.Hairline, thickness = 1.dp, modifier = Modifier.padding(start = 16.dp))

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleColor: androidx.compose.ui.graphics.Color = SurcoColors.Text,
    subtitleContent: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SurcoColors.TextMuted)
            } else if (subtitleContent != null) {
                Spacer(Modifier.height(3.dp))
                subtitleContent()
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(14.dp))
            trailing()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title = title, subtitle = subtitle, onClick = { onChange(!checked) }) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
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

@Composable
private fun QualityPicker(current: AudioQuality, onPick: (AudioQuality) -> Unit) {
    Column(Modifier.padding(top = 14.dp, bottom = 6.dp)) {
        Text(stringResource(R.string.home_settings_quality), style = MaterialTheme.typography.titleMedium, color = SurcoColors.Text, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(3.dp))
        Text(stringResource(R.string.home_settings_quality_sub), style = MaterialTheme.typography.bodyMedium, color = SurcoColors.TextMuted, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        AudioQuality.entries.forEach { q ->
            val (title, body) = when (q) {
                AudioQuality.BEST -> R.string.home_settings_quality_best to R.string.home_settings_quality_best_sub
                AudioQuality.COMPATIBLE -> R.string.home_settings_quality_compatible to R.string.home_settings_quality_compatible_sub
                AudioQuality.SAVER -> R.string.home_settings_quality_saver to R.string.home_settings_quality_saver_sub
            }
            val selected = q == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(q) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioDot(selected)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = if (selected) SurcoColors.Text else SurcoColors.TextMuted)
                    Spacer(Modifier.height(1.dp))
                    Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = SurcoColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        stringResource(R.string.home_settings_quality_size, formatBytes(approxSongBytes(q))),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) SurcoColors.Text else SurcoColors.TextMuted,
                    )
                    Text(stringResource(R.string.home_settings_quality_per_song), style = MaterialTheme.typography.labelSmall, color = SurcoColors.TextFaint)
                }
            }
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val ring by animateColorAsState(if (selected) SurcoColors.Accent else SurcoColors.TextFaint, label = "ring")
    val dot by animateFloatAsState(if (selected) 1f else 0f, Motion.bouncy(), label = "dot")
    Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(10.dp).scale(dot).clip(CircleShape).background(SurcoColors.Accent))
    }
}

@Composable
private fun ParallelRow(current: Int, onPick: (Int) -> Unit) {
    SettingRow(
        title = stringResource(R.string.home_settings_parallel),
        subtitle = stringResource(R.string.home_settings_parallel_sub),
        onClick = null,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(SurcoColors.Surface3).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            (1..4).forEach { n ->
                val selected = n == current
                val bg by animateColorAsState(if (selected) SurcoColors.Text else SurcoColors.Surface3, label = "seg")
                val fg by animateColorAsState(if (selected) SurcoColors.Ink else SurcoColors.TextMuted, label = "segText")
                Box(
                    Modifier
                        .size(width = 34.dp, height = 32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(bg)
                        .clickable { onPick(n) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$n", style = MaterialTheme.typography.labelLarge, color = fg)
                }
            }
        }
    }
}

@Composable
private fun StorageBlock(usedBytes: Long?, stats: LibraryStats?) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                usedBytes?.let { formatBytes(it) } ?: "—",
                style = MaterialTheme.typography.displaySmall,
                color = SurcoColors.Text,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.home_settings_storage_used),
                style = MaterialTheme.typography.bodyMedium,
                color = SurcoColors.TextMuted,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            val st = stats ?: LibraryStats(0, 0, 0, 0, 0)
            Stat(st.trackCount.toString(), pluralStringResource(R.plurals.home_settings_stat_songs, st.trackCount), Modifier.weight(1f))
            Stat(st.albumCount.toString(), pluralStringResource(R.plurals.home_settings_stat_albums, st.albumCount), Modifier.weight(1f))
            Stat(st.artistCount.toString(), pluralStringResource(R.plurals.home_settings_stat_artists, st.artistCount), Modifier.weight(1f))
            Stat(formatHours(st.durationMs), stringResource(R.string.home_settings_stat_hours), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = SurcoColors.Text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = SurcoColors.TextMuted, maxLines = 1)
    }
}

@Composable
private fun About() {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)) {
        SurcoWordmark(Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.home_settings_version, versionLabel(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)),
            style = MaterialTheme.typography.bodyMedium,
            color = SurcoColors.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.home_settings_credits).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = SurcoColors.TextFaint,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(4.dp))
        Credit("NewPipeExtractor", stringResource(R.string.home_settings_credit_newpipe)) { runCatching { uri.openUri(URL_NEWPIPE) } }
        Credit("iTunes Search API", stringResource(R.string.home_settings_credit_itunes)) { runCatching { uri.openUri(URL_ITUNES) } }
        Credit("LRCLIB", stringResource(R.string.home_settings_credit_lrclib)) { runCatching { uri.openUri(URL_LRCLIB) } }
    }
}

@Composable
private fun Credit(name: String, role: String, onClick: () -> Unit) {
    SettingRow(title = name, subtitle = role, onClick = onClick) {
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = SurcoColors.TextFaint, modifier = Modifier.size(18.dp))
    }
}
