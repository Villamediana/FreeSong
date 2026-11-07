package com.villamediana.sound.ui.library

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassifier
import android.widget.Toast
import com.villamediana.sound.online.RemoteCollection
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
 * YouTube Music; the playlist then opens like any online one (streams, nothing downloaded). The
 * work goes on with the sheet closed.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SpotifyImportSheet(onDismiss: () -> Unit, onOpen: (RemoteCollection) -> Unit) {
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
                    // Found: the sheet gives way to the playlist's page.
                    is SpotifyImporter.State.Done -> LaunchedEffect(s) {
                        if (s.missing.isNotEmpty()) {
                            Toast.makeText(context, context.resources.getQuantityString(R.plurals.spotify_import_missing, s.missing.size, s.missing.size), Toast.LENGTH_LONG).show()
                        }
                        importer.reset()
                        onDismiss()
                        onOpen(s.collection)
                    }
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
    Spacer(Modifier.height(12.dp))
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
    PlaylistHeader(p, count)
    Spacer(Modifier.height(20.dp))
    PillButton(
        text = stringResource(R.string.spotify_import_go),
        onClick = onImport,
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
    Spacer(Modifier.height(20.dp))
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
