package com.villamediana.sound.ui.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.social.Profile
import com.villamediana.sound.social.RelayException
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.sheets.ConfirmDialog
import com.villamediana.sound.ui.sheets.SoundTextField
import com.villamediana.sound.ui.sheets.rememberSheetFeedback
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.launch

/** Your profile: photo, name, @; what friends see; the recovery code; deleting it. */
@Composable
fun ProfileScreen() {
    val hub = SoundApplication.graph.social
    val nav = navigator()
    val me by hub.account.me.collectAsStateWithLifecycle()
    val code by hub.account.recoveryCode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val feedback = rememberSheetFeedback()
    val context = LocalContext.current
    val res = context.resources
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    var name by rememberSaveable(me?.name) { mutableStateOf(me?.name.orEmpty()) }
    var username by rememberSaveable(me?.username) { mutableStateOf(me?.username.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    var recovering by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { hub.account.refresh() }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            photoBusy = true
            hub.account.setPhoto(uri).onFailure { feedback.show(res.getString(if (it is RelayException) R.string.profile_error_photo else R.string.profile_error_network)) }
            photoBusy = false
        }
    }

    val p = me
    val changed = p != null && (name.trim() != p.name || username.trim().removePrefix("@").lowercase() != p.username)

    Column(
        Modifier
            .fillMaxSize()
            .background(SoundColors.Ink)
            .verticalScroll(rememberScrollState())
            .padding(top = top, bottom = LocalBottomInset.current),
    ) {
        IconButton(onClick = { nav.back() }, modifier = Modifier.padding(start = 6.dp, top = 4.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SoundColors.Text)
        }
        // The photo, big, with its camera.
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(124.dp).clip(CircleShape).clickable(enabled = !photoBusy) {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            ) {
                ProfileAvatar(p, 124.dp)
                if (photoBusy) {
                    Box(Modifier.size(124.dp).background(SoundColors.Ink.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = SoundColors.Accent, strokeWidth = 2.dp)
                    }
                }
            }
            Box(
                Modifier
                    .padding(start = 92.dp, top = 88.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(SoundColors.Ink)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(SoundColors.Accent)
                    .clickable { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PhotoCamera, stringResource(R.string.profile_photo_change), tint = SoundColors.OnAccent, modifier = Modifier.size(18.dp))
            }
        }
        if (p?.avatar != null) {
            Text(
                stringResource(R.string.profile_photo_remove),
                style = MaterialTheme.typography.labelLarge,
                color = SoundColors.TextMuted,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 6.dp)
                    .clip(CircleShape)
                    .clickable { scope.launch { hub.account.removePhoto() } }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        Column(Modifier.padding(horizontal = Dimens.Gutter).padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SoundTextField(name, { name = it; error = null }, label = stringResource(R.string.profile_name))
            SoundTextField(
                username,
                { username = it.trim().removePrefix("@").lowercase(); error = null },
                label = stringResource(R.string.profile_username),
                placeholder = "@",
                imeAction = ImeAction.Done,
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SoundColors.Danger) }
            PillButton(
                stringResource(R.string.profile_save),
                onClick = {
                    if (!changed || saving || p == null) return@PillButton
                    saving = true
                    scope.launch {
                        val newName = name.trim().takeIf { it != p.name }
                        val newUser = username.trim().removePrefix("@").lowercase().takeIf { it != p.username }
                        hub.account.rename(newName, newUser)
                            .onSuccess { feedback.show(res.getString(R.string.profile_saved)) }
                            .onFailure { e -> error = res.getString(profileError(e)) }
                        saving = false
                    }
                },
                filled = true,
                big = true,
                enabled = changed && !saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // What friends see.
        if (p != null) StatsBlock(p, hint = stringResource(R.string.profile_stats_hint))

        // Recovery code.
        Section(stringResource(R.string.profile_recovery_title)) {
            Text(stringResource(R.string.profile_recovery_body), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    code ?: "····-····-····-····",
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    color = SoundColors.Text,
                    modifier = Modifier.weight(1f),
                )
                if (code != null) {
                    TextButton(onClick = {
                        copy(context, code!!)
                        feedback.show(res.getString(R.string.profile_copied))
                    }) { Text(stringResource(R.string.profile_copy), color = SoundColors.Accent, style = MaterialTheme.typography.labelLarge) }
                }
            }
            Row {
                TextButton(onClick = { scope.launch { hub.account.newRecoveryCode() } }) {
                    Text(stringResource(R.string.profile_new_code), color = SoundColors.TextMuted, style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = { recovering = true }) {
                    Text(stringResource(R.string.profile_use_code), color = SoundColors.TextMuted, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Text(
            stringResource(R.string.profile_delete),
            style = MaterialTheme.typography.titleSmall,
            color = SoundColors.Danger,
            modifier = Modifier
                .padding(horizontal = Dimens.Gutter - 8.dp, vertical = 18.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { deleting = true }
                .padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }

    if (recovering) RecoverDialog(onDismiss = { recovering = false })
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.profile_delete_title),
            body = stringResource(R.string.profile_delete_body),
            confirm = stringResource(R.string.profile_delete_confirm),
            onConfirm = {
                deleting = false
                scope.launch {
                    hub.deleteProfile()
                        .onSuccess { nav.back() }
                        .onFailure { feedback.show(res.getString(R.string.profile_error_network)) }
                }
            },
            onDismiss = { deleting = false },
        )
    }
}

private fun profileError(e: Throwable): Int = when ((e as? RelayException)?.code) {
    "username_taken" -> R.string.profile_error_taken
    "username_invalid" -> R.string.profile_error_invalid
    "username_reserved" -> R.string.profile_error_reserved
    "invalid_name" -> R.string.profile_error_name
    else -> R.string.profile_error_network
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("FreeSong", text))
}

/** A titled part of a page (label in small caps over its content). */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter).padding(top = Dimens.SectionGap)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = SoundColors.TextFaint)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** Hours listened and the five most heard (tap one to play it). */
@Composable
internal fun StatsBlock(p: Profile, hint: String? = null) {
    val listened = p.listenedMs ?: return
    val top = p.top.orEmpty()
    val res = LocalContext.current.resources
    Section(stringResource(R.string.profile_top)) {
        Text(stringResource(R.string.profile_hours, hours(listened)), style = MaterialTheme.typography.titleMedium, color = SoundColors.Text)
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint)
        }
        if (top.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Always five places, so the covers keep one size whatever the count.
                (0 until 5).forEach { i ->
                    val song = top.getOrNull(i)
                    if (song == null) {
                        Spacer(Modifier.weight(1f))
                        return@forEach
                    }
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable {
                            playShared(song, res.getString(R.string.playback_source_radio, song.title))
                        },
                    ) {
                        com.villamediana.sound.ui.components.Cover(
                            song.cover,
                            Modifier.fillMaxWidth().aspectRatio(1f),
                            seed = song.title,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(song.title, style = MaterialTheme.typography.labelSmall, color = SoundColors.TextMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoverDialog(onDismiss: () -> Unit) {
    val hub = SoundApplication.graph.social
    val scope = rememberCoroutineScope()
    val res = LocalContext.current.resources
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SoundColors.Surface2,
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.profile_use_code), style = MaterialTheme.typography.headlineSmall, color = SoundColors.Text) },
        text = {
            Column {
                Text(stringResource(R.string.profile_use_code_body), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
                Spacer(Modifier.height(14.dp))
                SoundTextField(code, { code = it.uppercase(); error = null }, label = stringResource(R.string.profile_code_label), imeAction = ImeAction.Done)
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = SoundColors.Danger)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && code.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    hub.account.recover(code)
                        .onSuccess {
                            hub.bootstrap()
                            onDismiss()
                        }
                        .onFailure { e -> error = res.getString(if ((e as? RelayException)?.status == 404) R.string.profile_code_wrong else R.string.profile_error_network) }
                    busy = false
                }
            }) { Text(stringResource(R.string.profile_use), color = SoundColors.Accent, style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = SoundColors.TextMuted, style = MaterialTheme.typography.labelLarge) }
        },
    )
}
