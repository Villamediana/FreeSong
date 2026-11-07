package com.villamediana.sound.ui.detail

import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import com.villamediana.sound.ui.components.PillButton
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.sound.R
import com.villamediana.sound.ui.components.PlayPauseButton
import com.villamediana.sound.ui.components.pressScale
import com.villamediana.sound.ui.nav.LocalNavigator
import com.villamediana.sound.ui.sheets.LocalSheetFeedback
import com.villamediana.sound.ui.sheets.SheetFeedback
import com.villamediana.sound.ui.sheets.SnackbarFeedback
import com.villamediana.sound.ui.sheets.SoundSnackbarHost
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.SoundColors

/** Text of a collection header. [owner] (artist name) is tappable and comes before [meta]. */
@Immutable
internal data class HeaderSpec(
    val overline: String,
    val title: String,
    val meta: String,
    val wash: Color,
    val owner: String? = null,
    val onOwnerClick: (() -> Unit)? = null,
    val description: String? = null,
    val badge: String? = null,
    val badgeIcon: ImageVector? = null,
)

internal enum class ArtShape { Square, Circle }

internal val TopBarHeight = 56.dp

/** Top/bottom space the scaffold reserves, for overlays like the fast scroller. */
@Composable
internal fun collectionTopInset(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight

/** Holds where the big title / art sit inside the header (header coordinates, px). */
private class HeaderGeometry {
    var header: LayoutCoordinates? = null
    var title: LayoutCoordinates? = null
    var art: LayoutCoordinates? = null
    var titleBottom by mutableFloatStateOf(Float.POSITIVE_INFINITY)
    var artBottom by mutableFloatStateOf(0f)

    fun update() {
        val h = header?.takeIf { it.isAttached } ?: return
        title?.takeIf { it.isAttached }?.let { titleBottom = h.localPositionOf(it, Offset(0f, it.size.height.toFloat())).y }
        art?.takeIf { it.isAttached }?.let { artBottom = h.localPositionOf(it, Offset(0f, it.size.height.toFloat())).y }
    }
}

/**
 * The layout every detail screen shares: an edge-to-edge header where the cover color washes from
 * the top into Ink, the art (parallax: drifts, shrinks and fades as you scroll), overline, big
 * title, meta line and the action row with the big play button; a compact bar fades in with the
 * title once the header is gone. Song rows go in [content].
 */
@Composable
internal fun CollectionScaffold(
    header: HeaderSpec,
    art: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
    artShape: ArtShape = ArtShape.Square,
    listState: LazyListState = rememberLazyListState(),
    playing: Boolean = false,
    onPlay: (() -> Unit)? = null,
    shuffle: Boolean? = null,
    onShuffle: () -> Unit = {},
    showActionRow: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    barActions: @Composable RowScope.() -> Unit = {},
    snackbar: DetailSnackbar = rememberDetailSnackbar(),
    overlay: @Composable BoxScope.() -> Unit = {},
    sheets: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barBottomPx = with(density) { (statusBar + TopBarHeight).toPx() }
    val leadPx = with(density) { 72.dp.toPx() }
    val fadePx = with(density) { 28.dp.toPx() }
    val geometry = remember { HeaderGeometry() }
    val scrolled by remember(listState) {
        derivedStateOf { HeaderMath.scrolled(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }

    val bottomInset = LocalBottomInset.current
    val barColor = lerp(header.wash, SoundColors.Ink, 0.5f)

    CompositionLocalProvider(LocalSheetFeedback provides snackbar.feedback) {
        Box(modifier.fillMaxSize().background(SoundColors.Ink)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomInset),
            ) {
                item(key = "header", contentType = "header") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { geometry.header = it; geometry.update() },
                    ) {
                        // The one gradient Sound allows: the cover color washing into Ink.
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to header.wash.copy(alpha = SoundColors.palette.washAlpha),
                                        0.5f to header.wash.copy(alpha = SoundColors.palette.washAlpha * 0.45f),
                                        1f to header.wash.copy(alpha = 0f),
                                    ),
                                ),
                        )
                        Column(Modifier.fillMaxWidth().padding(top = statusBar + TopBarHeight + 4.dp, bottom = 6.dp)) {
                            if (art != null) {
                                Box(
                                    Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .fillMaxWidth(if (artShape == ArtShape.Circle) 0.56f else 0.64f)
                                        .widthIn(max = 300.dp)
                                        .aspectRatio(1f)
                                        .onGloballyPositioned { geometry.art = it; geometry.update() },
                                ) {
                                    art(
                                        Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                val s = scrolled
                                                val range = geometry.artBottom - barBottomPx
                                                val f = HeaderMath.fraction(s, range)
                                                translationY = HeaderMath.artTranslation(s, size.height * 0.6f)
                                                val sc = HeaderMath.artScale(f)
                                                scaleX = sc
                                                scaleY = sc
                                                alpha = HeaderMath.artAlpha(f)
                                            },
                                    )
                                }
                                Spacer(Modifier.height(24.dp))
                            }
                            HeaderTexts(header, onTitlePlaced = { geometry.title = it; geometry.update() })
                            if (showActionRow) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.align(Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) { actions() }
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (onPlay != null) {
                                        PillButton(
                                            text = stringResource(if (playing) R.string.pause else R.string.play),
                                            onClick = onPlay,
                                            icon = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            big = true,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (shuffle != null) {
                                        val shuffleLabel = stringResource(R.string.shuffle)
                                        PillButton(
                                            text = shuffleLabel,
                                            onClick = onShuffle,
                                            icon = Icons.Rounded.Shuffle,
                                            filled = shuffle,
                                            big = true,
                                            // TalkBack hears it as a switch (on / off).
                                            modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {
                                                contentDescription = shuffleLabel
                                                role = Role.Switch
                                                toggleableState = ToggleableState(shuffle)
                                            },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                content()
            }

            // Compact bar: background and title fade in as the big title slides under it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(statusBar + TopBarHeight)
                    .drawBehind {
                        val a = HeaderMath.barAlpha(scrolled, geometry.titleBottom, barBottomPx, leadPx)
                        if (a > 0f) drawRect(barColor.copy(alpha = a))
                    },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(top = statusBar).height(TopBarHeight).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackButton(backdropAlpha = { 1f - HeaderMath.barAlpha(scrolled, geometry.titleBottom, barBottomPx, leadPx) })
                    Spacer(Modifier.width(6.dp))
                    Text(
                        header.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = SoundColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .graphicsLayer {
                                val a = HeaderMath.titleAlpha(scrolled, geometry.titleBottom, barBottomPx, fadePx)
                                alpha = a
                                translationY = (1f - a) * 10.dp.toPx()
                            },
                    )
                    barActions()
                }
            }

            overlay()

            SoundSnackbarHost(
                snackbar.host,
                Modifier.align(Alignment.BottomCenter).padding(bottom = (bottomInset - 8.dp).coerceAtLeast(16.dp)),
            )
        }
        sheets()
    }
}

/** Snackbar host + the feedback that writes to it; screens create it so their own actions use it too. */
@Stable
internal class DetailSnackbar(val host: SnackbarHostState, val feedback: SheetFeedback)

@Composable
internal fun rememberDetailSnackbar(): DetailSnackbar {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        val host = SnackbarHostState()
        DetailSnackbar(host, SnackbarFeedback(host, scope))
    }
}

@Composable
private fun HeaderTexts(header: HeaderSpec, onTitlePlaced: (LayoutCoordinates) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            header.title,
            style = headerTitleStyle(header.title),
            color = SoundColors.Text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.onGloballyPositioned(onTitlePlaced),
        )
        header.description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        MetaLine(header.owner, header.onOwnerClick, header.meta)
        if (header.badge != null) {
            Spacer(Modifier.height(10.dp))
            Badge(header.badge, header.badgeIcon)
        }
    }
}

/** Short titles get the big display face; long ones step down so they don't wrap four times. */
@Composable
private fun headerTitleStyle(title: String): TextStyle = when {
    title.length <= 16 -> MaterialTheme.typography.displaySmall
    title.length <= 30 -> MaterialTheme.typography.headlineLarge
    else -> MaterialTheme.typography.headlineMedium
}

@Composable
private fun MetaLine(owner: String?, onOwnerClick: (() -> Unit)?, meta: String) {
    val text = buildAnnotatedString {
        if (!owner.isNullOrBlank()) {
            val ownerStyle = SpanStyle(color = SoundColors.Text, fontWeight = FontWeight.SemiBold)
            if (onOwnerClick != null) {
                withLink(LinkAnnotation.Clickable("owner", TextLinkStyles(style = ownerStyle)) { onOwnerClick() }) { append(owner) }
            } else {
                pushStyle(ownerStyle); append(owner); pop()
            }
            if (meta.isNotBlank()) append(MetaFormat.SEP)
        }
        append(meta)
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, color = SoundColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
}

@Composable
private fun Badge(text: String, icon: ImageVector?) {
    Row(
        Modifier.clip(CircleShape).background(SoundColors.Surface3.copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = SoundColors.TextMuted, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = SoundColors.TextMuted)
    }
}

/** Round back button; its dark backdrop keeps it legible over bright washes and fades with the bar. */
@Composable
internal fun BackButton(backdropAlpha: () -> Float = { 1f }) {
    val navigator = LocalNavigator.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(44.dp)
            .pressScale(interaction)
            .clip(CircleShape)
            .drawBehind { drawCircle(SoundColors.Ink.copy(alpha = 0.32f * backdropAlpha().coerceIn(0f, 1f))) }
            .clickable(interactionSource = interaction, indication = null, onClick = { navigator.back() }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SoundColors.Text, modifier = Modifier.size(24.dp))
    }
}

/** Status-bar-aware bar with just the back button (loading / not found states). */
@Composable
internal fun PlainTopBar() {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Row(
        Modifier.fillMaxWidth().padding(top = statusBar).height(TopBarHeight).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { BackButton() }
}

/** Shuffle toggle: tangerine with a dot when on; TalkBack hears it as a switch (on / off). */
@Composable
internal fun ShuffleToggle(on: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val tint by animateColorAsState(if (on) SoundColors.Accent else SoundColors.TextMuted, label = "shuffle")
    val dot by animateFloatAsState(if (on) 1f else 0f, Motion.bouncy(), label = "dot")
    Column(
        Modifier
            .size(48.dp)
            .pressScale(interaction)
            .clip(CircleShape)
            .toggleable(
                value = on,
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
                onValueChange = { onToggle() },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Shuffle, stringResource(R.string.shuffle), tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(3.dp))
        Box(Modifier.size(4.dp).scale(dot).clip(CircleShape).background(SoundColors.Accent))
    }
}

/** Icon button of the header action row; bounces when it turns on. */
@Composable
internal fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: Color = SoundColors.TextMuted,
    active: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pop by animateFloatAsState(if (active) 1f else 0.94f, Motion.bouncy(), label = "pop")
    val color by animateColorAsState(tint, label = "tint")
    Box(
        Modifier
            .size(44.dp)
            .pressScale(interaction)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = color, modifier = Modifier.size(26.dp).scale(if (active) pop else 1f))
    }
}

/** Shared rounded-corner shape for full-width rows that get a raised background (drag). */
internal val RowShape = RoundedCornerShape(10.dp)
