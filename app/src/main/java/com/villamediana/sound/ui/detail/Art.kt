package com.villamediana.sound.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.model.AlbumSummary
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.CoverColors
import com.villamediana.sound.ui.components.Grooves
import com.villamediana.sound.ui.components.pressScale
import com.villamediana.sound.ui.components.rememberCoverColor
import com.villamediana.sound.ui.components.seedColor
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors

private val SleeveShape = RoundedCornerShape(Dimens.CoverCorner)

/** Tint for generated art: cached cover color of the songs, else a stable color for the name. */
internal fun artTint(tracks: List<Track>, name: String): Color =
    tracks.sortedByDescending { it.playCount }.firstNotNullOfOrNull { it.accentColor }?.let { Color(it) }
        ?: CoverColors.tune(seedColor(name))

/** Header wash: the cover's color, or — without a cover — the hue of its groove placeholder. */
@Composable
internal fun rememberWash(coverPath: String?, cached: Int?, seed: String): Color =
    rememberCoverColor(coverPath, cached, fallback = remember(seed) { CoverColors.tune(seedColor(seed)) })

/** Up to [n] distinct covers, most played first. */
internal fun topCovers(tracks: List<Track>, n: Int = 3): List<String> =
    tracks.sortedByDescending { it.playCount }.mapNotNull { it.coverPath }.distinct().take(n)

/**
 * Art for genres and mixes: a flat tinted sleeve, the grooves, up to three covers fanned like
 * records pulled from a crate, and the name set heavy in the corner.
 */
@Composable
internal fun GeneratedArt(covers: List<String>, tint: Color, label: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.clip(SleeveShape).background(tint)) {
        val side = maxWidth
        Grooves(Color.White.copy(alpha = 0.09f), Modifier.fillMaxSize())
        val fan = covers.take(3)
        val coverSize = side * 0.5f
        val spread = side * 0.16f
        val angles = when (fan.size) {
            1 -> listOf(0f)
            2 -> listOf(-7f, 7f)
            else -> listOf(-11f, 0f, 11f)
        }
        val offsets = when (fan.size) {
            1 -> listOf(0f)
            2 -> listOf(-0.6f, 0.6f)
            else -> listOf(-1f, 0f, 1f)
        }
        fan.forEachIndexed { i, path ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .offset(x = spread * offsets[i], y = -side * 0.08f + (if (offsets[i] == 0f) 0.dp else side * 0.03f))
                    .rotate(angles[i])
                    .size(coverSize)
                    .shadow(10.dp, SleeveShape, clip = false),
            ) { Cover(path, Modifier.fillMaxSize(), seed = path) }
        }
        val fontSize = (side.value * 0.115f).coerceIn(14f, 34f)
        Text(
            label,
            style = MaterialTheme.typography.displaySmall.copy(
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.02f).sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.04).em,
            ),
            color = SoundColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(side * 0.07f),
        )
    }
}

/** Artist "photo": their most played cover, in a circle. */
@Composable
internal fun ArtistArt(coverPath: String?, name: String, modifier: Modifier = Modifier) {
    Cover(coverPath, modifier, seed = name, shape = CircleShape)
}

/** Album card for carousels: sleeve, title, one quiet line. */
@Composable
internal fun AlbumCard(album: AlbumSummary, caption: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .width(Dimens.CardCover)
            .pressScale(interaction, 0.96f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Cover(album.coverPath, Modifier.size(Dimens.CardCover), seed = album.title)
        Spacer(Modifier.height(9.dp))
        Text(album.title, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(1.dp))
        Text(caption, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun AlbumCarousel(albums: List<AlbumSummary>, caption: (AlbumSummary) -> String, onOpen: (AlbumSummary) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Dimens.Gutter),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(albums, key = { it.key.normalized }) { a ->
            AlbumCard(a, caption(a), onClick = { onOpen(a) }, modifier = Modifier.animateItem())
        }
    }
}
