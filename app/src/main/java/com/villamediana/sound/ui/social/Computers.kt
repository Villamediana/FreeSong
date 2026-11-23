package com.villamediana.sound.ui.social

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.social.Computer
import com.villamediana.sound.social.ComputerLink
import com.villamediana.sound.social.RelayException
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.components.SectionHeader
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * "Computador" on the profile: FreeSong on the web (freesong.simplyonegoal.com/web) with this
 * profile's hearts, playlists and listening. "Ligar um computador" reads the computer's QR code
 * (Google's code scanner: no camera permission here); the linked ones are listed, each can be unlinked.
 */
@Composable
internal fun ComputersCard(computers: List<Computer>, onLink: () -> Unit, onUnlink: (Computer) -> Unit) {
    Column(Modifier.padding(top = Dimens.SectionGap)) {
        SectionHeader(stringResource(R.string.computers_title))
        Column(
            Modifier
                .padding(horizontal = Dimens.Gutter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(SoundColors.Surface1)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.Computer, null, tint = SoundColors.Accent, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.computers_body),
                style = MaterialTheme.typography.bodyMedium,
                color = SoundColors.TextMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            PillButton(stringResource(R.string.computers_link), onClick = onLink, icon = Icons.Rounded.QrCodeScanner, filled = true)
            if (computers.isNotEmpty()) Spacer(Modifier.height(10.dp))
            for (c in computers) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val detail = when {
                            c.shared -> stringResource(R.string.computers_shared)
                            c.via == "code" -> stringResource(R.string.computers_by_code)
                            else -> stringResource(R.string.computers_since, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(c.linkedAt)))
                        }
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onUnlink(c) }) {
                        Text(stringResource(R.string.computers_unlink), color = SoundColors.Danger, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** Opens Google's code scanner; a FreeSong computer code goes to [onCode], anything else says so. */
internal fun scanComputerCode(context: Context, onCode: (ComputerLink.Code) -> Unit) {
    val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
        .build()
    runCatching {
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                val code = ComputerLink.code(barcode.rawValue)
                if (code != null) onCode(code) else Toast.makeText(context, R.string.computer_not_code, Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { Toast.makeText(context, R.string.computer_no_scanner, Toast.LENGTH_LONG).show() }
    }.onFailure { Toast.makeText(context, R.string.computer_no_scanner, Toast.LENGTH_LONG).show() }
}

/**
 * "Ligar este computador?": the code was read (in the app or by the phone's camera). Linking sends the
 * vault's key to that computer, so it asks first, and says to link only a code opened by oneself.
 */
@Composable
internal fun LinkComputerDialog(code: ComputerLink.Code, onDismiss: () -> Unit) {
    val hub = SoundApplication.graph.social
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = SoundColors.Surface2,
        shape = RoundedCornerShape(28.dp),
        icon = { Icon(Icons.Rounded.Computer, null, tint = SoundColors.Accent) },
        title = { Text(stringResource(R.string.computer_confirm_title), style = MaterialTheme.typography.headlineSmall, color = SoundColors.Text) },
        text = { Text(stringResource(R.string.computer_confirm_body), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    hub.linkComputer(code)
                        .onSuccess { name -> Toast.makeText(context, context.getString(R.string.computer_linked, name), Toast.LENGTH_LONG).show() }
                        .onFailure { e ->
                            val msg = when ((e as? RelayException)?.code) {
                                "expired", "delivered", "no_link" -> R.string.computer_expired
                                "too_many" -> R.string.computer_too_many
                                else -> R.string.computer_failed
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    busy = false
                    onDismiss()
                }
            }) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = SoundColors.Accent, strokeWidth = 2.dp)
                else Text(stringResource(R.string.computer_confirm), color = SoundColors.Accent, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(2.dp))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel), color = SoundColors.TextMuted, style = MaterialTheme.typography.labelLarge) }
        },
    )
}
