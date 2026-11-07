package com.villamediana.sound.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.ThemeMode
import com.villamediana.sound.ui.components.SoundMark
import com.villamediana.sound.ui.components.seedColor
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundAccents
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.launch

/** "Tema": a live preview of the app, light/dark/system, and the highlight color. */
@Composable
fun ThemeScreen() {
    val store = SoundApplication.graph.settings
    val settings by store.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val nav = navigator()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val s = settings ?: return

    Column(
        Modifier.fillMaxSize().background(SoundColors.Ink).verticalScroll(rememberScrollState()).padding(top = top, bottom = bottom + 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SoundColors.Text) }
            Text(stringResource(R.string.theme_title), style = MaterialTheme.typography.headlineMedium, color = SoundColors.Text, modifier = Modifier.padding(start = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
        ThemePreview(Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(28.dp))

        // Escuro / Claro / Sistema
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple(ThemeMode.DARK, R.string.home_settings_theme_dark, Icons.Rounded.DarkMode),
                Triple(ThemeMode.LIGHT, R.string.home_settings_theme_light, Icons.Rounded.LightMode),
                Triple(ThemeMode.SYSTEM, R.string.home_settings_theme_system, Icons.Rounded.Contrast),
            ).forEach { (mode, label, icon) ->
                ModeChip(stringResource(label), icon, selected = s.theme == mode, modifier = Modifier.weight(1f)) {
                    scope.launch { store.setTheme(mode) }
                }
            }
        }
        Spacer(Modifier.height(26.dp))

        // Colors
        LazyRow(contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(SoundAccents.all, key = { it.id }) { a ->
                val color = if (SoundColors.isLight) a.light else a.dark
                Swatch(color, selected = s.accent == a.id, label = a.id) { scope.launch { store.setAccent(a.id) } }
            }
        }
    }
}

@Composable
private fun ModeChip(text: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) SoundColors.Accent else SoundColors.Surface1, label = "modeBg")
    val fg by animateColorAsState(if (selected) SoundColors.OnAccent else SoundColors.TextMuted, label = "modeFg")
    Row(
        modifier
            .height(50.dp)
            .clip(CircleShape)
            .background(bg)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 1)
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    val ring by animateFloatAsState(if (selected) 1f else 0f, label = "ring")
    Box(
        Modifier
            .size(62.dp)
            .clip(CircleShape)
            .border(3.dp, SoundColors.Text.copy(alpha = ring), CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(color)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, label, tint = if (color.red + color.green + color.blue > 2.2f) Color.Black else Color.White, modifier = Modifier.size(24.dp).scale(ring))
    }
}

/** A small, live mock of the main screen in the current theme and color. */
@Composable
private fun ThemePreview(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    Column(
        modifier
            .width(250.dp)
            .height(440.dp)
            .clip(shape)
            .background(SoundColors.Ink)
            .border(4.dp, SoundColors.Accent.copy(alpha = 0.55f), shape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoundMark(size = 22.dp)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 17.sp), color = SoundColors.Text, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Search, null, tint = SoundColors.Text, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.Settings, null, tint = SoundColors.Text, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(true, false, false).forEachIndexed { i, on ->
                Box(
                    Modifier.height(22.dp).clip(CircleShape).background(if (on) SoundColors.Accent else SoundColors.Surface1).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(listOf(R.string.main_tab_home, R.string.main_tab_songs, R.string.main_tab_playlists)[i]),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.sp),
                        color = if (on) SoundColors.OnAccent else SoundColors.TextMuted,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        repeat(5) { i ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(seedColor("preview$i")))
                Spacer(Modifier.width(10.dp))
                Column {
                    Box(Modifier.width((90 + i * 9 % 40).dp).height(8.dp).clip(CircleShape).background(if (i == 1) SoundColors.Accent else SoundColors.Text.copy(alpha = 0.85f)))
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.width(60.dp).height(6.dp).clip(CircleShape).background(SoundColors.TextFaint))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(SoundColors.miniBackground(SoundColors.Accent)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(seedColor("now")))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Box(Modifier.width(70.dp).height(7.dp).clip(CircleShape).background(SoundColors.Text))
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(44.dp).height(5.dp).clip(CircleShape).background(SoundColors.TextMuted))
            }
            Icon(Icons.Rounded.Pause, null, tint = SoundColors.Text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.FastForward, null, tint = SoundColors.Text, modifier = Modifier.size(18.dp))
        }
    }
}
