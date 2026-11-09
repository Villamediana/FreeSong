package com.villamediana.sound.ui.social

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.db.SharedSong
import com.villamediana.sound.social.Profile
import com.villamediana.sound.social.SongRef
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.pressScale
import com.villamediana.sound.ui.detail.BackButton
import com.villamediana.sound.ui.detail.DetailSnackbar
import com.villamediana.sound.ui.detail.rememberDetailSnackbar
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.sheets.LocalSheetFeedback
import com.villamediana.sound.ui.sheets.SoundSnackbarHost
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The reactions a message takes (the relay accepts these and no others). */
val ChatReactions = listOf("❤️", "😂", "😮", "😢", "🔥", "👍")

/** A text on its way (shown faded until the relay takes it). */
internal data class PendingText(val key: Long, val text: String)

/**
 * The conversation with a friend: texts, songs and reactions, kept on the phones only. Songs play
 * on a tap; a long press on any message reacts to it. At the foot: a song from your likes (or the
 * one playing), the text, send.
 */
@Composable
fun ChatScreen(id: String) {
    val hub = SoundApplication.graph.social
    val nav = navigator()
    val friends by hub.friends.collectAsStateWithLifecycle()
    val friend = friends.friends.firstOrNull { it.id == id }
    val messages by remember(id) { hub.songs.thread(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = rememberDetailSnackbar()
    val res = LocalContext.current.resources
    val pending = remember(id) { mutableStateListOf<PendingText>() }
    var draft by rememberSaveable(id) { mutableStateOf("") }
    var picking by rememberSaveable { mutableStateOf(false) }

    // Open here: its messages are seen, and new ones come in without a notification.
    DisposableEffect(id) {
        hub.openChat = id
        onDispose { if (hub.openChat == id) hub.openChat = null }
    }
    LaunchedEffect(id, messages.size) { hub.songs.markSeen(id) }

    ChatPage(
        friend = friend,
        messages = messages,
        pending = pending,
        draft = draft,
        onDraft = { draft = it },
        snackbar = snackbar,
        onSend = {
            val text = draft.trim()
            if (text.isEmpty() || friend == null) return@ChatPage
            draft = ""
            val p = PendingText(System.nanoTime(), text)
            pending += p
            scope.launch {
                hub.songs.sendText(id, text).onFailure {
                    // Back in the box, so nothing typed is lost.
                    if (draft.isBlank()) draft = text
                    snackbar.feedback.show(res.getString(R.string.chat_failed))
                }
                pending -= p
            }
        },
        onPickSong = { picking = true },
        onPlay = { m -> playShared(m.toRef(), res.getString(R.string.playback_source_radio, m.title)) },
        onReact = { m, emoji ->
            scope.launch {
                hub.songs.react(m, emoji.takeIf { it != m.myReaction })
                    .onFailure { snackbar.feedback.show(res.getString(R.string.chat_failed)) }
            }
        },
        onOpenFriend = { nav.openFriend(id) },
        sheets = { if (picking && friend != null) SendSongSheet(friend, onDismiss = { picking = false }) },
    )
}

internal fun SharedSong.toRef() = SongRef(videoId, title, artist, album, durationMs, cover, url)

/** What the conversation draws (apart from the screen's state, so the tests can draw it). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatPage(
    friend: Profile?,
    messages: List<SharedSong>,
    pending: List<PendingText>,
    draft: String,
    onDraft: (String) -> Unit,
    snackbar: DetailSnackbar,
    onSend: () -> Unit,
    onPickSong: () -> Unit,
    onPlay: (SharedSong) -> Unit,
    onReact: (SharedSong, String) -> Unit,
    onOpenFriend: () -> Unit,
    sheets: @Composable () -> Unit = {},
) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val ime = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    // Just above the mini player; above the keyboard while typing.
    val foot = maxOf(ime + 8.dp, LocalBottomInset.current - 8.dp)
    val list = rememberLazyListState()
    // The newest at the foot: a new message (yours or theirs) scrolls into view.
    LaunchedEffect(messages.size, pending.size) { if (messages.isNotEmpty() || pending.isNotEmpty()) list.animateScrollToItem(0) }
    val rows = remember(messages) { chatRows(messages) }
    var reacting by remember { mutableStateOf<Long?>(null) }

    CompositionLocalProvider(LocalSheetFeedback provides snackbar.feedback) {
        Box(Modifier.fillMaxSize().background(SoundColors.Ink)) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(statusBar))
                ChatHeader(friend, onOpenFriend)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty() && pending.isEmpty()) {
                        ChatEmpty(friend, Modifier.align(Alignment.Center))
                    }
                    LazyColumn(
                        state = list,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 10.dp),
                    ) {
                        items(pending.reversed(), key = { "p-${it.key}" }) { p ->
                            Bubble(mine = true, joined = false, modifier = Modifier.alpha(0.55f)) { TextBody(p.text, null) }
                        }
                        items(rows.asReversed(), key = { it.key }, contentType = { it.type }) { row ->
                            when (row) {
                                is ChatRow.Day -> DayLabel(row.label())
                                is ChatRow.Message -> MessageRow(
                                    m = row.m,
                                    joined = row.joined,
                                    picking = reacting == row.m.id,
                                    onPlay = { onPlay(row.m) },
                                    onLongPress = { reacting = row.m.id },
                                    onPick = { emoji ->
                                        reacting = null
                                        onReact(row.m, emoji)
                                    },
                                    onDismissPicker = { reacting = null },
                                )
                            }
                        }
                    }
                }
                if (friend != null) {
                    Composer(draft, onDraft, onSend, onPickSong, Modifier.padding(bottom = foot))
                } else {
                    Text(
                        stringResource(R.string.chat_not_friends),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SoundColors.TextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = foot + 8.dp),
                    )
                }
            }
            SoundSnackbarHost(snackbar.host, Modifier.align(Alignment.BottomCenter).padding(bottom = foot + 72.dp))
        }
        sheets()
    }
}

/** Back, the friend's photo and name (tap for their page). */
@Composable
private fun ChatHeader(friend: Profile?, onOpenFriend: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 6.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        BackButton(backdropAlpha = { 0f })
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(enabled = friend != null, onClick = onOpenFriend).padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(friend, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(friend?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (friend != null) {
                    Text("@${friend.username}", style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ChatEmpty(friend: Profile?, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ProfileAvatar(friend, 76.dp)
        Spacer(Modifier.height(14.dp))
        Text(friend?.name.orEmpty(), style = MaterialTheme.typography.titleLarge, color = SoundColors.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.chat_empty), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted, textAlign = TextAlign.Center)
    }
}

// ------------------------------------------------------------------ rows

internal sealed interface ChatRow {
    val key: String
    val type: String

    data class Day(val date: LocalDate, val today: LocalDate) : ChatRow {
        override val key get() = "d-$date"
        override val type get() = "day"
    }

    /** [joined]: the next message is from the same side (they sit close, as one run). */
    data class Message(val m: SharedSong, val joined: Boolean) : ChatRow {
        override val key get() = "m-${m.id}"
        override val type get() = if (m.isText) "text" else "song"
    }
}

/** The conversation in order, a day label where the day changes. Pure. */
internal fun chatRows(messages: List<SharedSong>, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): List<ChatRow> {
    val out = ArrayList<ChatRow>(messages.size + 4)
    var day: LocalDate? = null
    messages.forEachIndexed { i, m ->
        val d = Instant.ofEpochMilli(m.at).atZone(zone).toLocalDate()
        if (d != day) {
            out += ChatRow.Day(d, today)
            day = d
        }
        val next = messages.getOrNull(i + 1)
        val joined = next != null && next.direction == m.direction &&
            Instant.ofEpochMilli(next.at).atZone(zone).toLocalDate() == d
        out += ChatRow.Message(m, joined)
    }
    return out
}

@Composable
private fun ChatRow.Day.label(): String = when (date) {
    today -> stringResource(R.string.chat_today)
    today.minusDays(1) -> stringResource(R.string.chat_yesterday)
    else -> {
        val locale = Locale.getDefault()
        val pattern = if (date.year == today.year) android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM") else android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMyyyy")
        date.format(DateTimeFormatter.ofPattern(pattern, locale))
    }
}

@Composable
private fun DayLabel(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = SoundColors.TextMuted,
            modifier = Modifier.clip(CircleShape).background(SoundColors.Surface1).padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

// ------------------------------------------------------------------ one message

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    m: SharedSong,
    joined: Boolean,
    picking: Boolean,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
    onPick: (String) -> Unit,
    onDismissPicker: () -> Unit,
) {
    val mine = m.direction == SharedSong.OUT
    val haptics = LocalHapticFeedback.current
    val reactions = listOfNotNull(m.theirReaction, m.myReaction)
    val time = remember(m.at) { DateFormat.getTimeInstance(DateFormat.SHORT).format(java.util.Date(m.at)) }
    Bubble(
        mine = mine,
        joined = joined && reactions.isEmpty(),
        reactions = reactions,
        modifier = Modifier.combinedClickable(
            onClick = { if (!m.isText) onPlay() },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongPress()
            },
            onLongClickLabel = stringResource(R.string.chat_react),
        ),
        overlay = {
            if (picking) ReactionPicker(selected = m.myReaction, mine = mine, onPick = onPick, onDismiss = onDismissPicker)
        },
    ) {
        if (m.isText) TextBody(m.text.orEmpty(), time) else SongBody(m, time)
    }
}

/**
 * A message's balloon: yours on the right in the accent's soft tone, theirs on the left; the corner
 * by the edge is tight. The reactions sit on its foot, overlapping it a little.
 */
@Composable
private fun Bubble(
    mine: Boolean,
    joined: Boolean,
    modifier: Modifier = Modifier,
    reactions: List<String> = emptyList(),
    overlay: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(
        topStart = 20.dp, topEnd = 20.dp,
        bottomStart = if (mine) 20.dp else 6.dp, bottomEnd = if (mine) 6.dp else 20.dp,
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 2.dp, bottom = if (joined) 2.dp else if (reactions.isEmpty()) 8.dp else 20.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(shape)
                    .background(if (mine) SoundColors.AccentSoft else SoundColors.Surface1)
                    .then(modifier),
            ) { content() }
            if (reactions.isNotEmpty()) {
                Reactions(reactions, Modifier.align(if (mine) Alignment.BottomEnd else Alignment.BottomStart).offset(x = if (mine) (-10).dp else 10.dp, y = 16.dp))
            }
            overlay()
        }
    }
}

@Composable
private fun TextBody(text: String, time: String?) {
    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 7.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = SoundColors.Text)
        if (time != null) {
            Text(time, style = MaterialTheme.typography.labelSmall, color = SoundColors.TextFaint, modifier = Modifier.align(Alignment.End))
        }
    }
}

/** A song in the conversation: its cover with a play mark, the title and artist; a tap plays it. */
@Composable
private fun SongBody(m: SharedSong, time: String) {
    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(60.dp)) {
            Cover(m.cover, Modifier.size(60.dp), seed = m.title, shape = RoundedCornerShape(12.dp))
            Box(
                Modifier.align(Alignment.Center).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.widthIn(max = 196.dp).padding(end = 6.dp)) {
            Text(m.title, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(m.artist, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(time, style = MaterialTheme.typography.labelSmall, color = SoundColors.TextFaint)
        }
    }
}

/** The reactions on a message: one each side; the same one twice shows "2". */
@Composable
private fun Reactions(emojis: List<String>, modifier: Modifier = Modifier) {
    val label = if (emojis.size == 2 && emojis[0] == emojis[1]) "${emojis[0]} 2" else emojis.distinct().joinToString(" ")
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        color = SoundColors.Text,
        modifier = modifier
            .clip(CircleShape)
            .border(2.dp, SoundColors.Ink, CircleShape)
            .background(SoundColors.Surface2)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private val PICKER_HEIGHT = 52.dp

/** Long press: the six reactions in a pill over the message (yours lit; the same again takes it back). */
@Composable
private fun ReactionPicker(selected: String?, mine: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val lift = with(androidx.compose.ui.platform.LocalDensity.current) { (PICKER_HEIGHT + 8.dp).roundToPx() }
    Popup(
        alignment = if (mine) Alignment.TopEnd else Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(0, -lift),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        AnimatedVisibility(shown, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut()) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(SoundColors.Surface2)
                    .border(1.dp, SoundColors.Surface3, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ChatReactions.forEach { e ->
                    val on = e == selected
                    val grow by animateFloatAsState(if (on) 1.12f else 1f, label = "react")
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (on) SoundColors.AccentSoft else Color.Transparent)
                            .clickable { onPick(e) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(e, fontSize = 24.sp, modifier = Modifier.scale(grow))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ the foot

/** A song (your likes or what's playing), the text box (grows to five lines), send. */
@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, onSend: () -> Unit, onPickSong: () -> Unit, modifier: Modifier = Modifier) {
    val canSend = draft.isNotBlank()
    Row(
        modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        RoundButton(Icons.Rounded.LibraryMusic, stringResource(R.string.friend_send), filled = false, enabled = true, onClick = onPickSong)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(SoundColors.Surface1).padding(horizontal = 18.dp, vertical = 13.dp),
        ) {
            if (draft.isEmpty()) {
                Text(stringResource(R.string.chat_hint), style = MaterialTheme.typography.bodyLarge, color = SoundColors.TextFaint)
            }
            BasicTextField(
                value = draft,
                onValueChange = { if (it.length <= MAX_TEXT) onDraft(it) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = SoundColors.Text),
                cursorBrush = SolidColor(SoundColors.Accent),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "chat-input" },
            )
        }
        Spacer(Modifier.width(8.dp))
        RoundButton(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.chat_send), filled = true, enabled = canSend, onClick = onSend)
    }
}

/** The relay keeps 2000 characters of a text. */
private const val MAX_TEXT = 2000

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit, size: Dp = 48.dp) {
    val interaction = remember { MutableInteractionSource() }
    val bg = when {
        filled && enabled -> SoundColors.Accent
        else -> SoundColors.Surface1
    }
    val fg = when {
        filled && enabled -> SoundColors.OnAccent
        filled -> SoundColors.TextFaint
        else -> SoundColors.Accent
    }
    Box(
        Modifier
            .size(size)
            .pressScale(interaction, 0.92f)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
    }
}
