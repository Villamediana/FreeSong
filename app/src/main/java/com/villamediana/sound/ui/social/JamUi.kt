package com.villamediana.sound.ui.social

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.social.Jam
import com.villamediana.sound.social.JamInvite
import com.villamediana.sound.social.Profile
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.sheets.SheetAction
import com.villamediana.sound.ui.sheets.SoundSheet
import com.villamediana.sound.ui.sheets.rememberSheetFeedback
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.launch

/** "Fulano te chamou pra uma jam": who, who else is in, Entrar / Agora não. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun JamInviteSheet(invite: JamInvite, onDone: () -> Unit) {
    val hub = SoundApplication.graph.social
    val scope = rememberCoroutineScope()
    val feedback = rememberSheetFeedback()
    val failed = stringResource(R.string.jam_failed)
    var busy by remember { mutableStateOf(false) }
    SoundSheet(onDismissRequest = onDone) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(6.dp))
            AvatarStack(listOf(invite.from) + invite.jam.members.filter { it.id != invite.from.id }, 56.dp)
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.jam_invite_title, invite.from.name),
                style = MaterialTheme.typography.headlineSmall,
                color = SoundColors.Text,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.jam_invite_body),
                style = MaterialTheme.typography.bodyMedium,
                color = SoundColors.TextMuted,
            )
            invite.jam.state.current?.let { song ->
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = SoundColors.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${song.title} · ${song.artist}", style = MaterialTheme.typography.bodyMedium, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(24.dp))
            PillButton(
                stringResource(R.string.jam_join),
                onClick = {
                    if (busy) return@PillButton
                    busy = true
                    scope.launch {
                        hub.jam.join(invite).onFailure { feedback.show(failed) }
                        busy = false
                        onDone()
                    }
                },
                filled = true,
                big = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.jam_not_now),
                    style = MaterialTheme.typography.labelLarge,
                    color = SoundColors.TextMuted,
                    modifier = Modifier.clip(CircleShape).clickable {
                        scope.launch { hub.jam.decline(invite) }
                        onDone()
                    }.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** The jam: who is in, who was called, call more friends, leave (or end it, for the one who started it). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun JamSheet(onDismiss: () -> Unit) {
    val hub = SoundApplication.graph.social
    val jam by hub.jam.jam.collectAsStateWithLifecycle()
    val me by hub.account.me.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var picking by rememberSaveable { mutableStateOf(false) }
    val current = jam ?: run {
        onDismiss()
        return
    }
    if (picking) {
        val inJam = (current.members + current.invited).map { it.id }.toSet()
        FriendPickerSheet(
            title = stringResource(R.string.jam_invite_more),
            exclude = inJam,
            confirm = stringResource(R.string.friend_invite_jam),
            onPick = { ids -> scope.launch { hub.jam.invite(ids) } },
            onDismiss = { picking = false },
        )
        return
    }
    val host = current.hostId == me?.id
    SoundSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.jam_title), style = MaterialTheme.typography.headlineSmall, color = SoundColors.Text)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.jam_count, current.members.size), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
            Spacer(Modifier.height(14.dp))
        }
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            current.members.sortedByDescending { it.id == current.hostId }.forEach { p ->
                PersonLine(p, note = if (p.id == current.hostId) stringResource(R.string.jam_host) else null)
            }
            if (current.invited.isNotEmpty()) {
                Text(
                    stringResource(R.string.jam_invited).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = SoundColors.TextFaint,
                    modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 4.dp),
                )
                current.invited.forEach { p -> PersonLine(p, faded = true) }
            }
        }
        Spacer(Modifier.height(8.dp))
        SheetAction(Icons.Rounded.PersonAdd, stringResource(R.string.jam_invite_more), onClick = { picking = true })
        SheetAction(
            Icons.AutoMirrored.Rounded.Logout,
            stringResource(if (host) R.string.jam_end else R.string.jam_leave),
            tint = SoundColors.Danger,
            labelColor = SoundColors.Danger,
            onClick = {
                scope.launch { hub.jam.leave() }
                onDismiss()
            },
        )
    }
}

@Composable
private fun PersonLine(p: Profile, note: String? = null, faded: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp)) { ProfileAvatar(p, 40.dp, Modifier.scale(if (faded) 0.9f else 1f)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, color = if (faded) SoundColors.TextMuted else SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@${p.username}", style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint, maxLines = 1)
        }
        if (note != null) Text(note, style = MaterialTheme.typography.labelMedium, color = SoundColors.Accent)
    }
}

/**
 * Friends to choose (several): for a new jam, to call into the jam, or one to send a song to
 * ([single]). Friends in [exclude] don't show.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FriendPickerSheet(
    title: String,
    confirm: String,
    onPick: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    exclude: Set<String> = emptySet(),
    single: Boolean = false,
) {
    val hub = SoundApplication.graph.social
    val page by hub.friends.collectAsStateWithLifecycle()
    val friends = page.friends.filter { it.id !in exclude }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    SoundSheet(onDismissRequest = onDismiss) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = SoundColors.Text,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 10.dp),
        )
        if (friends.isEmpty()) {
            Text(
                stringResource(R.string.send_no_friends),
                style = MaterialTheme.typography.bodyMedium,
                color = SoundColors.TextMuted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            )
            return@SoundSheet
        }
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            friends.forEach { f ->
                val on = f.id in chosen
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clickable(role = if (single) Role.Button else Role.Checkbox) {
                            if (single) {
                                onPick(listOf(f.id))
                                onDismiss()
                            } else {
                                chosen = if (on) chosen - f.id else chosen + f.id
                            }
                        }
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfileAvatar(f, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("@${f.username}", style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint, maxLines = 1)
                    }
                    if (!single) CheckDot(on)
                }
            }
        }
        if (!single) {
            Spacer(Modifier.height(12.dp))
            PillButton(
                confirm,
                onClick = {
                    if (chosen.isEmpty()) return@PillButton
                    onPick(chosen.toList())
                    onDismiss()
                },
                filled = true,
                big = true,
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
private fun CheckDot(on: Boolean) {
    val bg by animateColorAsState(if (on) SoundColors.Accent else Color.Transparent, label = "check")
    val tick by animateFloatAsState(if (on) 1f else 0f, Motion.bouncy(), label = "tick")
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(bg).border(2.dp, if (on) SoundColors.Accent else SoundColors.TextFaint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, null, tint = SoundColors.OnAccent, modifier = Modifier.size(16.dp).scale(tick))
    }
}

/** In the player's header while in a jam: the faces, tap for the jam. Its own sheet. */
@Composable
fun JamBadge(ink: Color) {
    val hub = SoundApplication.graph.social
    val jam by hub.jam.jam.collectAsStateWithLifecycle()
    val held by hub.jam.held.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val current = jam ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.clip(CircleShape).clickable { open = true }.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            AvatarStack(current.members, 22.dp, max = 3)
            Text(
                stringResource(R.string.jam_count, current.members.size),
                style = MaterialTheme.typography.titleSmall,
                color = ink,
                maxLines = 1,
            )
        }
        if (held) {
            Text(stringResource(R.string.jam_held), style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.72f), maxLines = 1)
        }
    }
    if (open) JamSheet(onDismiss = { open = false })
}

/** The jam's card on the Amigos page: faces, the song, tap for the jam. */
@Composable
fun JamCard(jam: Jam, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(SoundColors.AccentSoft)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.GraphicEq, null, tint = SoundColors.Accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.jam_count, jam.members.size), style = MaterialTheme.typography.titleSmall, color = SoundColors.Text)
            jam.state.current?.let {
                Text("${it.title} · ${it.artist}", style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        AvatarStack(jam.members, 30.dp, max = 3)
    }
}

/** An invite on the Amigos page (turned down in the sheet, still here). */
@Composable
fun InviteLine(invite: JamInvite, onJoin: () -> Unit, onDecline: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(invite.from, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.jam_invite_title, invite.from.name), style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        RoundAction(Icons.Rounded.Close, stringResource(R.string.jam_not_now), filled = false, onClick = onDecline)
        Spacer(Modifier.width(8.dp))
        RoundAction(Icons.Rounded.Check, stringResource(R.string.jam_join), filled = true, onClick = onJoin)
    }
}

/** A round icon button: accent filled (yes) or quiet (no). */
@Composable
fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (filled) SoundColors.Accent else SoundColors.Surface2)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (filled) SoundColors.OnAccent else SoundColors.TextMuted, modifier = Modifier.size(20.dp))
    }
}
