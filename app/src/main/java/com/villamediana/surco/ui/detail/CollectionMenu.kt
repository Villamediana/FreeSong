package com.villamediana.surco.ui.detail

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SubdirectoryArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.villamediana.surco.R
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.ui.sheets.SheetAction
import com.villamediana.surco.ui.sheets.SheetFeedback
import com.villamediana.surco.ui.sheets.SheetHeader
import com.villamediana.surco.ui.sheets.SurcoSheet
import com.villamediana.surco.ui.theme.SurcoColors
import kotlinx.coroutines.launch

/** One line of a collection ⋮ menu. */
internal data class MenuItem(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val tint: Color? = null,
    val onClick: () -> Unit,
)

/** The ⋮ of a collection header: small header + actions; each action closes the sheet first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionMenuSheet(
    title: String,
    subtitle: String,
    art: @Composable (Modifier) -> Unit,
    items: List<MenuItem>,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    SurcoSheet(onDismissRequest = onDismiss, sheetState = state) {
        SheetHeader(title = title, subtitle = subtitle, art = { art(Modifier.fillMaxSize()) })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            items.forEach { item ->
                SheetAction(
                    icon = item.icon,
                    label = item.label,
                    tint = item.tint ?: if (item.destructive) SurcoColors.Danger else SurcoColors.TextMuted,
                    labelColor = if (item.destructive) SurcoColors.Danger else SurcoColors.Text,
                    onClick = {
                        scope.launch { state.hide() }.invokeOnCompletion {
                            onDismiss()
                            item.onClick()
                        }
                    },
                )
            }
        }
    }
}

/** "Tocar a seguir / Adicionar à fila / Adicionar à playlist" for a whole collection. */
internal fun queueMenuItems(
    tracks: List<Track>,
    res: Resources,
    feedback: SheetFeedback,
    onAddToPlaylist: (List<Long>) -> Unit,
): List<MenuItem> {
    if (tracks.isEmpty()) return emptyList()
    val player = graph.player
    return listOf(
        MenuItem(Icons.Rounded.SubdirectoryArrowRight, res.getString(R.string.detail_menu_play_next)) {
            player.playNext(tracks)
            feedback.show(res.getQuantityString(R.plurals.detail_play_next_done, tracks.size, tracks.size))
        },
        MenuItem(Icons.AutoMirrored.Rounded.QueueMusic, res.getString(R.string.detail_menu_enqueue)) {
            player.enqueue(tracks)
            feedback.show(res.getQuantityString(R.plurals.detail_enqueue_done, tracks.size, tracks.size))
        },
        MenuItem(Icons.AutoMirrored.Rounded.PlaylistAdd, res.getString(R.string.detail_menu_add_to_playlist)) {
            onAddToPlaylist(tracks.map { it.id })
        },
    )
}
