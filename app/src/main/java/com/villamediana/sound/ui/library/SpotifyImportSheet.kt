package com.villamediana.sound.ui.library

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassifier
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.online.SpotifyError
import com.villamediana.sound.online.SpotifyImporter
import com.villamediana.sound.online.SpotifyLinks
import com.villamediana.sound.online.SpotifyPlaylist
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.sheets.SheetTitle
import com.villamediana.sound.ui.sheets.SoundSheet
import com.villamediana.sound.ui.sheets.SoundTextField
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.launch

/** Opens the import sheet: the Playlists tab's +, its empty state, a link shared from Spotify. */
object SpotifyImportRequest {
    var open by mutableStateOf(false)

    /** A link that came with the request (shared to FreeSong): looked up right away. */
    var link by mutableStateOf<String?>(null)

    fun show(link: String? = null) {
        this.link = link
        open = true
    }
}

/**
 * "Importar do Spotify": paste a public playlist link → see it → import. Each song is looked up on
 * YouTube Music and the found ones are downloaded into a playlist with the same name. The work
 * goes on with the sheet closed.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SpotifyImportSheet(onDismiss: () -> Unit, onOpenPlaylist: (Long) -> Unit) {
    val graph = SoundApplication.graph
    val importer = graph.spotifyImport
    val run by importer.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var text by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<SpotifyError?>(null) }
    var preview by remember { mutableStateOf<SpotifyPlaylist?>(null) }

    fun lookUp() {
        if (loading || text.isBlank()) return
        loading = true
        error = null
        scope.launch {
            runCatching { graph.spotify.find(text) }
                .onSuccess { preview = it }
                .onFailure { error = it as? SpotifyError ?: SpotifyError.Offline }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        val shared = SpotifyImportRequest.link
        SpotifyImportRequest.link = null
        when {
            shared != null -> { text = shared; lookUp() }
            text.isBlank() -> readSpotifyLink(context)?.let { text = it }
        }
    }

    SoundSheet(onDismissRequest = onDismiss) {
        SheetTitle(stringResource(R.string.spotify_import_title))
        val step: Any = when {
            run !is SpotifyImporter.State.Idle -> run
            preview != null -> preview!!
            else -> "input"
        }
        AnimatedContent(
            targetState = step,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
            label = "spotifyStep",
        ) { s ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                when (s) {
                    is SpotifyPlaylist -> Preview(s, onImport = { importer.start(s) }, onOther = { preview = null })
                    is SpotifyImporter.State.Matching -> Matching(s)
                    is SpotifyImporter.State.Done -> Done(
                        s,
                        onOpen = {
                            s.playlistId?.let(onOpenPlaylist)
                            importer.reset()
                            preview = null
                            onDismiss()
                        },
                        onAnother = { importer.reset(); preview = null; text = "" },
                    )
                    is SpotifyImporter.State.Failed -> Failed(s, onRetry = { importer.reset(); importer.start(s.playlist) }, onOther = { importer.reset(); preview = null })
                    else -> Input(
                        text = text,
                        onText = { text = it; error = null },
                        loading = loading,
                        error = error,
                        onLookUp = ::lookUp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Input(text: String, onText: (String) -> Unit, loading: Boolean, error: SpotifyError?, onLookUp: () -> Unit) {
    Text(stringResource(R.string.spotify_import_body), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
    Spacer(Modifier.height(16.dp))
    SoundTextField(
        value = text,
        onValueChange = onText,
        label = stringResource(R.string.spotify_import_link),
        placeholder = "open.spotify.com/playlist/…",
        imeAction = ImeAction.Done,
        onDone = onLookUp,
    )
    if (error != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                when (error) {
                    SpotifyError.NotALink -> R.string.spotify_import_not_a_link
                    SpotifyError.NotFound -> R.string.spotify_import_not_found
                    SpotifyError.Offline -> R.string.spotify_import_offline
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = SoundColors.Danger,
        )
    }
    Spacer(Modifier.height(18.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(32.dp), color = SoundColors.Accent, strokeWidth = 3.dp, strokeCap = StrokeCap.Round)
        } else {
            PillButton(
                text = stringResource(R.string.spotify_import_look_up),
                onClick = onLookUp,
                filled = true,
                big = true,
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(stringResource(R.string.spotify_import_how), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun PlaylistHeader(p: SpotifyPlaylist, line: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Cover(p.coverUrl, Modifier.size(76.dp), seed = p.name)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleLarge, color = SoundColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(line, style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ColumnScope.Preview(p: SpotifyPlaylist, onImport: () -> Unit, onOther: () -> Unit) {
    val count = pluralStringResource(R.plurals.n_songs, p.tracks.size, p.tracks.size)
    PlaylistHeader(p, listOfNotNull(count, p.owner).joinToString(" · "))
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.spotify_import_what_happens), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
    if (p.tracks.size >= 100) {
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.spotify_import_limit), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint)
    }
    Spacer(Modifier.height(18.dp))
    PillButton(
        text = stringResource(R.string.spotify_import_go),
        onClick = onImport,
        icon = Icons.Rounded.Download,
        filled = true,
        big = true,
        enabled = p.tracks.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = onOther, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text(stringResource(R.string.spotify_import_other), style = MaterialTheme.typography.labelLarge, color = SoundColors.TextMuted)
    }
}

@Composable
private fun Matching(s: SpotifyImporter.State.Matching) {
    val total = s.playlist.tracks.size.coerceAtLeast(1)
    val progress by animateFloatAsState(s.done.toFloat() / total, tween(300), label = "matching")
    PlaylistHeader(s.playlist, stringResource(R.string.spotify_import_matching, s.done, s.playlist.tracks.size))
    Spacer(Modifier.height(18.dp))
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        color = SoundColors.Accent,
        trackColor = SoundColors.AccentSoft,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.spotify_import_keeps_going), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ColumnScope.Done(s: SpotifyImporter.State.Done, onOpen: () -> Unit, onAnother: () -> Unit) {
    PlaylistHeader(s.playlist, pluralStringResource(R.plurals.spotify_import_found, s.found, s.found))
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(SoundColors.Accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, tint = SoundColors.OnAccent, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.spotify_import_done), style = MaterialTheme.typography.bodyMedium, color = SoundColors.Text)
    }
    if (s.missing.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text(
            pluralStringResource(R.plurals.spotify_import_missing, s.missing.size, s.missing.size),
            style = MaterialTheme.typography.labelLarge,
            color = SoundColors.TextMuted,
        )
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SoundColors.Surface3.copy(alpha = 0.5f)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            s.missing.take(MISSING_SHOWN).forEach { t ->
                Text(
                    listOf(t.title, t.artist).filter { it.isNotBlank() }.joinToString(" — "),
                    style = MaterialTheme.typography.bodySmall,
                    color = SoundColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val more = s.missing.size - MISSING_SHOWN
            if (more > 0) Text(stringResource(R.string.spotify_import_and_more, more), style = MaterialTheme.typography.bodySmall, color = SoundColors.TextFaint)
        }
    }
    Spacer(Modifier.height(18.dp))
    if (s.playlistId != null) {
        PillButton(stringResource(R.string.spotify_import_open), onOpen, filled = true, big = true, modifier = Modifier.fillMaxWidth())
    }
    TextButton(onClick = onAnother, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text(stringResource(R.string.spotify_import_another), style = MaterialTheme.typography.labelLarge, color = SoundColors.TextMuted)
    }
}

@Composable
private fun ColumnScope.Failed(s: SpotifyImporter.State.Failed, onRetry: () -> Unit, onOther: () -> Unit) {
    PlaylistHeader(s.playlist, pluralStringResource(R.plurals.n_songs, s.playlist.tracks.size, s.playlist.tracks.size))
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(if (s.offline) R.string.spotify_import_offline else R.string.spotify_import_none_found),
        style = MaterialTheme.typography.bodyMedium,
        color = SoundColors.Danger,
    )
    Spacer(Modifier.height(18.dp))
    PillButton(stringResource(R.string.spotify_import_retry), onRetry, filled = true, big = true, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = onOther, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text(stringResource(R.string.spotify_import_other), style = MaterialTheme.typography.labelLarge, color = SoundColors.TextMuted)
    }
}

private const val MISSING_SHOWN = 5

/** A Spotify playlist link in the clipboard (only read when the system says the clip may be a URL). */
private fun readSpotifyLink(context: Context): String? = runCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val description = clipboard.primaryClipDescription ?: return null
    if (!description.hasMimeType("text/*")) return null
    if (Build.VERSION.SDK_INT >= 31 &&
        description.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE &&
        description.getConfidenceScore(TextClassifier.TYPE_URL) <= 0f
    ) {
        return null
    }
    val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return null
    text.takeIf(SpotifyLinks::mentions)?.trim()
}.getOrNull()
