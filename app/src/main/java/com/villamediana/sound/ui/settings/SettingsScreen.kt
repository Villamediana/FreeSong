package com.villamediana.sound.ui.settings

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.BuildConfig
import com.villamediana.sound.R
import com.villamediana.sound.data.AudioQuality
import com.villamediana.sound.data.Settings
import com.villamediana.sound.data.model.LibraryStats
import com.villamediana.sound.ui.components.SoundWordmark
import com.villamediana.sound.ui.components.formatBytes
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.SoundColors

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
        Modifier.fillMaxSize().background(SoundColors.Ink),
        contentPadding = PaddingValues(top = top, bottom = LocalBottomInset.current),
    ) {
        item(key = "top") {
            Column {
                IconButton(onClick = { nav.back() }, modifier = Modifier.padding(start = 6.dp, top = 4.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SoundColors.Text)
                }
                Text(
                    stringResource(R.string.home_settings),
                    style = MaterialTheme.typography.displaySmall,
                    color = SoundColors.Text,
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
                PlaybackGroup(s, vm, onEqualizer = nav::openEqualizer)
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
                        titleColor = SoundColors.Danger,
                        subtitleContent = {
                            AnimatedContent(
                                targetState = cleared,
                                transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
                                label = "cleared",
                            ) { done ->
                                Text(
                                    stringResource(if (done) R.string.home_settings_history_cleared else R.string.home_settings_clear_history_sub),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (done) SoundColors.Success else SoundColors.TextMuted,
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
            containerColor = SoundColors.Surface2,
            shape = RoundedCornerShape(22.dp),
            title = { Text(stringResource(R.string.home_settings_clear_title), style = MaterialTheme.typography.titleLarge, color = SoundColors.Text) },
            text = { Text(stringResource(R.string.home_settings_clear_body), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearHistory()
                }) { Text(stringResource(R.string.home_settings_clear_confirm), color = SoundColors.Danger, style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel), color = SoundColors.TextMuted, style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

@Composable
private fun PlaybackGroup(s: Settings, vm: SettingsViewModel, onEqualizer: () -> Unit) {
    val equalizerOn by vm.equalizerOn.collectAsStateWithLifecycle()
    Group {
        SwitchRow(
            title = stringResource(R.string.home_settings_play_online),
            subtitle = stringResource(R.string.home_settings_play_online_sub),
            checked = s.playOnlineByDefault,
            onChange = vm::setPlayOnline,
        )
        Hairline()
        SettingRow(
            title = stringResource(R.string.home_settings_equalizer),
            subtitle = null,
            onClick = onEqualizer,
            subtitleContent = {
                AnimatedContent(
                    targetState = equalizerOn,
                    transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
                    label = "eqOn",
                ) { on ->
                    Text(
                        stringResource(if (on) R.string.home_settings_equalizer_on else R.string.home_settings_equalizer_sub),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (on) SoundColors.Accent else SoundColors.TextMuted,
                    )
                }
            },
        ) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = SoundColors.TextFaint, modifier = Modifier.size(22.dp))
        }
        Hairline()
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
        color = SoundColors.TextFaint,
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
            .background(SoundColors.Surface1),
        content = content,
    )
}

@Composable
private fun Hairline() = HorizontalDivider(color = SoundColors.Hairline, thickness = 1.dp, modifier = Modifier.padding(start = 16.dp))

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleColor: androidx.compose.ui.graphics.Color = SoundColors.Text,
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
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
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
    // The whole row is the switch: TalkBack reads title, subtitle and on/off as one control.
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = null,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SoundColors.OnAccent,
                checkedTrackColor = SoundColors.Accent,
                checkedBorderColor = SoundColors.Accent,
                uncheckedThumbColor = SoundColors.TextMuted,
                uncheckedTrackColor = SoundColors.Surface3,
                uncheckedBorderColor = SoundColors.Hairline,
            ),
        )
    }
}

@Composable
private fun QualityPicker(current: AudioQuality, onPick: (AudioQuality) -> Unit) {
    Column(Modifier.padding(top = 14.dp, bottom = 6.dp).selectableGroup()) {
        Text(stringResource(R.string.home_settings_quality), style = MaterialTheme.typography.titleMedium, color = SoundColors.Text, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(3.dp))
        Text(stringResource(R.string.home_settings_quality_sub), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted, modifier = Modifier.padding(horizontal = 16.dp))
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
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(q) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioDot(selected)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = if (selected) SoundColors.Text else SoundColors.TextMuted)
                    Spacer(Modifier.height(1.dp))
                    Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        stringResource(R.string.home_settings_quality_size, formatBytes(approxSongBytes(q))),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) SoundColors.Text else SoundColors.TextMuted,
                    )
                    Text(stringResource(R.string.home_settings_quality_per_song), style = MaterialTheme.typography.labelSmall, color = SoundColors.TextFaint)
                }
            }
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val ring by animateColorAsState(if (selected) SoundColors.Accent else SoundColors.TextFaint, label = "ring")
    val dot by animateFloatAsState(if (selected) 1f else 0f, Motion.bouncy(), label = "dot")
    Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(10.dp).scale(dot).clip(CircleShape).background(SoundColors.Accent))
    }
}

@Composable
private fun ParallelRow(current: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.home_settings_parallel), style = MaterialTheme.typography.titleMedium, color = SoundColors.Text)
        Spacer(Modifier.height(3.dp))
        Text(stringResource(R.string.home_settings_parallel_sub), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SoundColors.Surface3).padding(3.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            (1..4).forEach { n ->
                val selected = n == current
                val bg by animateColorAsState(if (selected) SoundColors.Text else SoundColors.Surface3, label = "seg")
                val fg by animateColorAsState(if (selected) SoundColors.Ink else SoundColors.TextMuted, label = "segText")
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(bg)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(n) }),
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
                color = SoundColors.Text,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.home_settings_storage_used),
                style = MaterialTheme.typography.bodyMedium,
                color = SoundColors.TextMuted,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            val st = stats ?: LibraryStats(0, 0, 0, 0, 0)
            Stat(st.trackCount.toString(), pluralStringResource(R.plurals.home_settings_stat_songs, labelQuantity(st.trackCount)), Modifier.weight(1f))
            Stat(st.albumCount.toString(), pluralStringResource(R.plurals.home_settings_stat_albums, labelQuantity(st.albumCount)), Modifier.weight(1f))
            Stat(st.artistCount.toString(), pluralStringResource(R.plurals.home_settings_stat_artists, labelQuantity(st.artistCount)), Modifier.weight(1f))
            Stat(formatHours(st.durationMs), stringResource(R.string.home_settings_stat_hours), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = SoundColors.Text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1)
    }
}

@Composable
private fun About() {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)) {
        SoundWordmark(Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.home_settings_version, versionLabel(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)),
            style = MaterialTheme.typography.bodyMedium,
            color = SoundColors.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.home_settings_credits).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = SoundColors.TextFaint,
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
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = SoundColors.TextFaint, modifier = Modifier.size(18.dp))
    }
}
