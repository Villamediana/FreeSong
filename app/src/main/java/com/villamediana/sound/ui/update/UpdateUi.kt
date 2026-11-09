package com.villamediana.sound.ui.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.ui.components.SoundMark
import com.villamediana.sound.ui.components.formatBytes
import com.villamediana.sound.ui.components.pressScale
import com.villamediana.sound.ui.sheets.SoundSheet
import com.villamediana.sound.update.AppRelease
import com.villamediana.sound.update.InstallResult
import com.villamediana.sound.update.ReleaseNotes
import com.villamediana.sound.update.UpdateFailure
import com.villamediana.sound.update.UpdateState
import com.villamediana.sound.update.Updates
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.SoundColors
import java.text.NumberFormat

/**
 * Everything the update needs from the screen: the quiet check on opening, the permission coming
 * back from the system settings, Android's confirmation screen and the sheet itself.
 */
@Composable
fun UpdateHost(updates: Updates = SoundApplication.graph.updates) {
    if (!updates.enabled) return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // On screen: the quiet check (at most every 6 h), back from "Instalar apps desconhecidos", and
    // Android's "Atualizar?" opened only now.
    LaunchedEffect(lifecycle, updates) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            updates.checkIfDue()
            updates.onResume()
            updates.confirm.collect { intent ->
                if (intent != null) {
                    updates.confirmShown()
                    // Can't open it: a failure the sheet shows, not an endless "Instalando…".
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        updates.onInstallStatus(InstallResult.FAILED, e.message, null)
                    } catch (e: SecurityException) {
                        updates.onInstallStatus(InstallResult.FAILED, e.message, null)
                    }
                }
            }
        }
    }

    val open by updates.sheet.collectAsStateWithLifecycle()
    val state by updates.state.collectAsStateWithLifecycle()
    val release = state.release
    if (open && release != null) {
        UpdateSheet(
            state = state,
            release = release,
            onUpdate = updates::update,
            onAllow = {
                val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(settings) }
            },
            onDismiss = updates::closeSheet,
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun UpdateSheet(
    state: UpdateState,
    release: AppRelease,
    onUpdate: () -> Unit,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    SoundSheet(onDismissRequest = onDismiss) {
        UpdateSheetBody(state, release, onUpdate, onAllow, onDismiss)
    }
}

/** What the sheet holds (apart from the sheet, so the tests can draw it). */
@Composable
internal fun UpdateSheetBody(
    state: UpdateState,
    release: AppRelease,
    onUpdate: () -> Unit,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 6.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SoundMark(size = 52.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_title), style = MaterialTheme.typography.headlineSmall, color = SoundColors.Text)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (release.sizeBytes > 0) stringResource(R.string.update_subtitle, release.version, formatBytes(release.sizeBytes)) else release.version,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoundColors.TextMuted,
                )
            }
        }

        val lines = remember(release.notes) { ReleaseNotes.lines(release.notes) }
        if (lines.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                lines.forEach { NoteLine(it) }
            }
            Spacer(Modifier.height(22.dp))
        }

        // One line above the button, only when there is something to say.
        val note: String? = when (state) {
            is UpdateState.NeedsPermission -> stringResource(R.string.update_permission_body)
            is UpdateState.Failed -> failureText(state.reason)
            else -> null
        }
        AnimatedContent(
            targetState = note,
            transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
            label = "updateNote",
        ) { text ->
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state is UpdateState.Failed) SoundColors.Danger else SoundColors.TextMuted,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 14.dp),
                )
            } else {
                Spacer(Modifier.fillMaxWidth())
            }
        }

        ProgressButton(
            state = state,
            onClick = if (state is UpdateState.NeedsPermission) onAllow else onUpdate,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        val busy = state is UpdateState.Downloading || state is UpdateState.Installing
        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
            if (!busy) {
                Text(
                    stringResource(R.string.update_later),
                    style = MaterialTheme.typography.labelLarge,
                    color = SoundColors.TextMuted,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun NoteLine(line: ReleaseNotes.Line) {
    when (line) {
        is ReleaseNotes.Line.Heading -> Text(
            line.text,
            style = MaterialTheme.typography.titleSmall,
            color = SoundColors.Text,
            modifier = Modifier.padding(top = 4.dp),
        )
        is ReleaseNotes.Line.Bullet -> Row {
            Box(Modifier.padding(top = 9.dp, end = 12.dp).size(5.dp).clip(CircleShape).background(SoundColors.Accent))
            Text(line.text, style = MaterialTheme.typography.bodyLarge, color = SoundColors.Text)
        }
        is ReleaseNotes.Line.Paragraph -> Text(line.text, style = MaterialTheme.typography.bodyLarge, color = SoundColors.TextMuted)
    }
}

/**
 * The button that becomes its own progress bar: the accent fills it left to right while the APK
 * comes down (the label turns light where the fill has passed), then breathes while Android installs.
 */
@Composable
private fun ProgressButton(state: UpdateState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing
    val target = when (state) {
        is UpdateState.Downloading -> state.fraction
        is UpdateState.Installing -> 1f
        else -> 1f
    }
    // Starting a download, the full button empties at once and fills from the left.
    val downloading = state is UpdateState.Downloading
    val fill = remember { Animatable(1f) }
    LaunchedEffect(downloading, target) {
        if (downloading && fill.value > 0.999f && target < 0.999f) fill.snapTo(0f)
        fill.animateTo(target, tween(if (downloading) 260 else 420))
    }
    // Breathes only while installing (no endless animation sitting in an idle sheet).
    val pulse = if (state is UpdateState.Installing) {
        val breathe = rememberInfiniteTransition(label = "installing")
        breathe.animateFloat(1f, 0.72f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse").value
    } else {
        1f
    }
    val label = when (state) {
        is UpdateState.Downloading -> stringResource(R.string.update_downloading, NumberFormat.getPercentInstance().format(state.fraction.toDouble()))
        is UpdateState.Installing -> stringResource(R.string.update_installing)
        is UpdateState.NeedsPermission -> stringResource(R.string.update_permission_action)
        is UpdateState.Failed -> stringResource(R.string.update_retry)
        else -> stringResource(R.string.update_action)
    }
    val interaction = remember { MutableInteractionSource() }
    val style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .pressScale(interaction, 0.97f)
            .clip(CircleShape)
            .background(SoundColors.Surface3)
            .clickable(interactionSource = interaction, indication = null, enabled = !busy, onClick = onClick)
            .semantics {
                if (state is UpdateState.Downloading) progressBarRangeInfo = ProgressBarRangeInfo(state.fraction, 0f..1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        // Under the fill: the label in ink.
        Text(label, style = style, color = SoundColors.Text, maxLines = 1)
        // The fill, and the same label in the on-accent color, cut where the fill ends.
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent { clipRect(right = size.width * fill.value) { this@drawWithContent.drawContent() } },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(SoundColors.Accent.copy(alpha = pulse)),
            )
            Text(label, style = style, color = SoundColors.OnAccent, maxLines = 1)
        }
    }
}

@Composable
private fun failureText(reason: UpdateFailure): String = stringResource(
    when (reason) {
        UpdateFailure.NETWORK -> R.string.update_error_network
        UpdateFailure.INTEGRITY -> R.string.update_error_integrity
        UpdateFailure.STORAGE -> R.string.update_error_storage
        UpdateFailure.PACKAGE -> R.string.update_error_package
        UpdateFailure.CONFLICT -> R.string.update_error_conflict
        UpdateFailure.INSTALL -> R.string.update_error_install
    },
)

/** Ajustes › Sobre: look for a new version, or the one found (opens the sheet). */
@Composable
fun UpdateRow(updates: Updates = SoundApplication.graph.updates) {
    if (!updates.enabled) return
    val state by updates.state.collectAsStateWithLifecycle()
    val release = state.release
    val title = when (state) {
        is UpdateState.Downloading, is UpdateState.Installing -> stringResource(R.string.settings_update_going, release!!.version)
        else -> if (release != null) stringResource(R.string.settings_update_available, release.version) else stringResource(R.string.settings_update_check)
    }
    val subtitle: String? = when (state) {
        UpdateState.Checking -> stringResource(R.string.settings_update_checking)
        UpdateState.UpToDate -> stringResource(R.string.settings_update_latest)
        UpdateState.CheckFailed -> stringResource(R.string.settings_update_failed)
        is UpdateState.Downloading -> NumberFormat.getPercentInstance().format((state as UpdateState.Downloading).fraction.toDouble())
        is UpdateState.Installing -> stringResource(R.string.update_installing)
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = state != UpdateState.Checking) {
                if (release != null) updates.openSheet() else updates.checkNow()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (release != null) SoundColors.Accent else SoundColors.Text,
            )
            AnimatedContent(
                targetState = subtitle,
                transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
                label = "updateSub",
            ) { text ->
                if (text != null) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state == UpdateState.UpToDate) SoundColors.Success else SoundColors.TextMuted,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when {
                state == UpdateState.Checking || state is UpdateState.Installing ->
                    CircularProgressIndicator(Modifier.size(18.dp), color = SoundColors.Accent, strokeWidth = 2.dp)
                state is UpdateState.Downloading ->
                    CircularProgressIndicator(
                        progress = { (state as UpdateState.Downloading).fraction },
                        modifier = Modifier.size(18.dp),
                        color = SoundColors.Accent,
                        trackColor = SoundColors.Surface3,
                        strokeWidth = 2.dp,
                    )
                release != null -> Box(Modifier.size(9.dp).clip(CircleShape).background(SoundColors.Accent))
            }
        }
    }
}
