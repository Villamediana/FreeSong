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

/** The DNA page's data: the summary (worked out only while the page is open) and the "Pra agora" mix. */
class DnaViewModel : ViewModel() {
    private val graph = SoundApplication.graph

    private val _summary = MutableStateFlow<DnaSummary?>(null)
    val summary: StateFlow<DnaSummary?> = _summary

    val moment: StateFlow<Mix?> = graph.madeForYou.mixes
        .map { list -> list.firstOrNull { it.id == MadeForYou.MOMENT_ID } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val learnPlaces: StateFlow<Boolean> = graph.settingsNow
        .map { it?.learnPlaces == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.settingsNow.value?.learnPlaces == true)

    init {
        load()
        graph.madeForYou.refresh()
    }

    fun load() {
        viewModelScope.launch { _summary.value = runCatching { graph.forYou.dna().summary }.getOrNull() }
    }

    /** "Lembrar lugares": on (the permission was asked by the page) or off (every place is forgotten). */
    fun setLearnPlaces(on: Boolean) {
        viewModelScope.launch {
            graph.settings.setLearnPlaces(on)
            if (on) {
                runCatching { graph.places.now() }
            } else {
                graph.places.forget()
                graph.forYou.invalidate()
                load()
            }
        }
    }

    fun playMoment() {
        val mix = moment.value ?: return
        if (mix.tracks.isEmpty()) return
        graph.player.play(mix.tracks, 0, PlaySource(PlaySource.Type.MIX, mix.title, mix.id))
    }
}

/** "Seu DNA musical": what the app learned about how you listen, and the mix it makes of it now. */
@Composable
fun DnaScreen() {
    val vm: DnaViewModel = viewModel()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val moment by vm.moment.collectAsStateWithLifecycle()
    val learnPlaces by vm.learnPlaces.collectAsStateWithLifecycle()
    val hub = SoundApplication.graph.social
    val friends by hub.friends.collectAsStateWithLifecycle()
    val jam by hub.jam.jam.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val people = remember(friends, jam) { (friends.friends + jam?.members.orEmpty()).associateBy { it.id } }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val always = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) vm.setLearnPlaces(true)
    }
    val hasLocation = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
    val hasAlways = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    DnaPage(
        summary = summary,
        moment = moment,
        learnPlaces = learnPlaces && hasLocation,
        placesInPocket = hasAlways,
        people = people,
        onPlayMoment = vm::playMoment,
        onPlaces = { on ->
            when {
                !on -> vm.setLearnPlaces(false)
                hasLocation -> vm.setLearnPlaces(true)
                else -> ask.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }
        },
        onPocket = { if (Build.VERSION.SDK_INT >= 29) always.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
    )
}

/** What the DNA page draws (apart from its state, so the tests can draw it). */
@Composable
internal fun DnaPage(
    summary: DnaSummary?,
    moment: Mix?,
    learnPlaces: Boolean,
    placesInPocket: Boolean,
    people: Map<String, Profile>,
    onPlayMoment: () -> Unit,
    onPlaces: (Boolean) -> Unit,
    onPocket: () -> Unit,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(
        Modifier.fillMaxSize().background(SoundColors.Ink),
        contentPadding = PaddingValues(top = top, bottom = LocalBottomInset.current),
    ) {
        item(key = "bar") {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(backdropAlpha = { 0f })
            }
        }
        item(key = "title") {
            Column(Modifier.padding(horizontal = Dimens.Gutter).padding(bottom = 6.dp)) {
                Text(stringResource(R.string.dna_title), style = MaterialTheme.typography.displaySmall, color = SoundColors.Text)
                Spacer(Modifier.height(6.dp))
                val main = summary?.mainPart
                Text(
                    if (main == null) stringResource(R.string.dna_learning) else stringResource(personaOf(main)),
                    style = MaterialTheme.typography.titleMedium,
                    color = SoundColors.TextMuted,
                )
            }
        }
        if (moment != null) item(key = "moment") { MomentCard(moment, onPlayMoment) }
        if (summary == null || summary.isEmpty) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.dna_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = SoundColors.TextMuted,
                    modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 28.dp),
                )
            }
        } else {
            item(key = "day") { DayCard(summary) }
            item(key = "week") { WeekCard(summary) }
        }
        item(key = "places") { PlacesCard(summary?.places.orEmpty(), learnPlaces, placesInPocket, onPlaces, onPocket) }
        val together = summary?.people.orEmpty().filter { it.id in people }
        if (together.isNotEmpty()) item(key = "people") { PeopleCard(together, people) }
    }
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

/** "Pra agora": the mix of this moment — its covers, what it is for, play. */
@Composable
private fun MomentCard(mix: Mix, onPlay: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = Dimens.Gutter)
            .padding(top = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SoundColors.AccentSoft)
            .clickable(onClick = onPlay)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(mix.covers.firstOrNull(), Modifier.size(76.dp), seed = mix.title, shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.dna_now).uppercase(), style = MaterialTheme.typography.labelSmall, color = SoundColors.Accent)
            Text(mix.title, style = MaterialTheme.typography.titleLarge, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(mix.subtitle, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(SoundColors.Accent).semantics { contentDescription = "dna-play" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PlayArrow, null, tint = SoundColors.OnAccent, modifier = Modifier.size(30.dp))
        }
    }
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

/**
 * "Seus lugares": off until you turn it on (the location stays on this phone, never GPS); then the
 * places you listen at most, by when you are there and what you hear there — no names asked.
 */
@Composable
private fun PlacesCard(places: List<DnaSummary.Place>, on: Boolean, inPocket: Boolean, onToggle: (Boolean) -> Unit, onPocket: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Dimens.SectionGap)) {
        SectionHeader(stringResource(R.string.dna_places))
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Place, null, tint = SoundColors.Accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dna_places_toggle), style = MaterialTheme.typography.titleSmall, color = SoundColors.Text)
                    Text(stringResource(R.string.dna_places_hint), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted)
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = on,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = SoundColors.Accent, checkedThumbColor = SoundColors.OnAccent),
                    modifier = Modifier.semantics { contentDescription = "dna-places" },
                )
            }
            if (on && !inPocket) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.dna_places_pocket), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted)
                Spacer(Modifier.height(8.dp))
                PillButton(stringResource(R.string.dna_places_pocket_action), onClick = onPocket)
            }
            if (on && places.isEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.dna_places_learning), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
            }
            if (on) {
                places.forEachIndexed { i, p ->
                    Spacer(Modifier.height(16.dp))
                    val whenThere = listOfNotNull(
                        p.part?.let { partName(it) },
                        when (p.weekend) {
                            true -> stringResource(R.string.dna_weekend)
                            false -> stringResource(R.string.dna_weekdays)
                            null -> null
                        },
                        formatLongDuration(p.minutes * 60_000L),
                    ).joinToString(" · ")
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
            if (together.none { it.id in people }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Groups, null, tint = SoundColors.TextMuted)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.dna_people_none), color = SoundColors.TextMuted)
                }
            }
        }
    }
}
