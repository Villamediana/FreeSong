package com.villamediana.sound.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.villamediana.sound.R
import com.villamediana.sound.ui.theme.SoundColors

/** "Nova playlist": name (required) and an optional description. */
@Composable
fun CreatePlaylistDialog(onCreate: (name: String, description: String?) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val canCreate = name.isNotBlank()
    fun submit() {
        if (canCreate) onCreate(name.trim(), description.trim().ifBlank { null })
    }

    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = SoundColors.Text,
        unfocusedTextColor = SoundColors.Text,
        focusedBorderColor = SoundColors.Accent,
        unfocusedBorderColor = SoundColors.Hairline,
        focusedLabelColor = SoundColors.Accent,
        unfocusedLabelColor = SoundColors.TextMuted,
        cursorColor = SoundColors.Accent,
        focusedContainerColor = SoundColors.Surface2,
        unfocusedContainerColor = SoundColors.Surface2,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SoundColors.Surface2,
        shape = RoundedCornerShape(22.dp),
        title = { Text(stringResource(R.string.home_lib_new_playlist), style = MaterialTheme.typography.headlineSmall, color = SoundColors.Text) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    label = { Text(stringResource(R.string.home_lib_playlist_name)) },
                    singleLine = true,
                    colors = colors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(300) },
                    label = { Text(stringResource(R.string.home_lib_playlist_description)) },
                    maxLines = 3,
                    colors = colors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = canCreate) {
                Text(
                    stringResource(R.string.home_lib_create),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (canCreate) SoundColors.Accent else SoundColors.TextFaint,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelLarge, color = SoundColors.TextMuted)
            }
        },
    )
}
