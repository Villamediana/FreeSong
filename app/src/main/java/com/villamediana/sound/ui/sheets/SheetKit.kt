package com.villamediana.sound.ui.sheets

import com.villamediana.sound.ui.theme.LocalSoundColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.villamediana.sound.R
import com.villamediana.sound.ui.theme.SoundColors

internal val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** ModalBottomSheet dressed for Sound: raised warm surface, small handle, 28 dp top corners. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = SheetShape,
        containerColor = SoundColors.Surface2,
        contentColor = SoundColors.Text,
        scrimColor = Color(0x99000000),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(SoundColors.TextFaint.copy(alpha = 0.6f)),
            )
        },
        contentWindowInsets = { WindowInsets(0) },
    ) {
        // A sheet is the app's own surface, even when opened from the search's colored page.
        CompositionLocalProvider(LocalSoundColors provides SoundColors) { content() }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars.union(WindowInsets.ime)))
        Spacer(Modifier.height(8.dp))
    }
}

/** Big sheet title with an optional quiet subtitle. */
@Composable
fun SheetTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = SoundColors.Text)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
        }
    }
}

/** Sheet header: art, title, subtitle, and a hairline under it. */
@Composable
fun SheetHeader(
    title: String,
    subtitle: String,
    art: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp)) { art() }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = SoundColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = SoundColors.Hairline, thickness = 1.dp)
        Spacer(Modifier.height(6.dp))
    }
}

/** One line of a sheet: icon + label. */
@Composable
fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = SoundColors.TextMuted,
    labelColor: Color = SoundColors.Text,
    /** Quiet, unavailable look (e.g. needs the internet); still tappable so it can explain why. */
    dimmed: Boolean = false,
    /** Short note on the right ("Na fila…", "Sem internet"). */
    trailing: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (dimmed) 0.45f else 1f
        Icon(icon, null, tint = tint.copy(alpha = tint.alpha * alpha), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(18.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = labelColor.copy(alpha = labelColor.alpha * alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = SoundColors.TextFaint, maxLines = 1)
        }
    }
}

/** "Are you sure?" dialog with a red confirm button. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SoundColors.Surface2,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, style = MaterialTheme.typography.headlineSmall, color = SoundColors.Text) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, style = MaterialTheme.typography.labelLarge, color = if (destructive) SoundColors.Danger else SoundColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelLarge, color = SoundColors.TextMuted)
            }
        },
    )
}

/** Outlined field in Sound colors (hairline at rest, tangerine when focused). */
@Composable
fun SoundTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    number: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = SoundColors.TextFaint) } },
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 4,
        shape = RoundedCornerShape(14.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(
            capitalization = if (number) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = SoundColors.Text,
            unfocusedTextColor = SoundColors.Text,
            focusedBorderColor = SoundColors.Accent,
            unfocusedBorderColor = SoundColors.Hairline,
            focusedLabelColor = SoundColors.Accent,
            unfocusedLabelColor = SoundColors.TextMuted,
            cursorColor = SoundColors.Accent,
            focusedContainerColor = SoundColors.Surface3.copy(alpha = 0.4f),
            unfocusedContainerColor = SoundColors.Surface3.copy(alpha = 0.4f),
        ),
    )
}

/** Vertical stack spacing for dialog forms. */
internal val FormSpacing = Arrangement.spacedBy(12.dp)
