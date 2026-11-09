package com.villamediana.sound.ui.dna

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.smart.DayPart
import com.villamediana.sound.smart.DnaSummary
import com.villamediana.sound.smart.MadeForYou
import com.villamediana.sound.smart.Mix
import com.villamediana.sound.social.Profile
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.components.SectionHeader
import com.villamediana.sound.ui.components.formatLongDuration
import com.villamediana.sound.ui.detail.BackButton
import com.villamediana.sound.ui.social.ProfileAvatar
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** The DNA on your page: worked out only while the page is open (and again when places change). */
class DnaViewModel : ViewModel() {
    private val graph = SoundApplication.graph

    private val _summary = MutableStateFlow<DnaSummary?>(null)
    val summary: StateFlow<DnaSummary?> = _summary

    /** "Lembrar lugares" is on (Ajustes): the places show here. */
    val places: StateFlow<Boolean> = graph.settingsNow
        .map { it?.learnPlaces == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.settingsNow.value?.learnPlaces == true)

    init {
        viewModelScope.launch { _summary.value = runCatching { graph.forYou.dna().summary }.getOrNull() }
    }
}

/**
 * Your musical DNA on your page (only yours is ever shown): your day (who you are by the hour, the
 * hours in bars, each part with its artists), your week (and what makes weekdays and the weekend
 * each its own), your places when "Lembrar lugares" is on, and the friends you listen with.
 */
internal fun LazyListScope.dnaSections(summary: DnaSummary?, places: Boolean, people: Map<String, Profile>) {
    if (summary == null || summary.isEmpty) return
    item(key = "dna-day", contentType = "dna") { DayCard(summary) }
    item(key = "dna-week", contentType = "dna") { WeekCard(summary) }
    if (places && summary.places.isNotEmpty()) item(key = "dna-places", contentType = "dna") { PlacesCard(summary.places) }
    val together = summary.people.filter { it.id in people }
    if (together.isNotEmpty()) item(key = "dna-people", contentType = "dna") { PeopleCard(together, people) }
}

private fun personaOf(part: DayPart): Int = when (part) {
    DayPart.NIGHT_LATE -> R.string.recap_rhythm_dawn
    DayPart.MORNING -> R.string.recap_rhythm_morning
    DayPart.AFTERNOON -> R.string.recap_rhythm_afternoon
    DayPart.EVENING -> R.string.recap_rhythm_night
}

@Composable
private fun partName(part: DayPart): String = stringResource(
    when (part) {
        DayPart.NIGHT_LATE -> R.string.dna_part_late
        DayPart.MORNING -> R.string.dna_part_morning
        DayPart.AFTERNOON -> R.string.dna_part_afternoon
        DayPart.EVENING -> R.string.dna_part_evening
    },
)

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .padding(horizontal = Dimens.Gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SoundColors.Surface1)
            .padding(20.dp),
    ) { content() }
}

private const val DAY_BAR = 70f

/** "Seu dia": the 24 hours in bars (the busiest part lit), then each part with its artists and style. */
@Composable
private fun DayCard(summary: DnaSummary) {
    val main = summary.mainPart
    val most = summary.hourMinutes.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    Column(Modifier.fillMaxWidth().padding(top = Dimens.SectionGap)) {
        SectionHeader(stringResource(R.string.dna_day))
        Panel {
            if (main != null) {
                Text(stringResource(personaOf(main)), style = MaterialTheme.typography.titleLarge, color = SoundColors.Text)
                val peak = summary.hourMinutes.indices.maxByOrNull { summary.hourMinutes[it] }
                if (peak != null) Text(stringResource(R.string.profile_stats_peak, peak), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
                Spacer(Modifier.height(18.dp))
            }
            Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                summary.hourMinutes.forEachIndexed { h, min ->
                    val share by animateFloatAsState((min.toFloat() / most).coerceIn(0.04f, 1f), tween(600), label = "h$h")
                    val lit = main != null && h in main.hours && min > 0
                    Box(
                        Modifier
                            .weight(1f)
                            .height((DAY_BAR * share).dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (lit) SoundColors.Accent else SoundColors.Surface3),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("0h", "6h", "12h", "18h").forEach { label ->
                    Text(label, style = MaterialTheme.typography.labelSmall, color = SoundColors.TextFaint, modifier = Modifier.weight(1f))
                }
            }
            summary.parts.filter { it.minutes > 0 }.forEach { p ->
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (p.part == main) SoundColors.Accent else SoundColors.Surface3))
                    Spacer(Modifier.width(10.dp))
                    Text(partName(p.part), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = SoundColors.Text, modifier = Modifier.weight(1f))
                    Text(formatLongDuration(p.minutes * 60_000L), style = MaterialTheme.typography.labelMedium, color = SoundColors.TextMuted)
                }
                if (p.artists.isNotEmpty()) {
                    Text(
                        p.artists.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SoundColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 20.dp, top = 2.dp),
                    )
                }
            }
        }
    }
}

/** "Sua semana": the days in bars, and what makes weekdays and the weekend each its own. */
@Composable
private fun WeekCard(summary: DnaSummary) {
    val locale = Locale.getDefault()
    val days = summary.weekdayMinutes
    val most = days.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val top = days.indices.maxByOrNull { days[it] }
    Column(Modifier.fillMaxWidth().padding(top = Dimens.SectionGap)) {
        SectionHeader(stringResource(R.string.dna_week))
        Panel {
            Row(Modifier.fillMaxWidth().height(86.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                days.forEachIndexed { i, ms ->
                    val share by animateFloatAsState((ms.toFloat() / most).coerceIn(0.04f, 1f), tween(600), label = "d$i")
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((62f * share).dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (i == top && ms > 0) SoundColors.Accent else SoundColors.Surface3),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            DayOfWeek.of(i + 1).getDisplayName(TextStyle.NARROW, locale).uppercase(locale),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (i == top && ms > 0) SoundColors.Text else SoundColors.TextFaint,
                        )
                    }
                }
            }
            if (summary.weekdayTop.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                LabeledLine(stringResource(R.string.dna_weekdays), summary.weekdayTop.joinToString(" · "))
            }
            if (summary.weekendTop.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LabeledLine(stringResource(R.string.dna_weekend), summary.weekendTop.joinToString(" · "))
            }
        }
    }
}

@Composable
private fun LabeledLine(label: String, text: String) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = SoundColors.TextFaint)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SoundColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** "Seus lugares": where you listen most (learned by itself, no names), when you are there and what you hear there. */
@Composable
private fun PlacesCard(places: List<DnaSummary.Place>) {
    Column(Modifier.fillMaxWidth().padding(top = Dimens.SectionGap)) {
        SectionHeader(stringResource(R.string.dna_places))
        Panel {
            places.forEachIndexed { i, p ->
                if (i > 0) Spacer(Modifier.height(16.dp))
                val whenThere = listOfNotNull(
                    p.part?.let { partName(it) },
                    when (p.weekend) {
                        true -> stringResource(R.string.dna_weekend)
                        false -> stringResource(R.string.dna_weekdays)
                        null -> null
                    },
                    formatLongDuration(p.minutes * 60_000L),
                ).joinToString(" · ")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Place, null, tint = if (i == 0) SoundColors.Accent else SoundColors.TextMuted, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.dna_place_n, i + 1), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = SoundColors.Text)
                        Text(whenThere, style = MaterialTheme.typography.labelMedium, color = SoundColors.TextMuted)
                        if (p.artists.isNotEmpty()) {
                            Text(p.artists.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** "Com quem você ouve": the friends you jam with, the time together and what you hear together. */
@Composable
private fun PeopleCard(together: List<DnaSummary.Person>, people: Map<String, Profile>) {
    Column(Modifier.fillMaxWidth().padding(top = Dimens.SectionGap)) {
        SectionHeader(stringResource(R.string.dna_people))
        Panel {
            together.forEachIndexed { i, t ->
                val p = people[t.id] ?: return@forEachIndexed
                if (i > 0) Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(p, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(formatLongDuration(t.minutes * 60_000L), t.artists.take(2).joinToString(" · ").ifBlank { null }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = SoundColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
