package com.villamediana.sound.ui.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.social.Profile
import com.villamediana.sound.social.RelayException
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.detail.HeaderIconButton
import com.villamediana.sound.ui.detail.rememberDetailSnackbar
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.sheets.ConfirmDialog
import com.villamediana.sound.ui.sheets.SheetAction
import com.villamediana.sound.ui.sheets.SoundSheet
import com.villamediana.sound.ui.sheets.SoundTextField
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.launch

/**
 * Your page, as your friends see it (photo, name, @, hours, five most heard), laid out like an
 * artist's. The name changes; the @ is your identifier on FreeSong and stays. Also: the photo,
 * the recovery code, using another phone's code, deleting the profile.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen() {
    val hub = SoundApplication.graph.social
    val nav = navigator()
    val me by hub.account.me.collectAsStateWithLifecycle()
    val code by hub.account.recoveryCode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = rememberDetailSnackbar()
    val context = LocalContext.current
    val res = context.resources

    var photoBusy by remember { mutableStateOf(false) }
    // The photo just picked, being framed ("Ajustar foto").
    var framing by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var recovering by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { hub.account.refresh() }

    // Picked → framed in the circle → applied.
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val photo = hub.account.loadPhoto(uri)
            if (photo == null) snackbar.feedback.show(res.getString(R.string.profile_error_photo)) else framing = photo
        }
    }
    val pickPhoto = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    val p = me
    ProfilePage(
        me = p,
        code = code,
        photoBusy = photoBusy,
        snackbar = snackbar,
        onPhoto = pickPhoto,
        onEdit = { editing = true },
        onMenu = { menu = true },
        onCopy = {
            code?.let { copy(context, it) }
            snackbar.feedback.show(res.getString(R.string.profile_copied))
        },
        onNewCode = { scope.launch { hub.newRecoveryCode() } },
        onUseCode = { recovering = true },
        sheets = {
            framing?.let { photo ->
                PhotoCropDialog(
                    photo = remember(photo) { photo.asImageBitmap() },
                    onCancel = { framing = null },
                    onApply = { cut ->
                        framing = null
                        scope.launch {
                            photoBusy = true
                            hub.account.setPhoto(photo, android.graphics.Rect(cut.left.toInt(), cut.top.toInt(), cut.right.toInt(), cut.bottom.toInt())).onFailure {
                                snackbar.feedback.show(res.getString(if (it is RelayException) R.string.profile_error_photo else R.string.profile_error_network))
                            }
                            photoBusy = false
                        }
                    },
                )
            }
            if (editing && p != null) EditNameSheet(p, onDismiss = { editing = false }, onSaved = { snackbar.feedback.show(res.getString(R.string.profile_saved)) })
            if (menu) {
                // Only what isn't on the page already: the photo changes from its camera, the code from its card.
                SoundSheet(onDismissRequest = { menu = false }) {
                    if (p?.avatar != null) {
                        SheetAction(Icons.Rounded.HideImage, stringResource(R.string.profile_photo_remove), onClick = {
                            menu = false
                            scope.launch { hub.account.removePhoto() }
                        })
                    }
                    SheetAction(Icons.Rounded.DeleteOutline, stringResource(R.string.profile_delete), tint = SoundColors.Danger, labelColor = SoundColors.Danger, onClick = {
                        menu = false
                        deleting = true
                    })
                }
            }
            if (recovering) RecoverDialog(onDismiss = { recovering = false }, onRecovered = { snackbar.feedback.show(res.getString(R.string.profile_recovered)) })
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
                                .onFailure { snackbar.feedback.show(res.getString(R.string.profile_error_network)) }
                        }
                    },
                    onDismiss = { deleting = false },
                )
            }
        },
    )
}

/** What the profile page draws (apart from the screen's state, so the tests can draw it). */
@Composable
internal fun ProfilePage(
    me: Profile?,
    code: String?,
    photoBusy: Boolean,
    snackbar: com.villamediana.sound.ui.detail.DetailSnackbar,
    onPhoto: () -> Unit,
    onEdit: () -> Unit,
    onMenu: () -> Unit,
    onCopy: () -> Unit,
    onNewCode: () -> Unit,
    onUseCode: () -> Unit,
    sheets: @Composable () -> Unit = {},
) {
    PersonPage(
        person = me,
        since = me?.let { stringResource(R.string.social_since, monthYear(it.since)) },
        top = me?.top.orEmpty(),
        topTitle = stringResource(R.string.profile_top),
        topHint = stringResource(R.string.profile_top_private),
        playTitle = stringResource(R.string.profile_top),
        snackbar = snackbar,
        avatar = { size -> CardAvatar(me, size, onCamera = onPhoto, busy = photoBusy) },
        buttons = {
            PersonButton(stringResource(R.string.profile_edit_name_action), onClick = onEdit, icon = Icons.Rounded.Edit, modifier = Modifier.weight(1f))
        },
        topActions = {
            HeaderIconButton(Icons.Rounded.MoreVert, stringResource(R.string.more_options), onClick = onMenu)
        },
        sheets = sheets,
        more = {
            // Your numbers (worked out on the phone, only you see them), then the recovery code.
            item(key = "stats", contentType = "stats") { ProfileStats() }
            item(key = "recovery", contentType = "card") { RecoveryCard(code, onCopy = onCopy, onNew = onNewCode, onUseCode = onUseCode) }
        },
    )
}

/** "Seu nome": the name friends see. The @ is said under it, as what doesn't change. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EditNameSheet(me: Profile, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val hub = SoundApplication.graph.social
    val scope = rememberCoroutineScope()
    val res = LocalContext.current.resources
    var name by rememberSaveable { mutableStateOf(me.name) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val changed = name.trim().isNotEmpty() && name.trim() != me.name

    SoundSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
            Text(stringResource(R.string.profile_edit_name), style = MaterialTheme.typography.headlineSmall, color = SoundColors.Text)
            Spacer(Modifier.height(16.dp))
            SoundTextField(name, { name = it; error = null }, label = stringResource(R.string.profile_name), imeAction = ImeAction.Done)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.profile_username_fixed, me.username), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint)
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = SoundColors.Danger)
            }
            Spacer(Modifier.height(20.dp))
            PillButton(
                stringResource(R.string.profile_save),
                onClick = {
                    if (!changed || saving) return@PillButton
                    saving = true
                    scope.launch {
                        hub.account.rename(name.trim(), null)
                            .onSuccess {
                                onSaved()
                                onDismiss()
                            }
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
    }
}

private fun profileError(e: Throwable): Int = when ((e as? RelayException)?.code) {
    "invalid_name" -> R.string.profile_error_name
    else -> R.string.profile_error_network
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("FreeSong", text))
}

/** A titled part of a sheet (label in small caps over its content). */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter).padding(top = Dimens.SectionGap)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = SoundColors.TextFaint)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun RecoverDialog(onDismiss: () -> Unit, onRecovered: () -> Unit) {
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
                            // The profile, its friends, and from its vault the hearts, playlists, listening.
                            hub.bootstrap()
                            onRecovered()
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
