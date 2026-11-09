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
import androidx.compose.material.icons.rounded.PersonAdd
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
import com.villamediana.sound.ui.components.SectionHeader
import com.villamediana.sound.ui.detail.HeaderIconButton
import com.villamediana.sound.ui.detail.rememberDetailSnackbar
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
 * Someone's page, laid out like an artist's. A friend: their color, photo, name, @ and since when
 * you're friends, the hours, the round play (their five most heard), "Mandar música", "Jam", the
 * five as a list and the songs you swapped (theirs on the left, yours on the right; tap to play).
 * Someone else: photo, name and the friend request.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FriendScreen(id: String) {
    val hub = SoundApplication.graph.social
    val friends by hub.friends.collectAsStateWithLifecycle()
    val jam by hub.jam.jam.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = rememberDetailSnackbar()
    val res = LocalContext.current.resources

    val asFriend = friends.friends.firstOrNull { it.id == id }
    var fetched by remember(id) { mutableStateOf<Profile?>(null) }
    LaunchedEffect(id, asFriend == null) {
        if (asFriend == null) fetched = runCatching { withContext(Dispatchers.IO) { hub.api.user(id) } }.getOrNull()
    }
    val incoming = friends.incoming.firstOrNull { it.user.id == id }
    val profile = asFriend ?: fetched ?: incoming?.user
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

    FriendPage(
        person = profile,
        isFriend = asFriend != null,
        songs = songs,
        relation = when {
            incoming != null -> "incoming"
            friends.outgoing.any { it.user.id == id } || profile?.relation == "outgoing" -> "outgoing"
            else -> profile?.relation
        },
        inJam = jam != null,
        snackbar = snackbar,
        onSend = { sending = true },
        onJam = {
            scope.launch {
                val result = if (jam != null) hub.jam.invite(listOf(id)) else hub.jam.start(listOf(id))
                result.onFailure { snackbar.feedback.show(res.getString(R.string.jam_failed)) }
            }
        },
        onMenu = { menu = true },
        onAdd = {
            scope.launch {
                if (incoming != null) hub.accept(incoming.requestId) else hub.requestFriend(id)
            }
        },
        onPlaySwapped = { s -> playShared(s.toRef(), res.getString(R.string.playback_source_radio, s.title)) },
        sheets = {
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
        },
    )
}

/** What a person's page draws (apart from the screen's state, so the tests can draw it). */
@Composable
internal fun FriendPage(
    person: Profile?,
    isFriend: Boolean,
    songs: List<SharedSong>,
    relation: String?,
    inJam: Boolean,
    snackbar: com.villamediana.sound.ui.detail.DetailSnackbar,
    onSend: () -> Unit,
    onJam: () -> Unit,
    onMenu: () -> Unit,
    onAdd: () -> Unit,
    onPlaySwapped: (SharedSong) -> Unit,
    sheets: @Composable () -> Unit = {},
) {
    val since = person?.let {
        val friendsSince = it.friendsSince
        if (isFriend && friendsSince != null) stringResource(R.string.friend_since, monthYear(friendsSince))
        else stringResource(R.string.social_since, monthYear(it.since))
    }
    PersonPage(
        person = person,
        since = since,
        top = if (isFriend) person?.top.orEmpty() else emptyList(),
        topTitle = stringResource(R.string.profile_top),
        playTitle = stringResource(R.string.friend_top_of, person?.name.orEmpty()),
        snackbar = snackbar,
        avatar = { size -> CardAvatar(person, size) },
        showHours = isFriend,
        buttons = {
            if (isFriend) {
                PersonButton(stringResource(R.string.friend_send), onClick = onSend, icon = Icons.AutoMirrored.Rounded.Send, filled = true, modifier = Modifier.weight(1f))
                PersonButton(
                    stringResource(if (inJam) R.string.friend_invite_jam else R.string.friend_jam),
                    onClick = onJam,
                    icon = Icons.Rounded.GraphicEq,
                    modifier = Modifier.weight(1f),
                )
            } else if (person != null) {
                when (relation) {
                    "incoming" -> PersonButton(stringResource(R.string.social_accept), onClick = onAdd, filled = true, modifier = Modifier.weight(1f))
                    "outgoing" -> PersonButton(stringResource(R.string.social_requested), onClick = {}, enabled = false, modifier = Modifier.weight(1f))
                    else -> PersonButton(stringResource(R.string.social_add), onClick = onAdd, filled = true, icon = Icons.Rounded.PersonAdd, modifier = Modifier.weight(1f))
                }
            }
        },
        topActions = {
            if (isFriend) HeaderIconButton(Icons.Rounded.MoreVert, stringResource(R.string.more_options), onClick = onMenu)
        },
        sheets = sheets,
        more = {
            if (isFriend) {
                item(key = "swapped-title", contentType = "section") {
                    Column(Modifier.padding(top = Dimens.SectionGap)) { SectionHeader(stringResource(R.string.friend_swapped)) }
                }
                if (songs.isEmpty()) {
                    item(key = "swapped-empty", contentType = "empty") {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(R.string.friend_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = SoundColors.TextMuted,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(14.dp))
                            PillButton(stringResource(R.string.friend_send), onClick = onSend, icon = Icons.AutoMirrored.Rounded.Send, filled = true)
                        }
                    }
                }
                items(songs, key = { "s-" + it.id }, contentType = { "bubble" }) { s -> SongBubble(s, onPlay = { onPlaySwapped(s) }) }
            } else if (person != null) {
                item(key = "not-friend", contentType = "empty") {
                    Text(
                        stringResource(R.string.friend_not_friend),
                        style = MaterialTheme.typography.bodyLarge,
                        color = SoundColors.TextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 28.dp),
                    )
                }
            }
        },
    )
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
