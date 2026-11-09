package com.villamediana.sound.ui.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.online.SearchFilter
import com.villamediana.sound.online.SearchItem
import com.villamediana.sound.playback.OnlineItem
import com.villamediana.sound.social.Profile
import com.villamediana.sound.social.SongRef
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.sheets.SoundSheet
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Music without leaving the conversation: search any song (or pick from what's playing and your
 * likes) and play it now, put it in the queue, or send it to the friend. In a jam, playing and
 * queueing are the jam's, like anywhere in the app.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatMusicSheet(friend: Profile, onDismiss: () -> Unit, onSend: (SongRef) -> Unit) {
    val graph = SoundApplication.graph
    val res = LocalContext.current.resources
    val playback by graph.player.state.collectAsStateWithLifecycle()
    val liked by graph.library.likedAll.collectAsStateWithLifecycle(initialValue = emptyList())
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<SongRef>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var queued by remember { mutableStateOf(setOf<String>()) }

    // Online, a moment after the typing stops.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            found = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(350)
        found = withContext(Dispatchers.IO) {
            runCatching {
                graph.youTube.search(q, SearchFilter.SONGS).items.filterIsInstance<SearchItem.Song>().map { SongRef.of(it.track) }
            }.getOrDefault(emptyList())
        }
        searching = false
    }

    val now = playback.current?.let(SongRef::of)
    val likes = liked.mapNotNull(SongRef::of)
        .filter { it.videoId != now?.videoId }
        .filter { query.isBlank() || it.title.contains(query.trim(), true) || it.artist.contains(query.trim(), true) }
        .take(if (query.isBlank()) 60 else 8)

    fun play(song: SongRef) {
        playShared(song, res.getString(R.string.playback_source_radio, song.title))
        onDismiss()
    }

    fun queue(song: SongRef) {
        graph.player.enqueue(listOf(OnlineItem.of(song.toRemote()).toTrack()))
        queued = queued + song.videoId
    }

    fun send(song: SongRef) {
        onSend(song)
        onDismiss()
    }

    SoundSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.chat_music),
            style = MaterialTheme.typography.headlineSmall,
            color = SoundColors.Text,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        SearchField(query, { query = it }, placeholder = stringResource(R.string.chat_music_search))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            if (query.isBlank() && now != null) {
                item(key = "now-label") { MusicLabel(stringResource(R.string.send_now_playing)) }
                item(key = "now") { MusicRow(now, friend, now.videoId in queued, { play(now) }, { queue(now) }, { send(now) }) }
            }
            if (query.isNotBlank()) {
                item(key = "found-label") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MusicLabel(stringResource(R.string.chat_music_found))
                        if (searching) CircularProgressIndicator(Modifier.padding(top = 6.dp).size(14.dp), color = SoundColors.Accent, strokeWidth = 2.dp)
                    }
                }
                items(found, key = { "f-" + it.videoId }) { s -> MusicRow(s, friend, s.videoId in queued, { play(s) }, { queue(s) }, { send(s) }) }
            }
            if (likes.isNotEmpty()) {
                item(key = "liked-label") { MusicLabel(stringResource(R.string.send_liked)) }
                items(likes, key = { "l-" + it.videoId }) { s -> MusicRow(s, friend, s.videoId in queued, { play(s) }, { queue(s) }, { send(s) }) }
            }
            item(key = "end") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun MusicLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = SoundColors.TextFaint,
        modifier = Modifier.padding(start = 24.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** A song: tap to play it now; the queue and send it to the friend on the side. */
@Composable
private fun MusicRow(song: SongRef, friend: Profile, queued: Boolean, onPlay: () -> Unit, onQueue: () -> Unit, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).height(64.dp).padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp)) {
            Cover(song.cover, Modifier.size(46.dp), seed = song.album ?: song.title, shape = RoundedCornerShape(10.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SmallAction(Icons.Rounded.PlayArrow, stringResource(R.string.chat_play_now), onPlay)
        SmallAction(if (queued) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.chat_queue), onQueue, enabled = !queued)
        SmallAction(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.chat_send_to, friend.name), onSend, accent = true)
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, accent: Boolean = false) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (accent) SoundColors.Accent else if (enabled) SoundColors.Text else SoundColors.TextFaint, modifier = Modifier.size(22.dp))
    }
}
