package com.villamediana.sound.ui.sheets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.MosaicCover
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.detail.PlaylistCovers
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The playlist "Editar informações" changes: one of yours, or one imported from Spotify. */
sealed interface PlaylistInfoTarget {
    data class Local(val id: Long) : PlaylistInfoTarget
    data class Imported(val url: String) : PlaylistInfoTarget
}

/** The cover after "Salvar". */
sealed interface CoverPick {
    /** The songs' covers together (playlists of yours). */
    data object Mosaic : CoverPick
    /** A song's cover, or the current one (a file or an url). */
    data class Image(val path: String) : CoverPick
    /** A photo from the gallery. */
    data class Photo(val uri: Uri) : CoverPick
}

/** What the sheet shows, whichever kind of playlist it is. */
private data class PlaylistInfo(
    val name: String,
    /** Null for an imported playlist (it has none to edit). */
    val description: String?,
    val cover: String?,
    /** Null for an imported playlist: no mosaic, its cover is always a picture. */
    val mosaic: List<String>?,
    val songCovers: List<String>,
)

/**
 * "Editar informações" of a playlist: name, description and cover in one place. The cover is one
 * of its songs', the mosaic, or a photo from the gallery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistInfoSheet(target: PlaylistInfoTarget, onDismiss: () -> Unit, onSaved: () -> Unit = {}) {
    val graph = SoundApplication.graph
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val info: PlaylistInfo? = when (target) {
        is PlaylistInfoTarget.Local -> {
            val detail by remember(target.id) { graph.library.playlist(target.id) }.collectAsStateWithLifecycle(initialValue = null)
            detail?.let { d ->
                PlaylistInfo(
                    name = d.summary.name,
                    description = d.summary.playlist.description.orEmpty(),
                    cover = d.summary.coverPath,
                    mosaic = d.summary.mosaic,
                    songCovers = d.tracks.mapNotNull { it.coverPath },
                )
            }
        }
        is PlaylistInfoTarget.Imported -> {
            val detail by produceState<com.villamediana.sound.online.CollectionDetail?>(null, target.url) { value = graph.library.importedDetail(target.url) }
            detail?.let { d ->
                PlaylistInfo(
                    name = d.collection.title,
                    description = null,
                    cover = d.collection.thumbnailUrl,
                    mosaic = null,
                    songCovers = d.tracks.mapNotNull { it.thumbnailUrl },
                )
            }
        }
    }

    var name by rememberSaveable { mutableStateOf<String?>(null) }
    var description by rememberSaveable { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf<CoverPick?>(null) }
    LaunchedEffect(info != null) {
        if (info != null && name == null) {
            name = info.name
            description = info.description
            pick = info.cover?.let { CoverPick.Image(it) } ?: CoverPick.Mosaic.takeIf { info.mosaic != null }
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pick = CoverPick.Photo(uri)
    }
    fun openGallery() = gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    val current = name
    val valid = !current.isNullOrBlank()
    fun save() {
        val i = info ?: return
        if (!valid) return
        val newName = current!!.trim()
        val newDescription = description?.trim()?.ifEmpty { null }
        val newCover = pick.takeIf { p -> p != (i.cover?.let { CoverPick.Image(it) } ?: CoverPick.Mosaic) }
        graph.scope.launch {
            when (target) {
                is PlaylistInfoTarget.Local -> PlaylistEdits.saveLocal(context.applicationContext, target.id, newName, newDescription, newCover)
                is PlaylistInfoTarget.Imported -> PlaylistEdits.saveImported(context.applicationContext, target.url, newName, newCover)
            }
            withContext(Dispatchers.Main) { onSaved() }
        }
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    SoundSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetTitle(stringResource(R.string.sheet_edit_info_title))
        if (info == null || current == null) {
            Spacer(Modifier.height(220.dp))
            return@SoundSheet
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            // The cover as it will be; a tap picks a photo.
            val tap = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(104.dp)
                    .clip(RoundedCornerShape(Dimens.CoverCorner + 4.dp))
                    .clickable(interactionSource = tap, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.sheet_cover_gallery), onClick = ::openGallery),
            ) {
                CoverArt(pick, info, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(16.dp))
            SoundTextField(
                value = current,
                onValueChange = { name = it.take(80) },
                label = stringResource(R.string.detail_field_name),
                modifier = Modifier.weight(1f),
                imeAction = if (info.description != null) ImeAction.Next else ImeAction.Done,
                onDone = ::save,
            )
        }
        if (info.description != null) {
            Spacer(Modifier.height(12.dp))
            SoundTextField(
                value = description.orEmpty(),
                onValueChange = { description = it.take(300) },
                label = stringResource(R.string.detail_field_description),
                modifier = Modifier.padding(horizontal = 24.dp),
                singleLine = false,
                imeAction = ImeAction.Done,
                onDone = ::save,
            )
        }
        Text(
            stringResource(R.string.sheet_cover_label).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = SoundColors.TextMuted,
            modifier = Modifier.padding(start = 24.dp, top = 22.dp, bottom = 10.dp),
        )
        val covers = remember(info) { PlaylistCovers.choices(info.cover, info.songCovers).take(MAX_CHOICES) }
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "gallery") {
                CoverTile(selected = pick is CoverPick.Photo, onClick = ::openGallery) {
                    val photo = pick as? CoverPick.Photo
                    if (photo != null) {
                        Cover(photo.uri, Modifier.fillMaxSize(), seed = "photo")
                    } else {
                        Box(Modifier.fillMaxSize().background(SoundColors.Surface3), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.sheet_cover_gallery), tint = SoundColors.TextMuted, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
            if (info.mosaic != null) {
                item(key = "mosaic") {
                    CoverTile(selected = pick == CoverPick.Mosaic, onClick = { pick = CoverPick.Mosaic }) {
                        MosaicCover(info.mosaic, Modifier.fillMaxSize(), seed = info.name)
                    }
                }
            }
            items(covers, key = { it }) { path ->
                CoverTile(selected = pick == CoverPick.Image(path), onClick = { pick = CoverPick.Image(path) }) {
                    Cover(path, Modifier.fillMaxSize(), seed = path)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, top = 22.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelLarge, color = SoundColors.TextMuted)
            }
            Spacer(Modifier.width(8.dp))
            PillButton(stringResource(R.string.save), onClick = ::save, filled = true, enabled = valid)
        }
    }
}

@Composable
private fun CoverArt(pick: CoverPick?, info: PlaylistInfo, modifier: Modifier) {
    when (pick) {
        is CoverPick.Photo -> Cover(pick.uri, modifier, seed = "photo")
        is CoverPick.Image -> Cover(pick.path, modifier, seed = info.name)
        CoverPick.Mosaic, null -> MosaicCover(info.mosaic.orEmpty(), modifier, seed = info.name)
    }
}

@Composable
private fun CoverTile(selected: Boolean, onClick: () -> Unit, art: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Dimens.CoverCorner + 2.dp)
    Box(
        Modifier
            .size(76.dp)
            .clip(shape)
            .then(if (selected) Modifier.border(2.dp, SoundColors.Accent, shape) else Modifier)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(if (selected) 4.dp else 0.dp)
            .clip(RoundedCornerShape(Dimens.CoverCorner)),
    ) {
        art()
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(SoundColors.Accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, null, tint = SoundColors.OnAccent, modifier = Modifier.size(13.dp)) }
        }
    }
}

private const val MAX_CHOICES = 60

/** Saves what "Editar informações" changed. Covers of their own are copied into the app's folder. */
object PlaylistEdits {
    suspend fun saveLocal(context: Context, id: Long, name: String, description: String?, cover: CoverPick?) = withContext(Dispatchers.IO) {
        val graph = SoundApplication.graph
        graph.library.renamePlaylist(id, name, description)
        if (cover == null) return@withContext
        val old = graph.library.getPlaylist(id)?.coverPath
        val owned = { path: String? -> PlaylistCovers.isOwned(path, id) }
        val newPath = when (cover) {
            CoverPick.Mosaic -> null
            is CoverPick.Image -> if (owned(cover.path)) cover.path else copyFile(cover.path, graph.storage.coverFile(PlaylistCovers.fileName(id, System.currentTimeMillis())))
            is CoverPick.Photo -> copyPhoto(context, cover.uri, graph.storage.coverFile(PlaylistCovers.fileName(id, System.currentTimeMillis()))) ?: return@withContext
        }
        graph.library.setPlaylistCover(id, newPath)
        if (old != null && old != newPath && owned(old)) runCatching { File(old).delete() }
    }

    suspend fun saveImported(context: Context, url: String, name: String, cover: CoverPick?) = withContext(Dispatchers.IO) {
        val graph = SoundApplication.graph
        val old = graph.library.importedDetail(url)?.collection?.thumbnailUrl
        val prefix = "imported-" + url.hashCode().toUInt() + "-"
        val newCover = when (cover) {
            null -> old
            CoverPick.Mosaic -> old
            is CoverPick.Image -> cover.path
            is CoverPick.Photo -> copyPhoto(context, cover.uri, graph.storage.coverFile(prefix + System.currentTimeMillis())) ?: old
        }
        graph.library.editImported(url, name, newCover)
        if (old != null && old != newCover && File(old).name.startsWith(prefix)) runCatching { File(old).delete() }
    }

    /** A song's cover file copied (it survives deleting the song); an url stays an url. */
    private fun copyFile(src: String, dest: File): String {
        if (src.startsWith("http")) return src
        return runCatching { File(src).copyTo(dest, overwrite = true) }.map { dest.absolutePath }.getOrDefault(src)
    }

    /** A gallery photo saved as a cover: at most [MAX_PX] on its longer side, as JPEG. */
    private fun copyPhoto(context: Context, uri: Uri, dest: File): String? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return@runCatching null
        dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        dest.absolutePath
    }.getOrNull()

    private const val MAX_PX = 1200
}
