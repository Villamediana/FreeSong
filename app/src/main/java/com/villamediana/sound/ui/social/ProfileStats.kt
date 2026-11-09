package com.villamediana.sound.ui.social

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.social.NumPeriod
import com.villamediana.sound.social.NumbersOf
import com.villamediana.sound.stats.ListeningStatsMaker
import com.villamediana.sound.stats.PartOfDay
import com.villamediana.sound.stats.RecapStoryPlan
import com.villamediana.sound.stats.StatsPeriod
import com.villamediana.sound.ui.components.ArtistPhoto
import com.villamediana.sound.ui.components.SectionHeader
import com.villamediana.sound.ui.components.formatLongDuration
import com.villamediana.sound.ui.components.keepHorizontalDrags
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Your numbers of this month, worked out on the phone (only while the profile is open). */
class ProfileStatsViewModel : ViewModel() {
    private val graph = SoundApplication.graph
    private val from = ListeningStatsMaker.start(StatsPeriod.MONTH, System.currentTimeMillis())

    val stats: StateFlow<NumPeriod?> =
        combine(graph.library.playEventsSince(from), graph.library.tracks, graph.library.onlinePlaysSince(from)) { events, tracks, online ->
            NumbersOf.period(ListeningStatsMaker.build(StatsPeriod.MONTH, events, tracks.associateBy { it.id }, online, System.currentTimeMillis()), forFriends = false)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * The foot of the person's card: a hairline, "Este mês", and the month in three numbers — minutes,
 * songs, artists — in white over their color. [emptyHint] stands in when there is nothing yet.
 */
@Composable
internal fun CardNumbers(data: NumPeriod?, emptyHint: String?) {
    val empty = data == null || data.empty
    if (empty && emptyHint == null) return
    Column {
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.16f)))
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.person_month).uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(8.dp))
        if (empty || data == null) {
            Text(emptyHint.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            return@Column
        }
        val numbers = NumberFormat.getIntegerInstance(Locale.getDefault())
        Row(Modifier.fillMaxWidth()) {
            CardNumber(numbers.format(data.minutes), stringResource(R.string.profile_stats_minutes), Modifier.weight(1f))
            CardNumber(numbers.format(data.songs.toLong()), pluralStringResource(R.plurals.recap_songs, data.songs), Modifier.weight(1f))
            CardNumber(numbers.format(data.artists.toLong()), pluralStringResource(R.plurals.recap_artists, data.artists), Modifier.weight(1f))
        }
    }
}

@Composable
private fun CardNumber(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = Color.White,
            maxLines = 1,
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Seus artistas" (or "Artistas de Ana") of the month: their photos in a row, the time under each. */
@Composable
internal fun PersonArtists(data: NumPeriod, firstName: String?) {
    val nav = navigator()
    Column(Modifier.fillMaxWidth().padding(top = Dimens.SectionGap)) {
        SectionHeader(
            if (firstName == null) stringResource(R.string.profile_stats_artists) else stringResource(R.string.friend_stats_artists, firstName),
            subtitle = stringResource(R.string.person_month),
        )
        LazyRow(
            modifier = Modifier.keepHorizontalDrags(),
            contentPadding = PaddingValues(horizontal = Dimens.Gutter),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(data.top, key = { it.name.lowercase() }) { a ->
                Column(
                    Modifier.width(96.dp).clip(RoundedCornerShape(14.dp)).clickable { nav.openArtist(a.name) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ArtistPhoto(a.name, a.cover, Modifier.size(96.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(a.name, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(formatLongDuration(a.minutes * 60_000L), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1)
                }
            }
        }
    }
}

private const val BAR_MAX = 62f

/**
 * "Seu ritmo" (or "Ritmo de Ana"): who they are by the hour, then the week in bars with the
 * fullest day lit in the person's own [tint] — the card's color carried down the page.
 */
@Composable
internal fun PersonRhythm(data: NumPeriod, firstName: String?, tint: Color) {
    val locale = Locale.getDefault()
    val weekdays = data.weekdays
    val most = weekdays.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val top = weekdays.indices.maxByOrNull { weekdays[it] }
    val hour = data.hour
    Column(Modifier.fillMaxWidth().padding(top = Dimens.SectionGap)) {
        SectionHeader(if (firstName == null) stringResource(R.string.profile_stats_rhythm) else stringResource(R.string.friend_stats_rhythm, firstName))
        Column(
            Modifier
                .padding(horizontal = Dimens.Gutter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(SoundColors.Surface1)
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            if (hour != null) {
                val part = RecapStoryPlan.partOfDay(hour)
                val persona = if (firstName == null) {
                    stringResource(
                        when (part) {
                            PartOfDay.DAWN -> R.string.recap_rhythm_dawn
                            PartOfDay.MORNING -> R.string.recap_rhythm_morning
                            PartOfDay.AFTERNOON -> R.string.recap_rhythm_afternoon
                            PartOfDay.NIGHT -> R.string.recap_rhythm_night
                        },
                    )
                } else {
                    stringResource(
                        when (part) {
                            PartOfDay.DAWN -> R.string.friend_rhythm_dawn
                            PartOfDay.MORNING -> R.string.friend_rhythm_morning
                            PartOfDay.AFTERNOON -> R.string.friend_rhythm_afternoon
                            PartOfDay.NIGHT -> R.string.friend_rhythm_night
                        },
                        firstName,
                    )
                }
                Text(persona, style = MaterialTheme.typography.titleLarge, color = SoundColors.Text)
                Text(stringResource(R.string.profile_stats_peak, hour), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
                Spacer(Modifier.height(18.dp))
            }
            Row(Modifier.fillMaxWidth().height(86.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                weekdays.forEachIndexed { i, ms ->
                    val share by animateFloatAsState((ms.toFloat() / most).coerceIn(0.04f, 1f), tween(600), label = "day$i")
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((BAR_MAX * share).dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (i == top && ms > 0) tint else SoundColors.Surface3),
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
        }
    }
}
