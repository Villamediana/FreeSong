package com.villamediana.sound.ui.social

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.stats.ListeningStatsMaker
import com.villamediana.sound.stats.PartOfDay
import com.villamediana.sound.stats.RecapStoryPlan
import com.villamediana.sound.stats.StatsPeriod
import com.villamediana.sound.social.NumPeriod
import com.villamediana.sound.social.Numbers
import com.villamediana.sound.social.NumbersOf
import com.villamediana.sound.ui.components.ArtistPhoto
import com.villamediana.sound.ui.components.formatLongDuration
import com.villamediana.sound.ui.components.keepHorizontalDrags
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Your own numbers, worked out on the phone (only while the profile is open). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileStatsViewModel : ViewModel() {
    private val graph = SoundApplication.graph

    val period = MutableStateFlow(StatsPeriod.MONTH)

    val stats: StateFlow<NumPeriod?> = period.flatMapLatest { p ->
        val from = ListeningStatsMaker.start(p, System.currentTimeMillis())
        combine(graph.library.playEventsSince(from), graph.library.tracks, graph.library.onlinePlaysSince(from)) { events, tracks, online ->
            NumbersOf.period(ListeningStatsMaker.build(p, events, tracks.associateBy { it.id }, online, System.currentTimeMillis()), forFriends = false)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * "Seus números" on your profile: this month or this year — time, songs and artists, your rhythm
 * across the week and the hour you listen most, your artists. Your friends see them too (totals
 * only, sent with the hours and the top songs).
 */
@Composable
internal fun ProfileStats(vm: ProfileStatsViewModel = viewModel()) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    ProfileStatsContent(
        title = stringResource(R.string.profile_stats_title),
        hint = stringResource(R.string.profile_stats_friends_see),
        data = stats,
        period = period,
        onPeriod = { vm.period.value = it },
    )
}

/** A friend's numbers, as their phone last sent them. */
@Composable
internal fun FriendStats(numbers: Numbers, name: String) {
    var period by rememberSaveable { mutableStateOf(StatsPeriod.MONTH) }
    ProfileStatsContent(
        title = stringResource(R.string.friend_stats_title, name.trim().substringBefore(' ').ifBlank { name }),
        hint = null,
        data = if (period == StatsPeriod.MONTH) numbers.month else numbers.year,
        period = period,
        onPeriod = { period = it },
    )
}

@Composable
internal fun ProfileStatsContent(title: String, hint: String?, data: NumPeriod?, period: StatsPeriod, onPeriod: (StatsPeriod) -> Unit) {
    val nav = navigator()
    Column(Modifier.fillMaxWidth().padding(top = Dimens.SectionGap)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint) }
            }
            PeriodSwitch(period, onPeriod)
        }
        Spacer(Modifier.height(14.dp))
        if (data == null || data.empty) {
            Text(
                stringResource(R.string.profile_stats_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = SoundColors.TextMuted,
                modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 8.dp),
            )
            return@Column
        }
        val numbers = NumberFormat.getIntegerInstance(Locale.getDefault())
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberTile(Icons.Rounded.Schedule, Color(0xFF3D7BEB), numbers.format(data.minutes), stringResource(R.string.profile_stats_minutes), Modifier.weight(1f))
            NumberTile(Icons.Rounded.MusicNote, Color(0xFF9B5DE5), numbers.format(data.songs.toLong()), pluralStringResource(R.plurals.recap_songs, data.songs), Modifier.weight(1f))
            NumberTile(Icons.Rounded.Person, Color(0xFF14A38B), numbers.format(data.artists.toLong()), pluralStringResource(R.plurals.recap_artists, data.artists), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        RhythmCard(data.weekdays, data.hour, Modifier.padding(horizontal = Dimens.Gutter))

        if (data.top.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.profile_stats_artists), style = MaterialTheme.typography.titleMedium, color = SoundColors.Text, modifier = Modifier.padding(horizontal = Dimens.Gutter))
            Spacer(Modifier.height(10.dp))
            LazyRow(
                modifier = Modifier.keepHorizontalDrags(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Dimens.Gutter),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(data.top, key = { it.name.lowercase() }) { a ->
                    Column(
                        Modifier.width(88.dp).clip(RoundedCornerShape(12.dp)).clickable { nav.openArtist(a.name) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ArtistPhoto(a.name, a.cover, Modifier.size(80.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(a.name, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Text(formatLongDuration(a.minutes * 60_000L), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Mês · Ano. */
@Composable
private fun PeriodSwitch(period: StatsPeriod, onPeriod: (StatsPeriod) -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(SoundColors.Surface2).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StatsPeriod.entries.forEach { p ->
            val selected = p == period
            val bg by animateColorAsState(if (selected) SoundColors.Accent else Color.Transparent, label = "period")
            val fg by animateColorAsState(if (selected) SoundColors.OnAccent else SoundColors.TextMuted, label = "periodText")
            Text(
                stringResource(if (p == StatsPeriod.MONTH) R.string.profile_stats_month else R.string.profile_stats_year),
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(bg)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onPeriod(p) })
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun NumberTile(icon: ImageVector, chip: Color, value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(SoundColors.Surface1).padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(chip), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = SoundColors.Text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val BAR_MAX = 62f

/** The week in bars (the fullest day lit) and who you are by the hour. */
@Composable
private fun RhythmCard(weekdays: List<Long>, hour: Int?, modifier: Modifier = Modifier) {
    val locale = Locale.getDefault()
    val most = weekdays.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val top = weekdays.indices.maxByOrNull { weekdays[it] }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(SoundColors.Surface1).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.profile_stats_rhythm), style = MaterialTheme.typography.titleMedium, color = SoundColors.Text, modifier = Modifier.weight(1f))
            if (hour != null) {
                val persona = when (RecapStoryPlan.partOfDay(hour)) {
                    PartOfDay.DAWN -> R.string.recap_rhythm_dawn
                    PartOfDay.MORNING -> R.string.recap_rhythm_morning
                    PartOfDay.AFTERNOON -> R.string.recap_rhythm_afternoon
                    PartOfDay.NIGHT -> R.string.recap_rhythm_night
                }
                Text(stringResource(persona), style = MaterialTheme.typography.labelLarge, color = SoundColors.Accent)
            }
        }
        if (hour != null) {
            Text(stringResource(R.string.profile_stats_peak, hour), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().height(86.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            weekdays.forEachIndexed { i, ms ->
                val share by animateFloatAsState((ms.toFloat() / most).coerceIn(0.04f, 1f), tween(600), label = "day$i")
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((BAR_MAX * share).dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (i == top) SoundColors.Accent else SoundColors.Surface3),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        DayOfWeek.of(i + 1).getDisplayName(TextStyle.NARROW, locale).uppercase(locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i == top) SoundColors.Text else SoundColors.TextFaint,
                    )
                }
            }
        }
    }
}
