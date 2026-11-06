package com.villamediana.surco.ui.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.villamediana.surco.R
import com.villamediana.surco.data.model.GenreSummary
import com.villamediana.surco.data.sortKey
import com.villamediana.surco.online.CollectionKind
import com.villamediana.surco.online.RemoteCollection
import com.villamediana.surco.online.RemoteTrack
import com.villamediana.surco.ui.components.Cover
import com.villamediana.surco.ui.components.CoverColors
import com.villamediana.surco.ui.components.formatDuration
import com.villamediana.surco.ui.components.pressScale
import com.villamediana.surco.ui.components.seedColor
import com.villamediana.surco.ui.theme.Dimens
import com.villamediana.surco.ui.theme.SurcoColors
import kotlin.math.roundToInt

/** "Artista · Álbum · 3:45" (album left out when it just repeats the title). */
fun remoteSubtitle(track: RemoteTrack): String = listOfNotNull(
    track.artist.takeIf { it.isNotBlank() },
    track.album?.takeIf { it.isNotBlank() && !it.equals(track.title, ignoreCase = true) },
    track.durationMs?.takeIf { it > 0 }?.let(::formatDuration),
).joinToString(" · ")

/** A song found online, with its live download state. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteSongRow(
    track: RemoteTrack,
    state: ItemDownload,
    onClick: () -> Unit,
    onIndicatorClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    subtitle: String = remoteSubtitle(track),
    /** Album pages show the track number instead of the thumbnail. */
    number: Int? = null,
    selecting: Boolean = false,
    selected: Boolean = false,
) {
    val bg by animateColorAsState(if (selected) SurcoColors.AccentSoft else Color.Transparent, tween(180), label = "rowBg")
    val dimmed = selecting && (state == ItemDownload.Done || state == ItemDownload.Queued || state is ItemDownload.Running)
    Row(
        modifier
            .fillMaxWidth()
            .height(Dimens.RowHeight)
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = Dimens.Gutter, end = Dimens.Gutter - 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                Text("$number", style = MaterialTheme.typography.bodyMedium, color = SurcoColors.TextMuted)
            }
        } else {
            Cover(track.thumbnailUrl, Modifier.size(Dimens.RowCover), seed = track.album ?: track.title)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (dimmed) SurcoColors.TextFaint else SurcoColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = if (dimmed) SurcoColors.TextFaint else SurcoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        if (selecting) {
            SelectMark(selected = selected, enabled = !dimmed)
        } else {
            DownloadIndicator(state, onIndicatorClick)
        }
    }
}

/** An album/playlist/artist found online. */
@Composable
fun RemoteCollectionRow(collection: RemoteCollection, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val kindLabel = stringResource(
        when (collection.kind) {
            CollectionKind.ALBUM -> R.string.search_kind_album
            CollectionKind.PLAYLIST -> R.string.search_kind_playlist
            CollectionKind.ARTIST -> R.string.search_kind_artist
        },
    )
    val count = collection.itemCount?.takeIf { it > 0 && collection.kind == CollectionKind.PLAYLIST }?.toInt()
    val countLabel = count?.let { pluralStringResource(R.plurals.n_songs, it, it) }
    val subtitle = listOfNotNull(
        kindLabel,
        collection.author?.takeIf { it.isNotBlank() && collection.kind != CollectionKind.ARTIST },
        collection.year?.toString(),
        countLabel,
    ).joinToString(" · ")
    val round = collection.kind == CollectionKind.ARTIST
    Row(
        modifier
            .fillMaxWidth()
            .height(Dimens.RowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(
            collection.thumbnailUrl,
            Modifier.size(Dimens.RowCover),
            seed = collection.title,
            shape = if (round) CircleShape else RoundedCornerShape(Dimens.CoverCorner),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(collection.title, style = MaterialTheme.typography.titleMedium, color = SurcoColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SurcoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = SurcoColors.TextFaint, modifier = Modifier.size(22.dp))
    }
}

/** Autocomplete line: what was typed stays light, the completion is bold. */
@Composable
fun SuggestionRow(text: String, typed: String, onClick: () -> Unit, onFill: () -> Unit, modifier: Modifier = Modifier) {
    val annotated = remember(text, typed) {
        val t = typed.trim()
        buildAnnotatedString {
            if (t.isNotEmpty() && text.sortKey().startsWith(t.sortKey()) && text.length >= t.length) {
                withStyle(SpanStyle(color = SurcoColors.TextMuted)) { append(text.substring(0, t.length)) }
                withStyle(SpanStyle(color = SurcoColors.Text, fontWeight = FontWeight.SemiBold)) { append(text.substring(t.length)) }
            } else {
                withStyle(SpanStyle(color = SurcoColors.Text)) { append(text) }
            }
        }
    }
    Row(
        modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick).padding(start = Dimens.Gutter, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = SurcoColors.TextFaint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(annotated, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onFill, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.NorthWest, stringResource(R.string.search_suggestion_fill, text), tint = SurcoColors.TextFaint, modifier = Modifier.size(18.dp))
        }
    }
}

/** "Abrir link do YouTube" while a link is typed in the field. */
@Composable
fun OpenLinkRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick).padding(horizontal = Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(SurcoColors.AccentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Link, null, tint = SurcoColors.Accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(stringResource(R.string.search_open_link), style = MaterialTheme.typography.titleMedium, color = SurcoColors.Text, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = SurcoColors.TextFaint)
    }
}

@Composable
fun RecentRow(text: String, onClick: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(50.dp).clickable(onClick = onClick).padding(start = Dimens.Gutter, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.History, null, tint = SurcoColors.TextFaint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = SurcoColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.Close, stringResource(R.string.search_recent_remove, text), tint = SurcoColors.TextFaint, modifier = Modifier.size(18.dp))
        }
    }
}

/** Quiet card that explains the link trick. */
@Composable
fun LinkHintCard(modifier: Modifier = Modifier, onPaste: (() -> Unit)? = null) {
    Row(
        modifier
            .padding(horizontal = Dimens.Gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurcoColors.Surface1)
            .then(if (onPaste != null) Modifier.clickable(onClick = onPaste) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(SurcoColors.AccentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Link, null, tint = SurcoColors.Accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.search_hint_overline).uppercase(), style = MaterialTheme.typography.labelSmall, color = SurcoColors.TextFaint)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.search_hint_body), style = MaterialTheme.typography.bodyMedium, color = SurcoColors.TextMuted)
        }
    }
}

/** "Colar link do YouTube" — shown while the clipboard holds a YouTube link. */
@Composable
fun PasteLinkChip(onPaste: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(38.dp)
            .pressScale(interaction, 0.96f)
            .clip(CircleShape)
            .background(SurcoColors.AccentSoft)
            .clickable(interactionSource = interaction, indication = null, onClick = onPaste)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ContentPaste, null, tint = SurcoColors.Accent, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.search_paste_link), style = MaterialTheme.typography.labelLarge, color = SurcoColors.Accent)
        Spacer(Modifier.width(2.dp))
        Box(
            Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, stringResource(R.string.search_paste_link_dismiss), tint = SurcoColors.Accent.copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
        }
    }
}

/** Genre tile of the idle Search screen — the color and one sleeve come from the genre's covers. */
@Composable
fun GenreTile(genre: GenreSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val base = remember(genre.name, genre.accentColor) {
        val c = genre.accentColor?.let { Color(it) } ?: seedColor(genre.name)
        lerp(CoverColors.tune(c), SurcoColors.Ink, 0.22f)
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .height(96.dp)
            .pressScale(interaction, 0.97f)
            .clip(RoundedCornerShape(12.dp))
            .background(base)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Text(
            genre.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
            color = SurcoColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 64.dp),
        )
        Cover(
            genre.covers.firstOrNull(),
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 14.dp, y = 6.dp)
                .rotate(24f)
                .size(64.dp),
            seed = genre.name,
            shape = RoundedCornerShape(4.dp),
        )
    }
}

/** Full-width pill button (52 dp). */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = true,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val bg = if (filled) SurcoColors.Accent else Color.Transparent
    val fg = if (filled) SurcoColors.OnAccent else SurcoColors.Text
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .pressScale(interaction, 0.97f)
            .clip(CircleShape)
            .background(bg)
            .then(if (filled) Modifier else Modifier.border(1.dp, SurcoColors.Hairline, CircleShape))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A pill that fills up with progress — "Baixando 3 de 12". [fraction] null = waiting (shimmer). */
@Composable
fun ProgressPill(text: String, fraction: Float?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val animated by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(450), label = "pill")
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .then(if (fraction == null) Modifier.shimmer(CircleShape) else Modifier.background(SurcoColors.Surface3))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (fraction != null) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(animated).background(SurcoColors.Accent.copy(alpha = 0.34f)))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (fraction != null) {
                ProgressRing(fraction, 18.dp)
            } else {
                SpinningRing(18.dp)
            }
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = SurcoColors.Text, maxLines = 1)
        }
    }
}

/** Card on top of Search for a pasted/shared song link. */
@Composable
fun LinkCardView(
    card: LinkCard,
    state: ItemDownload,
    onDownload: () -> Unit,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .padding(horizontal = Dimens.Gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurcoColors.Surface1)
            .border(1.dp, SurcoColors.Hairline, RoundedCornerShape(20.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val track = (card as? LinkCard.Found)?.track
            if (track == null) {
                Box(Modifier.size(80.dp).shimmer(RoundedCornerShape(Dimens.CoverCorner)))
            } else {
                Cover(track.thumbnailUrl, Modifier.size(80.dp), seed = track.album ?: track.title)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Link, null, tint = SurcoColors.Accent, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.search_link_overline).uppercase(), style = MaterialTheme.typography.labelSmall, color = SurcoColors.Accent)
                }
                Spacer(Modifier.height(5.dp))
                if (track == null) {
                    Text(stringResource(R.string.search_link_resolving), style = MaterialTheme.typography.titleMedium, color = SurcoColors.TextMuted)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(0.5f).height(11.dp).shimmer())
                } else {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, color = SurcoColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOfNotNull(track.artist.takeIf { it.isNotBlank() }, track.durationMs?.takeIf { it > 0 }?.let(::formatDuration)).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SurcoColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.Top).size(36.dp)) {
                Icon(Icons.Rounded.Close, stringResource(R.string.search_link_dismiss), tint = SurcoColors.TextFaint, modifier = Modifier.size(18.dp))
            }
        }
        if (card is LinkCard.Found) {
            Spacer(Modifier.height(14.dp))
            val glyph = when (state) {
                ItemDownload.None -> 0
                ItemDownload.Queued, is ItemDownload.Running -> 1
                ItemDownload.Done -> 2
                is ItemDownload.Failed -> 3
            }
            AnimatedContent(glyph, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(140)) }, label = "linkAction") { g ->
                when (g) {
                    0 -> BigButton(stringResource(R.string.search_link_download), onDownload, icon = Icons.Rounded.Download)
                    1 -> {
                        val running = state as? ItemDownload.Running
                        ProgressPill(
                            text = if (running != null) stringResource(R.string.search_link_downloading, (running.progress * 100).roundToInt()) else stringResource(R.string.search_link_queued),
                            fraction = running?.progress,
                        )
                    }
                    2 -> BigButton(stringResource(R.string.search_link_play), onPlay, icon = Icons.Rounded.PlayArrow)
                    else -> BigButton(stringResource(R.string.search_retry), onRetry, icon = Icons.Rounded.Refresh, filled = false)
                }
            }
        }
    }
}
