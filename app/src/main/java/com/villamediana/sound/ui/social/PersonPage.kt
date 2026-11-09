package com.villamediana.sound.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.social.Profile
import com.villamediana.sound.social.SongRef
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.components.PlayingBars
import com.villamediana.sound.ui.components.SectionHeader
import com.villamediana.sound.ui.components.keepHorizontalDrags
import com.villamediana.sound.ui.components.pressScale
import com.villamediana.sound.ui.components.seedColor
import com.villamediana.sound.ui.detail.BackButton
import com.villamediana.sound.ui.detail.DetailSnackbar
import com.villamediana.sound.ui.detail.rememberPlaybackState
import com.villamediana.sound.ui.nav.Chrome
import com.villamediana.sound.ui.sheets.LocalSheetFeedback
import com.villamediana.sound.ui.sheets.SoundSnackbarHost
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.SoundColors

/**
 * A person's page — yours or a friend's. Not an artist's page: a person is a card. At the top,
 * their card in their own color (sound waves rippling from a corner): the photo, the name, the @,
 * the hours listened set big and since when. Under it the page's buttons ("Mandar música" / "Jam",
 * or yours), then their five most heard as big covers with the rank over them ("Tocar" plays them),
 * then [more].
 */
@Composable
internal fun PersonPage(
    person: Profile?,
    since: String?,
    top: List<SongRef>,
    topTitle: String,
    playTitle: String,
    snackbar: DetailSnackbar,
    avatar: @Composable (Dp) -> Unit,
    buttons: @Composable RowScope.() -> Unit,
    topActions: @Composable RowScope.() -> Unit = {},
    topHint: String? = null,
    showHours: Boolean = true,
    sheets: @Composable () -> Unit = {},
    more: LazyListScope.() -> Unit = {},
) {
    val player = SoundApplication.graph.player
    val playback by rememberPlaybackState()
    val songs = remember(top) { top.map { it.toRemote() } }
    val source = PlaySource(PlaySource.Type.RADIO, playTitle, "top:" + person?.id.orEmpty())
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    fun play(i: Int) {
        player.playOnline(songs, i, source)
        Chrome.openPlayer()
    }

    CompositionLocalProvider(LocalSheetFeedback provides snackbar.feedback) {
        Box(Modifier.fillMaxSize().background(SoundColors.Ink)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = statusBar, bottom = LocalBottomInset.current)) {
                item(key = "bar", contentType = "bar") {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        BackButton(backdropAlpha = { 0f })
                        Spacer(Modifier.weight(1f))
                        topActions()
                    }
                }
                item(key = "card", contentType = "card") {
                    PersonCard(person, since, hours = person?.listenedMs?.takeIf { showHours }, avatar = avatar)
                }
                item(key = "buttons", contentType = "buttons") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter).padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = buttons,
                    )
                }
                if (songs.isNotEmpty()) {
                    item(key = "top", contentType = "carousel") {
                        Column(Modifier.fillMaxWidth().padding(top = Dimens.SectionGap)) {
                            SectionHeader(topTitle, subtitle = topHint, action = stringResource(R.string.play), onAction = { play(0) })
                            LazyRow(
                                modifier = Modifier.keepHorizontalDrags(),
                                contentPadding = PaddingValues(horizontal = Dimens.Gutter),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                itemsIndexed(songs, key = { i, s -> "t$i-${s.videoId}" }) { i, s ->
                                    RankedCover(
                                        rank = i + 1,
                                        song = top[i],
                                        current = playback.current?.videoId == s.videoId,
                                        playing = playback.isPlaying,
                                        onClick = { play(i) },
                                    )
                                }
                            }
                        }
                    }
                }
                more()
            }
            SoundSnackbarHost(
                snackbar.host,
                Modifier.align(Alignment.BottomCenter).padding(bottom = (LocalBottomInset.current - 8.dp).coerceAtLeast(16.dp)),
            )
        }
        sheets()
    }
}

/**
 * The person's card: their color, lit from the top-left and deepening to the corner, with sound
 * waves rippling out of the top-right; the photo ringed in white, the name, the @; at the foot the
 * hours set big and since when.
 */
@Composable
internal fun PersonCard(person: Profile?, since: String?, hours: Long?, avatar: @Composable (Dp) -> Unit) {
    val base = remember(person?.username) { if (person == null) SoundColors.Surface3 else seedColor(person.username) }
    val fill = remember(base) { personFill(base) }
    Box(
        Modifier
            .padding(horizontal = Dimens.Gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(fill)
            // Sound waves from the top-right corner: quiet, just enough to make it a card about music.
            .waves()
            .padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 22.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(96.dp).border(3.dp, Color.White.copy(alpha = 0.92f), CircleShape).padding(5.dp)) {
                    avatar(86.dp)
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        person?.name.orEmpty(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (person != null) {
                        Spacer(Modifier.height(2.dp))
                        Text("@${person.username}", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.78f), maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                if (hours != null) {
                    Text(
                        stringResource(R.string.person_hours_value, hours(hours)),
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.person_hours_label),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White.copy(alpha = 0.78f),
                        modifier = Modifier.padding(bottom = 7.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                since?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(bottom = 7.dp, start = 12.dp),
                    )
                }
            }
        }
    }
}

/** The person's color as their card paints it: lit from the top-left, deepening to the corner. */
internal fun personFill(base: Color): Brush =
    Brush.linearGradient(listOf(lerp(base, Color.White, 0.14f), lerp(base, Color.Black, 0.38f)), start = Offset.Zero, end = Offset.Infinite)

/** Sound waves rippling out of the card's top-right corner. */
private fun Modifier.waves(first: Dp = 96.dp, step: Dp = 54.dp): Modifier = drawBehind {
    val center = Offset(size.width + 12.dp.toPx(), -12.dp.toPx())
    repeat(4) { i ->
        drawCircle(Color.White.copy(alpha = 0.085f - i * 0.016f), radius = (first + step * i).toPx(), center = center, style = Stroke(width = 1.5.dp.toPx()))
    }
}

/** Your card at the top of Amigos: the same card as your page, small — photo, name, @, hours; tap for your page. */
@Composable
internal fun MiniPersonCard(me: Profile, onClick: () -> Unit) {
    val fill = remember(me.username) { personFill(seedColor(me.username)) }
    Row(
        Modifier
            .padding(horizontal = Dimens.Gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(fill)
            .waves(first = 70.dp, step = 40.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(68.dp).border(2.5.dp, Color.White.copy(alpha = 0.92f), CircleShape).padding(4.dp)) {
            ProfileAvatar(me, 60.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(me.name, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@${me.username}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.78f), maxLines = 1)
            me.listenedMs?.let {
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.profile_hours, hours(it)), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            stringResource(R.string.profile_title),
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(28.dp),
        )
    }
}

/** One of the five most heard: a big cover with the rank set over its foot, the title under it. */
@Composable
private fun RankedCover(rank: Int, song: SongRef, current: Boolean, playing: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .width(148.dp)
            .pressScale(interaction, 0.97f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(Modifier.size(148.dp).clip(shape)) {
            Cover(song.cover, Modifier.matchParentSize(), seed = song.title, shape = shape)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
            if (current) {
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                    PlayingBars(playing, Modifier.size(30.dp), color = Color.White, barWidth = 5.dp)
                }
            }
            Text(
                "$rank",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Black,
                    shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 8f),
                ),
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 2.dp),
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(song.title, style = MaterialTheme.typography.titleSmall, color = if (current) SoundColors.Accent else SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A button under the person's card: two side by side fit their whole labels ("Mandar música"). */
@Composable
internal fun PersonButton(
    text: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val fg = (if (filled) SoundColors.OnAccent else SoundColors.Accent).copy(alpha = if (enabled) 1f else 0.45f)
    Row(
        modifier
            .height(52.dp)
            .pressScale(interaction, 0.96f)
            .clip(CircleShape)
            .background(if (filled) SoundColors.Accent else SoundColors.Surface1)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The photo inside the card; on your own page, a little camera on its edge to change it. */
@Composable
internal fun CardAvatar(person: Profile?, size: Dp, onCamera: (() -> Unit)? = null, busy: Boolean = false) {
    Box(Modifier.size(size)) {
        ProfileAvatar(person, size)
        if (busy) {
            Box(Modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            }
        }
        if (onCamera != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(onClick = onCamera),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PhotoCamera, stringResource(R.string.profile_photo_change), tint = Color(0xFF1B1D24), modifier = Modifier.size(17.dp))
            }
        }
    }
}

/** The recovery code, set like a ticket: big and spaced, with "Copiar" and "Gerar outro" under it. */
@Composable
internal fun RecoveryCard(code: String?, onCopy: () -> Unit, onNew: () -> Unit, onUseCode: (() -> Unit)? = null) {
    Column(Modifier.padding(top = Dimens.SectionGap)) {
        SectionHeader(stringResource(R.string.profile_recovery_title))
        Column(
            Modifier
                .padding(horizontal = Dimens.Gutter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(SoundColors.Surface1)
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                code ?: "····-····-····-····",
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp),
                color = SoundColors.Text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.profile_recovery_body),
                style = MaterialTheme.typography.bodyMedium,
                color = SoundColors.TextMuted,
                textAlign = TextAlign.Center,
            )
            if (code != null) {
                Spacer(Modifier.height(16.dp))
                Row {
                    PillButton(stringResource(R.string.profile_copy), onClick = onCopy, icon = Icons.Rounded.ContentCopy, filled = true)
                    Spacer(Modifier.width(10.dp))
                    PillButton(stringResource(R.string.profile_new_code), onClick = onNew)
                }
            }
            if (onUseCode != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.profile_use_code),
                    style = MaterialTheme.typography.labelLarge,
                    color = SoundColors.TextMuted,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onUseCode).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
