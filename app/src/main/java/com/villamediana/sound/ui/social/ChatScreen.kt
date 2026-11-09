package com.villamediana.sound.ui.social

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
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

/** A message on its way (shown faded until the relay takes it): a text, or a voice message ([voiceMs]). */
internal data class PendingText(val key: Long, val text: String, val voiceMs: Long? = null, val wave: String? = null)

/**
 * The voice side of the conversation: the recorder, the player, where each message's audio is,
 * [canRecord] (asks for the microphone when it isn't allowed yet) and what to do with a recording.
 * Null in previews and tests (no microphone: the send button stays).
 */
internal class ChatVoice(
    val recorder: VoiceRecorder,
    val player: VoicePlayer,
    val fileOf: (SharedSong) -> java.io.File?,
    val canRecord: () -> Boolean,
    val onRecorded: (VoiceRecorder.Recorded?) -> Unit,
)

/**
 * The conversation with a friend: texts, songs, voice messages and reactions, kept on the phones
 * only. Swipe a message to answer it, hold it to react, answer or correct your text. At the foot:
 * music (play, queue or send any song without leaving), the text, send — or hold the microphone.
 */
@Composable
fun ChatScreen(id: String) {
    val graph = SoundApplication.graph
    val hub = graph.social
    val nav = navigator()
    val friends by hub.friends.collectAsStateWithLifecycle()
    val friend = friends.friends.firstOrNull { it.id == id }
    val messages by remember(id) { hub.songs.thread(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val typing by hub.typing.collectAsStateWithLifecycle()
    val snackbar = rememberDetailSnackbar()
    val context = LocalContext.current
    val res = context.resources
    val pending = remember(id) { mutableStateListOf<PendingText>() }
    var draft by rememberSaveable(id) { mutableStateOf("") }
    var replyId by rememberSaveable(id) { mutableStateOf<Long?>(null) }
    var editId by rememberSaveable(id) { mutableStateOf<Long?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()

    // Open here: its messages are seen, its notification goes, and new ones come without one.
    DisposableEffect(id) {
        hub.openChat = id
        onDispose { if (hub.openChat == id) hub.openChat = null }
    }
    LaunchedEffect(id, messages.size) {
        hub.songs.markSeen(id)
        com.villamediana.sound.social.SocialNotifier.clearChat(context, id)
    }
    TypingSignal(id, draft)

    // The voice: recorder and player live while the conversation is open.
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    val player = remember { VoicePlayer(context.applicationContext, uiScope) }
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            recorder.cancel()
        }
    }
    val micAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) snackbar.feedback.show(res.getString(R.string.chat_mic_denied))
    }
    val replyTo = replyId?.let { r -> messages.firstOrNull { it.deliveryId == r } }
    val editing = editId?.let { e -> messages.firstOrNull { it.id == e } }

    /** Sending goes on even if the conversation closes (the app's scope); a failure puts things back. */
    fun later(work: suspend () -> Result<Unit>, onFail: () -> Unit = {}) {
        graph.scope.launch(Dispatchers.Main) {
            work().onFailure {
                onFail()
                snackbar.feedback.show(res.getString(R.string.chat_failed))
            }
        }
    }

    val voice = remember(id) {
        ChatVoice(
            recorder = recorder,
            player = player,
            fileOf = { m -> hub.songs.voiceFile(m) },
            canRecord = {
                val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (!ok) micAsk.launch(Manifest.permission.RECORD_AUDIO)
                ok
            },
            onRecorded = { rec ->
                if (rec == null) {
                    snackbar.feedback.show(res.getString(R.string.chat_voice_hold))
                } else {
                    val reply = replyId
                    replyId = null
                    val p = PendingText(System.nanoTime(), "", rec.ms, rec.wave)
                    pending += p
                    later({ hub.songs.sendVoice(id, rec.file, rec.ms, rec.wave, reply).also { pending -= p } }, onFail = { rec.file.delete() })
                }
            },
        )
    }

    ChatPage(
        friend = friend,
        messages = messages,
        pending = pending,
        draft = draft,
        onDraft = { draft = it },
        snackbar = snackbar,
        typing = id in typing,
        replyTo = replyTo,
        editing = editing,
        voice = voice,
        onSend = {
            val text = draft.trim()
            if (text.isEmpty() || friend == null) return@ChatPage
            val fixing = editing
            draft = ""
            if (fixing != null) {
                editId = null
                later({ hub.songs.edit(fixing, text) }) {
                    if (draft.isBlank()) {
                        draft = text
                        editId = fixing.id
                    }
                }
                return@ChatPage
            }
            val reply = replyId
            replyId = null
            val p = PendingText(System.nanoTime(), text)
            pending += p
            later({ hub.songs.sendText(id, text, reply).also { pending -= p } }) {
                // Back in the box, so nothing typed is lost.
                if (draft.isBlank()) draft = text
                if (replyId == null) replyId = reply
            }
        },
        onPickSong = { picking = true },
        onPlay = { m -> playShared(m.toRef(), res.getString(R.string.playback_source_radio, m.title)) },
        onReact = { m, emoji ->
            uiScope.launch {
                hub.songs.react(m, emoji.takeIf { it != m.myReaction })
                    .onFailure { snackbar.feedback.show(res.getString(R.string.chat_failed)) }
            }
        },
        onReply = { m ->
            if (editId != null) {
                editId = null
                draft = ""
            }
            replyId = m.deliveryId
        },
        onEdit = { m ->
            replyId = null
            editId = m.id
            draft = m.text.orEmpty()
        },
        onCancelCompose = {
            if (editId != null) draft = ""
            editId = null
            replyId = null
        },
        onOpenFriend = { nav.openFriend(id) },
        sheets = {
            if (picking && friend != null) {
                ChatMusicSheet(
                    friend = friend,
                    onDismiss = { picking = false },
                    onSend = { song ->
                        val reply = replyId
                        replyId = null
                        later({ hub.songs.send(id, song, reply) })
                    },
                )
            }
        },
    )
}

/**
 * "Escrevendo…" for the friend while something is typed: said again every few seconds while it
 * changes, and "parou" when the box empties, the text goes, a while passes or the conversation closes.
 */
@Composable
private fun TypingSignal(friendId: String, draft: String) {
    val hub = SoundApplication.graph.social
    var saidAt by remember { mutableStateOf(0L) }
    LaunchedEffect(friendId, draft) {
        if (draft.isBlank()) {
            if (saidAt > 0) hub.sendTyping(friendId, false)
            saidAt = 0
            return@LaunchedEffect
        }
        val now = System.currentTimeMillis()
        if (now - saidAt > TYPING_AGAIN_MS) {
            hub.sendTyping(friendId, true)
            saidAt = now
        }
        kotlinx.coroutines.delay(TYPING_QUIET_MS)
        hub.sendTyping(friendId, false)
        saidAt = 0
    }
    DisposableEffect(friendId) {
        onDispose { if (saidAt > 0) hub.sendTyping(friendId, false) }
    }
}

private const val TYPING_AGAIN_MS = 3_000L
private const val TYPING_QUIET_MS = 5_000L

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
    typing: Boolean = false,
    replyTo: SharedSong? = null,
    editing: SharedSong? = null,
    voice: ChatVoice? = null,
    onReply: (SharedSong) -> Unit = {},
    onEdit: (SharedSong) -> Unit = {},
    onCancelCompose: () -> Unit = {},
    sheets: @Composable () -> Unit = {},
) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val ime = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    // Just above the mini player; above the keyboard while typing.
    val foot = maxOf(ime + 8.dp, LocalBottomInset.current - 8.dp)
    val list = rememberLazyListState()
    // The newest at the foot: a new message (yours or theirs) scrolls into view.
    LaunchedEffect(messages.size, pending.size) { if (messages.isNotEmpty() || pending.isNotEmpty()) list.animateScrollToItem(0) }
    // The friend's dots show at the foot only for someone already there (reading old messages stays put).
    LaunchedEffect(typing) { if (typing && list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0) }
    val rows = remember(messages) { chatRows(messages) }
    val byDelivery = remember(messages) { messages.associateBy { it.deliveryId } }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var lit by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(lit) {
        if (lit != null) {
            kotlinx.coroutines.delay(1_400)
            lit = null
        }
    }
    val scope = rememberCoroutineScope()
    val friendFirst = friend?.name?.trim()?.substringBefore(' ')?.ifBlank { friend.name }.orEmpty()
    val focus = remember { FocusRequester() }
    LaunchedEffect(replyTo?.id, editing?.id) {
        if (replyTo != null || editing != null) runCatching { focus.requestFocus() }
    }

    /** A quote tapped: the list goes to the message it quotes, which lights for a moment. */
    fun goTo(deliveryId: Long) {
        val target = byDelivery[deliveryId] ?: return
        val reversed = rows.asReversed()
        val at = reversed.indexOfFirst { it is ChatRow.Message && it.m.id == target.id }
        if (at < 0) return
        val index = (if (typing) 1 else 0) + pending.size + at
        scope.launch { list.animateScrollToItem(index, -120) }
        lit = target.id
    }

    CompositionLocalProvider(LocalSheetFeedback provides snackbar.feedback) {
        Box(Modifier.fillMaxSize().background(SoundColors.Ink)) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(statusBar))
                ChatHeader(friend, typing, onOpenFriend)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty() && pending.isEmpty() && !typing) {
                        ChatEmpty(friend, Modifier.align(Alignment.Center))
                    }
                    LazyColumn(
                        state = list,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 10.dp),
                    ) {
                        if (typing) item(key = "typing", contentType = "typing") { TypingBubble() }
                        items(pending.reversed(), key = { "p-${it.key}" }) { p ->
                            Bubble(mine = true, joined = false, modifier = Modifier.alpha(0.55f)) {
                                if (p.voiceMs != null) VoiceBody(null, p.voiceMs, p.wave, null, mine = true, time = null, edited = false)
                                else TextBody(p.text, null, false)
                            }
                        }
                        items(rows.asReversed(), key = { it.key }, contentType = { it.type }) { row ->
                            when (row) {
                                is ChatRow.Day -> DayLabel(row.label())
                                is ChatRow.Message -> MessageRow(
                                    m = row.m,
                                    joined = row.joined,
                                    quoted = row.m.replyTo?.let { byDelivery[it] },
                                    friendName = friendFirst,
                                    lit = lit == row.m.id,
                                    menu = menuFor == row.m.id,
                                    voice = voice,
                                    canAnswer = friend != null,
                                    onPlay = { onPlay(row.m) },
                                    onLongPress = { menuFor = row.m.id },
                                    onReact = { emoji ->
                                        menuFor = null
                                        onReact(row.m, emoji)
                                    },
                                    onReply = {
                                        menuFor = null
                                        onReply(row.m)
                                    },
                                    onEdit = {
                                        menuFor = null
                                        onEdit(row.m)
                                    },
                                    onDismissMenu = { menuFor = null },
                                    onQuote = { row.m.replyTo?.let(::goTo) },
                                )
                            }
                        }
                    }
                }
                if (friend != null) {
                    Composer(
                        draft = draft,
                        onDraft = onDraft,
                        onSend = onSend,
                        onPickSong = onPickSong,
                        replyTo = replyTo,
                        editing = editing,
                        friendName = friendFirst,
                        onCancel = onCancelCompose,
                        voice = voice,
                        focus = focus,
                        modifier = Modifier.padding(bottom = foot),
                    )
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

/** Back, the friend's photo and name (tap for their page); "escrevendo…" under the name while they write. */
@Composable
private fun ChatHeader(friend: Profile?, typing: Boolean, onOpenFriend: () -> Unit) {
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
                    androidx.compose.animation.Crossfade(typing, label = "typing") { on ->
                        if (on) Text(stringResource(R.string.chat_typing), style = MaterialTheme.typography.bodySmall, color = SoundColors.Accent, maxLines = 1)
                        else Text("@${friend.username}", style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint, maxLines = 1)
                    }
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
        override val type get() = m.kind
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
    quoted: SharedSong?,
    friendName: String,
    lit: Boolean,
    menu: Boolean,
    voice: ChatVoice?,
    canAnswer: Boolean,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDismissMenu: () -> Unit,
    onQuote: () -> Unit,
) {
    val mine = m.mine
    val haptics = LocalHapticFeedback.current
    val reactions = listOfNotNull(m.theirReaction, m.myReaction)
    val time = remember(m.at) { DateFormat.getTimeInstance(DateFormat.SHORT).format(java.util.Date(m.at)) }
    SwipeToReply(enabled = canAnswer, onReply = onReply) {
        Bubble(
            mine = mine,
            joined = joined && reactions.isEmpty(),
            reactions = reactions,
            lit = lit,
            modifier = Modifier.combinedClickable(
                onClick = { if (m.isSong) onPlay() },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
                onLongClickLabel = stringResource(R.string.chat_react),
            ),
            overlay = {
                if (menu) {
                    MessageMenu(
                        selected = m.myReaction,
                        mine = mine,
                        canAnswer = canAnswer,
                        canEdit = canAnswer && mine && m.isText,
                        onReact = onReact,
                        onReply = onReply,
                        onEdit = onEdit,
                        onDismiss = onDismissMenu,
                    )
                }
            },
        ) {
            Column {
                if (m.replyTo != null) {
                    ReplyQuote(quoted, mine = mine, friendName = friendName, onClick = onQuote, onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    })
                }
                when {
                    m.isText -> TextBody(m.text.orEmpty(), time, m.edited)
                    m.isVoice -> VoiceBody(m, m.voiceMs ?: 0, m.voiceWave, voice, mine = mine, time = time, edited = false)
                    else -> SongBody(m, time)
                }
            }
        }
    }
}

/**
 * A message dragged to the right answers it (like the phone's messengers): it follows the finger,
 * the answer arrow shows under it, a tick of the phone says it's enough, and letting go answers.
 */
@Composable
private fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, content: @Composable () -> Unit) {
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    var armed by remember { mutableStateOf(false) }
    val reply by androidx.compose.runtime.rememberUpdatedState(onReply)
    Box(
        Modifier.fillMaxWidth().pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (armed) reply()
                    armed = false
                    scope.launch { offset.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f)) }
                },
                onDragCancel = {
                    armed = false
                    scope.launch { offset.animateTo(0f) }
                },
            ) { change, amount ->
                val now = offset.value
                // Only to the right; past the mark it gets heavy.
                val next = (now + amount * if (now > threshold) 0.3f else 1f).coerceIn(0f, threshold * 1.5f)
                if (next != now) change.consume()
                scope.launch { offset.snapTo(next) }
                val on = next >= threshold
                if (on && !armed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                armed = on
            }
        },
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.Reply,
            null,
            tint = SoundColors.Accent,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 18.dp)
                .size(22.dp)
                .graphicsLayer {
                    val f = (offset.value / threshold).coerceIn(0f, 1f)
                    alpha = f
                    scaleX = 0.6f + 0.4f * f
                    scaleY = 0.6f + 0.4f * f
                },
        )
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

/**
 * A message's balloon: yours on the right in the accent's soft tone, theirs on the left; the corner
 * by the edge is tight. The reactions sit on its foot, overlapping it a little. [lit] for a moment
 * when a quote brought the list to it.
 */
@Composable
private fun Bubble(
    mine: Boolean,
    joined: Boolean,
    modifier: Modifier = Modifier,
    reactions: List<String> = emptyList(),
    lit: Boolean = false,
    overlay: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(
        topStart = 20.dp, topEnd = 20.dp,
        bottomStart = if (mine) 20.dp else 6.dp, bottomEnd = if (mine) 6.dp else 20.dp,
    )
    val base = if (mine) SoundColors.AccentSoft else SoundColors.Surface1
    val fill by animateColorAsState(if (lit) androidx.compose.ui.graphics.lerp(base, SoundColors.Accent, 0.35f) else base, label = "lit")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 2.dp, bottom = if (joined) 2.dp else if (reactions.isEmpty()) 8.dp else 20.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(shape)
                    .background(fill)
                    .then(modifier),
            ) { content() }
            if (reactions.isNotEmpty()) {
                Reactions(reactions, Modifier.align(if (mine) Alignment.BottomEnd else Alignment.BottomStart).offset(x = if (mine) (-10).dp else 10.dp, y = 16.dp))
            }
            overlay()
        }
    }
}

/** The time at a message's foot, with "editada" when its text was corrected. */
@Composable
private fun Stamp(time: String, edited: Boolean, modifier: Modifier = Modifier) {
    Text(
        if (edited) stringResource(R.string.chat_edited_at, time) else time,
        style = MaterialTheme.typography.labelSmall,
        color = SoundColors.TextFaint,
        modifier = modifier,
    )
}

@Composable
private fun TextBody(text: String, time: String?, edited: Boolean) {
    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 7.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = SoundColors.Text)
        if (time != null) Stamp(time, edited, Modifier.align(Alignment.End))
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

/**
 * A voice message: play/pause, its waves (what was heard lit; a tap on them jumps there), how long
 * it is (or where it is, while playing) and the time. [m] null: one on its way.
 */
@Composable
private fun VoiceBody(m: SharedSong?, ms: Long, wave: String?, voice: ChatVoice?, mine: Boolean, time: String?, edited: Boolean) {
    val file = m?.let { voice?.fileOf?.invoke(it) }
    val player = voice?.player
    val here = m != null && player?.current == m.id
    val playing = here && player?.playing == true
    val progress = if (here) player?.progress ?: 0f else 0f
    val bars = remember(wave) { com.villamediana.sound.social.VoiceWave.decode(wave) }
    val label = com.villamediana.sound.social.SocialNotifier.clock(if (here && (player?.positionMs ?: 0L) > 0) player?.positionMs ?: ms else ms)
    Column(Modifier.width(248.dp).padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (file != null) SoundColors.Accent else SoundColors.Surface3)
                    .clickable(enabled = file != null && m != null) { if (file != null && m != null) player?.toggle(m.id, file) }
                    .semantics { contentDescription = "voice-play" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = if (file != null) SoundColors.OnAccent else SoundColors.TextFaint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(10.dp))
            Waveform(
                bars = bars,
                progress = progress,
                played = SoundColors.Accent,
                rest = if (mine) SoundColors.Text.copy(alpha = 0.32f) else SoundColors.TextFaint,
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .pointerInput(file, m?.id) {
                        if (file == null || m == null) return@pointerInput
                        detectTapGestures { pos -> player?.toggle(m.id, file, pos.x / size.width) }
                    },
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = 52.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = if (here) SoundColors.Accent else SoundColors.TextMuted)
            if (m != null && file == null) {
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.chat_voice_missing), style = MaterialTheme.typography.labelSmall, color = SoundColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.weight(1f))
            if (time != null) Stamp(time, edited)
        }
    }
}

/** The waves of a voice message: rounded bars, lit up to [progress]. */
@Composable
internal fun Waveform(bars: List<Float>, progress: Float, played: Color, rest: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        if (bars.isEmpty()) return@Canvas
        val gap = 2.dp.toPx()
        val w = ((size.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(1f)
        val litUpTo = progress * size.width
        bars.forEachIndexed { i, h ->
            val x = i * (w + gap)
            val bh = (h * size.height).coerceAtLeast(w)
            drawRoundRect(
                color = if (x + w / 2 <= litUpTo) played else rest,
                topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - bh) / 2),
                size = androidx.compose.ui.geometry.Size(w, bh),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2),
            )
        }
    }
}

/** What a message answers, on top of it: a bar in the accent, who said it and a line of it; a tap goes there. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReplyQuote(target: SharedSong?, mine: Boolean, friendName: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val who = when {
        target == null -> null
        target.mine -> stringResource(R.string.chat_you)
        else -> friendName
    }
    Row(
        Modifier
            .padding(start = 6.dp, end = 6.dp, top = 6.dp)
            .widthIn(min = 120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (mine) Color.Black.copy(alpha = 0.10f) else SoundColors.Surface2)
            // A tap goes to the message; held, it is the message's menu like anywhere on it.
            .combinedClickable(onClick = { if (target != null) onClick() }, onLongClick = onLongClick)
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(SoundColors.Accent))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (who != null) Text(who, style = MaterialTheme.typography.labelLarge, color = SoundColors.Accent, maxLines = 1)
            Text(
                target?.let { snippet(it) } ?: stringResource(R.string.chat_quote_gone),
                style = MaterialTheme.typography.bodySmall,
                color = SoundColors.TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A message in one line: its text, its song, or that it is a voice message. */
@Composable
internal fun snippet(m: SharedSong): String = when {
    m.isText -> m.text.orEmpty().lines().firstOrNull().orEmpty()
    m.isVoice -> "🎤 " + stringResource(R.string.chat_voice) + " · " + com.villamediana.sound.social.SocialNotifier.clock(m.voiceMs ?: 0)
    else -> "🎵 " + listOf(m.title, m.artist).filter { it.isNotBlank() }.joinToString(" · ")
}

/** The friend writing: three dots rising in turn, in a balloon of theirs (drawn only while it shows). */
@Composable
private fun TypingBubble() {
    Bubble(mine = false, joined = false) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "dots")
        val phase by t.animateFloat(
            0f, 3f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1_050, easing = androidx.compose.animation.core.LinearEasing)),
            label = "phase",
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { i ->
                val d = ((phase - i + 3f) % 3f)
                val up = if (d < 1f) kotlin.math.sin(d * Math.PI).toFloat() else 0f
                Box(
                    Modifier
                        .size(8.dp)
                        .graphicsLayer { translationY = -up * 4.dp.toPx() }
                        .clip(CircleShape)
                        .background(SoundColors.TextMuted.copy(alpha = 0.45f + 0.55f * up)),
                )
            }
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

/** Above the message, or under it when there is no room above (the top of the list). */
private class MenuPlace(private val mine: Boolean, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = if (mine) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val above = anchorBounds.top - popupContentSize.height - gap
        val y = if (above >= gap * 6) above else (anchorBounds.bottom + gap).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), y)
    }
}

/**
 * Long press on a message: the six reactions in a pill (yours lit; the same again takes it back)
 * and, under them, answer it — and correct it, when it is a text of yours.
 */
@Composable
private fun MessageMenu(
    selected: String?,
    mine: Boolean,
    canAnswer: Boolean,
    canEdit: Boolean,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Popup(popupPositionProvider = remember(mine, gap) { MenuPlace(mine, gap) }, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        AnimatedVisibility(shown, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut()) {
            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                .clickable { onReact(e) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(e, fontSize = 24.sp, modifier = Modifier.scale(grow))
                        }
                    }
                }
                if (canAnswer) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(SoundColors.Surface2)
                            .border(1.dp, SoundColors.Surface3, RoundedCornerShape(18.dp)),
                    ) {
                        MenuItem(Icons.AutoMirrored.Rounded.Reply, stringResource(R.string.chat_reply), onReply)
                        if (canEdit) MenuItem(Icons.Rounded.Edit, stringResource(R.string.chat_edit), onEdit)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = SoundColors.Text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = SoundColors.Text)
    }
}

// ------------------------------------------------------------------ the foot

/**
 * Over the box while answering or correcting: what is being answered (or "Editando"), with a way
 * out.
 */
@Composable
private fun ComposeBar(replyTo: SharedSong?, editing: SharedSong?, friendName: String, onCancel: () -> Unit) {
    val target = editing ?: replyTo ?: return
    val title = when {
        editing != null -> stringResource(R.string.chat_editing)
        target.mine -> stringResource(R.string.chat_replying_you)
        else -> stringResource(R.string.chat_replying_to, friendName)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SoundColors.Surface1)
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(SoundColors.Accent))
        Icon(
            if (editing != null) Icons.Rounded.Edit else Icons.AutoMirrored.Rounded.Reply,
            null,
            tint = SoundColors.Accent,
            modifier = Modifier.padding(start = 12.dp).size(20.dp),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = SoundColors.Accent, maxLines = 1)
            Text(snippet(target), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onCancel).semantics { contentDescription = "compose-cancel" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, null, tint = SoundColors.TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Music (play, queue or send any song), the text box (grows to five lines), send. With nothing
 * typed, the microphone instead of send: hold to record, let go to send, slide left to give up.
 */
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onPickSong: () -> Unit,
    replyTo: SharedSong?,
    editing: SharedSong?,
    friendName: String,
    onCancel: () -> Unit,
    voice: ChatVoice?,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val canSend = draft.isNotBlank()
    val mic = voice != null && !canSend && editing == null
    var recording by remember { mutableStateOf(false) }
    var slide by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(replyTo != null || editing != null) {
            ComposeBar(replyTo, editing, friendName, onCancel)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (recording && voice != null) {
                RecordingStrip(voice.recorder, slide, Modifier.weight(1f))
            } else {
                RoundButton(Icons.Rounded.LibraryMusic, stringResource(R.string.chat_music), filled = false, enabled = true, onClick = onPickSong)
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
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "chat-input" },
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (mic && voice != null) {
                MicButton(
                    voice = voice,
                    recording = recording,
                    onStart = {
                        val ok = voice.recorder.start(scope) {
                            // Five minutes: it goes by itself.
                            recording = false
                            voice.onRecorded(voice.recorder.finish())
                        }
                        recording = ok
                        slide = 0f
                        ok
                    },
                    onSlide = { slide = it },
                    onCancel = {
                        recording = false
                        voice.recorder.cancel()
                    },
                    onRelease = {
                        if (recording) {
                            recording = false
                            voice.onRecorded(voice.recorder.finish())
                        }
                    },
                )
            } else {
                RoundButton(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.chat_send), filled = true, enabled = canSend, onClick = onSend)
            }
        }
    }
}

/** While recording, in place of the box: a pulsing red dot, the time, and "slide to cancel" drifting with the finger. */
@Composable
private fun RecordingStrip(recorder: VoiceRecorder, slide: Float, modifier: Modifier = Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "rec")
    val pulse by t.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(SoundColors.Surface1).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).alpha(pulse).clip(CircleShape).background(SoundColors.Danger))
        Spacer(Modifier.width(10.dp))
        Text(
            com.villamediana.sound.social.SocialNotifier.clock(recorder.elapsedMs),
            style = MaterialTheme.typography.titleSmall,
            color = SoundColors.Text,
        )
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.chat_voice_slide),
            style = MaterialTheme.typography.bodyMedium,
            color = SoundColors.TextMuted,
            maxLines = 1,
            modifier = Modifier.offset { IntOffset((slide * 0.6f).roundToInt(), 0) }.alpha((1f + slide / 400f).coerceIn(0.2f, 1f)),
        )
    }
}

/** Hold to record; let go to send; slide left past the mark to give up. Grows while it listens. */
@Composable
private fun MicButton(voice: ChatVoice, recording: Boolean, onStart: () -> Boolean, onSlide: (Float) -> Unit, onCancel: () -> Unit, onRelease: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val cancelAt = with(LocalDensity.current) { 120.dp.toPx() }
    val grow by animateFloatAsState(if (recording) 1.35f + voice.recorder.level * 0.25f else 1f, label = "mic")
    val start by androidx.compose.runtime.rememberUpdatedState(onStart)
    val slideTo by androidx.compose.runtime.rememberUpdatedState(onSlide)
    val cancel by androidx.compose.runtime.rememberUpdatedState(onCancel)
    val release by androidx.compose.runtime.rememberUpdatedState(onRelease)
    Box(
        Modifier
            .size(48.dp)
            .scale(grow)
            .clip(CircleShape)
            .background(if (recording) SoundColors.Danger else SoundColors.Accent)
            .semantics { contentDescription = "chat-mic" }
            .pointerInput(voice) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (!voice.canRecord()) return@awaitEachGesture
                    if (!start()) return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    var gaveUp = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dx = (change.position.x - down.position.x).coerceAtMost(0f)
                        slideTo(dx)
                        change.consume()
                        if (-dx > cancelAt) {
                            gaveUp = true
                            break
                        }
                    }
                    if (gaveUp) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        cancel()
                    } else {
                        release()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Mic, null, tint = SoundColors.OnAccent, modifier = Modifier.size(22.dp))
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
