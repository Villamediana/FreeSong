package com.villamediana.sound.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.villamediana.sound.R
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.ui.theme.SoundColors

/** What the edit form produces; numbers already parsed. */
data class TrackEdit(
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val genre: String?,
    val year: Int?,
    val trackNumber: Int?,
)

/** Pure form rules (unit-tested). */
object TrackEditRules {
    /** Keeps only digits, at most [max] of them (typing "19a9" gives "199"). */
    fun digits(input: String, max: Int): String = input.filter { it.isDigit() }.take(max)

    /** Year 1000..2999 or null. */
    fun parseYear(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1000..2999 }

    /** Track number 1..999 or null. */
    fun parseTrackNumber(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..999 }

    /** Title and artist can't be blank; year, if typed, must be a real year. */
    fun isValid(title: String, artist: String, year: String): Boolean =
        title.isNotBlank() && artist.isNotBlank() && (year.isBlank() || parseYear(year) != null)

    fun build(title: String, artist: String, album: String, albumArtist: String, genre: String, year: String, trackNumber: String) = TrackEdit(
        title = title.trim(),
        artist = artist.trim(),
        album = album.trim().ifEmpty { null },
        albumArtist = albumArtist.trim().ifEmpty { null },
        genre = genre.trim().ifEmpty { null },
        year = parseYear(year),
        trackNumber = parseTrackNumber(trackNumber),
    )
}

/** "Editar informações": title, artist, album, album artist, genre, year, track number. */
@Composable
fun EditTrackDialog(track: Track, onDismiss: () -> Unit, onSave: (TrackEdit) -> Unit) {
    var title by rememberSaveable(track.id) { mutableStateOf(track.title) }
    var artist by rememberSaveable(track.id) { mutableStateOf(track.artist) }
    var album by rememberSaveable(track.id) { mutableStateOf(track.album.orEmpty()) }
    var albumArtist by rememberSaveable(track.id) { mutableStateOf(track.albumArtist.orEmpty()) }
    var genre by rememberSaveable(track.id) { mutableStateOf(track.genre.orEmpty()) }
    var year by rememberSaveable(track.id) { mutableStateOf(track.year?.toString().orEmpty()) }
    var number by rememberSaveable(track.id) { mutableStateOf(track.trackNumber?.toString().orEmpty()) }
    val valid = TrackEditRules.isValid(title, artist, year)
    val save = { if (valid) onSave(TrackEditRules.build(title, artist, album, albumArtist, genre, year, number)) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding(),
            shape = RoundedCornerShape(28.dp),
            color = SoundColors.Surface2,
            contentColor = SoundColors.Text,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Text(
                    stringResource(R.string.sheet_edit_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = SoundColors.Text,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.padding(top = 16.dp))
                Column(
                    Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    verticalArrangement = FormSpacing,
                ) {
                    SoundTextField(title, { title = it }, stringResource(R.string.sheet_field_title))
                    SoundTextField(artist, { artist = it }, stringResource(R.string.sheet_field_artist))
                    SoundTextField(album, { album = it }, stringResource(R.string.sheet_field_album))
                    SoundTextField(albumArtist, { albumArtist = it }, stringResource(R.string.sheet_field_album_artist), placeholder = artist.ifBlank { null })
                    SoundTextField(genre, { genre = it }, stringResource(R.string.sheet_field_genre))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SoundTextField(
                            year, { year = TrackEditRules.digits(it, 4) }, stringResource(R.string.sheet_field_year),
                            modifier = Modifier.weight(1f), number = true,
                        )
                        SoundTextField(
                            number, { number = TrackEditRules.digits(it, 3) }, stringResource(R.string.sheet_field_track_number),
                            modifier = Modifier.weight(1f), number = true, imeAction = ImeAction.Done, onDone = save,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelLarge, color = SoundColors.TextMuted)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = save, enabled = valid) {
                        Text(
                            stringResource(R.string.save),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (valid) SoundColors.Accent else SoundColors.TextFaint,
                        )
                    }
                }
            }
        }
    }
}
