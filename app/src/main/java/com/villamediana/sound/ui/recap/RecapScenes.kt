package com.villamediana.sound.ui.recap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.villamediana.sound.R
import com.villamediana.sound.stats.PartOfDay
import com.villamediana.sound.stats.Recap
import com.villamediana.sound.stats.RecapStoryPlan
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.SoundMark
import com.villamediana.sound.ui.components.formatLongDuration
import com.villamediana.sound.ui.theme.Outfit
import java.time.Instant
import java.time.Month
import java.time.Year
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateText
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 1% of the story's width: type and objects scale with the phone, not with a fixed size. */
internal val LocalStoryUnit = compositionLocalOf { 3.9.dp }

@Composable
private fun u(percent: Float): Dp = LocalStoryUnit.current * percent

@Composable
private fun usp(percent: Float): TextUnit = with(LocalDensity.current) { (LocalStoryUnit.current * percent).toSp() }

@Composable
internal fun ProvideStoryUnit(width: Dp, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStoryUnit provides width / 100f, content = content)
}

private val Huge = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Black, letterSpacing = (-0.04).em, lineHeight = 1.0.em)
private val Big = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.025).em, lineHeight = 1.05.em)
private val Lead = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.005).em, lineHeight = 1.25.em)
private val Small = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, letterSpacing = 0.14.em)

/** A scene's frame: the content centered, clear of the bar on top. */
@Composable
private fun Stage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().padding(top = 96.dp, bottom = 40.dp).padding(horizontal = u(7f)), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Caps(text: String, t: Float, start: Float, color: Color = StoryMuted) {
    Text(text.uppercase(Locale.getDefault()), style = Small.copy(fontSize = usp(3.3f)), color = color, textAlign = TextAlign.Center, modifier = Modifier.rise(t, start, 0.6f, 14.dp))
}

// ---------------------------------------------------------------- 1. opening

@Composable
internal fun IntroScene(r: Recap, t: Float, clock: Float, glow: Glow) {
    Box(Modifier.fillMaxSize()) {
        Ripples(clock, Color.White, strength = easeOut(phase(t, 0.4f, 1.6f)))
        WaveLine(
            clock, drawn = easeInOut(phase(t, 0.1f, 1.5f)), color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth().height(u(30f)).align(Alignment.Center).graphicsLayer { alpha = 1f - 0.6f * phase(t, 1.6f, 1.2f) },
        )
        Stage {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Caps(stringResource(R.string.recap_title), t, 0.5f)
                Spacer(Modifier.height(u(3f)))
                Row {
                    r.year.toString().forEachIndexed { i, ch ->
                        Text(
                            ch.toString(),
                            style = Huge.copy(
                                fontSize = usp(29f),
                                brush = Brush.verticalGradient(listOf(Color.White, lerp(Color.White, glow.a, 0.55f))),
                            ),
                            modifier = Modifier.graphicsLayer {
                                val p = phase(t, 0.9f + i * 0.16f, 0.9f)
                                alpha = easeOut(p * 1.6f)
                                translationY = (1f - backOut(p)) * 90.dp.toPx()
                                rotationX = (1f - easeOut(p)) * 70f
                            },
                        )
                    }
                }
                Spacer(Modifier.height(u(4f)))
                Kinetic(stringResource(R.string.recap_hero_label), t, 2.1f, Big.copy(fontSize = usp(8.2f)))
                Spacer(Modifier.height(u(10f)))
                Text(
                    stringResource(R.string.recap_intro_hint),
                    style = Lead.copy(fontSize = usp(3.6f)),
                    color = StoryMuted,
                    modifier = Modifier.graphicsLayer { alpha = easeOut(phase(t, 3.4f, 0.8f)) * (0.55f + 0.45f * sin(clock * 3f)) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 2. the first song

@Composable
internal fun FirstScene(r: Recap, t: Float, clock: Float) {
    val song = r.firstSong ?: return
    val at = r.firstAt ?: return
    val zoned = remember(at) { Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()) }
    val pattern = stringResource(R.string.recap_date_pattern)
    val date = remember(zoned, pattern) { zoned.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault())) }
    val time = remember(zoned) { "%02d:%02d".format(zoned.hour, zoned.minute) }
    Stage {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Caps(stringResource(R.string.recap_first_label), t, 0.2f)
            Spacer(Modifier.height(u(2f)))
            Text(date, style = Big.copy(fontSize = usp(11f)), color = StoryText, textAlign = TextAlign.Center, modifier = Modifier.rise(t, 0.5f))
            Text(stringResource(R.string.recap_first_time, time), style = Lead.copy(fontSize = usp(5f)), color = StoryMuted, modifier = Modifier.rise(t, 0.8f))
            Spacer(Modifier.height(u(7f)))
            // The record arrives spinning and keeps turning.
            Vinyl(
                song.cover, song.title, u(62f), angle = t * 95f,
                modifier = Modifier.slide(t, 1.4f, 1.1f, 260.dp).graphicsLayer {
                    val p = phase(t, 1.4f, 1.1f)
                    rotationZ = (1f - easeOut(p)) * 40f
                },
            )
            Spacer(Modifier.height(u(7f)))
            Caps(stringResource(R.string.recap_first_with), t, 2.4f)
            Spacer(Modifier.height(u(1.5f)))
            Text(song.title, style = Big.copy(fontSize = usp(8f)), color = StoryText, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.rise(t, 2.6f))
            Text(song.artist, style = Lead.copy(fontSize = usp(4.6f)), color = StoryMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rise(t, 2.8f))
        }
    }
}

// ---------------------------------------------------------------- 3. the time

@Composable
internal fun TimeScene(r: Recap, t: Float, clock: Float, glow: Glow) {
    val minutes = r.listenedMs / 60_000L
    val counting = easeOutExpo(phase(t, 0.7f, 2.8f))
    Box(Modifier.fillMaxSize()) {
        Ripples(clock, glow.b, center = Offset(0.5f, 0.42f), strength = if (counting < 1f) 1f else 0.45f, period = if (counting < 1f) 1.4f else 3.2f)
        Stage {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Kinetic(stringResource(R.string.recap_time_label), t, 0.2f, Lead.copy(fontSize = usp(6.2f)))
                Spacer(Modifier.height(u(3f)))
                Text(
                    formatCount((minutes * counting).toLong()),
                    style = Huge.copy(fontSize = usp(if (minutes >= 100_000) 19f else 23f), fontFeatureSettings = "tnum"),
                    color = StoryText,
                    maxLines = 1,
                    modifier = Modifier.pop(t, 0.6f, 0.9f, 0.7f),
                )
                Text(stringResource(R.string.recap_time_unit), style = Big.copy(fontSize = usp(6.5f)), color = StoryText, modifier = Modifier.rise(t, 0.9f))
                Spacer(Modifier.height(u(6f)))
                val days = (minutes / 1_440L).toInt()
                val hours = (minutes / 60L).toInt()
                val equal = if (days >= 1) pluralStringResource(R.plurals.recap_time_days, days, days) else pluralStringResource(R.plurals.recap_time_hours, hours, hours)
                Kinetic(equal, t, 3.7f, Lead.copy(fontSize = usp(5.2f)), color = StoryMuted)
                Spacer(Modifier.height(u(8f)))
                Row(horizontalArrangement = Arrangement.spacedBy(u(2.5f))) {
                    Chip(formatCount(r.songs.toLong()), pluralStringResource(R.plurals.recap_songs, r.songs), Modifier.pop(t, 4.6f, 0.7f))
                    Chip(formatCount(r.artists.toLong()), pluralStringResource(R.plurals.recap_artists, r.artists), Modifier.pop(t, 4.85f, 0.7f))
                    Chip(formatCount(r.plays.toLong()), pluralStringResource(R.plurals.recap_plays, r.plays), Modifier.pop(t, 5.1f, 0.7f))
                }
            }
        }
    }
}

@Composable
private fun Chip(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(u(5f)))
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(u(5f)))
            .padding(horizontal = u(4f), vertical = u(3f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = Big.copy(fontSize = usp(5.6f), fontFeatureSettings = "tnum"), color = StoryText, maxLines = 1)
        Text(label, style = Lead.copy(fontSize = usp(3.2f)), color = StoryMuted, maxLines = 1)
    }
}

// ---------------------------------------------------------------- 4. month by month

@Composable
internal fun MonthsScene(r: Recap, t: Float, clock: Float, glow: Glow) {
    val peak = RecapStoryPlan.peakMonth(r) ?: return
    val most = r.months.max().coerceAtLeast(1L)
    val locale = Locale.getDefault()
    Stage {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Kinetic(stringResource(R.string.recap_months_title), t, 0.2f, Big.copy(fontSize = usp(8f)))
            Spacer(Modifier.height(u(8f)))
            Box(Modifier.fillMaxWidth().height(u(62f))) {
                Canvas(Modifier.fillMaxSize()) {
                    val gap = size.width * 0.022f
                    val bw = (size.width - gap * 11) / 12
                    for (i in 0 until 12) {
                        val grow = backOut(phase(t, 0.8f + i * 0.085f, 0.7f), 1.2f)
                        val share = r.months[i].toFloat() / most
                        val h = (0.04f + 0.96f * share) * size.height * grow
                        val x = i * (bw + gap)
                        val isPeak = i == peak
                        if (isPeak) {
                            // The peak glows and breathes once it is there.
                            val pulse = phase(t, 2.4f, 0.6f) * (0.75f + 0.25f * sin(clock * 3.2f))
                            drawCircle(
                                Brush.radialGradient(listOf(glow.a.copy(alpha = 0.75f * pulse), Color.Transparent), Offset(x + bw / 2, size.height - h), bw * 3.2f),
                                bw * 3.2f, Offset(x + bw / 2, size.height - h),
                            )
                        }
                        drawRoundRect(
                            if (isPeak) Brush.verticalGradient(listOf(Color.White, lerp(Color.White, glow.a, 0.6f)), size.height - h, size.height)
                            else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.16f)), size.height - h, size.height),
                            topLeft = Offset(x, size.height - h),
                            size = Size(bw, h),
                            cornerRadius = CornerRadius(bw / 2, bw / 2),
                        )
                    }
                }
            }
            Spacer(Modifier.height(u(2f)))
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 12) {
                    Text(
                        Month.of(i + 1).getDisplayName(DateText.NARROW, locale).uppercase(locale),
                        style = Small.copy(fontSize = usp(3f), letterSpacing = 0.em),
                        color = if (i == peak) StoryText else StoryMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).rise(t, 0.8f + i * 0.085f, 0.5f, 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(u(9f)))
            val name = Month.of(peak + 1).getDisplayName(DateText.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
            Text(name, style = Huge.copy(fontSize = usp(14f)), color = StoryText, modifier = Modifier.pop(t, 2.6f, 0.9f, 0.7f))
            Kinetic(stringResource(R.string.recap_months_peak, formatLongDuration(r.months[peak])), t, 3.2f, Lead.copy(fontSize = usp(5f)), color = StoryMuted)
        }
    }
}

// ---------------------------------------------------------------- 5. the rhythm (hour, day, streak)

/** The sky at [hour] (0–24, fractional): night, dawn, day, dusk. */
internal fun skyAt(hour: Float): Pair<Color, Color> {
    val night = Color(0xFF0B1030) to Color(0xFF1B1446)
    val dawn = Color(0xFFFF8A5C) to Color(0xFF7A3E9E)
    val day = Color(0xFF3FA0FF) to Color(0xFF7FD3FF)
    val dusk = Color(0xFFFF5E7E) to Color(0xFF3B2A86)
    val stops = listOf(0f to night, 5f to night, 6.5f to dawn, 9f to day, 16f to day, 18.5f to dusk, 20.5f to night, 24f to night)
    val h = ((hour % 24f) + 24f) % 24f
    for (i in 0 until stops.lastIndex) {
        val (a, ca) = stops[i]
        val (b, cb) = stops[i + 1]
        if (h in a..b) {
            val f = if (b == a) 0f else (h - a) / (b - a)
            return lerp(ca.first, cb.first, f) to lerp(ca.second, cb.second, f)
        }
    }
    return night
}

@Composable
internal fun RhythmScene(r: Recap, t: Float, clock: Float) {
    val hour = r.favoriteHour ?: return
    val sweep = easeInOut(phase(t, 0.5f, 2.4f))
    val now = hour * sweep
    val (top, bottom) = skyAt(now)
    val part = RecapStoryPlan.partOfDay(hour)
    val locale = Locale.getDefault()
    Box(Modifier.fillMaxSize()) {
        // The sky turns with the dial, over the backdrop.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.85f * easeOut(phase(t, 0f, 0.8f)) }.background(Brush.verticalGradient(listOf(top, bottom, StoryInk))))
        Stage {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Caps(stringResource(R.string.recap_rhythm_label), t, 0.2f, color = StoryText.copy(alpha = 0.8f))
                Spacer(Modifier.height(u(5f)))
                Box(Modifier.size(u(64f)).pop(t, 0.3f, 0.9f, 0.8f), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val c = Offset(size.width / 2, size.height / 2)
                        val radius = size.minDimension / 2 * 0.86f
                        // 24 ticks, the quarters longer.
                        for (i in 0 until 24) {
                            val a = (i / 24f) * 2 * PI.toFloat() - PI.toFloat() / 2
                            val long = i % 6 == 0
                            val inner = radius * (if (long) 0.84f else 0.90f)
                            drawLine(Color.White.copy(alpha = if (long) 0.7f else 0.32f), Offset(c.x + cos(a) * inner, c.y + sin(a) * inner), Offset(c.x + cos(a) * radius, c.y + sin(a) * radius), strokeWidth = (if (long) 2.5f else 1.5f).dp.toPx())
                        }
                        // The trail from midnight to the hour, and the light on it.
                        val end = (now / 24f) * 360f
                        drawArc(
                            Brush.sweepGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.9f)), c),
                            -90f, end, false, Offset(c.x - radius * 1.12f, c.y - radius * 1.12f), Size(radius * 2.24f, radius * 2.24f),
                            style = Stroke(4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                        )
                        val a = (now / 24f) * 2 * PI.toFloat() - PI.toFloat() / 2
                        val dot = Offset(c.x + cos(a) * radius * 1.12f, c.y + sin(a) * radius * 1.12f)
                        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.7f), Color.Transparent), dot, 34.dp.toPx()), 34.dp.toPx(), dot)
                        drawCircle(Color.White, 7.dp.toPx(), dot)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.recap_hour, now.toInt()), style = Huge.copy(fontSize = usp(17f), fontFeatureSettings = "tnum"), color = StoryText)
                    }
                }
                Spacer(Modifier.height(u(6f)))
                val persona = when (part) {
                    PartOfDay.DAWN -> R.string.recap_rhythm_dawn
                    PartOfDay.MORNING -> R.string.recap_rhythm_morning
                    PartOfDay.AFTERNOON -> R.string.recap_rhythm_afternoon
                    PartOfDay.NIGHT -> R.string.recap_rhythm_night
                }
                Kinetic(stringResource(persona), t, 3.0f, Big.copy(fontSize = usp(9.5f)))
                Spacer(Modifier.height(u(3f)))
                r.favoriteDay?.let { d ->
                    val day = d.getDisplayName(DateText.FULL, locale).replaceFirstChar { it.titlecase(locale) }
                    Kinetic(stringResource(R.string.recap_rhythm_day, day), t, 3.9f, Lead.copy(fontSize = usp(5f)), color = StoryText.copy(alpha = 0.82f))
                }
                Spacer(Modifier.height(u(6f)))
                Row(horizontalArrangement = Arrangement.spacedBy(u(2.5f))) {
                    if (r.longestStreak >= 2) {
                        Chip(formatCount(r.longestStreak.toLong()), pluralStringResource(R.plurals.recap_rhythm_streak, r.longestStreak), Modifier.pop(t, 4.8f, 0.7f))
                    }
                    if (r.daysListened > 0) {
                        val total = Year.of(r.year).length()
                        Chip("${r.daysListened}/$total", stringResource(R.string.recap_rhythm_days), Modifier.pop(t, 5.1f, 0.7f))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 6. the genre

@Composable
internal fun GenreScene(r: Recap, t: Float, clock: Float) {
    val genre = r.topGenre ?: return
    val outline = Huge.copy(fontSize = usp(17f), drawStyle = Stroke(width = 2.5f) as DrawStyle)
    Box(Modifier.fillMaxSize()) {
        // Rows of the genre sliding behind, in turns.
        Column(Modifier.fillMaxSize().padding(vertical = 80.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            repeat(6) { row ->
                val dir = if (row % 2 == 0) -1f else 1f
                Text(
                    List(6) { genre.uppercase(Locale.getDefault()) }.joinToString("  ·  "),
                    style = outline,
                    color = Color.White.copy(alpha = 0.12f),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.graphicsLayer {
                        translationX = dir * ((clock * 60.dp.toPx()) % (size.width / 3f)) - size.width / 6f
                        alpha = easeOut(phase(t, 0.1f + row * 0.08f, 0.8f))
                    },
                )
            }
        }
        Stage {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Caps(stringResource(R.string.recap_genre_label), t, 0.4f, StoryText.copy(alpha = 0.8f))
                Spacer(Modifier.height(u(3f)))
                Text(
                    genre,
                    style = Huge.copy(fontSize = usp(if (genre.length > 9) 13f else 18f)),
                    color = StoryText,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.graphicsLayer {
                        val p = easeOut(phase(t, 1.0f, 1.2f))
                        alpha = p
                        scaleX = 1.35f - 0.35f * p
                        scaleY = 1.35f - 0.35f * p
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 7. the artist of the year

@Composable
internal fun ArtistScene(r: Recap, t: Float, clock: Float, glow: Glow) {
    val artist = r.topArtists.firstOrNull() ?: return
    val reveal = 3.4f
    val tease = 1f - easeOut(phase(t, reveal - 0.5f, 0.5f))
    Box(Modifier.fillMaxSize()) {
        if (t >= reveal) Ripples(clock, glow.a, center = Offset(0.5f, 0.42f), strength = easeOut(phase(t, reveal, 0.8f)), period = 2.6f)
        // The wait: a heart beating in the dark.
        if (tease > 0f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = tease }, contentAlignment = Alignment.Center) {
                val beat = 1f + 0.08f * sin(clock * 7.5f).coerceAtLeast(0f)
                Box(Modifier.size(u(40f)).graphicsLayer { scaleX = beat; scaleY = beat }.background(Brush.radialGradient(listOf(glow.a.copy(alpha = 0.7f), Color.Transparent)), CircleShape))
                Kinetic(stringResource(R.string.recap_artist_tease), t, 0.3f, Big.copy(fontSize = usp(8.5f)), modifier = Modifier.padding(horizontal = u(8f)))
            }
        }
        if (t >= reveal - 0.05f) {
            Stage {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Caps(stringResource(R.string.recap_artist_label), t, reveal + 0.3f, StoryText.copy(alpha = 0.85f))
                    Spacer(Modifier.height(u(5f)))
                    Box(
                        Modifier
                            .size(u(64f))
                            .pop(t, reveal, 1.0f, 0.4f)
                            .graphicsLayer { rotationZ = (1f - easeOut(phase(t, reveal, 1.0f))) * -12f }
                            .drawBehind { drawCircle(Brush.radialGradient(listOf(glow.a.copy(alpha = 0.8f), Color.Transparent)), size.minDimension * 0.78f) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Cover(artist.cover, Modifier.fillMaxSize().border(3.dp, Color.White.copy(alpha = 0.85f), CircleShape), seed = artist.name, shape = CircleShape)
                    }
                    Spacer(Modifier.height(u(6f)))
                    Text(artist.name, style = Huge.copy(fontSize = usp(if (artist.name.length > 14) 10f else 13f)), color = StoryText, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.rise(t, reveal + 0.5f, 0.8f, 30.dp))
                    Spacer(Modifier.height(u(2f)))
                    Text(
                        stringResource(R.string.recap_artist_stats, formatLongDuration(artist.listenedMs), pluralStringResource(R.plurals.recap_times_long, artist.plays, artist.plays)),
                        style = Lead.copy(fontSize = usp(4.8f)), color = StoryMuted, textAlign = TextAlign.Center,
                        modifier = Modifier.rise(t, reveal + 0.9f),
                    )
                }
            }
        }
        Flash(t, reveal)
    }
}

// ---------------------------------------------------------------- 8. the artists

@Composable
internal fun ArtistsScene(r: Recap, t: Float) {
    Stage {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Kinetic(stringResource(R.string.recap_artists_title), t, 0.2f, Big.copy(fontSize = usp(8f)))
            Spacer(Modifier.height(u(7f)))
            r.topArtists.forEachIndexed { i, a ->
                val first = i == 0
                Row(
                    Modifier.fillMaxWidth().padding(vertical = u(1.6f)).slide(t, 0.8f + i * 0.2f, 0.7f, 120.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Rank(i + 1, if (first) 12f else 8.5f)
                    Cover(a.cover, Modifier.size(u(if (first) 20f else 14f)), seed = a.name, shape = CircleShape)
                    Spacer(Modifier.width(u(4f)))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = Big.copy(fontSize = usp(if (first) 7f else 5.4f)), color = StoryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatLongDuration(a.listenedMs), style = Lead.copy(fontSize = usp(3.6f)), color = StoryMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** A rank number in outline. */
@Composable
private fun Rank(n: Int, size: Float) {
    Text(
        "$n",
        style = Huge.copy(fontSize = usp(size), drawStyle = Stroke(width = 3f) as DrawStyle),
        color = StoryText,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(u(size * 0.95f)),
    )
}

// ---------------------------------------------------------------- 9. the song of the year

@Composable
internal fun SongScene(r: Recap, t: Float, clock: Float, glow: Glow) {
    val song = r.topSongs.firstOrNull() ?: return
    val reveal = 3.2f
    val tease = 1f - easeOut(phase(t, reveal - 0.5f, 0.5f))
    Box(Modifier.fillMaxSize()) {
        if (tease > 0f) {
            Column(Modifier.fillMaxSize().graphicsLayer { alpha = tease }.padding(horizontal = u(8f)), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Kinetic(stringResource(R.string.recap_song_tease), t, 0.3f, Big.copy(fontSize = usp(8.5f)))
                Spacer(Modifier.height(u(10f)))
                // A drumroll: the meter rises as the moment comes.
                Meter(clock * 1.6f, energy = 0.25f + 0.75f * easeOut(phase(t, 0.6f, 2.4f)), color = Color.White.copy(alpha = 0.85f), modifier = Modifier.fillMaxWidth().height(u(22f)))
            }
        }
        if (t >= reveal - 0.05f) {
            Stage {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Caps(stringResource(R.string.recap_song_label), t, reveal + 0.3f, StoryText.copy(alpha = 0.85f))
                    Spacer(Modifier.height(u(5f)))
                    Box(
                        Modifier
                            .size(u(66f))
                            .graphicsLayer {
                                val p = phase(t, reveal, 1.1f)
                                val s = 1.3f - 0.3f * backOut(p)
                                scaleX = s
                                scaleY = s
                                alpha = easeOut(p * 2f)
                                rotationZ = (1f - backOut(p)) * -9f
                                translationY = (1f - easeOut(p)) * -50.dp.toPx()
                            }
                            .drawBehind {
                                val pulse = 0.85f + 0.15f * sin(clock * 4.2f)
                                drawCircle(Brush.radialGradient(listOf(glow.a.copy(alpha = 0.85f * pulse), Color.Transparent)), size.minDimension * 0.85f)
                            },
                    ) {
                        Cover(song.cover, Modifier.fillMaxSize(), seed = song.title, shape = RoundedCornerShape(u(4f)))
                    }
                    Spacer(Modifier.height(u(4f)))
                    Meter(clock, energy = 0.9f * easeOut(phase(t, reveal + 0.6f, 0.8f)), color = Color.White.copy(alpha = 0.7f), modifier = Modifier.width(u(50f)).height(u(7f)))
                    Spacer(Modifier.height(u(4f)))
                    Text(song.title, style = Huge.copy(fontSize = usp(if (song.title.length > 16) 9f else 12f)), color = StoryText, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.rise(t, reveal + 0.5f, 0.8f, 30.dp))
                    Text(song.artist, style = Lead.copy(fontSize = usp(5f)), color = StoryMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rise(t, reveal + 0.7f))
                    Spacer(Modifier.height(u(4f)))
                    Text(
                        pluralStringResource(R.plurals.recap_song_plays, song.plays, song.plays),
                        style = Big.copy(fontSize = usp(5f)), color = StoryText,
                        modifier = Modifier.pop(t, reveal + 1.2f, 0.7f).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = u(5f), vertical = u(2f)),
                    )
                }
            }
        }
        Flash(t, reveal)
    }
}

// ---------------------------------------------------------------- 10. the top songs, counting down

@Composable
internal fun SongsScene(r: Recap, t: Float, clock: Float, glow: Glow) {
    val n = r.topSongs.size
    Stage {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Kinetic(stringResource(R.string.recap_songs_title), t, 0.2f, Big.copy(fontSize = usp(8f)))
            Spacer(Modifier.height(u(7f)))
            r.topSongs.forEachIndexed { i, s ->
                // From the last to the first: the #1 arrives at the end.
                val at = 0.8f + (n - 1 - i) * 0.55f
                val first = i == 0
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = u(1.2f))
                        .rise(t, at, 0.7f, 40.dp)
                        .clip(RoundedCornerShape(u(4f)))
                        .background(if (first) Color.White.copy(alpha = 0.14f * easeOut(phase(t, at, 0.8f))) else Color.Transparent)
                        .padding(u(2f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Rank(i + 1, if (first) 11f else 8f)
                    Cover(s.cover, Modifier.size(u(if (first) 17f else 13f)), seed = s.title, shape = RoundedCornerShape(u(2f)))
                    Spacer(Modifier.width(u(4f)))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = Big.copy(fontSize = usp(if (first) 6.2f else 5f)), color = StoryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, style = Lead.copy(fontSize = usp(3.6f)), color = StoryMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(u(2f)))
                    Text(pluralStringResource(R.plurals.recap_times, s.plays, s.plays), style = Big.copy(fontSize = usp(4.2f), fontFeatureSettings = "tnum"), color = StoryText.copy(alpha = 0.9f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 11. the closing poster

@Composable
internal fun OutroScene(r: Recap, t: Float, clock: Float, glow: Glow, onAgain: () -> Unit) {
    val locale = Locale.getDefault()
    Stage {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .pop(t, 0.15f, 1.0f, 0.88f)
                    .clip(RoundedCornerShape(u(8f)))
                    .background(Brush.linearGradient(listOf(lerp(glow.a, StoryInk, 0.15f), lerp(glow.b, StoryInk, 0.55f), lerp(glow.c, StoryInk, 0.35f))))
                    .drawBehind {
                        // The sound waves of the profile card, slow.
                        repeat(5) { i ->
                            val p = ((clock / 6f) + i / 5f) % 1f
                            drawCircle(Color.White.copy(alpha = (1f - p) * 0.12f), size.width * (0.2f + p * 1.1f), Offset(size.width * 1.05f, -size.height * 0.05f), style = Stroke(1.5.dp.toPx()))
                        }
                    }
                    .padding(u(7f)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(t, 0.5f)) {
                    SoundMark(size = 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("FreeSong", style = Big.copy(fontSize = usp(4f)), color = StoryText)
                }
                Spacer(Modifier.height(u(5f)))
                Text(r.year.toString(), style = Huge.copy(fontSize = usp(20f)), color = StoryText, modifier = Modifier.rise(t, 0.6f, 0.8f, 30.dp))
                Text(stringResource(R.string.recap_hero_label), style = Lead.copy(fontSize = usp(4.6f)), color = StoryText.copy(alpha = 0.8f), modifier = Modifier.rise(t, 0.75f))
                Spacer(Modifier.height(u(6f)))
                val facts = buildList {
                    add(stringResource(R.string.recap_outro_minutes) to formatCount(r.listenedMs / 60_000L))
                    r.topArtists.firstOrNull()?.let { add(stringResource(R.string.recap_outro_artist) to it.name) }
                    r.topSongs.firstOrNull()?.let { add(stringResource(R.string.recap_outro_song) to it.title) }
                    when {
                        r.topGenre != null -> add(stringResource(R.string.recap_outro_genre) to r.topGenre)
                        r.favoriteHour != null -> add(stringResource(R.string.recap_outro_hour) to stringResource(R.string.recap_hour, r.favoriteHour))
                    }
                }
                facts.chunked(2).forEachIndexed { row, pair ->
                    Row(Modifier.fillMaxWidth().padding(vertical = u(2f))) {
                        pair.forEachIndexed { col, (label, value) ->
                            Column(Modifier.weight(1f).rise(t, 1.0f + (row * 2 + col) * 0.15f)) {
                                Text(label.uppercase(locale), style = Small.copy(fontSize = usp(2.8f)), color = StoryText.copy(alpha = 0.7f), maxLines = 1)
                                Spacer(Modifier.height(u(0.8f)))
                                Text(value, style = Big.copy(fontSize = usp(5.6f)), color = StoryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(u(8f)))
            Text(
                stringResource(R.string.recap_again),
                style = Big.copy(fontSize = usp(4.4f)),
                color = StoryText,
                modifier = Modifier
                    .rise(t, 1.8f)
                    .clip(CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                    .clickable(onClick = onAgain)
                    .padding(horizontal = u(7f), vertical = u(3f)),
            )
        }
    }
}

// ---------------------------------------------------------------- nothing heard yet

@Composable
internal fun EmptyScene(t: Float) {
    Stage {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Kinetic(stringResource(R.string.recap_empty_title), t, 0.2f, Big.copy(fontSize = usp(10f)))
            Spacer(Modifier.height(u(3f)))
            Kinetic(stringResource(R.string.recap_empty_body), t, 0.7f, Lead.copy(fontSize = usp(5f)), color = StoryMuted)
        }
    }
}
