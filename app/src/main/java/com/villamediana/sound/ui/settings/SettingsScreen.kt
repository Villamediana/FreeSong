package com.villamediana.sound.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Wifi
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.BuildConfig
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.AudioQuality
import com.villamediana.sound.data.CacheKind
import com.villamediana.sound.data.ThemeMode
import com.villamediana.sound.data.model.LibraryStats
import com.villamediana.sound.ui.components.SoundMark
import com.villamediana.sound.ui.components.formatBytes
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.sheets.SoundSheet
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.SoundColors

/** The icon chips' colors: one per row, the app's palette (the same as the covers'). */
private object Chip {
    val Blue = Color(0xFF2F7BE0)
    val Indigo = Color(0xFF2341C9)
    val Violet = Color(0xFF5B3FD6)
    val Purple = Color(0xFF7A3FC2)
    val Pink = Color(0xFFB0397A)
    val Red = Color(0xFFD9453B)
    val Orange = Color(0xFFC2563A)
    val Amber = Color(0xFFC98A2B)
    val Green = Color(0xFF1E9E6E)
    val Teal = Color(0xFF0E8C8C)
    val Slate = Color(0xFF4A5468)
}

/**
 * Ajustes, by what one wants to do: your profile up top; Som (equalizer, video); Downloads
 * (quality, Wi-Fi, smart, likes, lyrics, how many at once); Aparência (theme, Início tab);
 * Armazenamento (what is used and free, the library in numbers, freeing space, the history); Sobre.
 * Every row has its colored icon and says its current value; long choices open in a sheet.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val vm: SettingsViewModel = viewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val usedBytes by vm.usedBytes.collectAsStateWithLifecycle()
    val caches by vm.caches.collectAsStateWithLifecycle()
    val freeBytes by vm.freeBytes.collectAsStateWithLifecycle()
    val equalizerOn by vm.equalizerOn.collectAsStateWithLifecycle()
    val nav = navigator()
    val context = androidx.compose.ui.platform.LocalContext.current
    // "Lembrar lugares": the location is asked for when it is turned on (approximate is enough).
    fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val hasLocation = granted(android.Manifest.permission.ACCESS_COARSE_LOCATION) || granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
    val hasAlways = android.os.Build.VERSION.SDK_INT < 29 || granted(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) vm.setLearnPlaces(true)
    }
    val askAlways = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    var qualityOpen by rememberSaveable { mutableStateOf(false) }
    var spaceOpen by rememberSaveable { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        Modifier.fillMaxSize().background(SoundColors.Ink),
        contentPadding = PaddingValues(top = top, bottom = LocalBottomInset.current + 12.dp),
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
                    modifier = Modifier.padding(horizontal = Dimens.Gutter).padding(top = 4.dp, bottom = 18.dp),
                )
            }
        }

        val s = settings ?: return@LazyColumn

        item(key = "sound") {
            Section(stringResource(R.string.settings_group_sound)) {
                NavRow(
                    icon = Icons.Rounded.GraphicEq, chip = Chip.Violet,
                    title = stringResource(R.string.home_settings_equalizer),
                    value = stringResource(if (equalizerOn) R.string.home_settings_equalizer_on else R.string.settings_off),
                    valueAccent = equalizerOn,
                    onClick = nav::openEqualizer,
                )
                Hairline()
                SwitchRow(Icons.Rounded.Movie, Chip.Pink, stringResource(R.string.settings_player_video), stringResource(R.string.settings_player_video_sub), s.playerVideo, vm::setPlayerVideo)
            }
        }

        item(key = "dna") {
            Section(stringResource(R.string.settings_group_dna)) {
                val on = s.learnPlaces && hasLocation
                SwitchRow(Icons.Rounded.Place, Chip.Teal, stringResource(R.string.dna_places_toggle), stringResource(R.string.dna_places_hint), on) { want ->
                    when {
                        !want -> vm.setLearnPlaces(false)
                        hasLocation -> vm.setLearnPlaces(true)
                        else -> askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                }
                // With the screen off Android needs "Permitir o tempo todo" (music plays in the pocket).
                if (on && !hasAlways) {
                    Hairline()
                    NavRow(
                        icon = Icons.Rounded.LocationOn, chip = Chip.Blue,
                        title = stringResource(R.string.dna_places_pocket_action),
                        value = stringResource(R.string.dna_places_pocket_short),
                        onClick = { if (android.os.Build.VERSION.SDK_INT >= 29) askAlways.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                    )
                }
            }
        }

        item(key = "downloads") {
            Section(stringResource(R.string.home_settings_group_downloads)) {
                NavRow(
                    icon = Icons.Rounded.HighQuality, chip = Chip.Blue,
                    title = stringResource(R.string.home_settings_quality),
                    value = stringResource(qualityTitle(s.audioQuality)) + " · " + stringResource(R.string.home_settings_quality_size, formatBytes(approxSongBytes(s.audioQuality))) + " " + stringResource(R.string.home_settings_quality_per_song),
                    onClick = { qualityOpen = true },
                )
                Hairline()
                SwitchRow(Icons.Rounded.Wifi, Chip.Teal, stringResource(R.string.home_settings_wifi), stringResource(R.string.home_settings_wifi_sub), s.wifiOnly, vm::setWifiOnly)
                Hairline()
                SwitchRow(Icons.Rounded.AutoAwesome, Chip.Amber, stringResource(R.string.settings_smart_downloads), stringResource(R.string.settings_smart_downloads_sub), s.smartDownloads, vm::setSmartDownloads)
                Hairline()
                SwitchRow(Icons.Rounded.Favorite, Chip.Red, stringResource(R.string.home_settings_like_downloads), stringResource(R.string.home_settings_like_downloads_sub), s.likeDownloads, vm::setLikeDownloads)
                Hairline()
                SwitchRow(Icons.Rounded.Lyrics, Chip.Green, stringResource(R.string.home_settings_lyrics), stringResource(R.string.home_settings_lyrics_sub), s.fetchLyrics, vm::setFetchLyrics)
                Hairline()
                ParallelRow(s.parallelDownloads, vm::setParallel)
            }
        }

        item(key = "look") {
            Section(stringResource(R.string.home_settings_group_look)) {
                NavRow(
                    icon = Icons.Rounded.Palette, chip = Chip.Indigo,
                    title = stringResource(R.string.home_settings_theme),
                    value = stringResource(
                        when (s.theme) {
                            ThemeMode.LIGHT -> R.string.home_settings_theme_light
                            ThemeMode.DARK -> R.string.home_settings_theme_dark
                            ThemeMode.SYSTEM -> R.string.home_settings_theme_system
                        },
                    ),
                    onClick = nav::openTheme,
                )
                Hairline()
                SwitchRow(Icons.Rounded.Home, Chip.Orange, stringResource(R.string.home_settings_show_home), stringResource(R.string.home_settings_show_home_sub), s.showHome, vm::setShowHome)
            }
        }

        item(key = "storage") {
            Section(stringResource(R.string.home_settings_group_storage)) {
                StorageBlock(usedBytes, freeBytes, stats)
                Hairline(start = 16.dp)
                val kept = caches?.values?.sum()
                NavRow(
                    icon = Icons.Rounded.CleaningServices, chip = Chip.Teal,
                    title = stringResource(R.string.settings_free_space),
                    value = kept?.let { stringResource(R.string.settings_free_space_value, formatBytes(it)) } ?: "—",
                    onClick = { spaceOpen = true },
                )
            }
        }

        item(key = "about") {
            Section(stringResource(R.string.home_settings_group_about)) {
                About()
                // New versions come from the GitHub releases (release builds only).
                if (SoundApplication.graph.updates.enabled) {
                    Hairline(start = 16.dp)
                    com.villamediana.sound.ui.update.UpdateRow()
                }
            }
        }
    }

    if (qualityOpen) {
        SoundSheet(onDismissRequest = { qualityOpen = false }) {
            QualityPicker(settings?.audioQuality ?: AudioQuality.BEST) {
                vm.setQuality(it)
                qualityOpen = false
            }
        }
    }
    if (spaceOpen) {
        SoundSheet(onDismissRequest = { spaceOpen = false }) {
            CacheRows(caches, onClear = vm::clearCache, onClearAll = vm::clearAllCaches)
        }
    }
}

private fun qualityTitle(q: AudioQuality): Int = when (q) {
    AudioQuality.BEST -> R.string.home_settings_quality_best
    AudioQuality.COMPATIBLE -> R.string.home_settings_quality_compatible
    AudioQuality.SAVER -> R.string.home_settings_quality_saver
}

// ---------------------------------------------------------------------------- building blocks

/** A group: its name in small caps, then its rows on one rounded card. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 26.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = SoundColors.TextFaint,
            modifier = Modifier.padding(start = Dimens.Gutter + 6.dp, end = Dimens.Gutter, bottom = 10.dp),
        )
        Column(
            Modifier
                .padding(horizontal = Dimens.Gutter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(SoundColors.Surface1),
            content = content,
        )
    }
}

/** A line between rows, starting after the icons. */
@Composable
private fun Hairline(start: androidx.compose.ui.unit.Dp = 68.dp) =
    HorizontalDivider(color = SoundColors.Hairline, thickness = 1.dp, modifier = Modifier.padding(start = start))

/** The row's colored square with its white icon. */
@Composable
private fun IconChip(icon: ImageVector, color: Color) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RowShell(
    icon: ImageVector,
    chip: Color,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(icon, chip)
        Spacer(Modifier.width(16.dp))
        content()
    }
}

/** A row that opens something: its title, its current value, an arrow. */
@Composable
private fun NavRow(icon: ImageVector, chip: Color, title: String, value: String, onClick: () -> Unit, valueAccent: Boolean = false) {
    RowShell(icon, chip, Modifier.clickable(onClick = onClick)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SoundColors.Text)
            Spacer(Modifier.height(1.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = if (valueAccent) SoundColors.Accent else SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = SoundColors.TextFaint, modifier = Modifier.size(22.dp))
    }
}

/** A row that is a switch (the whole row: TalkBack reads title, line and on/off as one control). */
@Composable
private fun SwitchRow(icon: ImageVector, chip: Color, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    RowShell(icon, chip, Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SoundColors.Text)
            Spacer(Modifier.height(1.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted)
        }
        Spacer(Modifier.width(12.dp))
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

/** "Ao mesmo tempo": 1 to 4, small pills on the row itself. */
@Composable
private fun ParallelRow(current: Int, onPick: (Int) -> Unit) {
    RowShell(Icons.Rounded.Layers, Chip.Slate) {
        // Title and line on top, the four under them across the row (no word squeezed by the pills).
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_parallel_title), style = MaterialTheme.typography.titleMedium, color = SoundColors.Text)
            Spacer(Modifier.height(1.dp))
            Text(stringResource(R.string.settings_parallel_short), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(CircleShape).background(SoundColors.Surface3).padding(3.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                (1..4).forEach { n ->
                    val selected = n == current
                    val bg by animateColorAsState(if (selected) SoundColors.Accent else Color.Transparent, label = "seg")
                    val fg by animateColorAsState(if (selected) SoundColors.OnAccent else SoundColors.TextMuted, label = "segText")
                    Box(
                        Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(CircleShape)
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
}

/** "Qualidade do áudio" in its sheet: the three, with their sound and size. */
@Composable
private fun QualityPicker(current: AudioQuality, onPick: (AudioQuality) -> Unit) {
    Column(Modifier.padding(bottom = 10.dp).selectableGroup()) {
        Text(stringResource(R.string.home_settings_quality), style = MaterialTheme.typography.headlineSmall, color = SoundColors.Text, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.home_settings_quality_sub), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(12.dp))
        AudioQuality.entries.forEach { q ->
            val body = when (q) {
                AudioQuality.BEST -> R.string.home_settings_quality_best_sub
                AudioQuality.COMPATIBLE -> R.string.home_settings_quality_compatible_sub
                AudioQuality.SAVER -> R.string.home_settings_quality_saver_sub
            }
            val selected = q == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(q) })
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioDot(selected)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(qualityTitle(q)), style = MaterialTheme.typography.titleMedium, color = SoundColors.Text)
                    Spacer(Modifier.height(1.dp))
                    Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

/**
 * What the app keeps on the phone: the size set big, a bar of how much of the free space it takes,
 * and the library in four numbers.
 */
@Composable
private fun StorageBlock(usedBytes: Long?, freeBytes: Long?, stats: LibraryStats?) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(usedBytes?.let { formatBytes(it) } ?: "—", style = MaterialTheme.typography.displaySmall, color = SoundColors.Text)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.home_settings_storage_used), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted, modifier = Modifier.padding(bottom = 6.dp))
        }
        val used = usedBytes ?: 0L
        val free = freeBytes ?: 0L
        if (free > 0) {
            Spacer(Modifier.height(12.dp))
            // The app's share of what is left on the phone (always a sliver at least, so it shows).
            val share = (used.toFloat() / (used + free)).coerceIn(0.015f, 1f)
            Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(SoundColors.Surface3)) {
                Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(CircleShape).background(SoundColors.Accent))
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.home_settings_storage_free, formatBytes(free)), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint)
        }
        Spacer(Modifier.height(16.dp))
        val st = stats ?: LibraryStats(0, 0, 0, 0, 0)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(Icons.Rounded.MusicNote, st.trackCount.toString(), pluralStringResource(R.plurals.home_settings_stat_songs, labelQuantity(st.trackCount)), Modifier.weight(1f))
            Stat(Icons.Rounded.Album, st.albumCount.toString(), pluralStringResource(R.plurals.home_settings_stat_albums, labelQuantity(st.albumCount)), Modifier.weight(1f))
            Stat(Icons.Rounded.Person, st.artistCount.toString(), pluralStringResource(R.plurals.home_settings_stat_artists, labelQuantity(st.artistCount)), Modifier.weight(1f))
            Stat(Icons.Rounded.Schedule, formatHours(st.durationMs), stringResource(R.string.home_settings_stat_hours), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(SoundColors.Surface2).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = SoundColors.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = SoundColors.Text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Liberar espaço" in its sheet: every cache with its size and "Limpar"; then "Limpar tudo". Nothing is lost for good. */
@Composable
private fun CacheRows(sizes: Map<CacheKind, Long>?, onClear: (CacheKind) -> Unit, onClearAll: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(stringResource(R.string.settings_cache_title), style = MaterialTheme.typography.headlineSmall, color = SoundColors.Text, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.settings_cache_sub), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(10.dp))
        CacheKind.entries.forEach { kind ->
            val size = sizes?.get(kind)
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(cacheIcon(kind), null, tint = SoundColors.TextMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Text(stringResource(cacheLabel(kind)), style = MaterialTheme.typography.bodyLarge, color = SoundColors.Text, modifier = Modifier.weight(1f))
                Text(size?.let { formatBytes(it) } ?: "—", style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
                TextButton(onClick = { onClear(kind) }, enabled = (size ?: 0L) > 0L) {
                    Text(stringResource(R.string.settings_cache_clear), style = MaterialTheme.typography.labelLarge, color = if ((size ?: 0L) > 0L) SoundColors.Accent else SoundColors.TextFaint)
                }
            }
        }
        val total = sizes?.values?.sum() ?: 0L
        HorizontalDivider(color = SoundColors.Hairline, thickness = 1.dp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        Row(
            Modifier.fillMaxWidth().clickable(enabled = total > 0L, onClick = onClearAll).padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_cache_clear_all),
                style = MaterialTheme.typography.titleSmall,
                color = if (total > 0L) SoundColors.Danger else SoundColors.TextFaint,
                modifier = Modifier.weight(1f),
            )
            Text(formatBytes(total), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
        }
    }
}

private fun cacheLabel(kind: CacheKind): Int = when (kind) {
    CacheKind.SONGS -> R.string.settings_cache_songs
    CacheKind.VIDEOS -> R.string.settings_cache_videos
    CacheKind.COVERS -> R.string.settings_cache_covers
    CacheKind.LYRICS -> R.string.settings_cache_lyrics
    CacheKind.HOME -> R.string.settings_cache_home
    CacheKind.CHORDS -> R.string.settings_cache_chords
}

private fun cacheIcon(kind: CacheKind): ImageVector = when (kind) {
    CacheKind.SONGS -> Icons.Rounded.MusicNote
    CacheKind.VIDEOS -> Icons.Rounded.Movie
    CacheKind.COVERS -> Icons.Rounded.Image
    CacheKind.LYRICS -> Icons.Rounded.Lyrics
    CacheKind.HOME -> Icons.Rounded.Home
    CacheKind.CHORDS -> com.villamediana.sound.ui.chords.ChordsIcon
}

/** The mark, the name and the version. */
@Composable
private fun About() {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        SoundMark(size = 48.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text("FreeSong", style = MaterialTheme.typography.titleLarge, color = SoundColors.Text)
            Text(
                stringResource(R.string.home_settings_version, versionLabel(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)),
                style = MaterialTheme.typography.bodyMedium,
                color = SoundColors.TextMuted,
            )
        }
    }
}
