package com.villamediana.sound.ui.social

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.db.SharedSong
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.social.Profile
import com.villamediana.sound.social.SongRef
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.sheets.ConfirmDialog
import com.villamediana.sound.ui.sheets.SheetAction
import com.villamediana.sound.ui.sheets.SoundSheet
import com.villamediana.sound.ui.sheets.rememberSheetFeedback
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Someone's page. A friend: photo, hours and top 5, the songs you swapped (theirs on the left,
 * yours on the right; tap to play), "Mandar música" and "Jam". Someone else: photo and name, and
 * the friend request.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FriendScreen(id: String) {
    val hub = SoundApplication.graph.social
    val nav = navigator()
    val friends by hub.friends.collectAsStateWithLifecycle()
    val jam by hub.jam.jam.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val feedback = rememberSheetFeedback()
    val res = LocalContext.current.resources
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val asFriend = friends.friends.firstOrNull { it.id == id }
    var fetched by remember(id) { mutableStateOf<Profile?>(null) }
    LaunchedEffect(id, asFriend == null) {
        if (asFriend == null) fetched = runCatching { withContext(Dispatchers.IO) { hub.api.user(id) } }.getOrNull()
    }
    val incoming = friends.incoming.firstOrNull { it.user.id == id }
    val profile = asFriend ?: fetched ?: incoming?.user
    val isFriend = asFriend != null
    val songs by remember(id) { hub.songs.thread(id) }.collectAsStateWithLifecycle(initialValue = emptyList())

    // Open here: its songs are seen, and new ones come in without a notification.
    DisposableEffect(id) {
        hub.openFriend = id
        onDispose { if (hub.openFriend == id) hub.openFriend = null }
    }
    LaunchedEffect(id, songs.size) { hub.songs.markSeen(id) }

    var menu by rememberSaveable { mutableStateOf(false) }
    var unfriending by rememberSaveable { mutableStateOf(false) }
    var sending by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().background(SoundColors.Ink),
        contentPadding = PaddingValues(top = top, bottom = LocalBottomInset.current),
    ) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SoundColors.Text) }
                Spacer(Modifier.weight(1f))
                if (isFriend) IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options), tint = SoundColors.Text) }
            }
        }
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                ProfileAvatar(profile, 112.dp)
                Spacer(Modifier.height(14.dp))
                Text(profile?.name.orEmpty(), style = MaterialTheme.typography.headlineMedium, color = SoundColors.Text, textAlign = TextAlign.Center, maxLines = 2)
                if (profile != null) {
                    Text("@${profile.username}", style = MaterialTheme.typography.bodyLarge, color = SoundColors.TextMuted)
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.social_since, monthYear(profile.since)), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint)
                }
                Spacer(Modifier.height(18.dp))
                if (isFriend) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(stringResource(R.string.friend_send), onClick = { sending = true }, icon = Icons.AutoMirrored.Rounded.Send, filled = true)
                        PillButton(
                            stringResource(if (jam != null) R.string.friend_invite_jam else R.string.friend_jam),
                            icon = Icons.Rounded.GraphicEq,
                            onClick = {
                                scope.launch {
                                    val result = if (jam != null) hub.jam.invite(listOf(id)) else hub.jam.start(listOf(id))
                                    result.onFailure { feedback.show(res.getString(R.string.jam_failed)) }
                                }
                            },
                        )
                    }
                } else if (profile != null) {
                    Text(stringResource(R.string.friend_not_friend), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
                    Spacer(Modifier.height(12.dp))
                    when {
                        incoming != null -> PillButton(stringResource(R.string.social_accept), onClick = { scope.launch { hub.accept(incoming.requestId) } }, filled = true)
                        friends.outgoing.any { it.user.id == id } || profile.relation == "outgoing" ->
                            Text(stringResource(R.string.social_requested), style = MaterialTheme.typography.labelLarge, color = SoundColors.TextFaint)
                        else -> PillButton(stringResource(R.string.social_add), onClick = { scope.launch { hub.requestFriend(id) } }, filled = true)
                    }
                }
            }
        }
        if (isFriend && asFriend != null) {
            item(key = "stats") { StatsBlock(asFriend) }
            item(key = "gap") { Spacer(Modifier.height(Dimens.SectionGap)) }
            if (songs.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.friend_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SoundColors.TextFaint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            items(songs, key = { it.id }) { s -> SongBubble(s, onPlay = { playShared(s.toRef(), res.getString(R.string.playback_source_radio, s.title)) }) }
        }
    }

    if (menu) {
        SoundSheet(onDismissRequest = { menu = false }) {
            SheetAction(Icons.Rounded.PersonRemove, stringResource(R.string.friend_unfriend), tint = SoundColors.Danger, labelColor = SoundColors.Danger, onClick = {
                menu = false
                unfriending = true
            })
        }
    }
    if (unfriending && profile != null) {
        ConfirmDialog(
            title = stringResource(R.string.friend_unfriend_title, profile.name),
            body = stringResource(R.string.friend_unfriend_body),
            confirm = stringResource(R.string.friend_unfriend_confirm),
            onConfirm = {
                unfriending = false
                scope.launch { hub.unfriend(id) }
            },
            onDismiss = { unfriending = false },
        )
    }
    if (sending && asFriend != null) SendSongSheet(asFriend, onDismiss = { sending = false })
}

private fun SharedSong.toRef() = SongRef(videoId, title, artist, album, durationMs, cover, url)

/** A song in the conversation: the friend's on the left, yours on the right. */
@Composable
internal fun SongBubble(s: SharedSong, onPlay: () -> Unit) {
    val mine = s.direction == SharedSong.OUT
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 5.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Row(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (mine) 20.dp else 6.dp, bottomEnd = if (mine) 6.dp else 20.dp))
                .background(if (mine) SoundColors.AccentSoft else SoundColors.Surface1)
                .clickable(onClick = onPlay)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(s.cover, Modifier.size(52.dp), seed = s.title, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.padding(end = 8.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(s.artist, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Mandar pra Fulano": the song playing now, then your likes (filterable). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SendSongSheet(friend: Profile, onDismiss: () -> Unit) {
    val graph = SoundApplication.graph
    val hub = graph.social
    val scope = rememberCoroutineScope()
    val feedback = rememberSheetFeedback()
    val res = LocalContext.current.resources
    val playback by graph.player.state.collectAsStateWithLifecycle()
    val liked by graph.library.likedAll.collectAsStateWithLifecycle(initialValue = emptyList())
    var filter by remember { mutableStateOf("") }
    val now = playback.current?.takeIf { it.videoId != null }
    val likes = liked.filter { it.videoId != null && it.videoId != now?.videoId }
        .filter { filter.isBlank() || it.title.contains(filter, true) || it.artist.contains(filter, true) }
        .take(60)

    fun send(t: Track) {
        val song = SongRef.of(t) ?: return
        onDismiss()
        scope.launch {
            hub.songs.send(friend.id, song)
                .onSuccess { feedback.show(res.getString(R.string.send_sent, friend.name)) }
                .onFailure { feedback.show(res.getString(R.string.send_failed)) }
        }
    }

    SoundSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.send_sheet_title, friend.name),
            style = MaterialTheme.typography.headlineSmall,
            color = SoundColors.Text,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
        )
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            if (now != null) {
                SendLabel(stringResource(R.string.send_now_playing))
                SendRow(now) { send(now) }
            }
            if (liked.isNotEmpty()) {
                SendLabel(stringResource(R.string.send_liked))
                com.villamediana.sound.ui.sheets.SoundTextField(
                    filter, { filter = it }, label = stringResource(R.string.send_filter),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                )
                likes.forEach { t -> SendRow(t) { send(t) } }
            }
        }
    }
}

@Composable
private fun SendLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = SoundColors.TextFaint,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun SendRow(t: Track, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).height(64.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(t.coverPath, Modifier.size(46.dp), seed = t.album ?: t.title, shape = RoundedCornerShape(10.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.Send, null, tint = SoundColors.Accent, modifier = Modifier.size(20.dp))
    }
}
