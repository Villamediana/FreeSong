package com.villamediana.sound.ui.social

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.db.SharedSong
import com.villamediana.sound.social.AccountStatus
import com.villamediana.sound.social.FriendRequest
import com.villamediana.sound.social.Profile
import com.villamediana.sound.ui.components.EmptyState
import com.villamediana.sound.ui.components.FilterPill
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A friend's line: the last song you swapped and how many of theirs you haven't opened. */
data class FriendLine(val friend: Profile, val last: SharedSong?, val unread: Int)

data class PeopleState(
    val query: String = "",
    val users: List<Profile> = emptyList(),
    val next: Int? = 0,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

class SocialViewModel(app: Application) : AndroidViewModel(app) {
    private val hub = SoundApplication.graph.social

    /** Songs swapped, per friend (newest first): the friends list shows the last one and the unread. */
    val swapped: StateFlow<Map<String, Pair<SharedSong, Int>>> = hub.songs.all
        .map { all ->
            all.groupBy { it.friendId }.mapValues { (_, list) -> list.first() to list.count { it.direction == SharedSong.IN && !it.seen } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _people = MutableStateFlow(PeopleState())
    val people: StateFlow<PeopleState> = _people
    private var search: Job? = null

    init {
        viewModelScope.launch {
            hub.bootstrap()
            loadMore(reset = true)
        }
    }

    fun query(q: String) {
        _people.value = _people.value.copy(query = q)
        search?.cancel()
        search = viewModelScope.launch {
            delay(300)
            loadMore(reset = true)
        }
    }

    fun loadMore(reset: Boolean = false) {
        val s = _people.value
        val offset = if (reset) 0 else s.next ?: return
        if (s.loading && !reset) return
        _people.value = s.copy(loading = true, failed = false)
        viewModelScope.launch {
            val page = runCatching { withContext(kotlinx.coroutines.Dispatchers.IO) { hub.api.users(s.query.trim(), offset) } }.getOrNull()
            val now = _people.value
            _people.value = if (page == null) {
                now.copy(loading = false, failed = true)
            } else {
                now.copy(users = if (reset) page.users else now.users + page.users, next = page.next, loading = false)
            }
        }
    }

    /** A friend request (or accepting theirs), and the row changes at once. */
    fun add(p: Profile) = viewModelScope.launch {
        val status = if (p.relation == "incoming" && p.requestId != null) hub.accept(p.requestId).getOrNull() else hub.requestFriend(p.id).getOrNull()
        status ?: return@launch
        val relation = if (status == "friends") "friends" else "outgoing"
        _people.value = _people.value.copy(users = _people.value.users.map { if (it.id == p.id) it.copy(relation = relation) else it })
    }

    fun accept(r: FriendRequest) = viewModelScope.launch { hub.accept(r.requestId) }
    fun decline(r: FriendRequest) = viewModelScope.launch { hub.decline(r.requestId) }
    fun createProfile() = viewModelScope.launch {
        runCatching { hub.account.ensure(force = true) }
        hub.bootstrap()
        loadMore(reset = true)
    }
    fun retry() = viewModelScope.launch {
        hub.bootstrap()
        loadMore(reset = true)
    }
}

private enum class SocialTab { FRIENDS, PEOPLE }

/** Amigos: your profile, the jam, friends (with the last song you swapped) and everyone on FreeSong. */
@Composable
fun SocialScreen() {
    val vm: SocialViewModel = viewModel()
    val hub = SoundApplication.graph.social
    val nav = navigator()
    val status by hub.account.status.collectAsStateWithLifecycle()
    val me by hub.account.me.collectAsStateWithLifecycle()
    val friends by hub.friends.collectAsStateWithLifecycle()
    val swapped by vm.swapped.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val jam by hub.jam.jam.collectAsStateWithLifecycle()
    val invites by hub.jam.invites.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val feedback = com.villamediana.sound.ui.sheets.rememberSheetFeedback()
    val jamFailed = stringResource(R.string.jam_failed)
    var tab by rememberSaveable { mutableStateOf(SocialTab.FRIENDS) }
    var starting by rememberSaveable { mutableStateOf(false) }
    var jamOpen by rememberSaveable { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        Modifier.fillMaxSize().background(SoundColors.Ink),
        contentPadding = PaddingValues(top = top, bottom = LocalBottomInset.current),
    ) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.back() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SoundColors.Text)
                }
                Spacer(Modifier.weight(1f))
                if (status == AccountStatus.READY && friends.friends.isNotEmpty() && jam == null) {
                    PillButton(stringResource(R.string.social_start_jam), onClick = { starting = true }, icon = Icons.Rounded.GraphicEq)
                }
            }
            Text(
                stringResource(R.string.social_title),
                style = MaterialTheme.typography.displaySmall,
                color = SoundColors.Text,
                modifier = Modifier.padding(horizontal = Dimens.Gutter).padding(top = 4.dp, bottom = 18.dp),
            )
        }

        item(key = "me") {
            MeHeader(status, me, onEdit = nav::openProfile, onCreate = vm::createProfile, onRetry = vm::retry)
        }
        if (status != AccountStatus.READY) return@LazyColumn

        jam?.let { j ->
            item(key = "jam") {
                JamCard(j, onOpen = { jamOpen = true }, modifier = Modifier.padding(horizontal = Dimens.Gutter).padding(top = 18.dp))
            }
        }
        if (invites.isNotEmpty()) {
            item(key = "invites") { Label(stringResource(R.string.jam_invites_title)) }
            items(invites, key = { "inv-" + it.jam.id }) { inv ->
                InviteLine(
                    inv,
                    onJoin = { scope.launch { hub.jam.join(inv).onFailure { feedback.show(jamFailed) } } },
                    onDecline = { scope.launch { hub.jam.decline(inv) } },
                )
            }
        }

        item(key = "tabs") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter).padding(top = 22.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val requests = friends.incoming.size
                FilterPill(
                    if (requests > 0) "${stringResource(R.string.social_tab_friends)} · $requests" else stringResource(R.string.social_tab_friends),
                    selected = tab == SocialTab.FRIENDS,
                    onClick = { tab = SocialTab.FRIENDS },
                )
                FilterPill(stringResource(R.string.social_tab_people), selected = tab == SocialTab.PEOPLE, onClick = { tab = SocialTab.PEOPLE })
            }
        }

        when (tab) {
            SocialTab.FRIENDS -> {
                if (friends.incoming.isNotEmpty()) {
                    item(key = "req-label") { Label(stringResource(R.string.social_requests)) }
                    items(friends.incoming, key = { "req-" + it.requestId }) { r ->
                        RequestRow(r, onAccept = { vm.accept(r) }, onDecline = { vm.decline(r) }, onOpen = { nav.openFriend(r.user.id) })
                    }
                    item(key = "req-gap") { Spacer(Modifier.height(10.dp)) }
                }
                val lines = friends.friends
                    .map { f -> swapped[f.id].let { FriendLine(f, it?.first, it?.second ?: 0) } }
                    .sortedWith(compareByDescending<FriendLine> { it.last?.at ?: 0L }.thenBy { it.friend.name.lowercase() })
                if (lines.isEmpty() && friends.incoming.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            icon = Icons.Rounded.Groups,
                            title = stringResource(R.string.social_empty_friends_title),
                            body = stringResource(R.string.social_empty_friends_body),
                            action = { PillButton(stringResource(R.string.social_find_people), onClick = { tab = SocialTab.PEOPLE }, filled = true) },
                        )
                    }
                }
                items(lines, key = { "f-" + it.friend.id }) { line -> FriendRow(line, onOpen = { nav.openFriend(line.friend.id) }) }
            }
            SocialTab.PEOPLE -> {
                item(key = "search") { SearchField(people.query, vm::query) }
                items(people.users, key = { "p-" + it.id }) { p ->
                    PersonRow(p, onAdd = { vm.add(p) }, onOpen = { nav.openFriend(p.id) })
                }
                item(key = "people-end") {
                    LaunchedEffect(people.users.size, people.next) { if (people.next != null && people.users.isNotEmpty()) vm.loadMore() }
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        when {
                            people.loading -> CircularProgressIndicator(Modifier.size(22.dp), color = SoundColors.Accent, strokeWidth = 2.dp)
                            people.failed -> Text(stringResource(R.string.social_offline), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
                            people.users.isEmpty() -> Text(stringResource(R.string.social_empty_people), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
                        }
                    }
                }
            }
        }
    }

    if (starting) {
        FriendPickerSheet(
            title = stringResource(R.string.jam_start_title),
            confirm = stringResource(R.string.jam_start),
            onPick = { ids -> scope.launch { hub.jam.start(ids).onFailure { feedback.show(jamFailed) } } },
            onDismiss = { starting = false },
        )
    }
    if (jamOpen) JamSheet(onDismiss = { jamOpen = false })
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = SoundColors.TextFaint,
        modifier = Modifier.padding(start = Dimens.Gutter, end = Dimens.Gutter, top = 22.dp, bottom = 6.dp),
    )
}

/** Your card at the top (the same as your page's, small; tap for your page), or the state of the profile while there is none. */
@Composable
internal fun MeHeader(status: AccountStatus, me: Profile?, onEdit: () -> Unit, onCreate: () -> Unit, onRetry: () -> Unit) {
    AnimatedContent(
        targetState = status to (me != null),
        transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
        label = "me",
    ) { (s, has) ->
        when {
            s == AccountStatus.READY && has && me != null -> MiniPersonCard(me, onClick = onEdit)
            s == AccountStatus.OPTED_OUT -> StatusLine(stringResource(R.string.social_opted_out), stringResource(R.string.social_create_profile), onCreate, Icons.Rounded.Groups)
            s == AccountStatus.LOADING || (s == AccountStatus.READY && !has) -> StatusLine(stringResource(R.string.social_creating), null, null, null, busy = true)
            else -> StatusLine(stringResource(R.string.social_offline), stringResource(R.string.social_retry), onRetry, Icons.Rounded.WifiOff)
        }
    }
}

@Composable
private fun StatusLine(text: String, action: String?, onAction: (() -> Unit)?, icon: androidx.compose.ui.graphics.vector.ImageVector?, busy: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(SoundColors.Surface2), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = SoundColors.Accent, strokeWidth = 2.dp)
            else if (icon != null) Icon(icon, null, tint = SoundColors.TextMuted, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = SoundColors.TextMuted, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) PillButton(action, onClick = onAction, filled = true)
    }
}

@Composable
internal fun RequestRow(r: FriendRequest, onAccept: () -> Unit, onDecline: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).height(72.dp).padding(horizontal = Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(r.user, 50.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.user.name, style = MaterialTheme.typography.titleMedium, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@${r.user.username}", style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint, maxLines = 1)
        }
        RoundAction(Icons.Rounded.Close, stringResource(R.string.social_decline), filled = false, onClick = onDecline)
        Spacer(Modifier.width(8.dp))
        RoundAction(Icons.Rounded.Check, stringResource(R.string.social_accept), filled = true, onClick = onAccept)
    }
}

@Composable
internal fun FriendRow(line: FriendLine, onOpen: () -> Unit) {
    val f = line.friend
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).height(76.dp).padding(horizontal = Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ProfileAvatar(f, 54.dp)
            if (line.unread > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(14.dp).clip(CircleShape).background(SoundColors.Ink).padding(2.dp).clip(CircleShape).background(SoundColors.Accent),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        val last = line.last
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.titleMedium, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                last == null -> "@${f.username}"
                last.direction == SharedSong.OUT -> stringResource(R.string.social_you_sent, last.title)
                else -> "${last.title} · ${last.artist}"
            }
            Text(
                sub,
                style = MaterialTheme.typography.bodyMedium,
                color = if (line.unread > 0) SoundColors.Text else SoundColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The last song swapped, as a little cover: the list reads like music, not like contacts.
        if (last != null) {
            Spacer(Modifier.width(12.dp))
            com.villamediana.sound.ui.components.Cover(last.cover, Modifier.size(48.dp), seed = last.title, shape = RoundedCornerShape(12.dp))
        }
    }
}

@Composable
internal fun PersonRow(p: Profile, onAdd: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).height(72.dp).padding(horizontal = Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(p, 50.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "@${p.username} · ${monthYear(p.since)}",
                style = MaterialTheme.typography.bodySmall,
                color = SoundColors.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        when (p.relation) {
            "friends" -> Text(stringResource(R.string.social_is_friend), style = MaterialTheme.typography.labelLarge, color = SoundColors.TextMuted)
            "outgoing" -> Text(stringResource(R.string.social_requested), style = MaterialTheme.typography.labelLarge, color = SoundColors.TextFaint)
            "incoming" -> PillButton(stringResource(R.string.social_accept), onClick = onAdd, filled = true)
            else -> PillButton(stringResource(R.string.social_add), onClick = onAdd)
        }
    }
}

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Gutter, vertical = 8.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(SoundColors.Surface1)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = SoundColors.TextMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(stringResource(R.string.social_search_people), style = MaterialTheme.typography.bodyLarge, color = SoundColors.TextFaint)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = SoundColors.Text),
                cursorBrush = SolidColor(SoundColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(Icons.Rounded.Close, null, tint = SoundColors.TextMuted, modifier = Modifier.size(20.dp).clip(CircleShape).clickable { onChange("") })
        }
    }
}
