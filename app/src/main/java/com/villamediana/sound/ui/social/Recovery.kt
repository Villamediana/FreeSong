package com.villamediana.sound.ui.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.social.RelayException
import com.villamediana.sound.ui.sheets.ConfirmDialog
import com.villamediana.sound.ui.sheets.SoundSheet
import com.villamediana.sound.ui.sheets.SoundTextField
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.launch

/**
 * Ajustes › Código de recuperação (1.38): the code like a ticket, "Copiar", "Gerar outro" (asked once:
 * the old code stops opening anything) and "Usar o código de outro celular". It left the profile page:
 * it is about the phone, not what friends see.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RecoverySheet(onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val hub = SoundApplication.graph.social
    val code by hub.account.recoveryCode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val res = context.resources
    val scope = rememberCoroutineScope()
    var renewing by rememberSaveable { mutableStateOf(false) }
    var recovering by rememberSaveable { mutableStateOf(false) }

    SoundSheet(onDismissRequest = onDismiss) {
        RecoveryCard(
            code,
            onCopy = {
                code?.let { copySecret(context, it) }
                onMessage(res.getString(R.string.profile_copied))
            },
            onNew = { renewing = true },
            onUseCode = { recovering = true },
        )
        Spacer(Modifier.height(16.dp))
    }
    if (renewing) {
        ConfirmDialog(
            title = stringResource(R.string.recovery_new_title),
            body = stringResource(R.string.recovery_new_body),
            confirm = stringResource(R.string.profile_new_code),
            onConfirm = {
                renewing = false
                scope.launch { hub.newRecoveryCode().onFailure { onMessage(res.getString(R.string.profile_error_network)) } }
            },
            onDismiss = { renewing = false },
        )
    }
    if (recovering) {
        RecoverDialog(
            onDismiss = { recovering = false },
            onRecovered = {
                onMessage(res.getString(R.string.profile_recovered))
                onDismiss()
            },
        )
    }
}

/** The code on the clipboard, marked sensitive (Android 13+ doesn't show it in the copy preview). */
internal fun copySecret(context: Context, text: String) {
    val clip = ClipData.newPlainText("FreeSong", text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** "Usar o código de outro celular": this phone's profile becomes the code's. */
@Composable
internal fun RecoverDialog(onDismiss: () -> Unit, onRecovered: () -> Unit) {
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
                SoundTextField(code, { code = it.uppercase().take(40); error = null }, label = stringResource(R.string.profile_code_label), imeAction = ImeAction.Done)
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
                        .onFailure { e ->
                            error = res.getString(
                                when ((e as? RelayException)?.status) {
                                    404 -> R.string.profile_code_wrong
                                    429 -> R.string.recovery_too_many
                                    else -> R.string.profile_error_network
                                },
                            )
                        }
                    busy = false
                }
            }) { Text(stringResource(R.string.profile_use), color = SoundColors.Accent, style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = SoundColors.TextMuted, style = MaterialTheme.typography.labelLarge) }
        },
    )
}
