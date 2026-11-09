package com.villamediana.sound.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.social.Profile
import com.villamediana.sound.social.SongRef
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.ui.components.seedColor
import com.villamediana.sound.ui.nav.Navigator
import com.villamediana.sound.ui.theme.SoundColors
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a social notification asked to open ("friends", "friend:<id>", "jam"), read by [SocialHost]. */
object SocialRequests {
    var pending by mutableStateOf<String?>(null)
        private set

    fun open(what: String) {
        pending = what
    }

    fun consume(): String? = pending.also { pending = null }
}

/**
 * A round photo, or the initials on the person's own color (lit from the top-left, like the covers)
 * when there is none.
 */
@Composable
fun ProfileAvatar(profile: Profile?, size: Dp, modifier: Modifier = Modifier, ring: Color? = null) {
    val hub = SoundApplication.graph.social
    val url = remember(profile?.avatar) { hub.api.absolute(profile?.avatar) }
    val seed = profile?.username ?: ""
    val fill = remember(seed, profile == null) {
        if (profile == null) Brush.linearGradient(listOf(SoundColors.Surface3, SoundColors.Surface3))
        else seedColor(seed).let { c -> Brush.linearGradient(listOf(lerp(c, Color.White, 0.22f), lerp(c, Color.Black, 0.16f))) }
    }
    Box(
        modifier
            .size(size)
            .then(if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        if (profile != null) {
            Text(
                initials(profile.name),
                color = Color.White,
                fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (size.value * -0.004f).sp,
                maxLines = 1,
            )
        }
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        }
    }
}

/** Overlapping photos (the people in a jam). */
@Composable
fun AvatarStack(people: List<Profile>, size: Dp, modifier: Modifier = Modifier, max: Int = 4) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        people.take(max).forEachIndexed { i, p ->
            ProfileAvatar(p, size, Modifier.offset(x = -(size * 0.32f) * i), ring = SoundColors.Ink)
        }
    }
}

fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}

/** "nov 2026" in the phone's language. */
fun monthYear(epochMs: Long, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("MMM yyyy", locale).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())).replace(".", "")

/** Hours listened: "0,5" · "12" · "1.204". */
fun hours(ms: Long, locale: Locale = Locale.getDefault()): String {
    val h = ms / 3_600_000.0
    val f = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = if (h < 10) 1 else 0 }
    return f.format(h)
}

/** A song from a friend (or from a profile's top 5) played like a search result: the song, then its radio. */
fun playShared(song: SongRef, radioTitle: String) {
    val remote = song.toRemote()
    SoundApplication.graph.player.playOnline(listOf(remote), 0, PlaySource(PlaySource.Type.RADIO, radioTitle, song.videoId))
}

/** Jam invites while the app is open, and what a social notification asked to open. */
@Composable
fun SocialHost(navigator: Navigator) {
    val hub = SoundApplication.graph.social
    val invites by hub.jam.invites.collectAsStateWithLifecycle()
    val asked = SocialRequests.pending
    LaunchedEffect(asked) {
        when (val what = SocialRequests.consume() ?: return@LaunchedEffect) {
            "friends", "jam" -> navigator.openSocial()
            else -> if (what.startsWith("friend:")) navigator.openFriend(what.removePrefix("friend:"))
        }
    }
    // Each invite asks once, in a sheet; turned down here it stays listed on the Amigos page.
    var shown by remember { mutableStateOf(setOf<String>()) }
    val next = invites.firstOrNull { it.jam.id !in shown }
    if (next != null) {
        JamInviteSheet(next, onDone = { shown = shown + next.jam.id })
    }
}

/**
 * The header's door to Amigos: your photo (a people icon before the profile exists), with a dot
 * when something waits — a friend request, a song not opened yet, a jam invite.
 */
@Composable
fun SocialButton(onClick: () -> Unit) {
    val hub = SoundApplication.graph.social
    val me by hub.account.me.collectAsStateWithLifecycle()
    val friends by hub.friends.collectAsStateWithLifecycle()
    val invites by hub.jam.invites.collectAsStateWithLifecycle()
    val unseen by remember { hub.songs.all }.collectAsStateWithLifecycle(initialValue = emptyList())
    val waiting = friends.incoming.isNotEmpty() || invites.isNotEmpty() ||
        unseen.any { it.direction == com.villamediana.sound.data.db.SharedSong.IN && !it.seen }
    androidx.compose.material3.IconButton(onClick = onClick) {
        Box(Modifier.size(30.dp)) {
            if (me != null) {
                ProfileAvatar(me, 30.dp)
            } else {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Rounded.Groups,
                    androidx.compose.ui.res.stringResource(com.villamediana.sound.R.string.social_open),
                    tint = SoundColors.Text,
                    modifier = Modifier.size(26.dp).align(Alignment.Center),
                )
            }
            if (waiting) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(11.dp).clip(CircleShape).background(SoundColors.Ink).padding(2.dp).clip(CircleShape).background(SoundColors.Accent),
                )
            }
        }
    }
}
